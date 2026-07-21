package app.vela.ui

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * Process-wide memory-pressure fan-out, in the same shape as the other app-level holders
 * (`TransitLayer`, `AppTheme`): `init()` from `VelaApp`, then anything holding a large or native
 * allocation registers a release callback.
 *
 * Why registration and not a Hilt entry point: reaching `WhisperRecognizer`/`PiperSynth` from
 * `onTrimMemory` through an EntryPoint would CONSTRUCT them if they had never been used, so a
 * trim would allocate the very models it is trying to free. A holder registers only once it
 * actually owns something worth releasing, so a trim can never create work.
 *
 * Measured on the M5 (2.9 GB, Android 13, standardDebug) before this existed: TRIM_MEMORY_COMPLETE
 * released 0 KB, because nothing in the app implemented ComponentCallbacks2 at all.
 */
object MemoryPressure {

    /** A registered releaser. [level] is a `ComponentCallbacks2.TRIM_MEMORY_*` constant. */
    fun interface Listener {
        fun release(level: Int)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * True when the OS classes this device as low-RAM (`ActivityManager.isLowRamDevice`) OR its
     * heap class is small enough that our normal budgets do not fit. The heap-class arm matters:
     * plenty of cheap keypad phones do NOT set the low-RAM system property yet still hand out a
     * 96 MB heap class, and those are exactly the phones this work is for.
     */
    @Volatile var lowRam: Boolean = false
        private set

    /** The device's normal (non-large) heap class in MB. 0 until [init]. */
    @Volatile var heapClassMb: Int = 0
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        heapClassMb = am?.memoryClass ?: 0
        val forced = forcedLowRam()
        lowRam = forced ?: ((am?.isLowRamDevice == true) || (heapClassMb in 1..127))
        Timber.i(
            "MemoryPressure init lowRam=%b heapClassMb=%d forced=%s",
            lowRam, heapClassMb, forced?.toString() ?: "no",
        )
    }

    /**
     * Debug-only override so the low-RAM path can be exercised on a normal dev phone:
     *
     *     adb shell setprop debug.vela.lowram true    # force the low-RAM path, then relaunch
     *     adb shell setprop debug.vela.lowram false   # force the normal path, then relaunch
     *     adb shell setprop debug.vela.lowram none    # clear the override, back to real detection
     *
     * All three need a relaunch; this is read once from [init]. Note that `false` FORCES the normal
     * path rather than clearing the override - the two only look alike because every dev phone we
     * own detects as normal anyway. Clearing needs an unparseable value ([debugFlag] returns null
     * for anything that is not true/1/false/0), hence `none`; `setprop <name> ""` is a shell syntax
     * error, not a reset.
     *
     * Without this the low-RAM branches are dead code on every device we actually own (the M5 dev
     * phone reports heapClassMb=256, lowRam=false), which means they would ship unverified.
     */
    private fun forcedLowRam(): Boolean? = debugFlag("debug.vela.lowram")

    /**
     * Read a debug-only tri-state system property. Returns null when unset, unparseable, or on a
     * non-debug build, so release behaviour is never affected by one of these.
     *
     * NB clearing one is `setprop <name> false`, NOT `setprop <name> ""` - an empty value is a
     * syntax error at the shell, not a reset.
     */
    private fun debugFlag(name: String): Boolean? {
        if (!app.vela.BuildConfig.DEBUG) return null
        val v = runCatching {
            @Suppress("PrivateApi")
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java).invoke(null, name) as? String
        }.getOrNull()
        return when (v?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    /** Register [listener]; returns a handle whose `close()` unregisters. Safe to call any time. */
    fun register(listener: Listener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * Fan a trim out to every registered holder. Each listener is isolated: one throwing must not
     * stop the rest from releasing, since under real pressure we want every byte we can get.
     *
     * The fan-out alone is only half the job: a listener's `release()` returns pages to SCUDO, not
     * to the kernel, so RSS barely moves. [schedulePurge] finishes it. See [nativePurge].
     */
    fun dispatch(level: Int) {
        Timber.i("MemoryPressure dispatch level=%d listeners=%d", level, listeners.size)
        for (l in listeners) {
            runCatching { l.release(level) }
                .onFailure { Timber.w(it, "MemoryPressure listener failed") }
        }
        // Anything except the gentlest level is worth a purge. Deliberately WIDER than isSevere:
        // measured on the M5, backgrounding the app delivers only TRIM_MEMORY_UI_HIDDEN (20), never
        // TRIM_MEMORY_BACKGROUND (40), so gating the purge on isSevere would skip the single most
        // common moment we are handed - the app is off-screen, nothing can jank, and the allocator
        // is holding pages nobody will touch again for minutes.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) schedulePurge(isSevere(level))
    }

    // ---------------------------------------------------------------- native allocator purge

    /**
     * Scudo hands freed pages back only when asked, and `mallopt` is the only way to ask. Measured
     * on the M5 (Android 13, app.vela.debug, all 8 listeners releasing): a full TRIM_MEMORY_COMPLETE
     * moved `scudo:primary` just 56,578 -> 54,978 KB while mallinfo showed a 442 MB arena holding
     * 46 MB live. Everything in that gap is reclaimable and unreachable from Kotlin.
     *
     * Returns which lever took: 2 = M_PURGE_ALL, 1 = M_PURGE, 0 = neither (pre-API-28).
     */
    private external fun nativePurge(all: Boolean): Int

    /** False when libvelamem is missing (an ABI we do not ship, a stripped install). The purge is
     *  then skipped rather than taking the process down over an optimization. */
    private val nativeReady: Boolean =
        runCatching { System.loadLibrary("velamem") }
            .onFailure { Timber.w(it, "libvelamem unavailable, native purge disabled") }
            .isSuccess

    /** One daemon thread, created lazily. Never the main thread: a purge walks the allocator's free
     *  lists behind its global lock, and that is not something to do on the UI thread. */
    private val purgeExec by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mem-purge").apply { isDaemon = true }
        }
    }

    /** Coalesces a burst of trims into one purge. The OS routinely sends several levels in a row. */
    private val purgePending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Purge after [PURGE_DELAY_MS], not immediately: the WebView reapers post their `destroy()` to
     * the main looper and `VelaApp` clears Coil right after [dispatch] returns, so an inline purge
     * would run BEFORE the memory it is meant to reclaim has actually been freed and reclaim close
     * to nothing.
     *
     * [all] picks the lever. M_PURGE_ALL walks every arena and is documented as able to take over
     * twice as long as a plain M_PURGE, so it is spent only on levels [isSevere] already treats as
     * "drop it"; a routine UI_HIDDEN gets the cheap one.
     */
    private fun schedulePurge(all: Boolean) {
        if (!nativeReady) return
        // Debug-only kill switch, so the purge's contribution can be A/B measured on ONE binary:
        //     adb shell setprop debug.vela.nopurge true    # then relaunch, this is read per-trim
        // Without it the only way to attribute a delta is to compare two different builds, which
        // also differ in background settling and cannot be paired inside a single run.
        if (debugFlag("debug.vela.nopurge") == true) {
            Timber.i("MemoryPressure native purge suppressed by debug.vela.nopurge")
            return
        }
        if (!purgePending.compareAndSet(false, true)) return
        val scheduled = runCatching {
            purgeExec.schedule({
                purgePending.set(false)
                val t0 = android.os.SystemClock.uptimeMillis()
                val mode = runCatching { nativePurge(all) }.getOrDefault(0)
                Timber.i(
                    "MemoryPressure native purge all=%b mode=%d took=%dms",
                    all, mode, android.os.SystemClock.uptimeMillis() - t0,
                )
            }, PURGE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (scheduled == null) purgePending.set(false) // executor rejected; let the next trim retry
    }

    /** Long enough for the main-looper-posted WebView destroys and the Coil clear to have landed. */
    private const val PURGE_DELAY_MS = 750L

    /**
     * The app is backgrounded or the OS is genuinely short of memory, so caches that only speed
     * things up should go. Everything at or above this level is a "drop it" signal.
     */
    fun isSevere(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    /** Only the harshest levels, where we drop things that cost real time to rebuild. */
    fun isCritical(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
}
