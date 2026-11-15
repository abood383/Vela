package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.bearingTo
import app.vela.core.model.distanceTo

/**
 * Where a roundabout exit leaves, relative to the approach, measured on the route line (SPEC 10.9).
 * Google's steps carry no roundabout geometry, so for the HUD arrow the angle is read from the
 * polyline: the bearing of the last [SPAN_M] before the entry against the bearing of the first
 * [SPAN_M] after the exit. Same convention as [app.vela.core.model.RoundaboutGeometry.exitAngleDeg]:
 * 0 straight on, +90 right, -90 left, +/-180 back, normalized to (-180, 180].
 */
object RoundaboutExitAngle {
    const val SPAN_M = 30.0

    /** The angle for the roundabout [current] belongs to, or null when the route cannot say. */
    fun of(maneuvers: List<Maneuver>, current: Maneuver, polyline: List<LatLng>): Double? {
        current.roundabout?.exitAngleDeg?.let { return it }
        val i = maneuvers.indexOf(current).takeIf { it >= 0 } ?: return null
        val (entry, exit) = when (current.type) {
            ManeuverType.ROUNDABOUT -> current to maneuvers.drop(i + 1).firstOrNull()
            ManeuverType.EXIT_ROUNDABOUT -> maneuvers.take(i).lastOrNull { it.type == ManeuverType.ROUNDABOUT } to current
            else -> return null
        }
        if (entry == null || exit == null) return null
        return fromPolyline(polyline, entry.location, exit.location)
    }

    fun fromPolyline(polyline: List<LatLng>, entry: LatLng, exit: LatLng): Double? {
        if (polyline.size < 3) return null
        val i0 = nearest(polyline, entry, 0)
        val i1 = nearest(polyline, exit, i0)
        if (i1 <= i0) return null
        val before = walk(polyline, i0, -1) ?: return null
        val after = walk(polyline, i1, +1) ?: return null
        val inBearing = before.bearingTo(polyline[i0])
        val outBearing = polyline[i1].bearingTo(after)
        var d = (outBearing - inBearing) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    private fun nearest(line: List<LatLng>, p: LatLng, from: Int): Int {
        var best = from
        var bestD = Double.MAX_VALUE
        for (k in from until line.size) {
            val d = line[k].distanceTo(p)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** The first point at least [SPAN_M] from line[i] going in [step] direction, or the line's end. */
    private fun walk(line: List<LatLng>, i: Int, step: Int): LatLng? {
        var k = i + step
        while (k in line.indices) {
            if (line[k].distanceTo(line[i]) >= SPAN_M) return line[k]
            k += step
        }
        return line.getOrNull(i + step)
    }
}
