package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoundaboutGeometry
import app.vela.core.vehicle.SomeIpHudArrows.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SomeIpHudArrowsTest {
    private fun m(type: ManeuverType, clockwise: Boolean? = null) = Maneuver(
        type, "", LatLng(38.5449, -121.7405), 0.0, 0.0,
        roundabout = clockwise?.let { RoundaboutGeometry(90.0, it) },
    )

    private val launchermap = mapOf(
        ManeuverType.TURN_LEFT to 1, ManeuverType.TURN_RIGHT to 2,
        ManeuverType.SLIGHT_LEFT to 3, ManeuverType.SLIGHT_RIGHT to 5,
        ManeuverType.KEEP_LEFT to 3, ManeuverType.KEEP_RIGHT to 5,
        ManeuverType.FORK_LEFT to 3, ManeuverType.FORK_RIGHT to 5,
        ManeuverType.RAMP_LEFT to 3, ManeuverType.RAMP_RIGHT to 5,
        ManeuverType.SHARP_LEFT to 7, ManeuverType.SHARP_RIGHT to 8,
        ManeuverType.UTURN to 9,
        ManeuverType.DEPART to 11, ManeuverType.CONTINUE to 11, ManeuverType.STRAIGHT to 11, ManeuverType.MERGE to 11,
        ManeuverType.ROUNDABOUT to 13, ManeuverType.EXIT_ROUNDABOUT to 24,
        ManeuverType.ARRIVE to 48, ManeuverType.UNKNOWN to 0,
    )

    private val native = launchermap + mapOf(
        ManeuverType.SHARP_LEFT to 1, ManeuverType.SHARP_RIGHT to 2, ManeuverType.UTURN to 7,
        ManeuverType.ROUNDABOUT to 99, ManeuverType.EXIT_ROUNDABOUT to 99,
        ManeuverType.ARRIVE to 99, ManeuverType.UNKNOWN to 99,
    )

    @Test fun `the stock sender's ids for every maneuver type`() {
        assertEquals(ManeuverType.entries.toSet(), launchermap.keys)
        for ((type, id) in launchermap) assertEquals(type.name, id, SomeIpHudArrows.of(m(type), Table.LAUNCHERMAP))
    }

    @Test fun `the native-arrow ids for every maneuver type`() {
        for ((type, id) in native) assertEquals(type.name, id, SomeIpHudArrows.of(m(type), Table.NATIVE_ARROW))
    }

    @Test fun `clockwise roundabouts have their own stock ids`() {
        assertEquals(14, SomeIpHudArrows.of(m(ManeuverType.ROUNDABOUT, clockwise = true), Table.LAUNCHERMAP))
        assertEquals(23, SomeIpHudArrows.of(m(ManeuverType.EXIT_ROUNDABOUT, clockwise = true), Table.LAUNCHERMAP))
        assertEquals(99, SomeIpHudArrows.of(m(ManeuverType.ROUNDABOUT, clockwise = true), Table.NATIVE_ARROW))
    }

    @Test fun `no maneuver is none in both`() {
        assertEquals(0, SomeIpHudArrows.of(null, Table.LAUNCHERMAP))
        assertEquals(0, SomeIpHudArrows.of(null, Table.NATIVE_ARROW))
    }

    @Test fun `the HUD's ids are not the FCE cluster's`() {
        // The two vocabularies share no code for a turn: mixing them up draws a wrong arrow.
        for (type in listOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.STRAIGHT, ManeuverType.ARRIVE)) {
            assertNotEquals(type.name, FceIcons.of(m(type)), SomeIpHudArrows.of(m(type), Table.LAUNCHERMAP))
        }
    }

    private fun rb(angle: Double?) = Maneuver(
        ManeuverType.ROUNDABOUT, "", LatLng(38.5449, -121.7405), 0.0, 0.0,
        roundabout = angle?.let { RoundaboutGeometry(it, false) },
    )

    @Test fun `a roundabout exit is drawn as the turn it makes`() {
        assertEquals(null, SomeIpHudArrows.roundaboutAsTurn(rb(10.0), Table.LAUNCHERMAP)) // straight: keep the roundabout glyph
        assertEquals(2, SomeIpHudArrows.roundaboutAsTurn(rb(90.0), Table.LAUNCHERMAP))
        assertEquals(1, SomeIpHudArrows.roundaboutAsTurn(rb(-95.0), Table.LAUNCHERMAP))
        assertEquals(5, SomeIpHudArrows.roundaboutAsTurn(rb(45.0), Table.LAUNCHERMAP))
        assertEquals(3, SomeIpHudArrows.roundaboutAsTurn(rb(-45.0), Table.LAUNCHERMAP))
        assertEquals(9, SomeIpHudArrows.roundaboutAsTurn(rb(175.0), Table.LAUNCHERMAP))
        assertEquals(7, SomeIpHudArrows.roundaboutAsTurn(rb(-170.0), Table.NATIVE_ARROW))
        assertEquals(null, SomeIpHudArrows.roundaboutAsTurn(rb(null), Table.LAUNCHERMAP))
        assertEquals(null, SomeIpHudArrows.roundaboutAsTurn(m(ManeuverType.TURN_LEFT), Table.LAUNCHERMAP))
        // A Google roundabout has no geometry; the angle measured on the route line is passed in.
        assertEquals(1, SomeIpHudArrows.roundaboutAsTurn(rb(null), Table.LAUNCHERMAP, angleDeg = -88.0))
        assertEquals(1, SomeIpHudArrows.roundaboutAsTurn(m(ManeuverType.EXIT_ROUNDABOUT), Table.LAUNCHERMAP, angleDeg = -88.0))
    }
}
