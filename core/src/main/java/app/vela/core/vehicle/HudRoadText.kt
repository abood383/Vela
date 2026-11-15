package app.vela.core.vehicle

import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType

/**
 * The text the windshield HUD shows under the distance (SPEC 10.9, the road field), as the phone's
 * banner reads it, most important part first:
 * - a roundabout: which exit, then the road ("3rd exit · Elm St"), because a glass with one
 *   roundabout glyph cannot draw which exit;
 * - a highway exit: the exit number and where it goes ("Exit 12B · Folsom Blvd");
 * - otherwise the road being entered.
 *
 * The road name comes from the step instruction first ("Turn right onto Elm St"), the same text the
 * banner shows, then the maneuver's road and ref. A name the glass cannot draw is left out: a glass
 * seen on a BYD drew Latin text only, about nine characters wide, so a name with characters outside
 * Latin script is dropped and common street words are shortened. The patterns are English.
 */
object HudRoadText {
    private val EXIT = Regex("""\b[Ee]xit\s+([0-9]{1,4}[A-Za-z]?)\b""")
    private val TOWARD = Regex("""\btowards?\s+(.+?)\s*$""")
    private val ONTO = Regex("""\bonto\s+(.+?)(?:\s+towards?\s+.*)?\s*$""")
    private val ORDINAL_EXIT = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+exit\b""", RegexOption.IGNORE_CASE)
    private val WORD_EXIT = Regex("""\b(first|second|third|fourth|fifth|sixth|seventh|eighth)\s+exit\b""", RegexOption.IGNORE_CASE)
    private val WORDS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")
    private val SHORT = listOf(
        Regex("""\bStreet\b""") to "St", Regex("""\bRoad\b""") to "Rd", Regex("""\bAvenue\b""") to "Ave",
        Regex("""\bBoulevard\b""") to "Blvd", Regex("""\bHighway\b""") to "Hwy", Regex("""\bDrive\b""") to "Dr",
    )

    fun of(m: Maneuver): String {
        val onto = ONTO.find(m.instruction)?.groupValues?.get(1)
        val road = listOf(onto, m.road, m.ref).firstNotNullOfOrNull { drawable(it) }
        val sign = drawable(TOWARD.find(m.instruction)?.groupValues?.get(1))
        val roundabout = m.type == ManeuverType.ROUNDABOUT || m.type == ManeuverType.EXIT_ROUNDABOUT || m.roundabout != null ||
            m.instruction.contains("roundabout", ignoreCase = true)
        val exitNo = m.roundaboutExit ?: roundaboutExit(m.instruction)
        if (roundabout && exitNo != null) return join("${ordinal(exitNo)} exit", road ?: sign)
        val exit = EXIT.find(m.instruction)?.groupValues?.get(1)
        if (exit != null) return join("Exit ${exit.uppercase()}", sign ?: road)
        return road ?: ""
    }

    /** The exit number in "take the 3rd exit" or "take the third exit", if the step says one. */
    fun roundaboutExit(instruction: String): Int? =
        ORDINAL_EXIT.find(instruction)?.groupValues?.get(1)?.toIntOrNull()
            ?: WORD_EXIT.find(instruction)?.groupValues?.get(1)?.lowercase()?.let { WORDS.indexOf(it) + 1 }

    /** 1st, 2nd, 3rd, 4th, ... 11th, 12th, 13th, 21st. */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /** [name] shortened, or null when it is blank or has characters outside Latin script. */
    private fun drawable(name: String?): String? {
        // Invisible direction marks (Google wraps names in them) are no use to a glass and dropped.
        val t = name?.filterNot { Character.getType(it) == Character.FORMAT.toInt() }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) return null
        return SHORT.fold(t) { acc, (re, s) -> acc.replace(re, s) }
    }

    private fun join(head: String, tail: String?): String = if (tail.isNullOrEmpty()) head else "$head · $tail"
}
