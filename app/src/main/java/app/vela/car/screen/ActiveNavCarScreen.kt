package app.vela.car.screen

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarText
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.vela.car.ManeuverMapper
import app.vela.service.NavigationService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The active-guidance car screen: renders [NavSession] state into a [NavigationTemplate]
 *  (route map surface + next-maneuver card + destination ETA). Voice already speaks from NavSession.
 *  "End" stops the session; "Mute" toggles the voice; a faster-route offer is a tappable action. */
class ActiveNavCarScreen(carContext: CarContext, private val deps: CarDeps) :
    Screen(carContext), DefaultLifecycleObserver {

    // Leave the nav screen at most once — on arrival OR when nav stops (from here or elsewhere).
    private var left = false
    private val voice = app.vela.car.CarVoiceSearch(carContext, deps.whisper)
    // One head-up alert per faster-route offer (keyed by the candidate's duration+distance; a new
    // offer alerts again, the same one doesn't re-pop every state tick).
    private var alertedFasterKey = 0L

    private val navManager by lazy { carContext.getCarService(NavigationManager::class.java) }
    // The host only renders the RoutingInfo turn card once the app DECLARES it is navigating via
    // NavigationManager.navigationStarted() (the DestinationTravelEstimate shows without it — which
    // is why the ETA appeared but the next-turn banner never did). navigationStarted/Ended must be
    // balanced (a second navigationStarted() before navigationEnded() throws), so gate on this flag.
    private var navDeclared = false
    private var callbackSet = false

    init {
        // Marker so the terminal-state handler can peel back to THIS screen no matter what got
        // pushed on top mid-drive (along-route search, stops, categories) - a blind pop() removed
        // the covering screen instead and stranded a dead guidance screen (review finding).
        marker = NAV_MARKER
        lifecycle.addObserver(this)
        // Re-render on each nav-state change (state emits ~1 Hz during guidance); pop when the trip
        // ends. Voice already announces the arrival, so returning to the landing screen is enough.
        lifecycleScope.launch {
            deps.navSession.state.collect { s ->
                if (!left && (s.arrived || !s.navigating)) {
                    left = true
                    endNavDeclaration()
                    // Peel any covering screens first, then remove guidance itself.
                    runCatching { screenManager.popTo(NAV_MARKER) }
                    screenManager.pop()
                    // Arrival (not a manual End): land on the arrival card so the driver can save
                    // the parking spot where the car now sits - the phone offers the same.
                    if (s.arrived) {
                        screenManager.push(ArrivalCarScreen(carContext, deps, s.destinationLabel))
                    }
                } else {
                    declareNavStartedIfNeeded()
                    maybeAlertFasterRoute()
                    invalidate()
                }
            }
        }
    }

    /** Set the required callback (once) and declare navigation started so the host renders the turn
     *  card. The callback MUST be set before navigationStarted(); the host calls onStopNavigation()
     *  when it (not the user's End button) wants nav to stop. */
    private fun declareNavStartedIfNeeded() {
        if (!callbackSet) {
            runCatching {
                navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() { stopNav() }
                    override fun onAutoDriveEnabled() {}
                })
            }.onSuccess { callbackSet = true }
        }
        if (callbackSet && !navDeclared && deps.navSession.state.value.navigating) {
            runCatching { navManager.navigationStarted() }.onSuccess { navDeclared = true }
        }
    }

    private fun endNavDeclaration() {
        if (navDeclared) {
            runCatching { navManager.navigationEnded() }
            navDeclared = false
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // Claim the surface with the SHARED renderer + nav mode (route + puck, follow). Never clear it
        // on stop and never stop the collector here — the shared renderer lives for the session (a stop
        // here would race the next screen's start); VelaCarSession stops it on destroy.
        val renderer = deps.mapRenderer(carContext)
        renderer.follow() // nav mode: draws from NavSession.navigating
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
        // Declare navigation to the host now (nav is active by the time this screen shows) so the
        // first template already carries a rendered turn card, not just the ETA.
        declareNavStartedIfNeeded()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Balance the navigationStarted() declaration if the screen tears down without a terminal
        // state pass (e.g. the whole car session ends) — leaving it "started" would wedge the host's
        // nav state for the next session. onDestroy (not onStop) so a transient cover during nav
        // doesn't prematurely retract the turn card.
        endNavDeclaration()
    }

    override fun onGetTemplate(): Template {
        val s = deps.navSession.state.value
        val imperial = app.vela.ui.Units.imperial.value
        val next = s.route?.maneuvers?.getOrNull(s.nav.stepIndex)
        val then = s.route?.maneuvers?.getOrNull(s.nav.stepIndex + 1) // "then …" junction preview

        val info = ManeuverMapper.routingInfo(next, then, s.nav.distanceToNextManeuver, imperial)
        val estimate = ManeuverMapper.destinationEstimate(
            s.remainingDistance, s.remainingDuration, System.currentTimeMillis(), imperial,
        )

        // Feed the host's navigation DATA channel (a Trip), separate from the template's RoutingInfo.
        // Gearhead logged "No corresponding nav client source / Unable to send navigation status"
        // without it — on this Honda the turn card appears gated on the Trip data, not just the
        // template. Best-effort; guarded so a build/host hiccup can't blank the template.
        if (navDeclared && next != null) {
            runCatching {
                val secsToStep = if (s.remainingDistance > 0.0)
                    s.remainingDuration * (s.nav.distanceToNextManeuver / s.remainingDistance) else 0.0
                val trip = androidx.car.app.navigation.model.Trip.Builder()
                    .addStep(
                        ManeuverMapper.carStep(next),
                        ManeuverMapper.stepEstimate(s.nav.distanceToNextManeuver, secsToStep, System.currentTimeMillis(), imperial),
                    )
                    .addDestination(
                        androidx.car.app.navigation.model.Destination.Builder()
                            .setName(s.destinationLabel.ifBlank { "Destination" })
                            .build(),
                        estimate,
                    )
                    .build()
                navManager.updateTrip(trip)
            }
        }

        val strip = ActionStrip.Builder()
        // In-drive search-along-route: the mic and a search action (both push the corridor-filtered
        // SearchCarScreen; a pick becomes a stop). Icon-only - the strip's text budget stays on
        // Faster/Mute/End. The mic's transcript skips the keyboard entirely.
        // Same preference-law mic as the landing screen, along-route flavored.
        if (voice.available()) {
            strip.addAction(
                voice.micAction(
                    this, ::invalidate,
                    onTranscript = { q -> screenManager.push(SearchCarScreen(carContext, deps, q, alongRoute = true)) },
                    onSystem = { screenManager.push(SearchCarScreen(carContext, deps, alongRoute = true, voiceEntry = true)) },
                ),
            )
        }
        strip.addAction(
            carAction(android.R.drawable.ic_menu_search) {
                screenManager.push(SearchCarScreen(carContext, deps, alongRoute = true))
            },
        )
        // A faster-route offer (when present) takes the first slot as a tappable "Faster −N min".
        val faster = s.fasterRoute
        if (faster != null && s.fasterSavingSeconds >= 60) {
            val mins = (s.fasterSavingSeconds / 60).roundToInt().coerceAtLeast(1)
            strip.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(app.vela.R.string.car_faster_route, mins))
                    .setOnClickListener { deps.navSession.acceptFasterRoute(); invalidate() }
                    .build(),
            )
        } else {
            val muted = deps.voiceGuide.muted
            strip.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(if (muted) app.vela.R.string.car_unmute else app.vela.R.string.car_mute))
                    .setOnClickListener { deps.voiceGuide.muted = !deps.voiceGuide.muted; invalidate() }
                    .build(),
            )
        }
        strip.addAction(
            Action.Builder()
                .setTitle(carContext.getString(app.vela.R.string.car_end))
                // A background colour is ONLY allowed on a PRIMARY action — without FLAG_PRIMARY the
                // host throws "Background color can only be set for primary actions" building the strip.
                .setFlags(Action.FLAG_PRIMARY)
                .setBackgroundColor(CarColor.RED)
                .setOnClickListener { stopNav() }
                .build(),
        )

        // Google-style map controls: recenter (re-follow the puck) + zoom in/out; plus the stop
        // manager while the trip actually has stops (max 4 actions on this strip).
        val mapStripB = ActionStrip.Builder()
            .addAction(carAction(android.R.drawable.ic_menu_mylocation) { deps.mapRenderer(carContext).follow() })
            .addAction(carAction(android.R.drawable.ic_menu_add) { deps.mapRenderer(carContext).zoomBy(1.0) })
            .addAction(carAction(android.R.drawable.ic_menu_revert) { deps.mapRenderer(carContext).zoomBy(-1.0) })
        if (deps.navSession.remainingStops().isNotEmpty()) {
            mapStripB.addAction(carAction(android.R.drawable.ic_menu_agenda) { screenManager.push(StopsCarScreen(carContext, deps)) })
        }
        val mapStrip = mapStripB.build()

        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setDestinationTravelEstimate(estimate)
            .setActionStrip(strip.build())
            .setMapActionStrip(mapStrip)
            .build()
    }

    private fun carAction(iconRes: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(
                androidx.car.app.model.CarIcon.Builder(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, iconRes),
                ).build(),
            )
            .setOnClickListener(onClick)
            .build()

    /** Surface a faster-route offer as a HEAD-UP alert (Car API 5+), not just a strip button the
     *  driver never notices. Accept/dismiss mirror the strip action; the strip button stays as the
     *  fallback (and for hosts below API 5). One alert per distinct candidate. */
    private fun maybeAlertFasterRoute() {
        val s = deps.navSession.state.value
        val faster = s.fasterRoute ?: return
        if (s.fasterSavingSeconds < 60) return
        if (runCatching { carContext.carAppApiLevel < 5 }.getOrDefault(true)) return
        val key = (faster.durationSeconds.toLong() shl 20) xor faster.distanceMeters.toLong()
        if (key == alertedFasterKey) return
        alertedFasterKey = key
        val mins = (s.fasterSavingSeconds / 60).roundToInt().coerceAtLeast(1)
        runCatching {
            val alert = Alert.Builder(
                ALERT_FASTER_ID,
                CarText.create(carContext.getString(app.vela.R.string.car_faster_route, mins)),
                ALERT_DURATION_MS,
            )
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(app.vela.R.string.car_go))
                        .setOnClickListener { deps.navSession.acceptFasterRoute(); invalidate() }
                        .build(),
                )
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(app.vela.R.string.nav_done))
                        .setOnClickListener { deps.navSession.dismissFasterRoute(); invalidate() }
                        .build(),
                )
                .setCallback(object : AlertCallback {
                    override fun onCancel(reason: Int) {}
                    override fun onDismiss() {}
                })
                .build()
            carContext.getCarService(AppManager::class.java).showAlert(alert)
        }
    }

    private fun stopNav() {
        // Only stop — the state collector observes navigating=false and pops once (no double-pop).
        deps.navSession.stop()
        runCatching { NavigationService.stop(carContext.applicationContext) }
    }

    private companion object {
        const val NAV_MARKER = "active-nav"
        const val ALERT_FASTER_ID = 1
        const val ALERT_DURATION_MS = 15_000L
    }
}
