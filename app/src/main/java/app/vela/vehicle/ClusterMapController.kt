package app.vela.vehicle

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.compose.runtime.mutableStateOf
import app.vela.car.CarMapRenderer
import app.vela.car.CarLocationAccess
import app.vela.core.location.LocationProvider
import app.vela.core.nav.NavSession
import app.vela.core.vehicle.AdbWire
import app.vela.core.vehicle.ClusterRoute
import app.vela.core.vehicle.ClusterTargets
import app.vela.core.vehicle.VehicleSupport
import app.vela.core.vehicle.clusterRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.net.ConnectException

/**
 * Shows the cluster map on a BYD instrument cluster while a drive is on, and takes it down when
 * the drive ends or the display goes away (SPEC 10.9). It routes to the public presentation
 * display first. Only where the app sees no cluster display at all, and the driver has turned on
 * the adb route, does it reach the cluster through the car's own adb ([LocalAdb]): the shell user
 * starts [ClusterMapActivity] on the cluster display. Everything is gated on the user's opt-in,
 * and nothing is drawn on a display the user did not choose.
 *
 * The map is a [CarMapRenderer] reading the shared [NavSession]: one route for phone, Android Auto
 * and the cluster. The renderer is reused only while shown and released on teardown, so an idle
 * cluster never holds a native snapshotter.
 */
class ClusterMapController(
    private val appContext: Context,
    private val navSession: NavSession,
    private val locationProvider: LocationProvider,
) {
    @Volatile var support: VehicleSupport = VehicleSupport.UNKNOWN
        private set
    @Volatile var route: ClusterRoute = ClusterRoute.NONE
        private set

    /** Where the adb route stands, for the settings screen and diagnostics. */
    enum class AdbState { OFF, CONNECTING, AWAITING_APPROVAL, READY, NO_ADB, FAILED }

    val adbState = mutableStateOf(AdbState.OFF)
    /** Cluster display names the shell user sees, in the order offered. */
    val adbTargets = mutableStateOf<List<String>>(emptyList())

    private val main = Handler(Looper.getMainLooper())
    private val displayManager get() = appContext.getSystemService(DisplayManager::class.java)
    private val adb = LocalAdb(appContext)
    private var scope: CoroutineScope? = null
    private var presentation: ClusterMapPresentation? = null
    private var renderer: CarMapRenderer? = null
    private var navJob: Job? = null
    private var adbJob: Job? = null
    private var adbLaunched = false
    private var navigating = false
    private var listening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }

    fun start(scope: CoroutineScope) {
        if (navJob != null) return
        this.scope = scope
        refreshSupport()
        navJob = navSession.state
            .map { it.navigating && !it.arrived }
            .distinctUntilChanged()
            .onEach { on -> navigating = on; main.post { reconcile() } }
            .launchIn(scope)
    }

    /** The settings screen calls this when the cluster toggle or the chosen display changes. */
    fun onSettingChanged() { main.post { reconcile() } }

    /** Display names Vela would offer as the cluster, for the settings picker. */
    fun targets(): List<String> = ClusterTargets.candidates(presentationNames(), anyPresentation())

    /**
     * True where the adb route may be offered: the app sees no cluster display of its own. A car
     * where the Presentation path works never shows the adb switch.
     */
    fun adbOffered(): Boolean = targets().isEmpty()

    /**
     * Connects to the car's adb and reads which cluster displays the shell user sees. The first
     * time, the car shows its "Allow debugging?" prompt for Vela's key.
     */
    fun adbRefresh() {
        val s = scope ?: return
        if (adbJob?.isActive == true) return
        adbJob = s.launch(Dispatchers.IO) { adbDisplays() }
    }

    fun diagnostic(): String {
        val names = displayManager?.displays?.joinToString { "${it.displayId}:${it.name}" }.orEmpty()
        return "cluster: route=$route support=$support target=${chosenName() ?: "none"} displays=[$names]" +
            " adb=${adbState.value} adbTargets=${adbTargets.value}"
    }

    private fun reconcile() {
        val enabled = app.vela.ui.ClusterMapPref.on.value
        val public = if (enabled) chosenDisplay() else null
        val adbOn = enabled && app.vela.ui.ClusterAdbPref.on.value && adbOffered()
        route = clusterRoute(publicDisplay = public != null, adbRoute = adbOn)
        if (!adbOn && adbState.value != AdbState.OFF && !app.vela.ui.ClusterAdbPref.on.value) adbState.value = AdbState.OFF
        if (!navigating) adbLaunched = false
        when {
            public != null && navigating -> {
                if (presentation?.display?.displayId != public.displayId || presentation?.isShowing != true) show(public)
            }
            route == ClusterRoute.ADB && navigating -> {
                hide()
                if (!adbLaunched) adbLaunch()
            }
            else -> hide()
        }
        refreshSupport()
    }

    private fun show(display: Display) {
        hide()
        CarLocationAccess.check(appContext)
        val host = app.vela.ui.TopActivity.current ?: appContext
        val r = CarMapRenderer(host, locationProvider, navSession, routeEngine = null, badges = false)
        val p = ClusterMapPresentation(host, display, r, navSession)
        val ok = runCatching { p.show() }.onFailure { Log.w(TAG, "cluster presentation refused", it) }.isSuccess
        if (!ok) { support = VehicleSupport.ACCESS_DENIED; return }
        r.follow()
        r.start()
        renderer = r
        presentation = p
        support = VehicleSupport.ACTIVE
        if (!listening) { displayManager?.registerDisplayListener(displayListener, main); listening = true }
        Log.i(TAG, "cluster map on display ${display.displayId}:${display.name}")
    }

    private fun hide() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        renderer?.let { runCatching { it.stop() } }
        renderer = null
    }

    /** Starts [ClusterMapActivity] on the chosen cluster display as the shell user, once per drive. */
    private fun adbLaunch() {
        val s = scope ?: return
        adbLaunched = true
        s.launch(Dispatchers.IO) {
            val ids = adbDisplays() ?: return@launch
            val names = ids.map { it.second }
            val stored = app.vela.ui.ClusterMapPref.display.value
            val name = stored?.takeIf { it in names }
                ?: if (stored == null) names.firstOrNull() else null
            val id = ids.firstOrNull { it.second == name }?.first
            if (id == null) {
                Log.i(TAG, "adb route: no chosen cluster display among ${names.size}")
                support = VehicleSupport.UNAVAILABLE
                return@launch
            }
            val component = "${appContext.packageName}/${ClusterMapActivity::class.java.name}"
            val out = runCatching {
                adb.shell("am start --display $id -f 0x18000000 --ei ${ClusterMapActivity.EXTRA_DISPLAY} $id -n $component")
            }.getOrElse { Log.w(TAG, "adb route: start failed", it); "Error: ${it.javaClass.simpleName}" }
            if (out.contains("Error") || out.contains("Exception")) {
                Log.w(TAG, "adb route refused: ${out.lineSequence().firstOrNull { "Error" in it || "Exception" in it }}")
                support = VehicleSupport.ACCESS_DENIED
                adbState.value = AdbState.FAILED
            } else {
                Log.i(TAG, "cluster map (adb route) started on display $id")
                support = VehicleSupport.ACTIVE
            }
        }
    }

    /** The shell user's display list, filtered to cluster names; null when adb is not reachable. */
    private fun adbDisplays(): List<Pair<Int, String>>? {
        adbState.value = AdbState.CONNECTING
        return try {
            val dump = adb.shell("dumpsys display | grep mBaseDisplayInfo") {
                adbState.value = AdbState.AWAITING_APPROVAL
            }
            val all = AdbWire.displays(dump)
            val keep = ClusterTargets.adbCandidates(all.map { it.second })
            adbTargets.value = keep
            adbState.value = AdbState.READY
            keep.mapNotNull { n -> all.firstOrNull { it.second == n } }
        } catch (e: LocalAdb.AuthPending) {
            adbState.value = AdbState.AWAITING_APPROVAL
            null
        } catch (e: ConnectException) {
            adbState.value = AdbState.NO_ADB
            null
        } catch (e: Exception) {
            Log.w(TAG, "adb route: ${e.javaClass.simpleName}")
            adbState.value = if (e is java.net.SocketTimeoutException) AdbState.NO_ADB else AdbState.FAILED
            null
        } finally {
            main.post { refreshSupport() }
        }
    }

    private fun refreshSupport() {
        support = when {
            presentation != null || adbShowing -> VehicleSupport.ACTIVE
            app.vela.ui.ClusterMapPref.on.value && chosenDisplay() != null -> VehicleSupport.AVAILABLE
            targets().isNotEmpty() -> VehicleSupport.AVAILABLE
            app.vela.ui.ClusterAdbPref.on.value && adbState.value == AdbState.READY && adbTargets.value.isNotEmpty() -> VehicleSupport.AVAILABLE
            app.vela.ui.ClusterAdbPref.on.value && adbState.value == AdbState.AWAITING_APPROVAL -> VehicleSupport.ACCESS_DENIED
            else -> VehicleSupport.NOT_PRESENT
        }
    }

    private fun presentationNames(): List<String> =
        displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.map { it.name } ?: emptyList()

    private fun chosenName(): String? {
        val names = presentationNames()
        val stored = app.vela.ui.ClusterMapPref.display.value
        return ClusterTargets.pick(stored, names, anyPresentation())
            ?: if (stored == null) ClusterTargets.candidates(names, anyPresentation()).firstOrNull() else null
    }

    private fun chosenDisplay(): Display? {
        val name = chosenName() ?: return null
        return displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull { it.name == name }
    }

    // An adb-only test hook: on a phone or an emulator with no BYD cluster, target any presentation
    // display (a simulated secondary screen) to exercise the pipeline. Never on in the field.
    private fun anyPresentation(): Boolean = app.vela.ui.AppTune.localOn("clusterAnyDisplay")

    companion object {
        private const val TAG = "VelaCluster"
        /** True while [ClusterMapActivity] is up on a cluster display (the adb route). */
        @Volatile var adbShowing = false
    }
}
