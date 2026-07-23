package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import app.vela.R
import app.vela.core.model.Place

/** Place details on the car (a PaneTemplate the host content-gates while driving, full when
 *  parked): rating, address, category, phone, open status - whatever the search result carried.
 *  "Go" starts the normal route preview. */
class PlaceDetailsCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    private val place: Place,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        place.rating?.let { r ->
            val stars = "\u2605 %.1f".format(r) + (place.reviewCount?.let { " ($it)" } ?: "")
            pane.addRow(Row.Builder().setTitle(stars).build())
        }
        place.address?.takeIf { it.isNotBlank() }?.let {
            pane.addRow(Row.Builder().setTitle(it).build())
        }
        place.category?.takeIf { it.isNotBlank() }?.let {
            pane.addRow(Row.Builder().setTitle(it).build())
        }
        place.phone?.takeIf { it.isNotBlank() }?.let {
            pane.addRow(Row.Builder().setTitle(it).build())
        }
        if (place.rating == null && place.address.isNullOrBlank() && place.category.isNullOrBlank() && place.phone.isNullOrBlank()) {
            pane.addRow(Row.Builder().setTitle(place.name).build())
        }
        pane.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.car_go))
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener {
                    screenManager.push(RoutePreviewCarScreen(carContext, deps, place.name, place.location))
                }
                .build(),
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle(place.name)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
