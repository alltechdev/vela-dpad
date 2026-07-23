package app.vela.voice

import android.content.Context
import java.io.File

// File-level consts: enum entries are initialized BEFORE the companion object, so the constructor
// can't read companion members - these must live at file scope. [AsrEngine.VAD] re-exports VAD_FILE
// for callers outside this file. ASR_BASE points at THIS fork's release (not upstream's), and the
// assets are hosted as .tar.bz2 because the fork's KokoroInstaller only extracts bzip2 (upstream
// ships .tar.gz).
private const val ASR_BASE = "https://github.com/alltechdev/vela-dpad/releases/download/asr-models"
private const val VAD_FILE = "silero_vad.onnx"

/**
 * The on-device speech-to-text engines Vela can download for voice search (tier-1). All run through
 * the bundled sherpa-onnx runtime ([WhisperRecognizer]) with Silero VAD; nothing leaves the phone and
 * no account or third-party voice app is needed. Each engine is an OPTIONAL one-time download hosted
 * on this repo's `asr-models` GitHub release, extracted to `filesDir/asr/<id>/`.
 *
 * Three engines, because they trade off differently and the user picks (ported from upstream
 * PimpinPumpkin/Vela 5d2a6636 + 118e7e8c sizes + 137beea9 language fallback):
 *  - [WHISPER_TINY] - the multilingual default. 99-language Whisper tiny (int8); covers every
 *    language Vela's UI supports (incl. Hebrew, Russian, Spanish). The safe all-rounder and the
 *    smallest download - so it stays the default and the ONLY thing the one-tap onboarding/map
 *    offer installs. NOT the smallest loaded: ~214 MB PSS resident (measured, 32-bit M5) - but
 *    the idle reaper makes that transient, not session-long.
 *  - [SENSE_VOICE] - FunAudioLLM SenseVoice. More accurate + faster than Whisper tiny, but only for
 *    English, Chinese, Cantonese, Japanese, Korean. Bigger (opt-in).
 *  - [MOONSHINE] - Useful Sensors Moonshine tiny. Lowest latency, ENGLISH ONLY. Bigger (opt-in),
 *    and despite the small weights its four ORT sessions cost ~212 MB PSS loaded - no lighter
 *    resident than Whisper (measured, 32-bit M5).
 *
 * Before adding a "low-memory" fourth engine, read the measurement notes in AGENTS.md: NeMo
 * Conformer CTC small (46 MB file) ballooned to ~760 MB-1.2 GB resident through onnxruntime, and
 * k2 Zipformer small was built, measured and then REMOVED - librispeech-domain models mishear the
 * proper nouns a maps app lives on, and no amount of post-processing fixes that.
 *
 * Whisper stays the default so no language silently regresses; the other two are opt-in via the
 * voice-search engine picker in Settings. This holds only metadata + a cheap install check + the
 * selected-engine preference; loading + running a model lives in [WhisperRecognizer] (`:app`).
 */
enum class AsrEngine(
    val id: String,
    /** Proper-noun engine name shown in the picker (not localized). */
    val displayName: String,
    /** sherpa-onnx `modelType` string the recognizer passes through. */
    val modelType: String,
    val sizeMb: Int,
    /** Asset on this repo's `asr-models` release (.tar.bz2). */
    val url: String,
    /** Every file that must be present + non-empty for the engine to count as installed. A missing
     *  file reads as not-installed, so a partial or aborted download self-heals (re-download). */
    val files: List<String>,
) {
    WHISPER_TINY(
        id = "whisper-tiny",
        displayName = "Whisper tiny",
        modelType = "whisper",
        sizeMb = 58,
        url = "$ASR_BASE/vela-asr-whisper-tiny.tar.bz2",
        files = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt", VAD_FILE),
    ),
    SENSE_VOICE(
        id = "sensevoice",
        displayName = "SenseVoice",
        modelType = "sense_voice",
        sizeMb = 154,
        url = "$ASR_BASE/vela-asr-sensevoice.tar.bz2",
        files = listOf("model.int8.onnx", "tokens.txt", VAD_FILE),
    ),
    MOONSHINE(
        id = "moonshine",
        displayName = "Moonshine",
        modelType = "moonshine",
        sizeMb = 101,
        url = "$ASR_BASE/vela-asr-moonshine.tar.bz2",
        files = listOf(
            "preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx",
            "cached_decode.int8.onnx", "tokens.txt", VAD_FILE,
        ),
    ),
    ;

    /** `filesDir/asr/<id>/` - the extracted archive's single top-level folder. */
    fun dir(context: Context): File = File(context.filesDir, "asr/$id")

    /** True only when EVERY model file is present and non-empty. Pure file check, no model load, so
     *  it's safe from the UI thread / availability gates. */
    fun isInstalled(context: Context): Boolean {
        val d = dir(context)
        return files.all { File(d, it).length() > 0L }
    }

    /** Whether this engine can actually recognize [lang] (an app language code, e.g. "en"/"he"/"ja").
     *  Whisper covers everything; SenseVoice only en/zh/ja/ko/yue; Moonshine only English. Used to
     *  fall back off a picked engine that can't do the current language (upstream 137beea9). */
    fun supportsLanguage(lang: String): Boolean = when (this) {
        WHISPER_TINY -> true
        SENSE_VOICE -> lang in SENSE_VOICE_LANGS
        MOONSHINE -> lang == "en"
    }

    companion object {
        /** Silero VAD, shipped inside every engine archive so each is self-contained. */
        const val VAD = VAD_FILE
        /** The languages SenseVoice recognizes (its own codes). */
        val SENSE_VOICE_LANGS = setOf("zh", "en", "ja", "ko", "yue")
        private const val PREFS = "vela_settings"
        private const val PREF_ENGINE = "asr_engine"

        /** The multilingual default - never regresses a language when nothing is picked, and the only
         *  engine the one-tap onboarding / map offer installs. */
        val DEFAULT = WHISPER_TINY

        fun byId(id: String?): AsrEngine? = entries.firstOrNull { it.id == id }

        fun installed(context: Context): List<AsrEngine> = entries.filter { it.isInstalled(context) }

        /** Any engine downloaded at all -> voice search is available. */
        fun anyInstalled(context: Context): Boolean = entries.any { it.isInstalled(context) }

        /** The engine the user PICKED to run: their pick if it's still installed, else the first
         *  installed engine (so deleting the picked one degrades gracefully), else the default.
         *  Note: this is the raw pick shown as "Active" in Settings; [forRecognition] may load a
         *  different one for a language the pick can't do. */
        fun active(context: Context): AsrEngine {
            val picked = byId(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ENGINE, null),
            )
            if (picked != null && picked.isInstalled(context)) return picked
            return installed(context).firstOrNull() ?: DEFAULT
        }

        fun setActive(context: Context, engine: AsrEngine) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_ENGINE, engine.id).apply()

        /** The engine to actually RUN for [lang]: the [active] pick if it supports the language, else
         *  Whisper (the multilingual default) when it's installed, so dictating in a language the
         *  picked engine can't do (SenseVoice picked, Hebrew spoken) still works instead of returning
         *  garbage. Falls back to the pick as a last resort if Whisper isn't installed (upstream
         *  137beea9). The Settings picker's "Active" still reflects the raw pick ([active]); this only
         *  changes which model the recognizer loads for the current language. */
        fun forRecognition(context: Context, lang: String): AsrEngine {
            val picked = active(context)
            if (picked.supportsLanguage(lang)) return picked
            return WHISPER_TINY.takeIf { it.isInstalled(context) } ?: picked
        }
    }
}
