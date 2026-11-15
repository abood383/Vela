package app.vela.vehicle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * The windshield's roundabout picture (field 8) with the exit drawn where it really leaves
 * (SPEC 10.9): the entry from the bottom, a thick arc round the ring in the direction traffic
 * circulates to the exit, the rest of the ring thin, and an arrow leaving at [exitAngleDeg]. The
 * stock glyph is one picture for every roundabout, a circle with a straight arrow, which is what a
 * glass that draws field 8 showed for a left exit. Same angle convention as
 * [app.vela.core.model.RoundaboutGeometry.exitAngleDeg]: 0 straight on, +90 right, -90 left.
 */
internal object HudRoundaboutPicture {
    fun draw(exitAngleDeg: Double, clockwise: Boolean, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val cx = s / 2f
        val cy = s * 0.52f
        val r = s * 0.22f
        val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = s * 0.09f; strokeCap = Paint.Cap.ROUND
        }
        val thin = Paint(thick).apply { strokeWidth = s * 0.035f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

        // Canvas angles run clockwise from 3 o'clock; a compass angle a (0 up, 90 right) is a - 90.
        val entry = 90f
        val exit = (exitAngleDeg - 90.0).toFloat()
        val sweep = if (clockwise) mod360(exit - entry) else -mod360(entry - exit)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, 0f, 360f, false, thin)
        c.drawArc(oval, entry, sweep, false, thick)
        // Entry stub up from the bottom edge to the ring.
        c.drawLine(cx, s * 0.95f, cx, cy + r, thick)
        // Exit stub outward from the ring at the exit angle, with an arrowhead.
        val rad = Math.toRadians(exit.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val x0 = cx + ux * r
        val y0 = cy + uy * r
        val len = s * 0.2f
        val x1 = x0 + ux * len
        val y1 = y0 + uy * len
        c.drawLine(x0, y0, x1 - ux * s * 0.06f, y1 - uy * s * 0.06f, thick)
        val head = s * 0.13f
        val px = -uy
        val py = ux
        val tip = Path().apply {
            moveTo(x1 + ux * head * 0.4f, y1 + uy * head * 0.4f)
            lineTo(x1 - ux * head * 0.6f + px * head * 0.6f, y1 - uy * head * 0.6f + py * head * 0.6f)
            lineTo(x1 - ux * head * 0.6f - px * head * 0.6f, y1 - uy * head * 0.6f - py * head * 0.6f)
            close()
        }
        c.drawPath(tip, fill)
        return bmp
    }

    private fun mod360(a: Float): Float = ((a % 360f) + 360f) % 360f
}
