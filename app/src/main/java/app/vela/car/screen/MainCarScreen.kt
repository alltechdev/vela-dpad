package app.vela.car.screen

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.DurationSpan
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vela.car.CarMapRenderer
import app.vela.car.CarVoiceSearch
import app.vela.core.model.LatLng
import app.vela.core.model.ShortcutKind
import app.vela.core.voice.CarCommands

/**
 * Car landing screen: Home/Work shortcuts + recent + saved destinations, and a Search action.
 * Tapping a row previews a route to it ([RoutePreviewCarScreen]). Reuses [CarDeps] stores.
 *
 * Owns a [CarMapRenderer] so the landing map is a LIVE, clean browse map centred on you — otherwise
 * the surface keeps the previous nav screen's final frame and the finished trip's route lingers here.
 */
class MainCarScreen(carContext: CarContext, private val deps: CarDeps) :
    Screen(carContext), DefaultLifecycleObserver {

    private val voice = CarVoiceSearch(carContext, deps.whisper)

    // Live drive-time subtitles for the Home/Work rows, fetched once per screen instance (a cheap
    // pair of directions calls); rows render plain until they land, then invalidate() fills them in.
    private val etaSecs = HashMap<String, Long>()
    private var etaFetched = false

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        // Shared renderer, browse mode (clean live map, no route). Never clear the callback / stop the
        // collector on transitions — the shared renderer lives for the session (VelaCarSession stops it).
        val renderer = deps.mapRenderer(carContext)
        renderer.follow()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
    }

    override fun onGetTemplate(): Template {
        fetchEtasOnce()
        val list = ItemList.Builder()

        // PlaceListNavigationTemplate caps the list at MAX_ROWS (6) — adding more THROWS. Build the
        // candidates (Home, Work, recents, saved) and take the first 6, de-duped by location —
        // minus one when the parked-car row below needs its slot.
        val parked = deps.parkingStore.current()
        val rows = buildList {
            deps.shortcuts.get(ShortcutKind.HOME)?.let { add(it.name to it.location) }
            deps.shortcuts.get(ShortcutKind.WORK)?.let { add(it.name to it.location) }
            deps.recentPlaces.recent().forEach { add(it.place.name to it.place.location) }
            deps.savedPlaces.saved().forEach { add(it.name to it.location) }
        }.distinctBy { it.second.lat to it.second.lng }
            .take(MAX_ROWS - 1 - (if (parked != null) 1 else 0)) // categories row + optional parked row

        // Categories lives IN the list (Google Maps-style row with a colored tile): the landing
        // template's strip renders only two actions on real hosts, and a third silently pushed the
        // MIC off (head-unit report) - the strip stays mic + Search.
        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(app.vela.R.string.car_categories))
                .setImage(icon(app.vela.R.drawable.ic_car_categories_tile), Row.IMAGE_TYPE_SMALL)
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CategoriesCarScreen(carContext, deps)) }
                .build(),
        )
        rows.forEach { (name, loc) -> list.addItem(destRow(name, loc)) }
        // Parked car: when a spot is saved (phone Park or the car's arrival card), one row routes
        // back to it. After the destination rows so Home/Work keep their muscle-memory slots.
        parked?.let { spot ->
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(app.vela.R.string.map_parking_find))
                    .setImage(icon(app.vela.R.drawable.ic_car_parking), Row.IMAGE_TYPE_SMALL)
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            RoutePreviewCarScreen(
                                carContext, deps,
                                carContext.getString(app.vela.R.string.map_parking_find),
                                app.vela.core.model.LatLng(spot.lat, spot.lng),
                            ),
                        )
                    }
                    .build(),
            )
        }
        if (rows.isEmpty() && parked == null) {
            list.setNoItemsMessage(carContext.getString(app.vela.R.string.car_no_destinations))
        }

        val search = Action.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_search))
            .setIcon(icon(android.R.drawable.ic_menu_search))
            .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
            .build()
        // Voice search straight from the landing screen - no need to open Search first. The
        // transcript opens SearchCarScreen with the query already searched.
        // Mic LAST: when this host runs out of strip slots it keeps the TAIL of the list (head-unit
        // evidence across three builds - the mic sat first and was the one dropped every time).
        val strip = ActionStrip.Builder()
        strip.addAction(search)
        if (voice.available()) {
            strip.addAction(voice.micAction(this, ::invalidate, ::handleVoice))
        }

        return PlaceListNavigationTemplate.Builder()
            .setItemList(list.build())
            .setTitle(carContext.getString(app.vela.R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(strip.build())
            .build()
    }

    private fun destRow(name: String, dest: LatLng): Row {
        val b = Row.Builder()
            .setTitle(name)
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(RoutePreviewCarScreen(carContext, deps, name, dest)) }
        // Live drive time as the subtitle once fetched (Home/Work only - see fetchEtasOnce).
        etaSecs[rowKey(dest)]?.let { secs ->
            val t = SpannableString(" ")
            t.setSpan(DurationSpan.create(secs), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            b.addText(CarText.create(t))
        }
        return b.build()
    }

    private fun rowKey(loc: LatLng) = "%.5f,%.5f".format(loc.lat, loc.lng)

    /** Voice beyond search ([CarCommands]): "navigate home" previews the trip directly, "find my
     *  car" opens the parked spot, "mute"/"unmute" flip guidance. Anything unmatched (or a command
     *  whose target isn't set up, e.g. no Home shortcut) falls back to a plain search. */
    private fun handleVoice(q: String) {
        when (val c = CarCommands.parse(q)) {
            CarCommands.Command.GoHome -> previewShortcut(ShortcutKind.HOME, q)
            CarCommands.Command.GoWork -> previewShortcut(ShortcutKind.WORK, q)
            CarCommands.Command.FindMyCar -> {
                val spot = deps.parkingStore.current()
                if (spot != null) {
                    screenManager.push(
                        RoutePreviewCarScreen(
                            carContext, deps,
                            carContext.getString(app.vela.R.string.map_parking_find),
                            LatLng(spot.lat, spot.lng),
                        ),
                    )
                } else {
                    androidx.car.app.CarToast.makeText(
                        carContext, app.vela.R.string.map_parking_no_fix, androidx.car.app.CarToast.LENGTH_SHORT,
                    ).show()
                }
            }
            CarCommands.Command.Mute -> { deps.voiceGuide.muted = true; invalidate() }
            CarCommands.Command.Unmute -> { deps.voiceGuide.muted = false; invalidate() }
            CarCommands.Command.EndNav -> Unit // nothing to end from the landing screen
            // Voice -> straight to RESULTS ("searching..." then the list), not a search box.
            is CarCommands.Command.Search ->
                screenManager.push(CategoryResultsCarScreen(carContext, deps, c.query, c.query, alongRoute = false))
        }
    }

    private fun previewShortcut(kind: ShortcutKind, fallbackQuery: String) {
        val sc = deps.shortcuts.get(kind)
        if (sc != null) {
            screenManager.push(RoutePreviewCarScreen(carContext, deps, sc.name, sc.location))
        } else {
            screenManager.push(SearchCarScreen(carContext, deps, fallbackQuery))
        }
    }

    /** Fetch live ETAs for Home + Work (the two rows a commuter reads daily). Once per screen;
     *  best-effort - a failed fetch just leaves the row plain. */
    private fun fetchEtasOnce() {
        if (etaFetched) return
        etaFetched = true
        val targets = listOfNotNull(
            deps.shortcuts.get(ShortcutKind.HOME)?.location,
            deps.shortcuts.get(ShortcutKind.WORK)?.location,
        )
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            val from = deps.locationProvider.lastKnown() ?: return@launch
            var got = false
            targets.forEach { dest ->
                val r = runCatching {
                    deps.mapDataSource.directions(from, dest, app.vela.core.model.TravelMode.DRIVE).firstOrNull()
                }.getOrNull()
                if (r != null) {
                    etaSecs[rowKey(dest)] = (r.durationInTrafficSeconds ?: r.durationSeconds).toLong()
                    got = true
                }
            }
            if (got) invalidate()
        }
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private companion object {
        // PlaceListNavigationTemplate hard-caps its list at 6 rows (exceeding it throws at build).
        const val MAX_ROWS = 6
    }
}
