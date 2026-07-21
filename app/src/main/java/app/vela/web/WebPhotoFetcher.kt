package app.vela.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.vela.core.VelaConfig
import app.vela.core.model.Photo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a place's photo gallery through a hidden [WebView] by **loading the place's own
 * `?cid=` page and scraping the rendered photo URLs out of the DOM** - the same tactic as
 * [WebReviewsFetcher].
 *
 * Why not the dedicated `hspqX` photos RPC? On-device logging (2026-06-28) proved Google
 * **degrades a bare anonymous `hspqX` POST per-session** to a single Street-View-only reply
 * (`streetviewpixels`, ~2 KB) - and a same-session retry returns the byte-identical degraded
 * answer, so the RPC is unreliable keyless. But Google **renders the real photo collage to a
 * logged-out browser on the place PAGE itself** (that's how a user sees them). So we let
 * Google's own JS draw the page and read the `googleusercontent` photo URLs back out of the
 * DOM - much harder for it to bot-degrade than a naked RPC call.
 *
 * Anonymous / no-login, desktop UA (a mobile UA deep-links to `intent://`). Strictly
 * best-effort + lazy: any failure/timeout returns empty and the caller keeps the search-preview
 * photo. Serialized by a [Mutex] since the single WebView navigates per place.
 */
@Singleton
class WebPhotoFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val partials = ConcurrentHashMap<String, (String) -> Unit>()
    private val seq = AtomicInteger()
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null
    @Volatile private var warmed = false

    /** Pending idle-reap callback. Only ever read or written on the main thread - see [onMain]. */
    private var reap: Runnable? = null

    init {
        // A trim is the OS asking for memory NOW, far sooner than any idle timer (issue #83).
        app.vela.ui.MemoryPressure.register { level ->
            if (app.vela.ui.MemoryPressure.isSevere(level)) main.post { cancelReap(); reapNow() }
        }
    }

    /**
     * Run [block] on the main thread, inline when already there.
     *
     * All reap bookkeeping goes through here because [reap] is touched from two places on
     * different threads - the trim listener (main) and [fetch]/[warm] (the caller's dispatcher).
     * Making every mutation main-thread-only removes the race by construction; @Volatile would not,
     * since scheduling is a read-modify-write. Running inline when already on main keeps the
     * cancel-then-navigate ordering inside [fetch]'s `Dispatchers.Main` block intact.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Free the WebView after a quiet period, like the other four fetchers (issue #182) - this one
     * never had it, so a single search pinned a Chromium renderer for the WHOLE session and only
     * renderer death or a trim could clear it. Measured on the M5: after a search the shared
     * sandboxed renderer sat at 327 MB PSS, in a SEPARATE process, so it never showed up in the
     * app's own PSS and every issue-#83 measurement missed it.
     *
     * [delayMs] differs by caller on purpose. A real fetch uses the siblings' [REAP_IDLE_MS]; a
     * speculative [warm] uses the longer [WARM_REAP_IDLE_MS], because the warm exists precisely so
     * a later place tap skips the cold start, and reaping it at 120 s would undo it for the ordinary
     * search-then-browse-then-tap flow. Longer, but still bounded: the point is that it cannot be
     * session-long.
     */
    private fun scheduleReap(delayMs: Long) = onMain {
        reap?.let(main::removeCallbacks)
        val r = Runnable { reap = null; reapNow() }
        reap = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelReap() = onMain {
        reap?.let(main::removeCallbacks)
        reap = null
    }

    /** Destroy the WebView immediately. Main thread only (WebView requirement). The next
     *  [warm]/fetch rebuilds it via `ensureWebView`, exactly as after a renderer death.
     *
     *  Drains [pending] for the same reason [rendererGone] does: the injected scraper dies with the
     *  view, so nothing will ever complete those deferreds. Without this a reap landing mid-fetch
     *  leaves the fetch parked in `deferred.await()` for the full [TOTAL_TIMEOUT_MS] while it HOLDS
     *  [mutex], stalling every queued gallery behind it. An empty result is the documented
     *  best-effort failure mode; a 40 s hang is not. */
    private fun reapNow() {
        val wv = webView ?: return
        webView = null
        warmed = false
        runCatching { wv.loadUrl("about:blank"); wv.destroy() }
        val stranded = pending.keys.toList()
        stranded.forEach { id -> pending.remove(id)?.complete("") }
        // The renderer is shared and lives in ANOTHER process, so its cost is invisible in this
        // app's PSS - without a log there is no way to tell an idle reap from an OS trim killing
        // the renderer, which made the first attempt to verify this unfalsifiable.
        android.util.Log.i("WebPhotoFetcher", "photo WebView reaped (stranded fetches: ${stranded.size})")
    }

    // featureId → its scraped gallery. Re-tapping a place (or bouncing back from directions) then
    // shows photos INSTANTLY instead of re-running the ~20 s scrape. Access-order LRU, small cap.
    private val cache = object : LinkedHashMap<String, List<Photo>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Photo>>) = size > 32
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }

        // Streaming: the scraper reports the accumulated set whenever it GROWS, so the gallery
        // fills in as tabs are visited instead of arriving all at once at the end. JavaBridge
        // thread - the callback must be thread-safe.
        @JavascriptInterface
        fun onPartial(id: String, payload: String) {
            partials[id]?.invoke(payload)
        }
    }

    /** Prime the hidden WebView BEFORE the first place is opened (call on first search): creates
     * the WebView and loads maps.google.com once, so the first real photo fetch reuses a live
     * renderer, warm HTTP/2 connections, cookies, and cached JS - instead of paying the whole
     * cold start on top of the place page load. No-op after anything has used the WebView. */
    fun warm() {
        if (warmed || webView != null) return
        warmed = true
        main.post {
            runCatching {
                // Re-check on the MAIN thread: if a fetch's Dispatchers.Main block created + started
                // using the WebView after this warm() was posted (from a bg thread), it already owns it -
                // don't replace its webViewClient or navigate away from its in-flight ?cid page (audit
                // 2026-07-06). All WebView creation/mutation is on the main thread, so this check is race-free.
                if (webView != null) return@runCatching
                val wv = ensureWebView()
                // Even the idle warm page needs the renderer-gone handler - an unhandled renderer
                // death (default: false) kills the WHOLE app process, not just this hidden WebView.
                wv.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        rendererGone(view)
                        return true
                    }
                }
                wv.loadUrl("https://www.google.com/maps?hl=en")
                // Bound the speculative warm. Reached only when THIS call created the view (the
                // re-check above returns early when a fetch already owns it, and that fetch arms its
                // own reap in `finally`), so this never shortens a real fetch's window.
                scheduleReap(WARM_REAP_IDLE_MS)
            }
        }
    }

    /** The WebView's sandboxed renderer process died (OOM kill on a low-RAM phone, or a Chromium
     * crash). Runs on the UI thread. Tear the dead WebView down and fail every in-flight fetch the
     * normal empty-result way - the caller keeps the search-preview photo, exactly like a timeout.
     * The next fetch/warm recreates a fresh WebView via [ensureWebView]. Returning true from
     * [WebViewClient.onRenderProcessGone] is what keeps the APP alive - the unhandled default kills
     * the whole process (minSdk 26 == the API the override needs, so no version check). */
    private fun rendererGone(view: WebView?) {
        android.util.Log.w("WebPhotoFetcher", "WebView renderer process gone; failing in-flight photo fetch")
        val wv = webView
        webView = null
        warmed = false // let a later warm() rebuild the session
        runCatching { (wv ?: view)?.destroy() }
        pending.keys.toList().forEach { id -> pending.remove(id)?.complete("") }
    }

    /** The gallery for [featureId] (`0x..:0x..`) - each [Photo] is its URL plus the gallery-tab
     * [Photo.category] when Google tagged it (Menu / Food & drink / Vibe / By owner; null = All).
     * No posted date from a DOM scrape. Empty on any failure. [count] caps how many we keep. */
    suspend fun fetch(featureId: String, count: Int = 80, onPartial: ((List<Photo>) -> Unit)? = null): List<Photo> {
        val cid = cidOf(featureId) ?: return emptyList()
        synchronized(cache) { cache[featureId] }?.let { return it } // instant on revisit - skip the scrape
        return mutex.withLock {
            cancelReap() // a reap mid-scrape would destroy the view this fetch is about to drive
            val id = "p" + seq.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            if (onPartial != null) partials[id] = { raw -> onPartial(parseLines(raw)) }
            val raw = try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        val wv = ensureWebView()
                        val ready = CompletableDeferred<Unit>()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Block anything that isn't a google.com page - the overview has a bare
                                // "Menu" ACTION LINK to the restaurant's own site; following it would kill
                                // the scrape (and quietly load a third-party site in the hidden WebView).
                                val u = request?.url ?: return false
                                val scheme = u.scheme
                                if (scheme != "https" && scheme != "http") return true
                                val host = u.host.orEmpty()
                                return !(host == "google.com" || host.endsWith(".google.com"))
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                // IGNORE about:blank. [blankAfterScrape] parks the view there after the
                                // previous scrape, and that navigation can still be settling when this
                                // client is installed - its onPageFinished then opens the load gate
                                // early, the scraper injects into an empty document, and the fetch
                                // returns 0 photos. Device-caught: the same place scraped 33 photos as
                                // the first place opened and 0 as the second, until this guard.
                                if (url == null || url.startsWith("about:")) return
                                main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, SETTLE_MS)
                            }
                            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                // Renderer died mid-scrape: unblock the load gate first so the fetch
                                // coroutine resumes, then tear down + fail this fetch (empty result).
                                if (!ready.isCompleted) ready.complete(Unit)
                                rendererGone(view)
                                return true
                            }
                        }
                        // Blank the PREVIOUS place's DOM before navigating: on a slow load the MAX_LOAD
                        // fallback can inject the scraper before the new page commits - against an empty
                        // DOM that yields an empty result (safe) instead of the previous place's photos
                        // being returned for THIS featureId (cross-place data).
                        wv.evaluateJavascript("try{document.documentElement.innerHTML=''}catch(e){}", null)
                        // Size BEFORE navigating, so the ?cid= page's first layout is already at
                        // scrape geometry and the virtualized grids materialize exactly as before.
                        sizeForScrape(wv)
                        wv.loadUrl("https://www.google.com/maps?cid=$cid&hl=en&gl=us")
                        main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, MAX_LOAD_MS)
                        ready.await()
                        // Identity check, not null check: a renderer crash nulls [webView] (and
                        // destroys wv) on this same main thread - don't touch the destroyed view.
                        if (webView === wv) wv.evaluateJavascript(extractScript(id, count), null)
                    }
                    deferred.await()
                }
            } finally {
                pending.remove(id)
                partials.remove(id)
                blankAfterScrape() // the scraped page is dead weight from here until the next scrape
                scheduleReap(REAP_IDLE_MS) // start the quiet period from the END of the scrape
            }
            val out = raw?.let { parseLines(it) } ?: emptyList()
            // Result count, so a change to the offscreen viewport (WV_WIDTH/WV_HEIGHT drive how much
            // of the virtualized grid renders) can be A/B'd against scrape QUALITY, not just memory.
            android.util.Log.i("WebPhotoFetcher", "scraped ${out.size} photos for $featureId")
            if (out.isNotEmpty()) synchronized(cache) { cache[featureId] = out } // cache only real results
            out
        }
    }

    /** Each line is "category\turl" (category "" = uncategorized/All) - shared by the final result
     * and the streamed partials. */
    private fun parseLines(raw: String): List<Photo> = raw.split("\n").mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val tab = line.indexOf('\t')
        if (tab < 0) Photo(upsize(line.trim()))
        else Photo(upsize(line.substring(tab + 1).trim()), category = line.substring(0, tab).trim().ifBlank { null })
    }

    /** The Google "cid" = the LOW half of the `0xHIGH:0xLOW` feature id as an unsigned decimal. */
    private fun cidOf(featureId: String): String? {
        val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
        return runCatching { BigInteger(low, 16).toString() }.getOrNull()
    }

    /** Up-size a FIFE thumbnail URL for the sheet's photo strip. */
    private fun upsize(u: String): String =
        u.replace(Regex("=w\\d+-h\\d+[^=]*$"), "=w600-h450").replace(Regex("=s\\d+[^=]*$"), "=s600")

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = VelaConfig.USER_AGENT
        wv.addJavascriptInterface(Bridge(), "VelaBridge")
        // NOT laid out here on purpose - see [sizeForScrape]. The WebView stays 0x0 until a real
        // fetch needs the grids to materialize.
        webView = wv
        return wv
    }

    /**
     * Give the WebView its real offscreen viewport, immediately before a scrape navigates.
     *
     * The size itself is load-bearing: the category grids are VIRTUALIZED (like the reviews list),
     * so at 0x0 a category tab renders only about one tile and the scrape comes back nearly empty.
     * That is why the viewport exists at all.
     *
     * But it only has to exist for a SCRAPE. This used to run in `ensureWebView`, i.e. at
     * construction, and [warm] goes through `ensureWebView` - so a speculative warm created a full
     * WV_WIDTH x WV_HEIGHT composited surface over `maps?hl=en`, a page with zero scrapeable content,
     * and held it for the whole WARM_REAP_IDLE_MS window. Measured on the M5: `GL mtrack` is bimodal,
     * ~490 MB with this view laid out and alive versus ~71 MB without, on a 480x640 phone screen.
     * This fetcher is the only one that lays out during a warm at all (the other four either never
     * call layout or have no warm), which is why every GL number tracked THIS view.
     *
     * Idempotent, and called before `loadUrl` so the `?cid=` page's FIRST layout is already at scrape
     * geometry - that ordering is what keeps the scrape identical.
     */
    private fun sizeForScrape(wv: WebView) {
        if (wv.width == WV_WIDTH && wv.height == WV_HEIGHT) return
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(WV_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(WV_HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, WV_WIDTH, WV_HEIGHT)
    }

    /**
     * Throw the scraped page away the moment the scrape returns, rather than carrying it until the
     * reap 120 s later.
     *
     * The scrape is the only thing that needed the page and it is over - the result is already
     * parsed out of the bridge payload. What follows is the user reading the place sheet, which is
     * minutes of a fully rasterized Google Maps document serving nobody.
     *
     * It has to be a NAVIGATION, not a resize. Shrinking the view back to 0x0 was tried first and
     * measured to reclaim nothing at all (GL mtrack 494/496/497 MB against a 497/498 MB control):
     * Chromium keeps the tiles it has already rasterized for a live document regardless of the
     * view's size. Discarding the document is what frees them.
     *
     * Costs nothing functionally: the next `fetch` blanks the DOM and navigates to its own `?cid=`
     * page anyway, so this page was never going to be read again. The WebView itself stays alive, so
     * the renderer, HTTP/2 sockets, cookies and JS cache that make the next place fast are all kept -
     * which is exactly what destroying it early would have thrown away.
     */
    private fun blankAfterScrape() = onMain {
        val wv = webView ?: return@onMain
        runCatching { wv.loadUrl("about:blank") }
    }

    /** Self-polling DOM scraper: open the gallery, then VISIT EACH CATEGORY TAB (Menu / Food & drink /
     * Vibe / By owner) in turn - clicking it, scrolling, and tagging the photos it shows with that
     * category - then sweep the "All" view for the rest (uncategorized). Bridges "category\turl" lines
     * back, de-duped by image id (first category a photo appears under wins). Avatars + Street View
     * excluded. Google keeps these tabs in the DOM (verified on-device), so this is keyless. */
    private fun extractScript(id: String, cap: Int): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              var ID=$idj, CAP=$cap, acc={}, tries=0, phase=0, cats=[], ci=0, sub=0, opened=false;
              // The gallery tabs worth tagging (skip All/Latest/Videos/Street View - All is the fallback sweep).
              var CATRE=/^(menu|food|drink|vibe|by owner)/i;
              function ok(u){ return !!u && u.indexOf('googleusercontent')>=0 && !/streetviewpixels/.test(u) && !/\/a[\/-]|ACg8oc|ALV-/.test(u); }
              function idOf(u){ return u.replace(/=[wshpc].*$/,''); }
              function urlOf(el){ var u=el.currentSrc||el.src||''; if(!u || u.indexOf('googleusercontent')<0){ var bg=el.style.backgroundImage||''; if(!bg){ try{ bg=getComputedStyle(el).backgroundImage||''; }catch(e){} } var m=bg.match(/url\(["']?([^"')]+)/); if(m) u=m[1]; } return u; }
              function collect(cat){ [].slice.call(document.querySelectorAll('img,[role="img"],button,a,[style*="background"]')).forEach(function(el){ var u=urlOf(el); if(ok(u)){ var k=idOf(u); if(!acc[k]) acc[k]={c:cat,u:u}; } }); }
              // Tabs come from role="tab" ONLY: the place overview also has a bare "Menu" ACTION LINK (an
              // <a> to the restaurant's own site) - clicking that would navigate the WebView off Maps and
              // kill the scrape. (The Kotlin side also blocks off-google navigations as a belt-and-braces.)
              function tabEls(){ return [].slice.call(document.querySelectorAll('[role="tab"]')); }
              function clickTab(name){ var ts=tabEls(); for(var i=0;i<ts.length;i++){ if((((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim())===name){ try{ ts[i].click(); }catch(e){} return; } } }
              function tabSelected(name){ var ts=tabEls(); for(var i=0;i<ts.length;i++){ var t=(((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim()); if(t===name) return ts[i].getAttribute('aria-selected')==='true'; } return false; }
              // One-shot: after the gallery opens, its own tiles carry "Photo 2 of 45"-style labels that
              // match /photos?/ - re-firing this would click INTO a photo lightbox and break the tab walk.
              function clickPhotos(){ if(opened) return; var bs=[].slice.call(document.querySelectorAll('button,a')); for(var i=0;i<bs.length;i++){ var l=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].textContent||'')).toLowerCase(); if((/(^|\s)photos?(\s|${'$'})|see (all )?photos|all photos/.test(l)) && !/street ?view|review|profile|video/.test(l)){ try{ bs[i].click(); }catch(e){} opened=true; return; } } }
              function scroll(){ try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){ if(d.scrollHeight>d.clientHeight+300 && d.clientHeight>200) d.scrollTop=d.scrollHeight; }); }catch(e){} }
              // A real category tab is a clean name ("Menu", "Food & drink", "By owner") - EXCLUDE photo
              // captions that also start with a category word ("Menu · Photo 1 of 12") via the letters-only test.
              function tabsNow(){ var out=[]; tabEls().forEach(function(e){ var t=((e.getAttribute('aria-label')||e.textContent)||'').trim(); if(t && t.length<20 && CATRE.test(t) && /^[a-z &]+${'$'}/i.test(t) && out.indexOf(t)<0) out.push(t); }); return out; }
              function lines(){ var out=[]; for(var k in acc) out.push((acc[k].c||'')+'\t'+acc[k].u); return out.slice(0,CAP).join("\n"); }
              function finish(){ try{ VelaBridge.onResult(ID, lines()); }catch(e){ try{ VelaBridge.onResult(ID,''); }catch(e2){} } }
              var sentN=0;
              // Stream growth: the sheet fills in as photos are found instead of waiting ~20s for
              // the full tab walk (first partial = the OVERVIEW's hero photos, ~1 tick after load).
              function partial(){ var n=0; for(var k in acc) n++; if(n>sentN){ sentN=n; try{ VelaBridge.onPartial(ID, lines()); }catch(e){} } }
              function tick(){
                tries++;
                if(phase===0){
                  collect(''); clickPhotos(); scroll();
                  // Wait until the gallery's category tabs actually exist (slow loads) - but not forever:
                  // a place with no categorized gallery proceeds tab-less to the plain All sweep.
                  cats=tabsNow();
                  if(cats.length>0 || tries>=8){ ci=0; sub=0; phase=1; }
                }
                else if(phase===1){
                  if(ci>=cats.length){ phase=2; sub=0; }
                  // Per tab: click it, then scroll + COLLECT each tick - but only once the tab is actually
                  // SELECTED (aria-selected), else a slow grid swap would tag the previous tab's photos
                  // with this category. Accumulate across ticks (the grid virtualizes).
                  else { if(sub===0) clickTab(cats[ci]); scroll(); if(sub>=2 && tabSelected(cats[ci])) collect(cats[ci]); sub++; if(sub>=6){ ci++; sub=0; } }
                }
                else {
                  // The All sweep: click, give the grid one no-collect settle tick, then sweep uncategorized.
                  if(sub===0) clickTab('All');
                  scroll(); if(sub>=1) collect('');
                  sub++; if(sub>=5){ finish(); return; }
                }
                if(tries>58){ collect(''); finish(); return; }
                partial();
                setTimeout(tick, 500);
              }
              tick();
            })();
        """.trimIndent()
    }

    private companion object {
        // Must outlast the script's own hard stop (58 ticks × 500 ms = 29 s + page load ≤ 8 s) - if the
        // Kotlin timeout fires first we return NULL and throw away everything the walk accumulated,
        // instead of the partial set the script's salvage path would deliver.
        const val TOTAL_TIMEOUT_MS = 40_000L
        const val SETTLE_MS = 1_200L
        const val MAX_LOAD_MS = 7_000L
        // Offscreen viewport so the virtualized category grids render a full batch (not ~1 tile).
        //
        // Applied by [sizeForScrape] immediately before a scrape navigates, NOT at construction.
        //
        // These are deliberately UNCHANGED from stock. Shrinking the width to 720 was tried and did
        // hold the photo count on the one place it was A/B'd (28 -> 28), but scrape geometry governs
        // how much of a virtualized grid materializes, and one place is not enough evidence to risk a
        // quieter gallery in a locale or layout nobody sampled. Deferring the layout wins the same
        // memory back without changing anything the scraper sees, so the size stays stock.
        const val WV_WIDTH = 1200
        const val WV_HEIGHT = 3200
        // Destroy the idle WebView after this quiet period, same value as the other four fetchers.
        const val REAP_IDLE_MS = 120_000L
        // The speculative warm gets a longer leash: it is spent so the first place tap is instant,
        // and 120 s would expire during an ordinary browse and waste the warm entirely. Still
        // bounded, which is the whole point - before this the warm was held for the session.
        const val WARM_REAP_IDLE_MS = 300_000L
    }
}
