package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.destinationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundaboutDetectTest {
    // Roads at the Davis fixture, drawn with 5 m vertices like a router's polyline.
    private val origin = LatLng(38.5449, -121.7405)

    private fun straight(from: LatLng, bearing: Double, meters: Double): List<LatLng> =
        (1..(meters / 5).toInt()).map { from.destinationPoint(it * 5.0, bearing) }

    /** Up a south arm heading north, counter-clockwise round a 20 m ring, out at [exitCompass]. */
    private fun roundabout(exitCompass: Double): Pair<List<LatLng>, LatLng> {
        val start = origin
        val approach = listOf(start) + straight(start, 0.0, 100.0)
        val center = approach.last().destinationPoint(20.0, 0.0)
        // Ring from the south point (180) counter-clockwise (decreasing compass) to the exit arm.
        var b = 180.0
        val ccwTo = 180.0 - (((180.0 - exitCompass) % 360.0 + 360.0) % 360.0).let { if (it == 0.0) 360.0 else it }
        val ring = ArrayList<LatLng>()
        while (b > ccwTo) { b -= 10.0; ring += center.destinationPoint(20.0, maxOf(b, ccwTo)) }
        val exitPt = ring.last()
        val leave = straight(exitPt, exitCompass, 150.0)
        return (approach + ring + leave) to approach.last()
    }

    private fun turn(type: ManeuverType, at: LatLng) = Maneuver(type, "Turn", at, 0.0, 0.0)

    @Test fun leftExitIsDetectedAsALeftLeavingRoundabout() {
        val (line, entry) = roundabout(exitCompass = 270.0)
        val m = RoundaboutDetect.annotate(listOf(turn(ManeuverType.TURN_LEFT, entry)), line).single()
        val g = assertNotNullG(m.roundabout)
        assertTrue("left, got ${g.exitAngleDeg}", g.exitAngleDeg in -120.0..-60.0)
        assertFalse(g.clockwise)
    }

    @Test fun rightAndStraightAndUturnExits() {
        val r = RoundaboutDetect.annotate(listOf(turn(ManeuverType.TURN_RIGHT, roundabout(90.0).second)), roundabout(90.0).first).single()
        assertTrue(r.roundabout!!.exitAngleDeg in 60.0..120.0)
        val s = RoundaboutDetect.annotate(listOf(turn(ManeuverType.STRAIGHT, roundabout(0.0).second)), roundabout(0.0).first).single()
        assertTrue(s.roundabout!!.exitAngleDeg in -30.0..30.0)
        val u = RoundaboutDetect.annotate(listOf(turn(ManeuverType.UTURN, roundabout(180.0).second)), roundabout(180.0).first).single()
        assertTrue(kotlin.math.abs(u.roundabout!!.exitAngleDeg) > 150.0)
    }

    @Test fun aPlainLeftTurnIsNotARoundabout() {
        val corner = origin.destinationPoint(100.0, 0.0)
        val line = listOf(origin) + straight(origin, 0.0, 100.0) + straight(corner, 270.0, 150.0)
        assertNull(RoundaboutDetect.annotate(listOf(turn(ManeuverType.TURN_LEFT, corner)), line).single().roundabout)
    }

    @Test fun aGentleSBendIsNotARoundabout() {
        val a = origin.destinationPoint(100.0, 0.0)
        val b = straight(a, 25.0, 40.0)
        val line = listOf(origin) + straight(origin, 0.0, 100.0) + b + straight(b.last(), 0.0, 150.0)
        assertNull(RoundaboutDetect.annotate(listOf(turn(ManeuverType.SLIGHT_RIGHT, a)), line).single().roundabout)
    }

    @Test fun aLeftThenAQuickRightIsNotOneRoundabout() {
        val c1 = origin.destinationPoint(100.0, 0.0)
        val leg = straight(c1, 270.0, 40.0)
        val c2 = leg.last()
        val line = listOf(origin) + straight(origin, 0.0, 100.0) + leg + straight(c2, 0.0, 150.0)
        val out = RoundaboutDetect.annotate(listOf(turn(ManeuverType.TURN_LEFT, c1), turn(ManeuverType.TURN_RIGHT, c2)), line)
        assertNull(out[0].roundabout)
        assertNull(out[1].roundabout)
    }

    @Test fun aGoogleRoundaboutStepGetsItsExitAndTheExitStepShares() {
        val (line, entry) = roundabout(exitCompass = 270.0)
        val exitAt = line[line.size - 30]
        val steps = listOf(
            Maneuver(ManeuverType.ROUNDABOUT, "At the roundabout, take the 3rd exit", entry, 0.0, 0.0, roundaboutExit = 3),
            Maneuver(ManeuverType.EXIT_ROUNDABOUT, "Exit the roundabout", line[line.size - 30], 0.0, 0.0),
        )
        val out = RoundaboutDetect.annotate(steps, line)
        assertTrue(out[0].roundabout!!.exitAngleDeg < -60.0)
        assertEquals(out[0].roundabout, out[1].roundabout)
        assertNotNull(exitAt)
    }

    @Test fun givenGeometryIsKept() {
        val (line, entry) = roundabout(exitCompass = 270.0)
        val given = app.vela.core.model.RoundaboutGeometry(12.0, true)
        val m = Maneuver(ManeuverType.ROUNDABOUT, "x", entry, 0.0, 0.0, roundabout = given)
        assertEquals(given, RoundaboutDetect.annotate(listOf(m), line).single().roundabout)
    }

    private fun assertNotNullG(g: app.vela.core.model.RoundaboutGeometry?) = g ?: throw AssertionError("no roundabout found")
}
