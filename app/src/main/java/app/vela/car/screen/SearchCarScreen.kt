package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.SearchTemplate
import androidx.lifecycle.lifecycleScope
import app.vela.core.data.RouteCorridor
import app.vela.core.model.Place
import app.vela.core.nav.NavSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Destination search on the car: debounced [app.vela.core.data.MapDataSource.search] biased to the
 *  last known location; tapping a result previews a route to it. A mic action (hosts on Car API 5+,
 *  when the on-device model is installed) records from the CAR's mic and transcribes with the same
 *  Whisper pipeline as the phone - see [CarVoiceSearch].
 *
 *  [alongRoute] flips it into the in-drive "search along route" mode: results are fetched around
 *  the route's midpoint and filtered to the corridor ([RouteCorridor], same as the phone), and
 *  tapping one ADDS IT AS A STOP via [NavSession.addStop] (the drive replans through it) instead
 *  of previewing a new trip. */
class SearchCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    initialQuery: String? = null, // a transcript from another screen's mic, searched on entry
    private val alongRoute: Boolean = false,
) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    private var searching = false
    private var searchJob: Job? = null
    private var voiceQuery: String? = null // last transcript, echoed into the search field
    private val voiceLocal by lazy { app.vela.car.CarVoiceSearch(carContext, deps.whisper) }

    init {
        // Plain search only - NO command dispatch and NO pushes from init: constructor args are
        // evaluated before the caller's own push, so anything pushed here gets buried under this
        // screen (review round 2). Commands from mics are routed by VoiceCommandRouter instead.
        initialQuery?.let { voiceQuery = it; runSearch(it) }
    }

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = runSearch(searchText)
            override fun onSearchSubmitted(searchText: String) = handleSubmitted(searchText)
        }
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(false) // keyboard only on a bar tap (user request)
        voiceQuery?.let { builder.setInitialSearchText(it) }
        // The host's in-field mic is the SYSTEM recognizer and cannot be removed or rewired. For
        // a SYSTEM-resolved user that is correct - one mic, theirs. For a LOCAL (Vela) pin it
        // would be a silent reroute to Google, so LOCAL mode adds the Vela strip mic back: its
        // transcript stays fully on-device (review round 2 - the pin holds on every surface).
        if (app.vela.ui.VoiceSearch.resolvedMode(carContext) == app.vela.ui.VoiceSearch.Mode.LOCAL) {
            builder.setActionStrip(
                androidx.car.app.model.ActionStrip.Builder().addAction(
                    voiceLocal.micAction(
                        this, ::invalidate,
                        onTranscript = { q -> voiceQuery = q; handleSubmitted(q) },
                    ),
                ).build(),
            )
        }
        if (searching) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            if (results.isEmpty() && alongRoute) {
                // Google Maps-style: in-drive search offers the quick categories UNDER the bar
                // before you type (user request) - each goes straight to corridor results whose
                // pick becomes a stop. Exactly 6 rows = the template's row cap.
                listOf(
                    Triple(app.vela.R.string.cat_gas, "Gas", app.vela.R.drawable.ic_car_gas),
                    Triple(app.vela.R.string.cat_food, "Food", app.vela.R.drawable.ic_car_food),
                    Triple(app.vela.R.string.cat_coffee, "Coffee", app.vela.R.drawable.ic_car_coffee),
                    Triple(app.vela.R.string.cat_groceries, "Groceries", app.vela.R.drawable.ic_car_grocery),
                    Triple(app.vela.R.string.cat_parking, "Parking", app.vela.R.drawable.ic_car_parking),
                    Triple(app.vela.R.string.cat_charging, "EV charging", app.vela.R.drawable.ic_car_charging),
                ).forEach { (labelRes, query, iconRes) ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(carContext.getString(labelRes))
                            .setImage(
                                androidx.car.app.model.CarIcon.Builder(
                                    androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, iconRes),
                                ).build(),
                                Row.IMAGE_TYPE_SMALL,
                            )
                            .setOnClickListener {
                                screenManager.push(
                                    CategoryResultsCarScreen(carContext, deps, carContext.getString(labelRes), query, true),
                                )
                            }
                            .build(),
                    )
                }
            } else if (results.isEmpty()) {
                list.setNoItemsMessage(carContext.getString(app.vela.R.string.car_search_hint))
            } else {
                results.take(6).forEach { p ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(p.name)
                            .apply { p.address?.let { addText(it) } }
                            // NOT browsable — SearchTemplate rows are plain clickable results (browsable
                            // implies a drill-in sublist and isn't valid here). onClick pushes preview.
                            .setOnClickListener { onPick(p) }
                            .build(),
                    )
                }
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    /** Submitted text OR a mic transcript: voice commands act ("navigate home" routes, "mute"
     *  mutes) and everything else searches. */
    private fun handleSubmitted(searchText: String) {
        // Car searches join the SAME history the phone records (they never did - a port gap the
        // user caught): submitted text and mic transcripts only, never per-keystroke suggestions.
        if (searchText.isNotBlank()) deps.recentSearches.add(searchText)
        when (val c = app.vela.core.voice.CarCommands.parse(searchText)) {
            app.vela.core.voice.CarCommands.Command.Mute -> deps.voiceGuide.muted = true
            app.vela.core.voice.CarCommands.Command.Unmute -> deps.voiceGuide.muted = false
            app.vela.core.voice.CarCommands.Command.GoHome -> shortcutOrSearch(app.vela.core.model.ShortcutKind.HOME, searchText)
            app.vela.core.voice.CarCommands.Command.GoWork -> shortcutOrSearch(app.vela.core.model.ShortcutKind.WORK, searchText)
            app.vela.core.voice.CarCommands.Command.FindMyCar -> {
                val spot = deps.parkingStore.current()
                if (spot != null) {
                    screenManager.push(
                        RoutePreviewCarScreen(
                            carContext, deps,
                            carContext.getString(app.vela.R.string.map_parking_find),
                            app.vela.core.model.LatLng(spot.lat, spot.lng),
                        ),
                    )
                } else {
                    androidx.car.app.CarToast.makeText(carContext, app.vela.R.string.map_parking_no_fix, androidx.car.app.CarToast.LENGTH_SHORT).show()
                }
            }
            app.vela.core.voice.CarCommands.Command.EndNav -> {
                val nav = deps.navSession.state.value
                if (nav.navigating) {
                    deps.navSession.stop()
                    runCatching { app.vela.service.NavigationService.stop(carContext.applicationContext) }
                    screenManager.popToRoot()
                } else {
                    androidx.car.app.CarToast.makeText(carContext, app.vela.R.string.map_parking_no_fix, androidx.car.app.CarToast.LENGTH_SHORT).show()
                }
            }
            is app.vela.core.voice.CarCommands.Command.Search -> runSearch(c.query)
        }
    }

    private fun shortcutOrSearch(kind: app.vela.core.model.ShortcutKind, fallback: String) {
        val sc = deps.shortcuts.get(kind)
        if (sc != null) screenManager.push(RoutePreviewCarScreen(carContext, deps, sc.name, sc.location))
        else runSearch(fallback)
    }

    private fun runSearch(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) {
            results = emptyList(); searching = false; invalidate(); return
        }
        searching = true
        invalidate()
        searchJob = lifecycleScope.launch {
            delay(300) // debounce
            val route = if (alongRoute) deps.navSession.state.value.route?.polyline else null
            val found = runCatching {
                if (route != null && route.size >= 2) {
                    // Same shape as the phone's searchAlongRoute: bias the fetch to the route's
                    // midpoint, then keep only places within the corridor.
                    RouteCorridor.alongRoute(deps.mapDataSource.search(text, route[route.size / 2]).places, route)
                } else {
                    deps.mapDataSource.search(text, deps.locationProvider.lastKnown()).places
                }
            }.getOrDefault(emptyList())
            results = found
            searching = false
            invalidate()
        }
    }

    private fun onPick(p: Place) {
        val nav = deps.navSession.state.value
        if (alongRoute && nav.navigating && !nav.arrived) {
            val loc = deps.locationProvider.lastKnown()
            if (loc != null) {
                deps.navSession.addStop(NavSession.NavStop(p.location, p.name), loc)
                CarToast.makeText(carContext, app.vela.R.string.car_stop_added, CarToast.LENGTH_SHORT).show()
                screenManager.pop() // back to guidance; the replan announces itself by voice
                return
            }
        }
        screenManager.push(RoutePreviewCarScreen(carContext, deps, p.name, p.location, p))
    }
}
