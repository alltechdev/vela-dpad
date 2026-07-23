package app.vela.core.voice

/**
 * A tiny offline command grammar over a voice transcript, for the Android Auto mic: "navigate
 * home", "mute", "end navigation" become ACTIONS instead of falling through to a place search
 * for the literal words. Everything unrecognised is a [Command.Search] with the destination
 * phrase stripped of its leading verb ("navigate to the nearest gas station" searches "the
 * nearest gas station"), so a plain search is always the safe fallback - the grammar can only
 * ever upgrade a transcript, never lose one.
 *
 * English-only for now (Whisper's small models are strongest in English and the phrase tables
 * are trivially extendable per locale); a non-English transcript simply searches as before.
 */
object CarCommands {

    sealed interface Command {
        data object GoHome : Command
        data object GoWork : Command
        data object FindMyCar : Command
        data object Mute : Command
        data object Unmute : Command
        data object EndNav : Command
        data class Search(val query: String) : Command
    }

    // Leading verb phrases a destination request starts with, longest-first so "navigate to"
    // wins over "navigate" (sorted at init so a new entry can't silently break precedence).
    // NO bare verbs here: "drive" stripped "drive in movie theater" to "in movie theater" and
    // "take me" maimed "take me out diner" (review finding) - the same trap the bare-"go" comment
    // below documents. Verb-only phrasings of home/work are covered as whole phrases instead.
    private val NAV_PREFIXES = listOf(
        "navigate to", "navigate me to", "navigate", "take me to", "drive to",
        "drive me to", "directions to", "go to", "route to", "head to", "bring me to",
    ).sortedByDescending { it.length }

    // "go home" is listed whole rather than via a bare "go" prefix - stripping "go" would maim
    // legitimate searches ("go kart track" must not search "kart track").
    private val HOME_WORDS = setOf(
        "home", "my home", "my house", "the house", "go home", "take me home",
        "drive home", "drive me home",
    )
    private val WORK_WORDS = setOf("work", "my work", "my office", "the office")
    private val CAR_PHRASES = setOf(
        "find my car", "where is my car", "where's my car", "where did i park",
        "find the car", "my parked car", "parked car",
    )
    private val MUTE_PHRASES = setOf("mute", "mute voice", "be quiet", "silence", "stop talking")
    private val UNMUTE_PHRASES = setOf("unmute", "unmute voice", "speak", "voice on", "talk to me")
    private val END_PHRASES = setOf(
        "end navigation", "stop navigation", "cancel navigation", "end the navigation",
        "stop the navigation", "cancel the route", "end route", "stop routing", "end the trip",
        "stop nav", "end nav", "cancel nav",
    )

    fun parse(transcript: String): Command {
        val t = transcript.trim().trimEnd('.', '!', '?').lowercase()
        if (t.isEmpty()) return Command.Search(transcript)

        if (t in CAR_PHRASES) return Command.FindMyCar
        if (t in MUTE_PHRASES) return Command.Mute
        if (t in UNMUTE_PHRASES) return Command.Unmute
        if (t in END_PHRASES) return Command.EndNav

        // "navigate home" / bare "home": destination words after an (optional) nav verb.
        val stripped = NAV_PREFIXES.firstOrNull { t.startsWith("$it ") }
            ?.let { t.removePrefix("$it ").trim() } ?: t
        if (stripped in HOME_WORDS) return Command.GoHome
        if (stripped in WORK_WORDS) return Command.GoWork
        if (stripped in CAR_PHRASES) return Command.FindMyCar

        // A stripped verb means the rest is the destination; otherwise search the transcript as-is
        // (original casing - the geocoder does better with it than with a lowercased copy).
        return if (stripped != t) Command.Search(stripped) else Command.Search(transcript.trim())
    }
}
