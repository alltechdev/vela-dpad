package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import app.vela.R

/** The in-drive stop manager: the remaining stops in travel order; tapping one REMOVES it and the
 *  drive replans through the rest ([app.vela.core.nav.NavSession.removeStop]). One-tap removal, no
 *  confirm - it is a glanceable driving surface, and an accidental removal is one search away from
 *  restored. The phone's stops editor has no car counterpart until this. */
class StopsCarScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val stops = deps.navSession.remainingStops()
        val list = ItemList.Builder()
        if (stops.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.car_no_stops))
        } else {
            stops.forEach { stop ->
                list.addItem(
                    Row.Builder()
                        .setTitle(stop.label)
                        .addText(carContext.getString(R.string.car_stop_remove_hint))
                        .setOnClickListener {
                            val loc = deps.locationProvider.lastKnown()
                            if (loc != null) {
                                deps.navSession.removeStop(stop, loc)
                                CarToast.makeText(carContext, R.string.car_stop_removed, CarToast.LENGTH_SHORT).show()
                                invalidate()
                            }
                        }
                        .build(),
                )
            }
        }
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_stops))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
