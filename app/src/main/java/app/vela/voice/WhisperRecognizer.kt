package app.vela.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import timber.log.Timber
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device speech-to-text for voice search (tier-1). Loads **Whisper tiny (int8, multilingual)** +
 * **Silero VAD** through the bundled sherpa-onnx runtime, records from the mic, uses the VAD to spot
 * the end of speech, and returns the transcript. Nothing leaves the phone and no third-party voice
 * app is needed (that's tier-2 - the RECOGNIZE_SPEECH intent handoff in MapScreen).
 *
 * The Whisper recognizer loads lazily (~1 s) and is NO LONGER kept for the process lifetime: it costs
 * ~267 MB PSS, so it is dropped after [REAP_IDLE_MS] of quiet and on any severe memory trim, and
 * rebuilt on the next use (issue #83). The VAD is created per listen (it's tiny and holds streaming
 * state). R8 must keep `com.k2fsa.sherpa.onnx.**` (JNI resolves classes by name) - already in
 * `consumer-rules`/`proguard` for Piper.
 */
@Singleton
class WhisperRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Guards [recognizer]/[loadedLang] AND [leases]. A ReentrantLock rather than `synchronized`
     *  so [release] can `tryLock` instead of blocking - see there. */
    private val loadLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedLang: String? = null

    /**
     * Outstanding leases on the loaded recognizer: non-zero while a [listen] holds a pointer to it.
     * [release] refuses to free the model while this is set, because `OfflineRecognizer.release()`
     * frees C++ memory an in-flight decode is still reading - a use-after-free that takes the
     * process down rather than throwing, so `runCatching` around the decode cannot save it.
     *
     * **Only ever mutated while holding [loadLock]**, in [acquireRecognizer]/[releaseLease]. That is
     * the whole point. It used to be incremented in [listen] with no lock while [release] read it
     * with no lock, which is a check-then-act with a real window: the reaper could evaluate the
     * count as 0, a mic tap could then increment it and take the pointer off `ensureRecognizer`'s
     * lock-free fast path, and the reaper would go on to free the model under the running decode.
     * The window was not small either - any thread holding [loadLock] for a ~1 s model load parks
     * `release()` between its check and the free for that whole time.
     */
    private val leases = java.util.concurrent.atomic.AtomicInteger(0)

    /** Idle-reap timer, same idea as the web fetchers' `REAP_IDLE_MS` (issue #182). One daemon
     *  thread, shared, created lazily so a device that never loads the model never starts it. */
    private val reaper by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "asr-reaper").apply { isDaemon = true }
        }
    }
    @Volatile private var reapTask: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        // Measured on an M5 (2.9 GB, standardDebug, issue #83): the loaded Whisper tiny int8 model
        // costs ~267 MB PSS - ~101 MB of weights in scudo:secondary plus ~146 MB of onnxruntime
        // arena in scudo:primary. That was resident for the whole process with no way to reclaim it,
        // and it survived deleteAsrModel(). It is by far the largest single reclaimable allocation
        // in the app, so it releases on any severe trim and reloads (~1 s) on the next listen.
        app.vela.ui.MemoryPressure.register { level ->
            if (app.vela.ui.MemoryPressure.isSevere(level)) release()
        }
    }

    /**
     * Drop the model after a quiet period, on EVERY device, not just low-RAM ones.
     *
     * Warming at startup buys an instant first mic tap (a user asked for it, 2026-07-10) but the app
     * was then holding ~267 MB for the whole session on the CHANCE of a tap that many users never
     * make. Reaping after idle keeps the instant first tap and stops the model outliving the user's
     * interest in it; a later tap pays the same ~1 s load the very first one used to. Every load and
     * every listen re-arms the timer, so an active dictation session never reaps mid-use.
     */
    private fun armIdleReap() {
        reapTask?.cancel(false)
        reapTask = runCatching {
            reaper.schedule({ release() }, REAP_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * Free the native recognizer. Safe to call any time: no-op when nothing is loaded, and declines
     * while a listen holds a lease (see [leases]). The next [listen]/[warmUp] rebuilds it.
     *
     * The lease check happens INSIDE [loadLock], together with the free, so a listen cannot start
     * between the two. That is what makes this not a use-after-free.
     *
     * [wait] controls what happens when the lock is already held, which means a ~1 s native model
     * load is in progress. The default does NOT block: the trim path runs on the MAIN thread from
     * `Application.onTrimMemory`, and stalling the UI thread for a whole model load to reclaim
     * memory is a bad trade when the idle reaper or the next trim will retry anyway. `Remove model`
     * passes true, because there the user asked for it and a brief wait is correct.
     */
    fun release(wait: Boolean = false) {
        if (wait) loadLock.lock() else if (!loadLock.tryLock()) {
            Timber.tag(TAG).i("release skipped, model load in progress")
            return
        }
        try {
            if (leases.get() > 0) {
                Timber.tag(TAG).i("release skipped, listen in flight")
                return
            }
            val r = recognizer ?: return
            recognizer = null
            loadedLang = null
            runCatching { r.release() }
                .onFailure { Timber.tag(TAG).w(it, "recognizer release failed") }
            Timber.tag(TAG).i("recognizer released")
        } finally {
            loadLock.unlock()
        }
    }

    /**
     * Load if needed and take a LEASE, both under [loadLock]. Pair with [releaseLease] in a
     * `finally`. Returns null when the model is absent or the native load failed, in which case no
     * lease is taken.
     *
     * Callers must not hold the returned pointer past [releaseLease]: the lease is the only thing
     * stopping [release] from freeing it.
     */
    private fun acquireRecognizer(): OfflineRecognizer? {
        loadLock.lock()
        try {
            val r = ensureRecognizerLocked() ?: return null
            leases.incrementAndGet()
            return r
        } finally {
            loadLock.unlock()
        }
    }

    /**
     * Give back a lease taken by [acquireRecognizer]. Deliberately does NOT take [loadLock]: the
     * decrement happens only once the decode is finished with the pointer, so the worst a racing
     * [release] can do is read the pre-decrement value and conservatively decline. Taking the lock
     * here would instead park the end of every utterance behind an unrelated model load.
     */
    private fun releaseLease() {
        leases.decrementAndGet()
    }

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    @Volatile private var focusRequest: AudioFocusRequest? = null

    /** Take TRANSIENT audio focus so whatever is playing (music, a podcast) pauses while we listen,
     *  the way a phone assistant does - `AUDIOFOCUS_GAIN_TRANSIENT` (not `_MAY_DUCK`) makes media
     *  players pause rather than just duck. Abandoned in [abandonAudioFocus] the moment we stop. */
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    /** Give focus back so the paused music resumes right after the utterance. */
    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        focusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }

    private companion object {
        // Grep target for a tester's logcat: every listen() failure logs under this tag with its
        // VoiceResult.Reason, so "it doesn't work" arrives already diagnosed (issue #81).
        const val TAG = "VELAASR"
        // Set across the native model load, cleared once it returns. Still set at the next attempt =
        // the process died inside it. KEY_MODEL_BAD latches the quarantine so a bad model is not
        // retried on every launch; a fresh download clears it (see AsrModel.clearQuarantine)."
        const val KEY_LOAD_INFLIGHT = "asr_load_inflight"
        const val KEY_MODEL_BAD = "asr_model_bad"
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512            // Silero v4/v5 window at 16 kHz
        const val MAX_SECONDS = 15            // hard cap on one utterance
        // Drop the loaded model after this quiet period (issue #83). Matches the web
        // fetchers' REAP_IDLE_MS: long enough that a dictation session never reaps between
        // utterances, short enough that a session-long 267 MB hold cannot happen.
        const val REAP_IDLE_MS = 120_000L
    }

    fun isInstalled(): Boolean =
        AsrModel.isInstalled(context) &&
            !context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
                .getBoolean(KEY_MODEL_BAD, false)

    /** Lift the quarantine after a fresh download - the bad files are gone, so the next load is
     *  allowed to try again. Called by the installer path, never automatically. */
    fun clearQuarantine() {
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MODEL_BAD, false).putBoolean(KEY_LOAD_INFLIGHT, false).apply()
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The language Whisper is pinned to: the app language when it's one Vela supports, else
     *  auto-detect. Pinning matters - with auto-detect, a noisy capture can be misread as a whole
     *  other language and come back in the wrong script (a garbled far-field test transcribed to
     *  Cyrillic). The app language is what the user speaks to a maps app in practice. */
    private fun whisperLang(): String {
        // Android hands back the LEGACY code for Hebrew ("iw"), which isn't in SUPPORTED ("he"), so
        // without this normalize Hebrew dictation fell through to Whisper auto-detect instead of
        // being pinned. Whisper's own code for Hebrew is "he". (Ports upstream PR #87.)
        val l = app.vela.ui.AppLocale.effective().language.let { if (it == "iw") "he" else it }
        return l.takeIf { it in app.vela.ui.AppLocale.SUPPORTED } ?: ""
    }

    /** Build the recognizer ahead of the first mic tap, off the main thread. The ONNX load takes a
     *  second or two on a phone, which used to show as a "Getting ready" beat on the FIRST dictation
     *  of a session (user 2026-07-10); warmed, the mic listens immediately. Cheap to call when the
     *  model isn't installed (no-op), and safe to call repeatedly - the synchronized loader keeps a
     *  built recognizer for the current language. A mid-session app-language switch still rebuilds
     *  lazily on the next listen (rare enough not to chase). */
    fun warmUp() {
        if (!AsrModel.isInstalled(context)) return
        // On a low-RAM device the warm-up is a bad trade: it spends ~267 MB (measured, issue #83) at
        // EVERY launch to save ~1 s on a mic tap the user may never make, and refreshAsr() calls this
        // from VM init plus two LaunchedEffects. Those phones load on first listen instead. Roomier
        // devices keep the instant-mic behaviour they have always had.
        if (app.vela.ui.MemoryPressure.lowRam) {
            Timber.tag(TAG).i("skipping ASR warm-up on a low-RAM device, will load on first listen")
            return
        }
        Thread({ runCatching { ensureRecognizer() } }, "asr-warmup").start()
    }

    /** Load the Whisper recognizer once per language (rebuilt if the app language changes). Returns
     *  null if the model isn't installed or the native load fails - callers then fall back to the
     *  provider intent or hide the mic. */
    private fun ensureRecognizer(): OfflineRecognizer? {
        loadLock.lock()
        try {
            return ensureRecognizerLocked()
        } finally {
            loadLock.unlock()
        }
    }

    /**
     * The body of [ensureRecognizer]. **Caller must hold [loadLock].**
     *
     * There is deliberately NO lock-free fast path here any more. The old one
     * (`recognizer?.let { if (loadedLang == lang) return it }` before the lock) is what let a decode
     * obtain the native pointer while [release] was between its lease check and its free. Taking the
     * lock on every acquire costs an uncontended lock per listen, which is nothing next to a 15 s
     * utterance, and it is what makes the lease in [acquireRecognizer] atomic.
     */
    private fun ensureRecognizerLocked(): OfflineRecognizer? {
        val lang = whisperLang()
        recognizer?.let { if (loadedLang == lang) return it else runCatching { it.release() } }
        recognizer = null
        if (!AsrModel.isInstalled(context)) return null

        // CRASH SENTINEL around the native load. sherpa-onnx parses the .onnx files in C++, and a
        // TRUNCATED-but-non-empty model segfaults inside libsherpa-onnx-jni rather than throwing -
        // `runCatching` cannot catch a native abort, it takes the whole process down. That is
        // reachable in the real world: the installer deletes destDir BEFORE moving staging into
        // place, so a copy that stops partway (storage full on a flip phone, process killed) can
        // leave a short file that isInstalled()'s present-and-non-empty test happily accepts.
        // Because warmUp() runs at STARTUP, the result was an unrecoverable crash loop - the user
        // cannot even reach Settings to delete the model, since the app dies before any UI.
        // So: mark before the load, clear after. Finding the mark still set means the previous
        // attempt never returned - quarantine the model, tell the UI it is not installed, and let
        // the app start. Same idiom as the map's two-crash sentinel in VelaMapView.
        val prefs = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOAD_INFLIGHT, false)) {
            Timber.tag(TAG).e("previous ASR load never returned (native crash) - quarantining the model")
            prefs.edit().putBoolean(KEY_LOAD_INFLIGHT, false).putBoolean(KEY_MODEL_BAD, true).apply()
            runCatching { AsrModel.dir(context).deleteRecursively() }
            return null
        }
        if (prefs.getBoolean(KEY_MODEL_BAD, false)) return null
        prefs.edit().putBoolean(KEY_LOAD_INFLIGHT, true).apply()

        val dir = AsrModel.dir(context)
        val r = runCatching {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = File(dir, AsrModel.ENCODER).absolutePath,
                            decoder = File(dir, AsrModel.DECODER).absolutePath,
                            language = lang,      // pinned to the app language ("" = auto)
                            task = "transcribe",
                            tailPaddings = -1,
                        ),
                        tokens = File(dir, AsrModel.TOKENS).absolutePath,
                        numThreads = 2,
                        modelType = "whisper",
                    ),
                ),
            )
        }.getOrNull()
        // The load RETURNED (success or a catchable failure), so the process survived it: clear
        // the sentinel. Only a native abort leaves it set, which is exactly the case we want the
        // next launch to notice.
        prefs.edit().putBoolean(KEY_LOAD_INFLIGHT, false).apply()
        recognizer = r
        loadedLang = lang
        if (r != null) armIdleReap() // start the quiet-period countdown from the load
        return r
    }

    /**
     * Record from the mic and return what was said, or null if nothing usable was heard (or the
     * model/permission isn't there). [onLevel] gets a 0..1 loudness for the listening animation,
     * [onListening] fires once recording actually starts, and [cancelled] lets the UI stop early
     * (the user tapped done/close). Runs off the main thread; safe to cancel via coroutine too.
     */
    /** Listen, transcribe, and say WHY when it does not work - see [VoiceResult]. Every failure exit
     *  logs under `VELAASR` so a tester's logcat names the cause without another round-trip.
     *
     *  Thin wrapper over [listenInner] that holds a LEASE on the recognizer for the whole utterance,
     *  so a memory trim or the idle reaper arriving mid-utterance cannot free the native model out
     *  from under the decode (see [leases] and [acquireRecognizer]). Taking the lease out here, not
     *  inside the inner function, is what makes it exception-safe against that function's many
     *  early returns. */
    suspend fun listen(
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        cancelled: () -> Boolean,
    ): VoiceResult {
        // Acquire (and load) OFF the main thread: this can be a ~1 s native load, and callers reach
        // listen() from a UI coroutine. The lease is taken here rather than inside listenInner so
        // that the many early returns in there cannot leak it - the finally below always gives it
        // back, and it covers recording as well as decoding.
        reapTask?.cancel(false) // never reap mid-utterance
        val rec = withContext(Dispatchers.Default) { acquireRecognizer() }
            ?: run {
                Timber.tag(TAG).e("listen failed: MODEL (model absent or native load failed)")
                armIdleReap()
                return VoiceResult.Failed(VoiceResult.Reason.MODEL, "model absent or native load failed")
            }
        try {
            return listenInner(rec, onLevel, onListening, cancelled)
        } finally {
            releaseLease()
            armIdleReap() // restart the quiet period from the END of this utterance
        }
    }

    private suspend fun listenInner(
        rec: OfflineRecognizer,
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        cancelled: () -> Boolean,
    ): VoiceResult = withContext(Dispatchers.Default) {
        fun fail(reason: VoiceResult.Reason, detail: String? = null): VoiceResult.Failed {
            Timber.tag(TAG).e("listen failed: $reason${detail?.let { " ($it)" } ?: ""}")
            return VoiceResult.Failed(reason, detail)
        }
        if (!hasMicPermission()) return@withContext fail(VoiceResult.Reason.PERMISSION)

        val vad = runCatching {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(AsrModel.dir(context), AsrModel.VAD).absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.6f,   // ~0.6 s of quiet ends the utterance
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = MAX_SECONDS.toFloat(),
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        }.getOrNull() ?: return@withContext fail(VoiceResult.Reason.VAD, "silero VAD would not construct")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2),
            )
        }.getOrNull()
        if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
            val detail = if (audio == null) "constructor threw" else "state=${audio.state} minBuf=$minBuf"
            audio?.release(); vad.release()
            return@withContext fail(VoiceResult.Reason.AUDIO_INIT, detail)
        }

        val buf = ShortArray(VAD_WINDOW)
        val chunks = ArrayList<FloatArray>()
        var total = 0
        var sawSpeech = false
        var segment: FloatArray? = null
        try {
            requestAudioFocus() // pause any playing music/podcast while we listen
            audio.startRecording()
            onListening()
            while (!cancelled() && segment == null && total < SAMPLE_RATE * MAX_SECONDS) {
                val n = audio.read(buf, 0, VAD_WINDOW)
                if (n <= 0) continue
                val f = FloatArray(n) { buf[it] / 32768f }
                onLevel(rms(f))
                chunks.add(f)
                total += n
                if (n == VAD_WINDOW) vad.acceptWaveform(f)
                if (vad.isSpeechDetected()) sawSpeech = true
                // A finished speech segment (speech then a beat of silence) = the utterance.
                if (!vad.empty()) {
                    segment = vad.front().samples
                    vad.pop()
                }
            }
        } catch (t: Throwable) {
            runCatching { vad.release() }
            return@withContext fail(VoiceResult.Reason.RECORDING, t.message ?: t::class.java.simpleName)
        } finally {
            // Abandon focus FIRST so the music resumes even if a later call throws; every step is
            // guarded so one failure can't skip the rest and leave playback paused forever.
            abandonAudioFocus() // let the music resume
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }

        // Prefer the VAD-trimmed segment (leading/trailing silence stripped -> cleaner transcript);
        // fall back to everything captured if the user stopped before a segment closed.
        val samples = segment ?: run {
            if (!sawSpeech && total < SAMPLE_RATE / 2) { vad.release(); return@withContext VoiceResult.NoSpeech }
            val out = FloatArray(total)
            var off = 0
            for (c in chunks) { c.copyInto(out, off, 0, min(c.size, out.size - off)); off += c.size }
            out
        }
        vad.release()

        val text = runCatching {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val t = rec.getResult(stream).text
            stream.release()
            t
        }.getOrNull().orEmpty()

        // Whisper writes prose ("Coffee shops near me.") and, on non-speech audio, bracketed sound
        // tags ("[music]", "[thud]"). :core's SpeechText.cleanSearchTranscript strips those into a
        // clean query (or "" -> null below = heard nothing, no search). Unit-tested there.
        val query = app.vela.core.voice.SpeechText.cleanSearchTranscript(text).ifBlank { null }
        if (query == null) VoiceResult.NoSpeech else VoiceResult.Text(query)
    }

    private fun rms(f: FloatArray): Float {
        if (f.isEmpty()) return 0f
        var sum = 0.0
        for (v in f) sum += v.toDouble() * v
        // Scale so a normal speaking level reads near the top of the 0..1 range for the animation
        // (speech at arm's length has RMS around 0.05 to 0.15; scaled so it sweeps most of the range).
        return (sqrt(sum / f.size) * 8.5f).toFloat().coerceIn(0f, 1f)
    }
}
