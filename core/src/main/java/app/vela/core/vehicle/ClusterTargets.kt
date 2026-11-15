package app.vela.core.vehicle

/**
 * Which secondary display is a head unit's instrument cluster, and by which route Vela draws on it
 * (SPEC 10.9). The display list itself comes from Android in `:app`; this holds the rules.
 */
object ClusterTargets {
    /** DiLink 5.0 and 5.1: the two app-facing inputs the container service composites onto the
     *  cluster. `_1` is the right-hand card and is idle on the stock cluster; `_0` often carries
     *  the stock map or scene, so it is offered second. */
    const val DILINK5_SIDE = "shared_fission_bg_XDJAScreenProjection_1"
    const val DILINK5_FULL = "shared_fission_bg_XDJAScreenProjection_0"
    /** DiLink 4.0 (2022 Seal): one 1920 by 720 projection surface. */
    const val DILINK4 = "fission_bg_xdjaVirtualSurface"

    private val NAMES = listOf(DILINK5_SIDE, DILINK5_FULL, DILINK4)

    /**
     * The displays that may be offered, in the order offered. Only known cluster names: an aspect
     * ratio does not make a display a cluster, and a passenger screen or a mirror also counts as a
     * presentation display. [anyPresentation] is the test rig's switch (an emulator's simulated
     * display), set only from adb.
     */
    fun candidates(names: List<String>, anyPresentation: Boolean = false): List<String> {
        val known = NAMES.filter { it in names }
        return if (anyPresentation) known + names.filter { it !in known } else known
    }

    /**
     * The order for the adb route. On the firmware that hides these displays from apps, the
     * cluster's map view shows `_0`, where the stock cluster map runs, and keeps `_1` at 1 by 1
     * pixel, so `_0` is offered first. Measured on a Leopard 8 (DiLink 5.1), November 2026.
     */
    fun adbCandidates(names: List<String>): List<String> =
        listOf(DILINK5_FULL, DILINK5_SIDE, DILINK4).filter { it in names }

    /** The display to draw on: the one the user picked, and only while it exists. A name that is
     *  briefly gone (a cluster mode change restarts the container service) casts nothing rather
     *  than the next display on the list, which can be the passenger screen. */
    fun pick(stored: String?, names: List<String>, anyPresentation: Boolean = false): String? =
        stored?.takeIf { it in candidates(names, anyPresentation) }
}

/** How the cluster map reaches the cluster. */
enum class ClusterRoute { PRESENTATION, ADB, NONE }

/**
 * The public display comes first, always. The adb route is a fallback for a head unit whose
 * firmware hides the cluster displays from apps, so a failing Presentation on a display that exists
 * never falls through to it. [adbRoute] is true only when the driver turned the route on and the app
 * sees no cluster display of its own.
 */
fun clusterRoute(publicDisplay: Boolean, adbRoute: Boolean): ClusterRoute = when {
    publicDisplay -> ClusterRoute.PRESENTATION
    adbRoute -> ClusterRoute.ADB
    else -> ClusterRoute.NONE
}

/** One vehicle capability's state, as Settings > Diagnostics reports it. */
enum class VehicleSupport {
    /** In use now. */
    ACTIVE,
    /** Present and allowed; the switch is on and nothing needs it yet, or the switch is off. */
    AVAILABLE,
    /** The head unit does not have it. */
    NOT_PRESENT,
    /** Present, and Android or the service refused Vela. */
    ACCESS_DENIED,
    /** Present but gone for now (a service restarting, a display briefly removed). */
    UNAVAILABLE,
    /** Last use failed. */
    FAILED,
    /** Not checked yet. */
    UNKNOWN,
    /** This build has no code for it. */
    NOT_IN_THIS_BUILD,
}
