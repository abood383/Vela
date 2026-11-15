package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoundaboutGeometry
import app.vela.core.model.bearingTo
import app.vela.core.model.distanceTo
import kotlin.math.abs

/**
 * Roundabout geometry for steps a router does not describe as roundabouts (SPEC 10.9, Roundabouts
 * on the route line). Google often calls a small roundabout, or one left at its first or last exit,
 * a plain turn, and its roundabout steps carry no geometry at all, so the banner and the HUD drew a
 * bare arrow or a circle with a straight arrow whatever the exit.
 *
 * Driving a roundabout leaves a mark on the route line that a turn does not: a veer one way to
 * enter and a sustained curve the other way round the ring. So between the previous and the next
 * step, the line turns far more in total than its net change in direction, and in both directions.
 * A step is a roundabout when that excess is at least [MIN_EXCESS_DEG] and each direction turns at
 * least [MIN_EACH_DEG]. The window stops [GAP_M] short of the neighboring steps, so a left turn
 * followed closely by a right turn is not read as one. The exit angle is the net turn, and the ring
 * circulates the way the larger share of the turning goes.
 *
 * Only display reads [Maneuver.roundabout]; the step's type and instruction are left as they are.
 */
object RoundaboutDetect {
    const val BEFORE_M = 70.0
    const val AFTER_M = 200.0
    const val GAP_M = 12.0
    /** How far past a Google exit step its roundabout's window runs, to see the exit road's bearing. */
    const val EXIT_TAIL_M = 40.0
    const val STEP_M = 4.0
    const val MIN_EXCESS_DEG = 100.0
    const val MIN_EACH_DEG = 35.0

    private val TURNS = setOf(
        ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
        ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
        ManeuverType.UTURN, ManeuverType.STRAIGHT, ManeuverType.CONTINUE, ManeuverType.ROUNDABOUT,
    )

    /** [maneuvers] with roundabout geometry added where the line shows one and none was given. */
    fun annotate(maneuvers: List<Maneuver>, polyline: List<LatLng>): List<Maneuver> {
        if (polyline.size < 3 || maneuvers.isEmpty()) return maneuvers
        val cum = cumulative(polyline)
        val at = maneuvers.map { m -> along(polyline, cum, m.location) }
        return maneuvers.mapIndexed { i, m ->
            if (m.roundabout != null || m.type !in TURNS) return@mapIndexed m
            val lo = maxOf(at[i] - BEFORE_M, (at.getOrNull(i - 1) ?: Double.NEGATIVE_INFINITY) + GAP_M)
            // A Google roundabout step is followed by its exit step; the ring runs up to it.
            val nextGap = if (m.type == ManeuverType.ROUNDABOUT && maneuvers.getOrNull(i + 1)?.type == ManeuverType.EXIT_ROUNDABOUT) -EXIT_TAIL_M else GAP_M
            val hi = minOf(at[i] + AFTER_M, (at.getOrNull(i + 1) ?: Double.POSITIVE_INFINITY) - nextGap)
            val g = geometry(polyline, cum, lo, hi, forceRoundabout = m.type == ManeuverType.ROUNDABOUT)
            if (g == null) m else m.copy(roundabout = g)
        }.let { withExits(it) }
    }

    /** [route] with every leg's steps annotated against the whole route line, so a roundabout at a
     *  leg boundary still sees its neighbors. A route whose steps already all carry what the line
     *  shows comes back as the same object. */
    fun annotate(route: app.vela.core.model.Route): app.vela.core.model.Route {
        val all = route.legs.flatMap { it.maneuvers }
        val shaped = annotate(all, route.polyline)
        if (shaped.indices.all { shaped[it] === all[it] }) return route
        var k = 0
        val legs = route.legs.map { leg -> leg.copy(maneuvers = shaped.subList(k, k + leg.maneuvers.size).also { k += leg.maneuvers.size }.toList()) }
        return route.copy(legs = legs)
    }

    /** The turning the line shows round step [i] (left, right, excess in degrees, window meters), for
     *  the HUD's diagnostic log: angles only, never a place. */
    fun probe(maneuvers: List<Maneuver>, i: Int, polyline: List<LatLng>): String {
        if (polyline.size < 3 || i !in maneuvers.indices) return "none"
        val cum = cumulative(polyline)
        val at = maneuvers.map { m -> along(polyline, cum, m.location) }
        val lo = maxOf(at[i] - BEFORE_M, (at.getOrNull(i - 1) ?: Double.NEGATIVE_INFINITY) + GAP_M)
        val hi = minOf(at[i] + AFTER_M, (at.getOrNull(i + 1) ?: Double.POSITIVE_INFINITY) - GAP_M)
        val t = turning(polyline, cum, lo, hi) ?: return "short window=${(hi - lo).toInt()}m pts=${polyline.size}"
        return "left=${t.first.toInt()} right=${t.second.toInt()} excess=${(t.first + t.second - abs(t.second - t.first)).toInt()} window=${(hi - lo).toInt()}m"
    }

    /** A Google exit step takes its roundabout step's geometry, as the open routers' do. */
    private fun withExits(ms: List<Maneuver>): List<Maneuver> = ms.mapIndexed { i, m ->
        if (m.type == ManeuverType.EXIT_ROUNDABOUT && m.roundabout == null && i > 0 && ms[i - 1].type == ManeuverType.ROUNDABOUT) {
            m.copy(roundabout = ms[i - 1].roundabout)
        } else m
    }

    /** The geometry the line between [lo] and [hi] meters shows, or null when it is not a roundabout. */
    fun geometry(line: List<LatLng>, cum: DoubleArray, lo: Double, hi: Double, forceRoundabout: Boolean = false): RoundaboutGeometry? {
        val (left, right) = turning(line, cum, lo, hi) ?: return null
        val net = norm(right - left)
        val excess = (left + right) - abs(right - left)
        val isRoundabout = forceRoundabout || (excess >= MIN_EXCESS_DEG && left >= MIN_EACH_DEG && right >= MIN_EACH_DEG)
        if (!isRoundabout) return null
        // Right-hand traffic circulates counter-clockwise: most of the turning is leftward.
        return RoundaboutGeometry(exitAngleDeg = net, clockwise = right > left)
    }

    /** Total leftward and rightward turning of the line between [lo] and [hi] meters, in degrees. */
    private fun turning(line: List<LatLng>, cum: DoubleArray, lo: Double, hi: Double): Pair<Double, Double>? {
        if (hi - lo < 3 * STEP_M) return null
        val pts = ArrayList<LatLng>()
        var d = lo.coerceAtLeast(0.0)
        val end = hi.coerceAtMost(cum.last())
        while (d <= end) { pts += pointAt(line, cum, d); d += STEP_M }
        if (pts.size < 4) return null
        var left = 0.0
        var right = 0.0
        var prev: Double? = null
        for (k in 1 until pts.size) {
            if (pts[k - 1].distanceTo(pts[k]) < 0.5) continue
            val h = pts[k - 1].bearingTo(pts[k])
            prev?.let { p ->
                val dd = norm(h - p)
                if (dd > 0) right += dd else left -= dd
            }
            prev = h
        }
        return left to right
    }

    fun cumulative(line: List<LatLng>): DoubleArray {
        val c = DoubleArray(line.size)
        for (i in 1 until line.size) c[i] = c[i - 1] + line[i - 1].distanceTo(line[i])
        return c
    }

    /** Meters along [line] of the vertex nearest [p]. */
    private fun along(line: List<LatLng>, cum: DoubleArray, p: LatLng): Double {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in line.indices) {
            val dd = line[i].distanceTo(p)
            if (dd < bestD) { bestD = dd; best = i }
        }
        return cum[best]
    }

    private fun pointAt(line: List<LatLng>, cum: DoubleArray, d: Double): LatLng {
        var i = java.util.Arrays.binarySearch(cum, d)
        if (i >= 0) return line[i]
        i = -i - 1
        if (i <= 0) return line.first()
        if (i >= line.size) return line.last()
        val a = line[i - 1]; val b = line[i]
        val seg = cum[i] - cum[i - 1]
        val t = if (seg <= 0) 0.0 else (d - cum[i - 1]) / seg
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    private fun norm(a: Double): Double {
        var x = a % 360.0
        if (x > 180.0) x -= 360.0
        if (x <= -180.0) x += 360.0
        return x
    }
}
