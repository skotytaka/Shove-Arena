package com.romansgame.shove.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import com.romansgame.shove.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated menu logo: the red piece shoves the blue one, forever.
 */
class LogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val cP1 = ContextCompat.getColor(context, R.color.p1_red)
    private val cP2 = ContextCompat.getColor(context, R.color.p2_blue)
    private val cRim = ContextCompat.getColor(context, R.color.wood_dark)
    private val cGold = ContextCompat.getColor(context, R.color.gold)

    private fun easeOut(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun easeIn(t: Float): Float = t * t * t

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cycle = 2600L
        val t = (AnimationUtils.currentAnimationTimeMillis() % cycle) / cycle.toFloat()
        val r = min(w, h) * 0.22f
        val cy = h * 0.55f

        val redStart = w * 0.18f
        val contact = w * 0.52f - r * 2f     // red's position when it touches blue
        val blueHome = w * 0.52f
        val blueEnd = w * 0.80f

        val redX: Float
        val blueX: Float
        var spark = 0f

        when {
            t < 0.38f -> {                     // red charges
                val k = easeIn(t / 0.38f)
                redX = redStart + (contact - redStart) * k
                blueX = blueHome
            }
            t < 0.42f -> {                     // impact
                redX = contact
                blueX = blueHome
                spark = 1f
            }
            t < 0.72f -> {                     // blue flies away
                val k = easeOut((t - 0.42f) / 0.30f)
                redX = contact - r * 0.15f * k
                blueX = blueHome + (blueEnd - blueHome) * k
                spark = 1f - k
            }
            else -> {                          // everyone strolls home
                val k = easeOut((t - 0.72f) / 0.28f)
                redX = contact - r * 0.15f + (redStart - contact + r * 0.15f) * k
                blueX = blueEnd + (blueHome - blueEnd) * k
            }
        }

        // board shadow line
        stroke.color = cRim
        stroke.strokeWidth = r * 0.16f
        stroke.alpha = 60
        canvas.drawLine(w * 0.08f, cy + r * 1.25f, w * 0.92f, cy + r * 1.25f, stroke)
        stroke.alpha = 255

        drawPiece(canvas, blueX, cy, r, cP2)
        drawPiece(canvas, redX, cy, r, cP1)

        if (spark > 0f) {
            stroke.color = cGold
            stroke.strokeWidth = r * 0.14f
            stroke.alpha = (255 * spark).toInt().coerceIn(0, 255)
            val sx = (redX + blueX) / 2f + r
            for (i in 0 until 5) {
                val ang = -0.9f + i * 0.45f
                val len = r * (0.5f + 0.35f * sin(i * 2.1).toFloat())
                canvas.drawLine(
                    sx, cy - r * 0.2f,
                    sx + len * kotlin.math.cos(ang),
                    cy - r * 0.2f + len * kotlin.math.sin(ang),
                    stroke
                )
            }
            stroke.alpha = 255
        }

        postInvalidateOnAnimation()
    }

    private fun drawPiece(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = 0x22000000
        canvas.drawCircle(x + r * 0.08f, y + r * 0.14f, r, fill)
        fill.color = color
        canvas.drawCircle(x, y, r, fill)
        stroke.color = cRim
        stroke.strokeWidth = r * 0.12f
        canvas.drawCircle(x, y, r, stroke)
        fill.color = 0x55FFFFFF
        canvas.drawCircle(x - r * 0.3f, y - r * 0.35f, r * 0.3f, fill)
    }
}
