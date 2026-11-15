package app.vela.vehicle

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.car.app.SurfaceContainer
import app.vela.VelaApp
import app.vela.car.CarLocationAccess
import app.vela.car.CarMapRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The cluster map for the adb route (SPEC 10.9, The adb cluster route). On firmware that hides
 * the cluster displays from apps, the controller has the shell user start this activity on the
 * chosen cluster display; from there it is an ordinary window of Vela's own, drawing the same
 * [CarMapRenderer] the Presentation path uses.
 *
 * It is exported so the shell can start it, and it guards itself: it declines unless the launch
 * names a cluster display ([EXTRA_DISPLAY], which only the controller's adb command sets), the
 * cluster map is on and a drive is running, and it finishes by itself when the drive ends, so
 * taking it down needs no second adb call. It does not trust its own display id: on BYD firmware
 * the activity is created on display 0 and moved, and both Activity.getDisplay and the decor view
 * kept reporting 0 after the move.
 */
class ClusterMapActivity : Activity() {
    private var renderer: CarMapRenderer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vela = application as? VelaApp
        val nav = vela?.navSession?.state?.value
        val target = intent?.getIntExtra(EXTRA_DISPLAY, Display.DEFAULT_DISPLAY) ?: Display.DEFAULT_DISPLAY
        // Only the controller's adb launch names a cluster display; anything else is declined.
        if (vela == null || target == Display.DEFAULT_DISPLAY || !app.vela.ui.ClusterMapPref.on.value ||
            nav == null || !nav.navigating || nav.arrived) {
            Log.i(TAG, "cluster activity declined (no cluster target, switched off or no drive)")
            finish()
            return
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        CarLocationAccess.check(this)
        val r = CarMapRenderer(this, vela.locationProvider, vela.navSession, routeEngine = null, badges = false)
        val view = SurfaceView(this)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val dpi = resources.displayMetrics.densityDpi
                runCatching { r.onSurfaceAvailable(SurfaceContainer(holder.surface, width, height, dpi)) }
                    .onFailure { Log.w(TAG, "cluster surface handoff failed", it) }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                runCatching { r.onSurfaceDestroyed(SurfaceContainer(holder.surface, 0, 0, 0)) }
            }
        })
        val box = ClusterTripBox(this)
        setContentView(box.over(this, view))
        box.bind(scope, vela.navSession)
        r.follow()
        r.start()
        renderer = r
        ClusterMapController.adbShowing = true
        Log.i(TAG, "cluster map (adb route) created on display ${displayId()}")
        scope.launch {
            kotlinx.coroutines.delay(SETTLE_MS)
            Log.i(TAG, "cluster map (adb route) target=$target decor=${window?.decorView?.display?.displayId} " +
                "activity=${displayId()} attached=${window?.decorView?.isAttachedToWindow}")
        }
        // The drive ending, or the switch going off, takes the map down.
        scope.launch {
            vela.navSession.state.first { !it.navigating || it.arrived }
            finish()
        }
    }

    override fun onDestroy() {
        renderer?.let { runCatching { it.stop() } }
        renderer = null
        if (ClusterMapController.adbShowing) ClusterMapController.adbShowing = false
        scope.cancel()
        super.onDestroy()
    }

    // The display the window is attached to. On BYD firmware Activity.getDisplay keeps reporting
    // display 0 after the window manager moves the activity to the cluster, so read the view's.
    // Activity.getDisplay is API 30; minSdk is 26.
    @Suppress("DEPRECATION")
    private fun displayId(): Int =
        window?.decorView?.display?.displayId
            ?: if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY
            else windowManager.defaultDisplay.displayId

    companion object {
        private const val TAG = "VelaCluster"
        private const val SETTLE_MS = 1_500L
        /** The cluster display id the controller asked for; the activity declines without it. */
        const val EXTRA_DISPLAY = "app.vela.cluster.DISPLAY"
    }
}
