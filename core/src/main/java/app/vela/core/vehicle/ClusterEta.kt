package app.vela.core.vehicle

import kotlin.math.max

/**
 * Whether a drive is on track for the arrival time it started with, for the cluster's time box
 * (SPEC 10.9): green on track, red when behind or delayed.
 *
 * The baseline is the arrival time at the start of the drive to a destination. A reroute keeps
 * it, so time lost to a detour reads as behind; a new destination starts a new baseline. The
 * drive is behind when the current arrival time has slipped past the baseline by more than
 * [MIN_SLIP_S] or [SLIP_FRACTION] of the planned trip, whichever is larger, and delayed when the
 * route's traffic ratio is past [HEAVY_TRAFFIC], the same threshold that turns the phone's ETA red.
 */
object ClusterEta {
    const val MIN_SLIP_S = 120.0
    const val SLIP_FRACTION = 0.05
    const val HEAVY_TRAFFIC = 1.4

    fun onTrack(baselineArrivalMs: Long, plannedS: Double, nowMs: Long, remainingS: Double, trafficRatio: Double?): Boolean {
        if (trafficRatio != null && trafficRatio > HEAVY_TRAFFIC) return false
        val slipS = (nowMs + remainingS * 1000.0 - baselineArrivalMs) / 1000.0
        return slipS <= max(MIN_SLIP_S, plannedS * SLIP_FRACTION)
    }

    /** The arrival time a drive started with, kept per destination. */
    class Baseline {
        private var key: String? = null
        private var arrivalMs = 0L
        private var plannedS = 0.0

        /** Updates for this moment and returns whether the drive is on track. */
        fun onTrack(destinationKey: String?, nowMs: Long, remainingS: Double, trafficRatio: Double?): Boolean {
            if (destinationKey != key || arrivalMs == 0L) {
                key = destinationKey
                arrivalMs = nowMs + (remainingS * 1000.0).toLong()
                plannedS = remainingS
            }
            return onTrack(arrivalMs, plannedS, nowMs, remainingS, trafficRatio)
        }

        fun reset() { key = null; arrivalMs = 0L; plannedS = 0.0 }
    }

    /** A destination key that survives a reroute ending a few meters off: about 100 m cells. */
    fun destinationKey(lat: Double, lng: Double): String = "%.3f,%.3f".format(java.util.Locale.US, lat, lng)
}
