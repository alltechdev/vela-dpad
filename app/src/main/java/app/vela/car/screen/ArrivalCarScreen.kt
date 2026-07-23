package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import app.vela.R
import app.vela.core.model.ParkedSpot

/** The end-of-trip card: "You've arrived", with a one-tap "Save parking spot" for where the car
 *  now sits (the phone's Park feature reads the same [app.vela.core.data.ParkingStore], so the
 *  spot shows up there and on the landing screen's "Find my car" row). Dismissing either way
 *  fully stops the nav session - mirroring the phone's arrival-card dismiss. */
class ArrivalCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    private val destLabel: String,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val builder = MessageTemplate.Builder(destLabel.ifBlank { carContext.getString(R.string.nav_arrived) })
            .setTitle(carContext.getString(R.string.nav_arrived))
            .setHeaderAction(Action.BACK)
        val loc = deps.locationProvider.lastKnown()
        if (loc != null) {
            builder.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.map_parking_save))
                    .setOnClickListener {
                        deps.parkingStore.save(ParkedSpot(loc.lat, loc.lng, System.currentTimeMillis()))
                        CarToast.makeText(carContext, R.string.map_parking_saved, CarToast.LENGTH_SHORT).show()
                        finishTrip()
                    }
                    .build(),
            )
        }
        builder.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.nav_done))
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { finishTrip() }
                .build(),
        )
        return builder.build()
    }

    private fun finishTrip() {
        // Clear the arrived session (the phone's arrival-card dismiss does the same via clearRoute)
        // so the landing screen's resume logic never sees a stale arrived=true state.
        deps.navSession.stop()
        runCatching { app.vela.service.NavigationService.stop(carContext.applicationContext) }
        screenManager.pop()
    }
}
