package app.vela.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterEtaTest {
    private val t0 = 1_000_000L

    @Test fun onTrackWhileTheArrivalHolds() {
        val b = ClusterEta.Baseline()
        assertTrue(b.onTrack("d", t0, 1800.0, 1.0))
        // Ten minutes later with twenty minutes left: same arrival.
        assertTrue(b.onTrack("d", t0 + 600_000, 1200.0, 1.0))
    }

    @Test fun aSmallSlipStaysGreen() {
        val b = ClusterEta.Baseline()
        b.onTrack("d", t0, 1800.0, null)
        // 90 s late on a 30 min trip: under both the 2 min floor and 5 % (90 s).
        assertTrue(b.onTrack("d", t0 + 600_000, 1290.0, null))
    }

    @Test fun slippingPastTheToleranceTurnsRed() {
        val b = ClusterEta.Baseline()
        b.onTrack("d", t0, 1800.0, null)
        // 3 min late on a 30 min trip.
        assertFalse(b.onTrack("d", t0 + 600_000, 1380.0, null))
    }

    @Test fun longTripsGetAProportionalTolerance() {
        // 2 h trip: 5 % is 6 min, so 5 min late is still on track.
        assertTrue(ClusterEta.onTrack(t0 + 7_200_000, 7200.0, t0 + 3_600_000, 3900.0, null))
        assertFalse(ClusterEta.onTrack(t0 + 7_200_000, 7200.0, t0 + 3_600_000, 4000.0, null))
    }

    @Test fun heavyTrafficIsADelay() {
        val b = ClusterEta.Baseline()
        assertFalse(b.onTrack("d", t0, 1800.0, 1.5))
        assertTrue(b.onTrack("d", t0, 1800.0, 1.3))
    }

    @Test fun aNewDestinationStartsANewBaseline() {
        val b = ClusterEta.Baseline()
        b.onTrack("a", t0, 600.0, null)
        // Far later than the first destination's arrival, but it is a new trip.
        assertTrue(b.onTrack("b", t0 + 3_600_000, 1800.0, null))
    }

    @Test fun destinationKeyIgnoresAFewMeters() {
        assertEquals(ClusterEta.destinationKey(38.5449, -121.7404), ClusterEta.destinationKey(38.5451, -121.7402))
    }
}
