package com.coxy.ghost.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * GHOST's presence in Live mode: a lit sphere hanging in the void.
 *
 * Built as a lit object rather than a flat circle — a dark body, a cyan key light drifting across
 * the upper left, a violet fill light opposite it, a bright core, and an outer bloom. That layering
 * is what makes it read as a sphere with something alive inside instead of a gradient disc.
 *
 * The four states are not decorative. Each maps to a signal the app already has:
 *   IDLE      — nothing happening. Slow, dim, barely breathing.
 *   LISTENING — the mic is open ([com.coxy.ghost.perception.ContextState.micState]).
 *   THINKING  — the engine is generating ([com.coxy.ghost.EngineGate]).
 *   SPEAKING  — GHOST is talking back.
 * State changes are interpolated rather than snapped, so the orb never jumps between looks.
 *
 * Same performance discipline as the rest of the UI: shaders are built once per size change and
 * moved with canvas transforms, and the animator stops when the view leaves the window.
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OrbState(
        /** Overall luminosity. */
        val glow: Float,
        /** Breath depth, as a fraction of radius. */
        val breath: Float,
        /** Multiplier on the phase clock — how fast the lights travel. */
        val tempo: Float,
    ) {
        IDLE(0.74f, 0.016f, 0.5f),
        LISTENING(0.85f, 0.030f, 1.0f),
        THINKING(1.00f, 0.020f, 2.2f),
        SPEAKING(1.15f, 0.055f, 1.6f),
    }

    private var target = OrbState.IDLE

    // Interpolated toward the target's values so transitions are smooth.
    private var glow = OrbState.IDLE.glow
    private var breath = OrbState.IDLE.breath
    private var tempo = OrbState.IDLE.tempo

    private var phase = 0f
    private var radius = 0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 9_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener {
            phase = it.animatedValue as Float
            // Ease toward the target state rather than snapping to it.
            glow += (target.glow - glow) * 0.06f
            breath += (target.breath - breath) * 0.06f
            tempo += (target.tempo - tempo) * 0.06f
            invalidate()
        }
    }

    fun setOrbState(state: OrbState) {
        target = state
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        radius = min(w, h) * 0.36f
        val r = radius

        // The sphere's mass: lighter toward the top-left, falling to near-black at the rim.
        bodyPaint.shader = RadialGradient(
            -r * 0.25f, -r * 0.25f, r * 1.35f,
            intArrayOf(0xFF16303A.toInt(), 0xFF0C1622.toInt(), 0xFF05070A.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        keyPaint.shader = RadialGradient(
            0f, 0f, r * 0.62f,
            0x9900F0FF.toInt(), 0x0000F0FF, Shader.TileMode.CLAMP
        )
        fillPaint.shader = RadialGradient(
            0f, 0f, r * 0.70f,
            0x66D0BCFF, 0x00D0BCFF, Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            0f, 0f, r * 0.26f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xCC9BF6FF.toInt(), 0x0000F0FF),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        bloomPaint.shader = RadialGradient(
            0f, 0f, r * 1.9f,
            intArrayOf(0x3300F0FF, 0x1400F0FF, 0x0000F0FF),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val t = phase * tempo

        // Breathing: the whole sphere swells and settles.
        val scale = 1f + sin(t) * breath
        val r = radius * scale

        canvas.save()
        canvas.translate(cx, cy)

        bloomPaint.alpha = (110 * glow).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, r * 1.9f, bloomPaint)

        canvas.drawCircle(0f, 0f, r, bodyPaint)

        // Key and fill lights travel around inside the body on opposed paths, which is what sells
        // the illusion of a lit object rather than a flat gradient.
        val keyX = cos(t * 0.7f + 2.3f) * r * 0.30f
        val keyY = sin(t * 0.5f + 1.1f) * r * 0.30f - r * 0.18f
        keyPaint.alpha = (200 * glow).toInt().coerceIn(0, 255)
        canvas.save(); canvas.translate(keyX, keyY)
        canvas.drawCircle(0f, 0f, r * 0.62f, keyPaint); canvas.restore()

        val fillX = -keyX * 0.85f
        val fillY = -keyY * 0.6f + r * 0.22f
        fillPaint.alpha = (170 * glow).toInt().coerceIn(0, 255)
        canvas.save(); canvas.translate(fillX, fillY)
        canvas.drawCircle(0f, 0f, r * 0.70f, fillPaint); canvas.restore()

        // The core sits slightly off-centre, tracking the key light.
        corePaint.alpha = (230 * glow).toInt().coerceIn(0, 255)
        canvas.save(); canvas.translate(keyX * 0.4f, keyY * 0.4f)
        canvas.drawCircle(0f, 0f, r * 0.26f, corePaint); canvas.restore()

        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start() else if (animator.isPaused) animator.resume()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Live mode is one of three tabs; the orb must not animate while the user is in Chat.
        if (visibility == VISIBLE) {
            if (!animator.isStarted) animator.start() else if (animator.isPaused) animator.resume()
        } else {
            animator.pause()
        }
    }
}
