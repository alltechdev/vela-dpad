package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.SearchTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import app.vela.R
import app.vela.car.CarVoiceSearch
import app.vela.core.model.Place
import app.vela.voice.VoiceResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Destination search on the car: debounced [app.vela.core.data.MapDataSource.search] biased to the
 *  last known location; tapping a result previews a route to it. A mic action (hosts on Car API 5+,
 *  when the on-device model is installed) records from the CAR's mic and transcribes with the same
 *  Whisper pipeline as the phone - see [CarVoiceSearch]. */
class SearchCarScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    private var searching = false
    private var searchJob: Job? = null
    private val voice = CarVoiceSearch(carContext, deps.whisper)
    private var listening = false
    private var voiceQuery: String? = null // last transcript, echoed into the search field

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
                    Action.Builder()
                        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_mic)).build())
                        .setOnClickListener { onMicTapped() }
                        .build(),
                ).build(),
            )
        }
        if (listening || searching) {
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
                            .setOnClickListener {
                                screenManager.push(RoutePreviewCarScreen(carContext, deps, p.name, p.location))
                            }
                            .build(),
                    )
                }
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    /** Mic flow: tap to record from the car mic, tap again to stop early (like the phone's "done").
     *  The transcript lands in the search field (setInitialSearchText) and runs as a normal search. */
    private fun onMicTapped() {
        if (listening) { listening = false; return } // second tap = stop; capture() sees cancelled()
        if (!voice.hasPermission()) {
            voice.requestPermission { invalidate() }
            return
        }
        listening = true
        invalidate()
        CarToast.makeText(carContext, R.string.voice_capture_listening, CarToast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = voice.capture { !listening }
            listening = false
            when (result) {
                is VoiceResult.Text -> {
                    voiceQuery = result.query
                    runSearch(result.query)
                }
                else -> {
                    CarToast.makeText(carContext, R.string.car_voice_nothing, CarToast.LENGTH_SHORT).show()
                    invalidate()
                }
            }
        }
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
            val near = deps.locationProvider.lastKnown()
            val found = runCatching { deps.mapDataSource.search(text, near).places }.getOrDefault(emptyList())
            results = found
            searching = false
            invalidate()
        }
    }
}
