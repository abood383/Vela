package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.destinationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundaboutExitAngleTest {
    // A roundabout at the Davis fixture: approach from the south heading north, go counterclockwise
    // round a 25 m circle, and leave on one of four arms.
    private val center = LatLng(38.5449, -121.7405)
    private val r = 25.0

    private fun ring(fromDeg: Double, toDeg: Double): List<LatLng> {
        // Counterclockwise as seen from above: compass bearing decreasing.
        val out = ArrayList<LatLng>()
        var b = fromDeg
        while (b > toDeg) { out += center.destinationPoint(r, b); b -= 15.0 }
        out += center.destinationPoint(r, toDeg)
        return out
    }

    /** Approach up the south arm, round the ring, out along the arm at [exitBearing]. */
    private fun route(exitBearing: Double, ringTo: Double): Triple<List<LatLng>, LatLng, LatLng> {
        val entry = center.destinationPoint(r, 180.0)
        val approach = (5 downTo 1).map { entry.destinationPoint(it * 20.0, 180.0) }
        val circle = ring(180.0, ringTo)
        val exit = circle.last()
        val leave = (1..5).map { exit.destinationPoint(it * 20.0, exitBearing) }
        return Triple(approach + circle + leave, entry, exit)
    }

    @Test fun leftExitReadsAboutMinus90() {
        // Counterclockwise from the south arm (bearing 180) to the west arm (270, i.e. -90): the long way round.
        val (line, entry, exit) = route(exitBearing = 270.0, ringTo = -90.0)
        val a = RoundaboutExitAngle.fromPolyline(line, entry, exit)!!
        assertTrue("left exit, got $a", a in -120.0..-60.0)
    }

    @Test fun rightExitReadsAbout90() {
        val (line, entry, exit) = route(exitBearing = 90.0, ringTo = 90.0)
        val a = RoundaboutExitAngle.fromPolyline(line, entry, exit)!!
        assertTrue("right exit, got $a", a in 60.0..120.0)
    }

    @Test fun straightExitReadsAbout0() {
        val (line, entry, exit) = route(exitBearing = 0.0, ringTo = 0.0)
        val a = RoundaboutExitAngle.fromPolyline(line, entry, exit)!!
        assertTrue("straight exit, got $a", a in -30.0..30.0)
    }

    @Test fun theExitStepIsFoundFromTheEntry() {
        val (line, entry, exit) = route(exitBearing = 270.0, ringTo = -90.0)
        val enter = Maneuver(ManeuverType.ROUNDABOUT, "At the roundabout, take the 3rd exit", entry, 0.0, 0.0)
        val leave = Maneuver(ManeuverType.EXIT_ROUNDABOUT, "Exit the roundabout", exit, 0.0, 0.0)
        val list = listOf(enter, leave)
        val a1 = RoundaboutExitAngle.of(list, enter, line)!!
        val a2 = RoundaboutExitAngle.of(list, leave, line)!!
        assertEquals(a1, a2, 0.001)
        assertTrue(a1 < -60.0)
    }

    @Test fun notARoundabout() {
        val m = Maneuver(ManeuverType.TURN_LEFT, "Turn left", center, 0.0, 0.0)
        assertNull(RoundaboutExitAngle.of(listOf(m), m, listOf(center, center, center)))
    }
}
