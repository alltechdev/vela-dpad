package app.vela.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import app.vela.R
import app.vela.ui.VoiceSearch
import app.vela.voice.VoiceResult
import app.vela.voice.WhisperRecognizer
import kotlinx.coroutines.launch

/**
 * In-car voice search: the car's microphone ([CarAudioRecord], Car API level 5+) feeding the same
 * on-device Whisper pipeline as the phone mic ([WhisperRecognizer.listen] via a [WhisperRecognizer
 * .PcmSource]). CarAudioRecord records 16 kHz mono PCM16 — exactly Whisper's input format — so the
 * only adaptation is byte-pairs-to-shorts.
 *
 * The phone's tier-2 path (a RECOGNIZE_SPEECH provider activity) can't exist on a head unit, so the
 * car mic shows whenever voice search is enabled and the on-device model is installed — regardless
 * of the phone's engine pin, because on the car this is the only path there is.
 */
class CarVoiceSearch(private val carContext: CarContext, private val whisper: WhisperRecognizer) {

    /** Can the mic do anything at all on this head unit? Host API level gate + user toggle + model. */
    fun available(): Boolean =
        runCatching { carContext.carAppApiLevel >= 5 }.getOrDefault(false) &&
            VoiceSearch.enabled.value && VoiceSearch.localReady(carContext)

    fun hasPermission(): Boolean = whisper.hasMicPermission()

    /** Ask the host to run the phone-side RECORD_AUDIO grant flow, then invalidate via [onDone]. */
    fun requestPermission(onDone: () -> Unit) {
        carContext.requestPermissions(listOf(android.Manifest.permission.RECORD_AUDIO)) { _, _ -> onDone() }
    }

    /** True while a capture is in flight. Screens read it for their loading state; a second mic tap
     *  flips it off, which [capture]'s cancelled() sees (= the phone's "done": transcribe what was heard). */
    var listening = false
        private set

    /** The mic [Action] for a screen's action strip. One shared flow: tap to record (permission
     *  prompt first if needed), tap again to stop early; [onUpdate] fires on state changes the
     *  screen should re-template for, [onTranscript] gets the cleaned query. No-speech toasts. */
    fun micAction(screen: Screen, onUpdate: () -> Unit, onTranscript: (String) -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_mic)).build())
            .setOnClickListener { tap(screen, onUpdate, onTranscript) }
            .build()

    private fun tap(screen: Screen, onUpdate: () -> Unit, onTranscript: (String) -> Unit) {
        if (listening) { listening = false; return }
        if (!hasPermission()) {
            requestPermission { screen.invalidate() }
            return
        }
        listening = true
        onUpdate()
        CarToast.makeText(carContext, R.string.voice_capture_listening, CarToast.LENGTH_SHORT).show()
        screen.lifecycleScope.launch {
            val result = capture { !listening }
            listening = false
            when (result) {
                is VoiceResult.Text -> onTranscript(result.query)
                else -> {
                    CarToast.makeText(carContext, R.string.car_voice_nothing, CarToast.LENGTH_SHORT).show()
                    onUpdate()
                }
            }
        }
    }

    /** Record one utterance from the car mic and transcribe it. Suspends until speech ends (or
     *  [cancelled] returns true / the 15 s cap); safe to call from the screen's lifecycleScope. */
    suspend fun capture(cancelled: () -> Boolean): VoiceResult {
        val record = runCatching { CarAudioRecord.create(carContext) }.getOrNull()
            ?: return VoiceResult.Failed(VoiceResult.Reason.AUDIO_INIT, "CarAudioRecord.create failed")
        val bytes = ByteArray(WhisperRecognizer.VAD_WINDOW * 2)
        val source = object : WhisperRecognizer.PcmSource {
            override fun start() = record.startRecording()
            override fun read(buf: ShortArray, size: Int): Int {
                val n = record.read(bytes, 0, minOf(size * 2, bytes.size))
                if (n <= 0) return n
                // Little-endian PCM16 byte pairs -> shorts (an odd trailing byte is dropped).
                val shorts = n / 2
                for (i in 0 until shorts) {
                    buf[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                }
                return shorts
            }
            override fun stop() {
                runCatching { record.stopRecording() }
            }
        }
        return whisper.listen(onLevel = {}, onListening = {}, cancelled = cancelled, source = source)
    }
}
