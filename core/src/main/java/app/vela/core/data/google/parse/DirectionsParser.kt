package app.vela.core.data.google.parse

import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.dbl
import app.vela.core.data.google.int
import app.vela.core.data.google.long
import app.vela.core.data.google.str
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TrafficSpan
import app.vela.core.model.distanceTo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the `/maps/preview/directions` response.
 *
 * Schema calibrated against a live capture (2026-06-15):
 * routes        `root[0][1]`            (array of alternative routes)
 * per route `r` summary at `r[0]`:
 * distance m  `[2][0]`   ("10.6 miles" text at `[2][1]`)
 * duration s  `[3][0]`   typical/no-traffic ("22 min" at `[3][1]`)
 * traffic s   `[10][0][0]`  live duration_in_traffic ("18 min") - the goal
 * start pt    `[7][3][2]` = [.., .., lat, lng]
 * end pt      `[7][3][3]`
 * steps         emitted as `<step maneuver='TURN' meters='120'>Turn <turn side='LEFT'>left
 * </turn> onto <roadlist><road>Elm St</road></roadlist></step>` markup strings
 * scattered through the route subtree. The maneuver attr is GENERIC ('TURN',
 * 'ON_RAMP', 'ROUNDABOUT_ENTER_AND_EXIT', 'NAME_CHANGE', …) - the left/right +
 * slight/sharp live in the child `<turn side= type=>`, the road in `<road>`. NOTE:
 * roundabout steps carry NO `<road>` keyless ("take the 2nd exit", no "onto X").
 *
 * The exact encoded overview-polyline field did not decode cleanly during
 * calibration, so geometry is currently APPROXIMATED from the in-bounds
 * coordinate points present in the route subtree. This is good enough to draw a
 * route and to place maneuvers for ETA/preview; tightening it to the true
 * per-step polyline is the one remaining calibration item (CALIBRATE: geometry).
 */
object DirectionsParser {

    fun parse(root: JsonElement): List<Route> {
        val routes = root.at(0, 1).arr()
            ?: throw CalibrationNeededException("directions routes (root[0][1])")
        // Google ships each route's real geometry as delta-encoded E7 coordinate
        // arrays at root[0][7][i] (index-aligned with the route summaries) - so the
        // drawn line follows the actual roads of *that* route, alternates included.
        val geoms = root.at(0, 7).arr()
        val parsed = routes.mapIndexedNotNull { i, r ->
            runCatching { parseRoute(r, decodeGeometry(geoms?.getOrNull(i))) }.getOrNull()
        }
        if (parsed.isEmpty()) throw CalibrationNeededException("directions: 0 routes parsed")
        return parsed
    }

    private fun parseRoute(route: JsonElement, googleGeometry: List<LatLng>?): Route? {
        val summary = route.at(0) ?: return null
        val distance = summary.at(2, 0).dbl() ?: return null
        val typicalDur = summary.at(3, 0).dbl() ?: return null
        val trafficDur = summary.at(10, 0, 0).dbl() // null when no live traffic (e.g. off-peak)
        // Typical best→worst spread: summary[10][4] = [lowSeconds, highSeconds, "label"].
        // Google's own depart-time planning hint ("usually 1 hr 8 min to 1 hr 27 min"),
        // present on longer trips; absent (null) on short/no-traffic ones.
        val typicalLow = summary.at(10, 4, 0).dbl()
        val typicalHigh = summary.at(10, 4, 1).dbl()

        val start = coord(summary.at(7, 3, 2))
        val end = coord(summary.at(7, 3, 3))
        // Google's own geometry when present; otherwise a straight start→end segment
        // (the data source can still snap that to an open router). Never a guess that
        // doubles back on itself.
        val polyline = googleGeometry?.takeIf { it.size >= 2 } ?: listOfNotNull(start, end)
        val maneuvers = placeManeuvers(collectSteps(route), polyline)

        return Route(
            polyline = polyline.ifEmpty { listOfNotNull(start, end) },
            legs = listOf(RouteLeg(distance, typicalDur, trafficDur, maneuvers)),
            distanceMeters = distance,
            durationSeconds = typicalDur,
            durationInTrafficSeconds = trafficDur,
            summary = summary.at(1).str(),
            trafficSpans = parseTrafficSpans(route),
            typicalLowSeconds = typicalLow,
            typicalHighSeconds = typicalHigh,
        )
    }

    /** Per-segment live traffic: `route[3][5][0]` is a list of `[level, startMeters,
     * lengthMeters]` - only the congested stretches (free-flow gaps are omitted).
     * Note this hangs off the route node itself, NOT the `[0]` summary. Calibrated
     * 2026-06-19 against Davis→Sac + Berkeley→SF (levels 1=moderate, 2=heavy seen;
     * span starts+lengths chain contiguously through each jam, sum < route length). */
    private fun parseTrafficSpans(route: JsonElement): List<TrafficSpan> {
        val arr = route.at(3, 5, 0).arr() ?: return emptyList()
        return arr.mapNotNull { s ->
            val level = s.at(0).int() ?: return@mapNotNull null
            val start = s.at(1).dbl() ?: return@mapNotNull null
            val len = s.at(2).dbl() ?: return@mapNotNull null
            // Only congested spans are listed; drop a zero-length or stray non-graded
            // (level < 1) entry so the route gradient never paints a free-flow stretch.
            if (len <= 0.0 || level < 1) null else TrafficSpan(level, start, len)
        }.sortedBy { it.startMeters } // gradient stops must walk start→end in order
    }

    /** Decode a route-geometry node: `[0]` = latitude deltas (E7, first element
     * absolute), `[1]` = longitude deltas - into a real polyline. */
    private fun decodeGeometry(node: JsonElement?): List<LatLng>? {
        val lat = node.at(0).arr() ?: return null
        val lng = node.at(1).arr() ?: return null
        if (lat.size != lng.size || lat.size < 2) return null
        var la = 0L
        var ln = 0L
        return lat.indices.map { i ->
            la += lat[i].long() ?: 0L
            ln += lng[i].long() ?: 0L
            LatLng(la / 1e7, ln / 1e7)
        }
    }

    private fun coord(node: JsonElement?): LatLng? {
        val lat = node.at(2).dbl()
        val lng = node.at(3).dbl()
        return if (lat != null && lng != null) LatLng(lat, lng) else null
    }

    // --- maneuvers ----------------------------------------------------------

    private val MANEUVER_ATTR = Regex("maneuver='([^']+)'")
    private val METERS_ATTR = Regex("meters='([0-9]+)'")
    private val TAGS = Regex("<[^>]+>")
    private val WS = Regex("\\s+")
    // Google prefixes lane steps with "Use the right 2 lanes to …" / "Use any lane
    // to …". Pull that clause out as a separate hint and leave a clean instruction.
    private val LANE_PHRASE = Regex("^(Use (?:the .+? lanes?|any lane)) to ", RegexOption.IGNORE_CASE)

    private fun collectSteps(route: JsonElement): List<Maneuver> {
        val raw = ArrayList<String>()
        fun walk(n: JsonElement) {
            when (n) {
                is JsonArray -> n.forEach(::walk)
                is JsonPrimitive -> n.str()?.let { if (it.contains("<step ")) raw.add(it) }
                else -> {}
            }
        }
        walk(route)
        return raw.map { parseStep(it) }
    }

    // The keyless feed carries the turn DIRECTION in a child <turn side='LEFT'|'RIGHT' type='SLIGHT'
    // |'SHARP'> element - NOT in the maneuver attribute, which is the generic 'TURN' / 'ON_RAMP' /
    // 'ROUNDABOUT_ENTER_AND_EXIT'. (Verified against a live www.google.com directions capture.)
    private val SIDE_ATTR = Regex("<turn\\b[^>]*\\bside='([^']+)'", RegexOption.IGNORE_CASE)
    private val SEVERITY_ATTR = Regex("<turn\\b[^>]*\\btype='([^']+)'", RegexOption.IGNORE_CASE)

    internal fun parseStep(s: String): Maneuver {
        val token = MANEUVER_ATTR.find(s)?.groupValues?.get(1)
        val side = SIDE_ATTR.find(s)?.groupValues?.get(1)
        val severity = SEVERITY_ATTR.find(s)?.groupValues?.get(1)
        val type = mapType(token, side, severity)
        val meters = METERS_ATTR.find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val text = s.replace(TAGS, " ").replace(WS, " ").trim()
        val lane = LANE_PHRASE.find(text)
        val laneHint = lane?.groupValues?.get(1)?.trim()
        val instruction = (lane?.let { text.removeRange(it.range) } ?: text)
            .trim().replaceFirstChar { it.uppercase() }
        return Maneuver(
            type, instruction.ifEmpty { "Continue" }, LatLng(0.0, 0.0), meters, 0.0,
            laneHint = laneHint, rawToken = token,
        )
    }

    /** Map Google's keyless maneuver [token] (+ the child `<turn>` [side]/[severity]) to a
     * [ManeuverType]. The feed uses a GENERIC token with left/right + slight/sharp carried
     * separately, so reading only the token left every plain turn and ramp as UNKNOWN - a generic
     * arrow and the wrong direction-coded haptic. Old explicit *_LEFT/_RIGHT tokens stay handled in
     * case the feed varies. */
    internal fun mapType(token: String?, side: String?, severity: String?): ManeuverType {
        // The direction can arrive EITHER as the child `<turn side=>` OR baked into the token itself
        // ("OFF_RAMP_RIGHT"). Reading only the child meant every explicit-token ramp/fork/keep fell to
        // the lr() fallback, so EVERY EXIT CARD DREW THE SAME SYMBOL whichever way the exit went
        // (issue #79). Suffix-match the token too; the child still wins where both are present.
        val tok = token?.uppercase()
        val left = side.equals("LEFT", ignoreCase = true) || (side == null && tok?.endsWith("_LEFT") == true)
        val right = side.equals("RIGHT", ignoreCase = true) || (side == null && tok?.endsWith("_RIGHT") == true)
        val slight = severity.equals("SLIGHT", ignoreCase = true)
        val sharp = severity.equals("SHARP", ignoreCase = true)
        fun lr(l: ManeuverType, r: ManeuverType, fallback: ManeuverType) = if (left) l else if (right) r else fallback
        return when (tok) {
            "DEPART" -> ManeuverType.DEPART
            "DESTINATION", "ARRIVE" -> ManeuverType.ARRIVE
            "TURN_LEFT" -> ManeuverType.TURN_LEFT
            "TURN_RIGHT" -> ManeuverType.TURN_RIGHT
            "TURN_SLIGHT_LEFT" -> ManeuverType.SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT" -> ManeuverType.SLIGHT_RIGHT
            "TURN_SHARP_LEFT" -> ManeuverType.SHARP_LEFT
            "TURN_SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
            "TURN" -> when {
                slight -> lr(ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT, ManeuverType.STRAIGHT)
                sharp -> lr(ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.STRAIGHT)
                else -> lr(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.STRAIGHT)
            }
            "UTURN", "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverType.UTURN
            "STRAIGHT", "CONTINUE", "NAME_CHANGE" -> ManeuverType.STRAIGHT
            "MERGE", "MERGE_LEFT", "MERGE_RIGHT" -> ManeuverType.MERGE
            "FORK", "FORK_LEFT", "FORK_RIGHT" -> lr(ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.STRAIGHT)
            "ON_RAMP", "OFF_RAMP", "RAMP",
            "RAMP_LEFT", "ON_RAMP_LEFT", "OFF_RAMP_LEFT",
            "RAMP_RIGHT", "ON_RAMP_RIGHT", "OFF_RAMP_RIGHT" ->
                lr(ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.MERGE)
            "KEEP", "KEEP_LEFT", "KEEP_RIGHT" -> lr(ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT, ManeuverType.STRAIGHT)
            else -> when {
                token?.contains("ROUNDABOUT") == true -> ManeuverType.ROUNDABOUT
                left || right -> lr(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.UNKNOWN)
                else -> ManeuverType.UNKNOWN
            }
        }
    }

    /** Position each maneuver at its *actual* cumulative step distance along the
     * route line. Using the polyline's own length as the denominator (not the
     * summed step distances) matters: Google's step `meters` and its geometry
     * length differ by a few percent, and dividing by the step-sum stretched every
     * mid-route turn off its real spot - so tapping a step landed near, but not on,
     * the actual turn. Matching the polyline length pins each turn where it is. */
    internal fun placeManeuvers(maneuvers: List<Maneuver>, polyline: List<LatLng>): List<Maneuver> {
        if (maneuvers.isEmpty() || polyline.size < 2) return maneuvers
        // Place each turn by its fraction of the STEP-DISTANCE total, NOT the polyline length.
        // The two often disagree (Google's per-step metres vs our decoded geometry can differ by
        // a lot - seen 3.3 km of steps on a 6.4 km polyline), and dividing by polyLength then
        // crammed every turn into the first half of the route, landing them on the wrong roads
        // ("turn onto a road miles away"). Fraction-of-step-total maps each turn to the right
        // PROPORTIONAL point along the geometry regardless of the absolute-length mismatch.
        val stepTotal = maneuvers.sumOf { it.distanceMeters }.coerceAtLeast(1.0)
        var cum = 0.0
        val lastIdx = maneuvers.lastIndex
        return maneuvers.mapIndexed { i, m ->
            // Each turn sits at the START of its step (cumulative distance of the steps BEFORE it),
            // then we add this step's length. The final maneuver (ARRIVE) is pinned to the route
            // end (1.0) so a few-percent step/geometry drift can't undershoot the destination.
            val frac = if (i == lastIdx) 1.0 else (cum / stepTotal).coerceIn(0.0, 1.0)
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, frac))
        }.let { resolveRampSides(it, polyline) }
    }

    /** Ramp/exit families that carry a direction when the feed bothers to send one. A bare token from
     * this set with no `<turn side=>` is a maneuver whose direction we are EXPECTED to show but were
     * not told - the case [resolveRampSides] recovers from geometry. */
    private val RAMP_TOKENS = setOf("ON_RAMP", "OFF_RAMP", "RAMP", "FORK", "KEEP")

    /**
     * Give direction-less ramps and exits their left/right from the ROUTE GEOMETRY.
     *
     * Issue #79, @SILB: "the symbol on every exit card is the same. It should indicate the direction of
     * the exit." On live routes Google's keyless feed sends `maneuver='OFF_RAMP'` with **no** child
     * `<turn side=>` and no direction word in the localized instruction ("Take the exit toward
     * <hebrew road>"), so [mapType] correctly fell through to the generic MERGE arrow - every exit,
     * left or right, drawn identically. Nothing in the response can fix that; the direction only
     * exists in the shape of the road.
     *
     * So measure it: compare the heading INTO the maneuver with the heading OUT of it, sampled far
     * enough along the polyline (~[RAMP_SAMPLE_M]) to skip the decoded-geometry jitter right at the
     * point, and read the signed difference. Ramps are gentle - a motorway exit is often only 15-25
     * degrees where a turn is 90 - so the threshold is low ([RAMP_MIN_DEG]); below it the maneuver
     * genuinely goes straight ahead and keeps MERGE, which is the honest symbol for it.
     *
     * Only maneuvers whose [Maneuver.rawToken] is in [RAMP_TOKENS] AND that came out of [mapType]
     * with no direction are touched, so a real MERGE token and any maneuver the feed DID label are
     * left exactly as they were.
     */
    internal fun resolveRampSides(maneuvers: List<Maneuver>, polyline: List<LatLng>): List<Maneuver> {
        if (polyline.size < 2) return maneuvers
        return maneuvers.map { m ->
            val undirected = m.type == ManeuverType.MERGE || m.type == ManeuverType.STRAIGHT
            if (!undirected || m.rawToken?.uppercase() !in RAMP_TOKENS) return@map m
            val delta = headingChange(polyline, m.location) ?: return@map m
            when {
                delta >= RAMP_MIN_DEG -> m.copy(type = ManeuverType.RAMP_RIGHT)
                delta <= -RAMP_MIN_DEG -> m.copy(type = ManeuverType.RAMP_LEFT)
                else -> m
            }
        }
    }

    private const val RAMP_SAMPLE_M = 60.0
    private const val RAMP_MIN_DEG = 12.0

    /** Signed heading change at [at] along [poly], in degrees: positive right, negative left. Null when
     * the point sits too close to either end of the route to sample both sides. */
    private fun headingChange(poly: List<LatLng>, at: LatLng): Double? {
        var best = 0
        var bestD = Double.MAX_VALUE
        poly.forEachIndexed { i, p -> val d = p.distanceTo(at); if (d < bestD) { bestD = d; best = i } }
        val before = walk(poly, best, -RAMP_SAMPLE_M) ?: return null
        val after = walk(poly, best, RAMP_SAMPLE_M) ?: return null
        val inBearing = bearing(before, poly[best])
        val outBearing = bearing(poly[best], after)
        var d = outBearing - inBearing
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /** The point [meters] along the polyline from index [from] (negative walks backwards), or null if
     * the route ends first - too short a sample would read noise as a turn. */
    private fun walk(poly: List<LatLng>, from: Int, meters: Double): LatLng? {
        val step = if (meters < 0) -1 else 1
        var acc = 0.0
        var i = from
        while (acc < kotlin.math.abs(meters)) {
            val next = i + step
            if (next < 0 || next > poly.lastIndex) return null
            acc += poly[i].distanceTo(poly[next])
            i = next
        }
        return poly[i]
    }

    private fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = kotlin.math.sin(dLng) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
    }

    private fun pointAlong(poly: List<LatLng>, frac: Double): LatLng {
        val lengths = DoubleArray(poly.size - 1) { poly[it].distanceTo(poly[it + 1]) }
        val target = lengths.sum() * frac
        var acc = 0.0
        for (i in lengths.indices) {
            if (acc + lengths[i] >= target) {
                val f = if (lengths[i] == 0.0) 0.0 else (target - acc) / lengths[i]
                val a = poly[i]; val b = poly[i + 1]
                return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
            acc += lengths[i]
        }
        return poly.last()
    }

}
