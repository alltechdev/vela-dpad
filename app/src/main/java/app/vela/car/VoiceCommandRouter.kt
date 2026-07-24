package app.vela.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.ScreenManager
import app.vela.R
import app.vela.car.screen.CarDeps
import app.vela.car.screen.CategoryResultsCarScreen
import app.vela.car.screen.RoutePreviewCarScreen
import app.vela.core.model.LatLng
import app.vela.core.model.ShortcutKind
import app.vela.core.voice.CarCommands

/** Voice transcripts are interpreted HERE, at the calling screen, BEFORE anything is pushed:
 *  in-place commands (mute, end navigation) act and stay put, destination commands push their
 *  result screen directly, and only a real search pushes a results surface. The earlier design
 *  pushed the search screen first and parsed inside its init - which buried command results under
 *  a blank search screen and turned "end navigation" into a literal POI search (review round 2). */
object VoiceCommandRouter {

    fun handle(
        carContext: CarContext,
        deps: CarDeps,
        screenManager: ScreenManager,
        transcript: String,
        alongRoute: Boolean,
        onEndNav: (() -> Unit)? = null,
    ) {
        when (val c = CarCommands.parse(transcript)) {
            CarCommands.Command.Mute -> { deps.voiceGuide.muted = true; toast(carContext, R.string.car_mute) }
            CarCommands.Command.Unmute -> { deps.voiceGuide.muted = false; toast(carContext, R.string.car_unmute) }
            CarCommands.Command.EndNav ->
                if (onEndNav != null) onEndNav() else toast(carContext, R.string.car_no_route)
            CarCommands.Command.GoHome -> shortcut(carContext, deps, screenManager, ShortcutKind.HOME, transcript, alongRoute)
            CarCommands.Command.GoWork -> shortcut(carContext, deps, screenManager, ShortcutKind.WORK, transcript, alongRoute)
            CarCommands.Command.FindMyCar -> {
                val spot = deps.parkingStore.current()
                if (spot != null) {
                    screenManager.push(
                        RoutePreviewCarScreen(
                            carContext, deps,
                            carContext.getString(R.string.map_parking_find),
                            LatLng(spot.lat, spot.lng),
                        ),
                    )
                } else toast(carContext, R.string.map_parking_no_fix)
            }
            is CarCommands.Command.Search ->
                screenManager.push(CategoryResultsCarScreen(carContext, deps, c.query, c.query, alongRoute))
        }
    }

    private fun shortcut(
        carContext: CarContext,
        deps: CarDeps,
        screenManager: ScreenManager,
        kind: ShortcutKind,
        fallback: String,
        alongRoute: Boolean,
    ) {
        val sc = deps.shortcuts.get(kind)
        if (sc != null) screenManager.push(RoutePreviewCarScreen(carContext, deps, sc.name, sc.location))
        else screenManager.push(CategoryResultsCarScreen(carContext, deps, fallback, fallback, alongRoute))
    }

    private fun toast(carContext: CarContext, res: Int) =
        CarToast.makeText(carContext, res, CarToast.LENGTH_SHORT).show()
}
