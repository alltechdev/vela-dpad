package app.vela.core.voice

/**
 * Pure text helpers for the spoken-guidance path (kept in :core so they're unit-tested and reusable
 * across synths). No Android dependencies.
 */
object SpeechText {

    /**
     * Split [text] into sentences at a terminal ". "/"! "/"? ", but ONLY at a *real* sentence break -
     * never inside a name - so a neural synth can splice a pause between them at genuine periods.
     *
     * A period counts as a break only when the word before it is not an abbreviation / road-type /
     * directional word (`Jr.`, `Mt.`, `St.`, `Blvd.`, `N.`, and - because [VoiceGuide.forSpeech]
     * expands `St.`→`Street.`, `N.`→`North.` - the spelled-out forms too) AND the next clause starts
     * with a capital or digit. So `"Martin Luther King Jr. Boulevard"` stays whole while
     * `"…onto Main Street. Then merge…"` splits. Decimals (`"0.5 mi"`) and a trailing period never
     * split. Returns a single-element list when nothing qualifies, so single-sentence prompts are
     * untouched (and callers pay no extra synthesis cost).
     */
    fun splitSentences(text: String): List<String> {
        if (text.length < 2) return listOf(text)
        val cuts = ArrayList<Int>()
        for (m in SENTENCE_BREAK.findAll(text)) {
            val punct = m.range.first
            var j = punct - 1
            while (j >= 0 && text[j].isLetterOrDigit()) j--
            val word = text.substring(j + 1, punct)
            val nextStart = m.range.last + 1
            val next = text.getOrNull(nextStart) ?: continue
            if (word.length > 1 && word.lowercase() !in NO_SPLIT_BEFORE && (next.isUpperCase() || next.isDigit())) {
                cuts.add(nextStart)
            }
        }
        if (cuts.isEmpty()) return listOf(text)
        val parts = ArrayList<String>(cuts.size + 1)
        var start = 0
        for (c in cuts) { parts.add(text.substring(start, c).trim()); start = c }
        parts.add(text.substring(start).trim())
        return parts.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    /**
     * Split [text] at CLAUSE boundaries - a comma or semicolon followed by whitespace - so a neural
     * synth can splice a short beat there (Piper reads straight through commas otherwise, running
     * "In a quarter mile, turn right" together). Requires the space, so a grouped number like "1,000"
     * (comma-digit, no space) is never split. A `;` is always a break; a `,` is too (nav commas are
     * clause boundaries). Single-fragment inputs return unchanged.
     */
    fun splitClauses(text: String): List<String> {
        if (text.length < 2) return listOf(text)
        val cuts = CLAUSE_BREAK.findAll(text).map { it.range.last + 1 }.toList()
        if (cuts.isEmpty()) return listOf(text)
        val parts = ArrayList<String>(cuts.size + 1)
        var start = 0
        for (c in cuts) { parts.add(text.substring(start, c).trim()); start = c }
        parts.add(text.substring(start).trim())
        return parts.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    /**
     * The speakable fragments of [text], each tagged with the SILENCE (seconds) to splice AFTER it:
     * a strong [sentenceGap] at sentence ends (`. ! ?`), a shorter [clauseGap] at commas/semicolons,
     * and 0 after the final fragment. Lets the neural voice phrase a prompt naturally ("In a quarter
     * mile, ‹beat› turn right onto Main Street. ‹beat› Then …") in one pass. Falls back to the whole
     * text with no gap when nothing splits (so single-clause prompts pay no cost).
     */
    fun speechFragments(text: String, sentenceGap: Float, clauseGap: Float): List<Pair<String, Float>> {
        val sentences = splitSentences(text)
        val out = ArrayList<Pair<String, Float>>()
        for ((si, sentence) in sentences.withIndex()) {
            val clauses = splitClauses(sentence)
            for ((ci, clause) in clauses.withIndex()) {
                if (clause.isBlank()) continue
                val gap = when {
                    ci != clauses.lastIndex -> clauseGap // between clauses of the same sentence
                    si != sentences.lastIndex -> sentenceGap // between sentences
                    else -> 0f // the very end
                }
                out.add(clause to gap)
            }
        }
        return out.ifEmpty { listOf(text to 0f) }
    }

    /**
     * Read 3-digit street ordinals the way people (and Google's voice) say them - "120th" → "one
     * twentieth", not "one hundred and twentieth", which the neural G2P mangles into a stuttery
     * "one, hundred and 28th". Only touches 3-digit ordinals (100-999); espeak reads 1-2 digit ordinals
     * ("5th", "42nd") fine on its own, and 4-digit+ are left alone (rare in street names, and the
     * hundreds-word convention breaks down). Whole-number only (won't touch "1.28th").
     */
    fun spokenNumbers(text: String): String =
        STREET_ORDINAL.replace(text) { m ->
            val h = m.groupValues[1].toInt()
            val r = m.groupValues[2].toInt()
            when {
                r == 0 -> "${CARD[h]} hundredth"      // 100th → "one hundredth"
                r < 10 -> "${CARD[h]} oh ${ORD1[r]}"  // 105th → "one oh fifth"
                else -> "${CARD[h]} ${twoDigitOrdinal(r)}" // 120th → "one twentieth"
            }
        }

    /**
     * Clean a raw speech-to-text transcript into a search QUERY. Whisper (the on-device voice-search
     * ASR) is a general audio model: on non-speech audio (silence, a tap, background music) it emits
     * bracketed sound tags - "[music]", "[thud]", "[BLANK_AUDIO]" - instead of words. Those are never a
     * real search, so strip every "[...]" group, then drop the trailing sentence punctuation/quotes that
     * prose adds ("Coffee near me.") and collapse whitespace. A transcript that was ONLY sound tags
     * collapses to "", so the caller's `.ifBlank { null }` reads it as "heard nothing" (no search runs)
     * rather than searching for the literal "[music]". A real query with a stray tag ("coffee [noise]
     * shop") keeps just the words. Brackets never appear in a real place search, so this is safe; an
     * INNER period ("St. Paul") is preserved - only a trailing one is trimmed.
     *
     * A long non-speech run gets TRUNCATED at the utterance/15 s boundary, so the LAST tag can arrive
     * unterminated ("[music] [music] [musi") - device-seen. We strip both complete "[...]" groups AND a
     * dangling "[..." at the end, so that whole junk collapses to "" too (not the fragment "[musi").
     */
    fun cleanSearchTranscript(raw: String): String =
        raw.replace(BRACKET_TAG, " ")
            .replace(BRACKET_TAIL, " ")   // an unterminated trailing tag from a truncated capture
            .replace(WHITESPACE, " ")
            .trim()
            .trim('"', '“', '”')
            .trimEnd('.', '!', '?', ',', ';', ':', '…')
            .trim()
            .let(::unshout)
            .let(::spokenNumbersToDigits)

    private val BRACKET_TAG = Regex("\\[[^\\]]*]")
    private val BRACKET_TAIL = Regex("\\[[^\\]]*$")
    private val WHITESPACE = Regex("\\s+")

    /** The librispeech-trained engines (Zipformer small) emit ALL CAPS. A search query has no use
     *  for shouting; mixed-case prose (Whisper) passes through untouched. */
    private fun unshout(s: String): String =
        if (s.any { it.isLetter() } && s.none { it.isLowerCase() }) s.lowercase() else s

    /**
     * Inverse text normalization for SEARCH transcripts: spoken number words become digits, because
     * an address query must reach the geocoder as "123 main street", never "one twenty three main
     * street". Whisper writes digits itself (this is a no-op on its output); the librispeech-trained
     * Zipformer emits spoken-form words. Number words in any other language pass through untouched -
     * the English vocabulary simply doesn't match - so this is safe app-wide.
     *
     * Spoken addresses use JUXTAPOSITION, not place value: "one twenty three" is 1|23 -> "123",
     * "twelve thirty four" is 12|34 -> "1234", "one oh five" is 1|0|5 -> "105". So each spoken
     * GROUP converts on its own and adjacent groups concatenate; within a group the normal rules
     * apply ("twenty three" -> 23, "three hundred" -> 300, "five thousand two hundred" -> 5200).
     * A trailing ordinal closes the run with its suffix ("one hundred twenty fifth" -> "125th",
     * for numbered streets). "oh" counts as a zero only INSIDE a run, so the interjection alone is
     * never touched.
     */
    fun spokenNumbersToDigits(s: String): String {
        val words = s.split(' ')
        val out = StringBuilder()
        var i = 0
        while (i < words.size) {
            val run = parseNumberRun(words, i)
            if (run == null) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(words[i]); i++
            } else {
                if (out.isNotEmpty()) out.append(' ')
                out.append(run.first); i = run.second
            }
        }
        return out.toString()
    }

    /** Parse the longest spoken-number run starting at [start]; null if [start] isn't a number
     *  word. Returns the rendered digits (with ordinal suffix if the run ends on one) and the
     *  index PAST the run. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun parseNumberRun(words: List<String>, start: Int): Pair<String, Int>? {
        val groups = ArrayList<Long>()
        // A group is total + current: "thousand" banks (current * 1000) into total, "hundred"
        // multiplies current, tens/teens/units build current. Group value = total + current.
        var total = 0L
        var current = 0L
        var started = false    // distinguishes "no group" from a group currently worth 0
        var canAddTens = false // after "hundred"/"thousand" a tens/teen ADDS instead of juxtaposing
        var canAddUnit = false // after a tens word ("twenty"), a unit ADDS ("three" -> 23)
        var ordinalSuffix: String? = null
        var i = start
        fun closeGroup() {
            if (started) { groups.add(total + current); total = 0; current = 0; started = false }
            canAddTens = false; canAddUnit = false
        }
        loop@ while (i < words.size && ordinalSuffix == null) {
            val w = words[i].lowercase().trimEnd(',')
            // Hyphenated compounds ("twenty-three") arrive as one token: handle the parts in turn.
            val parts = if ('-' in w) w.split('-') else listOf(w)
            for (p in parts) {
                val unit = CARD.indexOf(p)          // one..nine -> 1..9 (index 0 is "")
                val teen = TEEN.indexOf(p)          // ten..nineteen -> 0..9
                val tens = TENS_CARD.indexOf(p)     // twenty..ninety -> 2..9 (0,1 unused)
                val ordU = ORD1.indexOf(p)          // first..ninth
                val ordTeen = TEEN_ORD.indexOf(p)   // tenth..nineteenth
                val ordTens = TENS_ORD.indexOf(p)   // twentieth..ninetieth
                when {
                    p == "zero" || (p == "oh" && (started || groups.isNotEmpty())) -> {
                        closeGroup(); groups.add(0)
                    }
                    unit > 0 -> {
                        if (started && current > 0 && !canAddUnit && !canAddTens) closeGroup() // juxtaposed
                        current += unit; started = true; canAddUnit = false; canAddTens = false
                    }
                    teen >= 0 -> {
                        if (started && current > 0 && !canAddTens) closeGroup()
                        current += 10 + teen; started = true; canAddTens = false; canAddUnit = false
                    }
                    tens >= 2 -> {
                        if (started && current > 0 && !canAddTens) closeGroup()
                        current += tens * 10; started = true; canAddTens = false; canAddUnit = true
                    }
                    p == "hundred" && current in 1..99 -> {
                        current *= 100; canAddTens = true; canAddUnit = false
                    }
                    p == "thousand" && current in 1..999 -> {
                        total += current * 1000; current = 0; canAddTens = true; canAddUnit = false
                    }
                    ordU > 0 || ordTeen >= 0 || ordTens >= 2 -> {
                        val v = when {
                            ordU > 0 -> ordU.toLong()
                            ordTeen >= 0 -> (10 + ordTeen).toLong()
                            else -> ordTens * 10L
                        }
                        // A LONE ordinal converts only for tenth+: bare "first".."ninth" are common
                        // non-numeric English ("second opinion clinic") and rewriting them is wrong
                        // more often than right, while "thirteenth"/"fortieth" in a query is a
                        // numbered street. Attached to a number ("forty second") it always converts.
                        if (!started && groups.isEmpty() && ordU > 0) return null
                        if (started && current > 0 && !canAddUnit && !canAddTens) closeGroup()
                        current += v; started = true
                        ordinalSuffix = ordSuffix(current)
                    }
                    else -> break@loop // not a number word: the run ends before this token
                }
            }
            i++
        }
        closeGroup()
        if (groups.isEmpty()) return null
        val digits = groups.joinToString("") { it.toString() }
        return (digits + (ordinalSuffix ?: "")) to i
    }

    private fun ordSuffix(v: Long): String = when {
        v % 100 in 11..13 -> "th"
        v % 10 == 1L -> "st"
        v % 10 == 2L -> "nd"
        v % 10 == 3L -> "rd"
        else -> "th"
    }

    private fun twoDigitOrdinal(r: Int): String = when {
        r in 10..19 -> TEEN_ORD[r - 10]
        r % 10 == 0 -> TENS_ORD[r / 10]
        // SPACE, not hyphen: as two words each gets its own full stress, so the tens word keeps its
        // final consonant. The hyphenated compound ("thirty-second") was read with a reduced/flapped
        // "-ty" by the neural voice - "42nd" came out sounding like "one third second" on a real
        // drive (user 2026-07-06).
        else -> "${TENS_CARD[r / 10]} ${ORD1[r % 10]}"
    }

    private val STREET_ORDINAL = Regex("\\b([1-9])(\\d\\d)(?:st|nd|rd|th)\\b")
    private val CARD = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val TEEN = arrayOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val ORD1 = arrayOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
    private val TEEN_ORD = arrayOf(
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth",
        "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    )
    private val TENS_ORD = arrayOf(
        "", "", "twentieth", "thirtieth", "fortieth",
        "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth",
    )
    private val TENS_CARD = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    // Terminal punctuation followed by whitespace (so "0.5 mi" and a trailing "." don't match).
    private val SENTENCE_BREAK = Regex("[.!?]+\\s+")

    // Comma/semicolon followed by whitespace - a clause beat. The required space means a grouped
    // number ("1,000") is never split (comma-digit has no space).
    private val CLAUSE_BREAK = Regex("[,;]\\s+")

    // A period after one of these is an abbreviation/name dot, not a sentence end - don't split on it.
    // Road-type + directional + title abbreviations AND the words forSpeech expands them into.
    private val NO_SPLIT_BEFORE = setOf(
        "st", "ave", "av", "blvd", "rd", "dr", "ln", "ct", "pl", "ter", "cir", "sq", "trl",
        "hwy", "pkwy", "fwy", "expy", "pt", "ft", "mt", "jr", "sr", "mr", "mrs", "ms", "no", "vs",
        "n", "s", "e", "w", "ne", "nw", "se", "sw",
        "street", "avenue", "boulevard", "road", "drive", "lane", "court", "place", "terrace",
        "circle", "square", "trail", "highway", "parkway", "freeway", "expressway", "way", "alley",
        "plaza", "path", "walk", "row", "loop", "crossing", "point", "bend", "pass",
        "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest",
        "saint", "mount", "junior", "senior",
    )
}
