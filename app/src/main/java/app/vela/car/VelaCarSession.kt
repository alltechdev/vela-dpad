package app.vela.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import app.vela.car.screen.RoutePreviewCarScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vela.car.screen.CarDeps
import app.vela.car.screen.MainCarScreen
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One car projection session. Owns a location collector that feeds [app.vela.core.nav.NavSession]
 * while the car UI is alive — so navigation runs off the car's own GPS feed, independent of the
 * phone Activity/ViewModel (which is stopped during projection).
 *
 * Phase-1 minimal-arbiter note: the phone [app.vela.ui.map.MapViewModel] is the other feeder of the
 * shared [NavSession] singleton. They don't run at once in projection (the phone UI is stopped), and
 * `NavSession.onLocation`/`state.update` are atomic, so a stray double-feed can't crash — it's just
 * redundant. A dedicated `NavCoordinator` arbiter (sole feeder/owner) is the clean follow-up.
 */
class VelaCarSession(private val deps: CarDeps) : Session(), DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var feedJob: Job? = null

    // Camera-alert bookkeeping: cams computed once per route identity (bundled DeFlock dataset,
    // no network), each camera announced at most once per trip.
    private var camRouteKey = 0
    private var camsOnRoute: List<app.vela.core.model.LatLng> = emptyList()
    private val camAnnounced = HashSet<Int>()

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Feed live GPS into NavSession while the car session is alive. Mirrors the phone's fix gate:
        // GPS fixes with accuracy ≤ 50 m drive guidance; coarser fixes are ignored for nav.
        // Cancel any prior collector first so a re-delivered onCreate can't leak a second one.
        feedJob?.cancel()
        feedJob = scope.launch {
            deps.locationProvider.updates().collect { loc ->
                val gps = loc.provider == android.location.LocationManager.GPS_PROVIDER
                if (gps && (!loc.hasAccuracy() || loc.accuracy <= 50f)) {
                    val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
                    val here = LatLng(loc.latitude, loc.longitude)
                    deps.navSession.onLocation(here, app.vela.ui.Units.imperial.value, speed)
                    maybeAlertCamera(here)
                }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        feedJob?.cancel()
        scope.cancel()
        deps.stopMapRenderer() // the shared map renderer lives for the session; stop its collector here
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Launched via a navigation intent (assistant "navigate to X" / another app)? Go straight to
        // the route preview for that destination; otherwise the normal landing screen. A free-text
        // destination geocodes asynchronously and pushes the preview on top of the landing screen.
        parseNavDest(intent)?.let { (dest, name) ->
            return RoutePreviewCarScreen(carContext, deps, name, dest)
        }
        parseNavQuery(intent)?.let(::resolveFreeText)
        // Resume: nav already running (started on the phone before plugging in, or the host
        // recreated the session mid-drive)? Land straight in guidance. Pushed onto the landing
        // screen (not returned as root) so End/arrival can pop back to something. arrived guards
        // a push-pop flicker: an arrived-but-undismissed session must not bounce the screen.
        val nav = deps.navSession.state.value
        if (nav.navigating && !nav.arrived) {
            scope.launch {
                carContext.getCarService(ScreenManager::class.java)
                    .push(app.vela.car.screen.ActiveNavCarScreen(carContext, deps))
            }
        }
        return MainCarScreen(carContext, deps)
    }

    /** A navigation intent delivered while the app is already running (the host reuses the session). */
    override fun onNewIntent(intent: Intent) {
        parseNavDest(intent)?.let { (dest, name) ->
            carContext.getCarService(ScreenManager::class.java)
                .push(RoutePreviewCarScreen(carContext, deps, name, dest))
            return
        }
        parseNavQuery(intent)?.let(::resolveFreeText)
    }

    /** Spoken heads-up approaching a mapped license-plate camera (Settings -> Navigation ->
     *  Driving alerts; off by default). Cameras come from the BUNDLED DeFlock dataset filtered to
     *  the route corridor - instant and offline, the same source the phone's avoid-cameras
     *  re-rank prefers. Each camera speaks once per trip, within [CAM_ALERT_METERS] of the puck. */
    private fun maybeAlertCamera(here: LatLng) {
        if (!app.vela.ui.DriveAlerts.cameras.value) return
        val s = deps.navSession.state.value
        val route = s.route ?: return
        if (!s.navigating || s.arrived) return
        val key = route.polyline.size * 31 + (route.polyline.firstOrNull()?.lat?.times(1e5)?.toInt() ?: 0)
        if (key != camRouteKey) {
            camRouteKey = key
            camAnnounced.clear()
            camsOnRoute = if (app.vela.data.FlockCameras.isLoaded) {
                runCatching { app.vela.data.FlockCameras.along(route.polyline).map { it.loc } }.getOrDefault(emptyList())
            } else emptyList()
        }
        camsOnRoute.forEachIndexed { i, cam ->
            if (i !in camAnnounced && here.distanceTo(cam) <= CAM_ALERT_METERS) {
                camAnnounced.add(i)
                deps.voiceGuide.speak(carContext.getString(app.vela.R.string.car_camera_ahead), interrupt = false)
            }
        }
    }

    /** Geocode a free-text NAVIGATE destination ("navigate to the nearest coffee shop") through the
     *  same [app.vela.core.data.MapDataSource.search] the search screens use, biased to the last
     *  known location, and land in the route preview for the top hit. Upstream punts these (the app
     *  just opens); resolving them is a fork addition. Misses get a toast, not silence. */
    private fun resolveFreeText(query: String) {
        scope.launch {
            val near = deps.locationProvider.lastKnown()
            val hit = runCatching { deps.mapDataSource.search(query, near).places }
                .getOrDefault(emptyList()).firstOrNull()
            if (hit != null) {
                carContext.getCarService(ScreenManager::class.java)
                    .push(RoutePreviewCarScreen(carContext, deps, hit.name, hit.location))
            } else {
                CarToast.makeText(
                    carContext,
                    carContext.getString(app.vela.R.string.mapvm_no_results, query),
                    CarToast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** The free-text `q=` payload of a NAVIGATE `geo:` URI, when [parseNavDest] found no coords in
     *  it. URL-decoded ("coffee+shop" and %XX escapes arrive encoded). */
    private fun parseNavQuery(intent: Intent): String? {
        if (intent.action != CarContext.ACTION_NAVIGATE) return null
        val ssp = intent.data?.schemeSpecificPart ?: return null
        val raw = ssp.substringAfter("q=", "").takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim().ifBlank { null }
    }

    /** Parse an `androidx.car.app.action.NAVIGATE` intent's `geo:` URI into a destination. Handles
     *  `geo:lat,lng` and `geo:0,0?q=lat,lng(Label)`; a free-text `q=` goes through [parseNavQuery] +
     *  [resolveFreeText] (geocoded, then previewed) instead. NB `geo:` URIs are OPAQUE
     *  (non-hierarchical), so `Uri.getQueryParameter()` THROWS ("This isn't a hierarchical URI") —
     *  we parse the scheme-specific part by hand instead. */
    private fun parseNavDest(intent: Intent): Pair<LatLng, String>? {
        if (intent.action != CarContext.ACTION_NAVIGATE) return null
        val uri: Uri = intent.data ?: return null
        val ssp = uri.schemeSpecificPart ?: return null
        val coordRe = Regex("""(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*(?:\((.*)\))?""")
        // Prefer a `q=` payload (geo:0,0?q=lat,lng(Label)); else the bare `geo:lat,lng` coords.
        val qVal = ssp.substringAfter("q=", "").takeIf { it.isNotBlank() }
        val target = qVal ?: ssp.substringBefore('?')
        // matchEntire, not find: "Store 12, 34th St" CONTAINS a coord-shaped substring but is a
        // free-text query for the geocoder, not a destination at lat 12 lng 34.
        coordRe.matchEntire(target.trim())?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull(); val lng = m.groupValues[2].toDoubleOrNull()
            // Reject the placeholder 0,0 only when it came from the bare coords (a real q= 0,0 is unheard of).
            if (lat != null && lng != null && !(qVal == null && lat == 0.0 && lng == 0.0)) {
                return LatLng(lat, lng) to m.groupValues[3].ifBlank { "Destination" }
            }
        }
        return null
    }

    private companion object {
        const val CAM_ALERT_METERS = 400.0
    }
}
