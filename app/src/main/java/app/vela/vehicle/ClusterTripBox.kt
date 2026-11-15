package app.vela.vehicle

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.vela.core.nav.NavSession
import app.vela.core.vehicle.ClusterEta
import app.vela.ui.formatClock
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The box in the top left of the cluster map (SPEC 10.9): the time left centered on the top row,
 * and the distance left with the arrival clock time below it, styled like the phone's turn banner: white text on the banner green while the drive
 * is on track for the arrival time it started with, on red when it is behind or delayed
 * ([ClusterEta]). It reads the same [NavSession]
 * as the phone, formatted with the phone's own units, and redraws only when the shown text or
 * color changes. Hidden when no drive is on.
 */
internal class ClusterTripBox(context: Context) {
    private val time = text(context, 21f, Typeface.BOLD)
    private val distance = text(context, 15f, Typeface.NORMAL)
    private val bg = GradientDrawable().apply {
        setColor(GREEN)
        cornerRadius = dp(context, 14f).toFloat()
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = dp(context, 170f)
        val pad = dp(context, 16f)
        setPadding(pad, dp(context, 9f), pad, dp(context, 9f))
        background = bg
        gravity = Gravity.CENTER_HORIZONTAL
        time.gravity = Gravity.CENTER_HORIZONTAL
        distance.gravity = Gravity.CENTER_HORIZONTAL
        addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(distance, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 3f)
        })
        visibility = View.GONE
    }

    /** Lays the box over [content] in the top-left corner and returns the combined view. */
    fun over(context: Context, content: View): View = FrameLayout(context).apply {
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val margin = dp(context, 16f)
        addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(margin, margin, margin, margin)
        })
    }

    /** Follows the drive on [scope] (a main-thread scope) until the scope ends. */
    fun bind(scope: CoroutineScope, navSession: NavSession) {
        scope.launch {
            navSession.state
                .map { s ->
                    if (s.navigating && !s.arrived && s.remainingDistance > 0.0) {
                        val end = s.route?.polyline?.lastOrNull()
                        val key = end?.let { ClusterEta.destinationKey(it.lat, it.lng) }
                        val now = System.currentTimeMillis()
                        val onTrack = baseline.onTrack(key, now, s.remainingDuration, s.route?.trafficRatio)
                        val arrival = formatClock(now + (s.remainingDuration * 1000.0).toLong())
                        Shown(formatDuration(s.remainingDuration), "${formatDistance(s.remainingDistance)}  ·  $arrival", onTrack)
                    } else {
                        if (!s.navigating) baseline.reset()
                        null
                    }
                }
                .distinctUntilChanged()
                .collect { shown ->
                    if (shown == null) {
                        view.visibility = View.GONE
                    } else {
                        time.text = shown.time
                        bg.setColor(if (shown.onTrack) GREEN else RED)
                        distance.text = shown.distance
                        view.visibility = View.VISIBLE
                    }
                }
        }
    }

    private fun text(context: Context, sp: Float, style: Int) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTypeface(typeface, style)
        includeFontPadding = false
    }

    private data class Shown(val time: String, val distance: String, val onTrack: Boolean)

    private companion object {
        /** The phone's turn-banner green (`NavBannerGreen`) and its traffic red. */
        val GREEN = Color.rgb(0x00, 0x65, 0x4F)
        val RED = Color.rgb(0xD9, 0x38, 0x38)
        /** One baseline for the process, so a cluster window recreated mid-drive keeps it. */
        val baseline = ClusterEta.Baseline()
    }

    private fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
}
