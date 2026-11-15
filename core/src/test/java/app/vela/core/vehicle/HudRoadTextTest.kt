package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class HudRoadTextTest {
    private fun m(type: ManeuverType, instruction: String, road: String? = null, ref: String? = null, exit: Int? = null) =
        Maneuver(type, instruction, LatLng(38.54, -121.74), 100.0, 10.0, road = road, ref = ref, roundaboutExit = exit)

    @Test fun highwayExitShowsNumberAndSign() {
        assertEquals("Exit 12B · Folsom Blvd", HudRoadText.of(m(ManeuverType.RAMP_RIGHT, "Take exit 12B toward Folsom Blvd")))
    }

    @Test fun laneHintBeforeTheExitIsIgnored() {
        assertEquals(
            "Exit 172 · Sacramento",
            HudRoadText.of(m(ManeuverType.RAMP_RIGHT, "Use the right 2 lanes to take exit 172 toward Sacramento")),
        )
    }

    @Test fun exitWithoutASignUsesTheRoad() {
        assertEquals("Exit 15 · W Covell Blvd", HudRoadText.of(m(ManeuverType.RAMP_RIGHT, "Take exit 15", road = "W Covell Blvd")))
        assertEquals("Exit 15", HudRoadText.of(m(ManeuverType.RAMP_RIGHT, "Take exit 15")))
    }

    @Test fun roundaboutShowsWhichExit() {
        assertEquals("2nd exit · Elm St", HudRoadText.of(m(ManeuverType.ROUNDABOUT, "At the roundabout, take the 2nd exit onto Elm St", road = "Elm St", exit = 2)))
        assertEquals("4th exit", HudRoadText.of(m(ManeuverType.ROUNDABOUT, "At the roundabout, take the 4th exit", exit = 4)))
    }

    @Test fun ordinaryTurnKeepsTheRoad() {
        assertEquals("Elm St", HudRoadText.of(m(ManeuverType.TURN_LEFT, "Turn left onto Elm St", road = "Elm St")))
        assertEquals("I-80", HudRoadText.of(m(ManeuverType.MERGE, "Merge onto I-80", ref = "I 80")))
        assertEquals("I 80", HudRoadText.of(m(ManeuverType.MERGE, "Merge", ref = "I 80")))
        assertEquals("", HudRoadText.of(m(ManeuverType.STRAIGHT, "Continue straight")))
    }

    @Test fun theInstructionNameWinsOverALocalScriptRoad() {
        // The banner's Latin name, not the map-matched Arabic one the glass could not draw.
        assertEquals(
            "Russell Blvd",
            HudRoadText.of(m(ManeuverType.TURN_RIGHT, "Turn right onto Russell Boulevard", road = "شارع")),
        )
        assertEquals(
            "3rd exit · Russell Blvd",
            HudRoadText.of(m(ManeuverType.ROUNDABOUT, "At the roundabout, take the 3rd exit onto Russell Boulevard", road = "شارع", exit = 3)),
        )
    }

    @Test fun roundaboutExitIsReadFromTheInstruction() {
        // No roundaboutExit on the maneuver and only an Arabic road: the glass gets "3rd exit".
        assertEquals("3rd exit", HudRoadText.of(m(ManeuverType.ROUNDABOUT, "At the roundabout, take the 3rd exit", road = "شارع")))
        assertEquals("2nd exit", HudRoadText.of(m(ManeuverType.EXIT_ROUNDABOUT, "Take the second exit", road = "شارع")))
        assertEquals(3, HudRoadText.roundaboutExit("At the roundabout, take the 3rd exit onto X"))
        assertEquals(null, HudRoadText.roundaboutExit("Turn left"))
    }

    @Test fun aNameTheGlassCannotDrawIsDropped() {
        assertEquals("", HudRoadText.of(m(ManeuverType.TURN_RIGHT, "Turn right", road = "شارع شارع")))
        assertEquals("Elm St", HudRoadText.of(m(ManeuverType.TURN_RIGHT, "Turn right onto شارع", road = "Elm Street")))
    }

    @Test fun directionMarksAreStripped() {
        assertEquals("W Covell St", HudRoadText.of(m(ManeuverType.TURN_LEFT, "Turn left onto ‎W Covell Street‎")))
    }

    @Test fun ontoStopsBeforeToward() {
        assertEquals("Elm St", HudRoadText.of(m(ManeuverType.TURN_LEFT, "Turn left onto Elm St toward Folsom")))
    }

    @Test fun ordinals() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 22).map(HudRoadText::ordinal))
    }

    @Test fun otherLanguagesFallBackToTheRoad() {
        assertEquals("Elm St", HudRoadText.of(m(ManeuverType.RAMP_RIGHT, "Nehmen Sie die Ausfahrt 12", road = "Elm St")))
    }
}
