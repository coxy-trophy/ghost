package com.coxy.ghost.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.coxy.ghost.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * The slow-moving light behind the glass.
 *
 * Three wide radial gradients drift on independent elliptical paths at different rates, so the
 * background never repeats a visibly identical frame. Everything above this view is translucent, so
 * this is what gives the panels their colour and their sense of depth.
 *
 * Performance was the design constraint — this runs on a Unisoc T612, and GHOST is a daemon that
 * must not be the reason the phone gets warm:
 *  - Each gradient shader is built **once**, at size change, and re-positioned per frame with a
 *    canvas translate. Rebuilding shaders every frame is what makes this pattern expensive.
 *  - The blobs are drawn with no blur pass. A RenderEffect blur over a full-screen view would cost
 *    far more than it adds here, because a wide radial gradient is already soft.
 *  - The animation stops whenever the view leaves the window or is hidden, so it cannot run behind
 *    a backgrounded Activity.
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Blob(
        val colorRes: Int,
        val radiusFactor: Float,
        val orbitX: Float,
        val orbitY: Float,
        val speed: Float,
        val phase: Float,
        val alpha: Int,
    )

    /**
     * Tuned on-device. The first pass used radii near a full screen width, which blended the three
     * into one flat blue wash — the movement was there but invisible. Tighter, brighter blobs on
     * wider orbits read as separate lights drifting behind the glass, which is the point.
     */
    private val blobs = listOf(
        Blob(R.color.aurora_one, 0.58f, 0.34f, 0.20f, 1.00f, 0f, 120),
        Blob(R.color.aurora_two, 0.48f, 0.30f, 0.26f, -0.62f, 2.1f, 105),
        Blob(R.color.aurora_three, 0.42f, 0.26f, 0.22f, 0.41f, 4.2f, 95),
    )

    private val paints = ArrayList<Paint>(blobs.size)
    private val radii = FloatArray(blobs.size)

    /** Radians. One full turn of the slowest blob takes about a minute. */
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 60_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = null // linear: any easing would make the drift visibly "pump"
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val base = maxOf(w, h).toFloat()
        paints.clear()
        blobs.forEachIndexed { i, blob ->
            val radius = base * blob.radiusFactor
            radii[i] = radius
            val color = ContextCompat.getColor(context, blob.colorRes)
            val core = (blob.alpha shl 24) or (color and 0x00FFFFFF)
            val edge = color and 0x00FFFFFF // fully transparent, same hue — avoids a grey halo
            paints += Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // Built once per size change, then moved with canvas translates.
                shader = RadialGradient(0f, 0f, radius, core, edge, Shader.TileMode.CLAMP)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (paints.size != blobs.size) return
        val cx = width * 0.5f
        val cy = height * 0.45f
        blobs.forEachIndexed { i, blob ->
            val t = phase * blob.speed + blob.phase
            val x = cx + cos(t) * width * blob.orbitX
            // The vertical term runs at a different multiple so the path is a slow Lissajous
            // figure rather than a circle — it never quite repeats.
            val y = cy + sin(t * 1.3f) * height * blob.orbitY
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, radii[i], paints[i])
            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) resume() else animator.pause()
    }

    private fun resume() {
        if (!animator.isStarted) animator.start() else if (animator.isPaused) animator.resume()
    }
}
