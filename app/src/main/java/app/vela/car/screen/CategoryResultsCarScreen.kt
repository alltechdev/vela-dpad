package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import app.vela.core.data.RouteCorridor
import app.vela.core.model.Place
import app.vela.core.nav.NavSession
import kotlinx.coroutines.launch

/** Category tap OR voice transcript -> RESULTS, directly ("searching..." spinner, then the list) - no search box with "Food" typed into it (user feedback:
 *  Google Maps AA goes straight to the list). Same fetch + pick semantics as SearchCarScreen's
 *  along-route mode: nearby when browsing, corridor-filtered add-a-stop while navigating. */
class CategoryResultsCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    private val title: String, // category label or a voice transcript - shown as the list title
    private val query: String, // what actually gets searched
    private val alongRoute: Boolean,
) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    private var loading = true
    private var fetched = false

    override fun onGetTemplate(): Template {
        if (!fetched) { fetched = true; fetch() }
        val builder = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
        if (loading) return builder.setLoading(true).build()
        val list = ItemList.Builder()
        if (results.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(app.vela.R.string.mapvm_no_results, title))
        } else {
            results.take(6).forEach { p ->
                list.addItem(
                    Row.Builder()
                        .setTitle(p.name)
                        .apply { p.address?.let { addText(it) } }
                        .setOnClickListener { onPick(p) }
                        .build(),
                )
            }
        }
        return builder.setSingleList(list.build()).build()
    }

    private fun fetch() {
        lifecycleScope.launch {
            val route = if (alongRoute) deps.navSession.state.value.route?.polyline else null
            results = runCatching {
                if (route != null && route.size >= 2) {
                    RouteCorridor.alongRoute(deps.mapDataSource.search(query, route[route.size / 2]).places, route)
                } else {
                    deps.mapDataSource.search(query, deps.locationProvider.lastKnown()).places
                }
            }.getOrDefault(emptyList())
            loading = false
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
                // Peel Results + Categories back to guidance (ActiveNavCarScreen's marker).
                runCatching { screenManager.popTo("active-nav") }
                return
            }
        }
        screenManager.push(RoutePreviewCarScreen(carContext, deps, p.name, p.location, p))
    }
}
