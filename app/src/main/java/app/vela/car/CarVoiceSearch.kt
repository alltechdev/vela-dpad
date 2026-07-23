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

    /** Should the mic SHOW? Mirrors the phone: whenever the resolved mode is not NONE. NOT gated
     *  on the host API level - the landing templates once at session create, before the handshake
     *  reports carAppApiLevel (that gate silently hid the mic from exactly that screen). */
    fun available(): Boolean {
        // Cached ~5 s: resolvedMode walks PackageManager (binder IPC) and the nav screen
        // re-templates every state tick (review round 2). Settings flips land within a beat.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - availCheckedAt > 5_000) {
            availCached = VoiceSearch.resolvedMode(carContext) != VoiceSearch.Mode.NONE
            availCheckedAt = now
        }
        return availCached
    }
    private var availCached = false
    private var availCheckedAt = 0L

    fun hasPermission(): Boolean = whisper.hasMicPermission()

    /** Ask the host to run the phone-side RECORD_AUDIO grant flow, then invalidate via [onDone].
     *  Guarded: a host that can't service the request must degrade to a dead tap, not a crash card. */
    fun requestPermission(onDone: () -> Unit) {
        runCatching {
            carContext.requestPermissions(listOf(android.Manifest.permission.RECORD_AUDIO)) { _, _ -> onDone() }
        }
    }

    /** True while a capture is in flight. Screens read it for their loading state; a second mic tap
     *  flips it off, which [capture]'s cancelled() sees (= the phone's "done": transcribe what was heard). */
    var listening = false
        private set

    /** The mic [Action] for a screen's action strip. One shared flow: tap to record (permission
     *  prompt first if needed), tap again to stop early; [onUpdate] fires on state changes the
     *  screen should re-template for, [onTranscript] gets the cleaned query. No-speech toasts. */
    fun micAction(
        screen: Screen,
        onUpdate: () -> Unit,
        onTranscript: (String) -> Unit,
        onSystem: () -> Unit,
    ): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_mic2)).build())
            .setOnClickListener { tap(screen, onUpdate, onTranscript, onSystem) }
            .build()

    /** THE USER'S ENGINE PREFERENCE IS LAW (Settings -> Search): resolved LOCAL runs Vela's
     *  on-device Whisper through the car mic; resolved SYSTEM routes to [onSystem] (the search
     *  surface whose inline host mic is the system recognizer). A LOCAL failure toasts and stops -
     *  it never silently reroutes speech to Google. */
    private fun tap(screen: Screen, onUpdate: () -> Unit, onTranscript: (String) -> Unit, onSystem: () -> Unit) {
        when (VoiceSearch.resolvedMode(carContext)) {
            VoiceSearch.Mode.SYSTEM, VoiceSearch.Mode.NONE -> { onSystem(); return }
            VoiceSearch.Mode.LOCAL -> Unit
        }
        if (listening) { listening = false; return }
        if (!hasPermission()) {
            requestPermission { screen.invalidate() }
            return
        }
        listening = true
        onUpdate()
        // LONG: the short toast died ~2 s into a capture that legitimately runs up to 15 s,
        // leaving no sign the car was still listening.
        CarToast.makeText(carContext, R.string.voice_capture_listening, CarToast.LENGTH_LONG).show()
        screen.lifecycleScope.launch {
            carPeak = 0f
            var result = capture { !listening }
            // Car-mic ladder: retry ONCE on the PHONE's mic only when the car mic looks DEAD
            // (no transcript AND essentially zero audio level) or failed outright - a genuine
            // "tapped and said nothing" into a WORKING car mic must NOT start a second
            // full-length capture with media paused (review round 2).
            val carMicDead = result is VoiceResult.Failed || (result is VoiceResult.NoSpeech && carPeak < 0.02f)
            if (listening && result !is VoiceResult.Text && carMicDead) {
                CarToast.makeText(carContext, R.string.voice_capture_listening, CarToast.LENGTH_LONG).show()
                result = whisper.listen(onLevel = {}, onListening = {}, cancelled = { !listening })
            }
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
        if (runCatching { carContext.carAppApiLevel < 5 }.getOrDefault(true)) {
            return VoiceResult.Failed(VoiceResult.Reason.AUDIO_INIT, "host below Car API 5 (no CarAudioRecord)")
        }
        val record = runCatching { CarAudioRecord.create(carContext) }.getOrNull()
            ?: return VoiceResult.Failed(VoiceResult.Reason.AUDIO_INIT, "CarAudioRecord.create failed")
        val bytes = ByteArray(WhisperRecognizer.VAD_WINDOW * 2 + 1)
        val source = object : WhisperRecognizer.PcmSource {
            // A carried low byte from an odd-length read: DROPPING it desynced the little-endian
            // pairing for the rest of the capture (every later sample straddled two real samples -
            // review finding); prepending it to the next read keeps the stream aligned.
            private var carry = -1
            override fun start() = record.startRecording()
            override fun read(buf: ShortArray, size: Int): Int {
                var off = 0
                if (carry != -1) { bytes[0] = carry.toByte(); off = 1; carry = -1 }
                val n = record.read(bytes, off, minOf(size * 2 - off, bytes.size - off))
                if (n < 0) return n
                val total = off + n
                val shorts = total / 2
                if (total % 2 == 1) carry = bytes[total - 1].toInt() and 0xFF
                for (i in 0 until shorts) {
                    buf[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                }
                return shorts
            }
            override fun stop() {
                runCatching { record.stopRecording() }
            }
        }
        return whisper.listen(onLevel = { if (it > carPeak) carPeak = it }, onListening = {}, cancelled = cancelled, source = source)
    }

    // Peak level of the last car-mic capture - the dead-mic discriminator for the fallback ladder.
    private var carPeak = 0f
}
