package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.SearchTemplate
import androidx.lifecycle.lifecycleScope
import app.vela.car.CarVoiceSearch
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
    private val voice = CarVoiceSearch(carContext, deps.whisper)
    private var voiceQuery: String? = null // last transcript, echoed into the search field

    init {
        initialQuery?.let { voiceQuery = it; runSearch(it) }
    }

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = runSearch(searchText)
            override fun onSearchSubmitted(searchText: String) = runSearch(searchText)
        }
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
        voiceQuery?.let { builder.setInitialSearchText(it) }
        if (voice.available()) {
            builder.setActionStrip(
                ActionStrip.Builder().addAction(
                    voice.micAction(this, ::invalidate) { q ->
                        voiceQuery = q
                        runSearch(q)
                    },
                ).build(),
            )
        }
        if (voice.listening || searching) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            if (results.isEmpty()) {
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
        screenManager.push(RoutePreviewCarScreen(carContext, deps, p.name, p.location))
    }
}
