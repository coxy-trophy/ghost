package com.coxy.ghost.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The band of light across the top of Live mode: stacked ribbons that swell when GHOST can
 * actually hear something.
 *
 * It is fed from [com.coxy.ghost.perception.MicListener.lastRms], the recognizer's own loudness, so
 * it is an instrument rather than an ornament — when the ribbon is flat, the mic genuinely is not
 * picking anything up. That mattered enough to build: the mic has been silently off or silently
 * deaf more than once in this project's history, and a decorative animation would have looked
 * identical either way.
 *
 * Note it is deliberately *not* fed from `ContextState.audioLevelDb`, which looks like the obvious
 * source and is in fact never written — the honest dBFS source went away with the always-on
 * `AudioRecord` spotter in v0.11.4, and the field has sat at its `-1f` default since. Driving the
 * band from it would have pinned it at full height and looked exactly like a working meter.
 *
 * Each ribbon is the same travelling wave at a slightly different phase and vertical offset. The
 * overlap is the point — the interference between neighbouring lines is what produces the woven
 * look, and it costs nothing beyond drawing the lines themselves.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** Ribbons stacked through each other. Enough to weave, few enough to hold 60fps. */
        const val LINES = 16

        /** Samples along each ribbon. */
        const val STEPS = 56

    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private var phase = 0f

    /** Smoothed 0..1 loudness. Eased so a single loud syllable does not snap the whole band. */
    private var level = 0f
    private var levelTarget = 0f

    /**
     * Supplies the current loudness as 0..1, sampled once per frame.
     *
     * A pull rather than a push, because loudness changes about ten times a second while the
     * perception state that would carry it changes far less often — pushing would show a band that
     * lags well behind the voice it is supposed to be drawing.
     */
    var levelProvider: (() -> Float)? = null

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 5_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener {
            phase = it.animatedValue as Float
            levelTarget = levelProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
            level += (levelTarget - level) * 0.12f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        // The ramp runs along the band, so the wave travels through the colours rather than
        // carrying one flat tint.
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x006B5BFF, 0xFF6B5BFF.toInt(), 0xFFC13BFF.toInt(),
                       0xFFFF2D9E.toInt(), 0xFFFF6B4A.toInt(), 0x00FF6B4A),
            floatArrayOf(0f, 0.16f, 0.4f, 0.64f, 0.86f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val mid = h * 0.5f
        // A resting amplitude keeps the band alive while idle; the level rides on top of it.
        val amp = h * (0.06f + 0.34f * level)

        for (line in 0 until LINES) {
            val k = line / (LINES - 1f)          // 0..1 across the stack
            val lead = k * 0.9f                   // each ribbon trails the one before it
            val drift = (k - 0.5f) * h * 0.16f    // vertical spread of the stack

            path.reset()
            for (s in 0..STEPS) {
                val u = s / STEPS.toFloat()
                val x = u * w
                // Taper to nothing at both ends so the band floats instead of being cropped.
                val envelope = exp(-((u - 0.5f) * (u - 0.5f) * 9f).toDouble()).toFloat()
                val y = mid + drift + amp * envelope * (
                    sin(u * 7.5f - phase * 2f + lead) * 0.65f +
                    sin(u * 4.1f + phase * 1.3f - lead * 1.7f) * 0.35f
                )
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Outer ribbons fade, which is what makes the stack read as one soft body.
            paint.alpha = (34 + 96 * (1f - kotlin.math.abs(k - 0.5f) * 2f)).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeIfVisible()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) resumeIfVisible() else animator.pause()
    }

    private fun resumeIfVisible() {
        // See OrbView: an infinite animator at animation-scale zero busy-loops instead of stopping.
        if (android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        ) {
            invalidate()
            return
        }
        if (!animator.isStarted) animator.start() else if (animator.isPaused) animator.resume()
    }
}
