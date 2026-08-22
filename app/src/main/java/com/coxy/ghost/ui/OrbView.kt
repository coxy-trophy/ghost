package com.coxy.ghost.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GHOST's presence: a shell of points hanging in the void, turning slowly.
 *
 * It is drawn as a particle shell rather than a gradient disc because the two read as completely
 * different things. A gradient is a picture of a light; a shell of points that occludes itself as
 * it turns is an *object*, and GHOST is meant to be a presence in the room rather than an
 * illustration on a page. The shell is deliberately crumpled — a smooth sphere reads as a loading
 * spinner, and the folds are what catch the ramp and give it a silhouette worth looking at.
 *
 * **The colour is not decoration — it is which brain answered.** GHOST runs on a cloud model when
 * she can reach one and a local 1.5B when she cannot, and that difference matters more than
 * anything else on screen. It already had a text chip; now the whole orb carries it. Hot
 * (amber → magenta → violet) is the cloud brain. Cool (violet → indigo) is local-only. The app
 * spent a long time silently answering from the local model while the cloud was assumed primary,
 * and nothing on screen said so — see [com.coxy.ghost.cloud.CloudRouter.lastBrain].
 *
 * The four states map to signals the app already has, and are interpolated rather than snapped:
 *   IDLE      — nothing happening. Slow, dim, barely breathing.
 *   LISTENING — the mic is open ([com.coxy.ghost.perception.ContextState.micState]).
 *   THINKING  — the engine is generating ([com.coxy.ghost.EngineGate]).
 *   SPEAKING  — GHOST is talking back.
 *
 * Performance, because this runs on a Unisoc T612 and must not cost more than it is worth:
 * the shell's geometry, its per-point colour band and its draw order are all computed **once**.
 * Each frame only rotates points (four multiplies each) and issues two [Canvas.drawPoints] calls
 * per colour band — 18 draw calls total, not one per particle. Nothing allocates after warm-up,
 * and the animator stops when the view leaves the window or its tab.
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
        /** Multiplier on the rotation clock. */
        val tempo: Float,
    ) {
        IDLE(0.78f, 0.014f, 0.45f),
        LISTENING(0.92f, 0.030f, 0.9f),
        THINKING(1.10f, 0.020f, 2.1f),
        SPEAKING(1.25f, 0.055f, 1.5f),
    }

    private companion object {
        /**
         * Points in the shell. This is the one knob that trades look against frame rate: below
         * ~1000 the shell reads as a scatter rather than a surface, and every point costs a
         * rasterised anti-aliased dot twice over (front pass and back pass). Drop it first if the
         * T612 cannot hold 60.
         */
        const val POINTS = 1500

        /** Colour bands the ramp is quantised into. Each is one paint and two draw calls. */
        const val BANDS = 9

        /** Depth slices per band: facing you, angled away, and behind. See [sliceXY]. */
        const val SLICES = 3

        /** Stroke weight per slice, as a fraction of radius. Nearer is fatter. */
        val SLICE_WIDTH = floatArrayOf(0.027f, 0.018f, 0.011f)

        /** Peak alpha per slice, before [glow]. The back of the shell only has to suggest itself. */
        val SLICE_ALPHA = floatArrayOf(248f, 165f, 58f)

        /**
         * How squarely a point must face the viewer to land in each slice.
         *
         * The first threshold is high on purpose: a small, bright, well-defined near face against a
         * soft falloff is what gives the shell a direction to be lit from.
         */
        val SLICE_CUT = floatArrayOf(0.55f, 0.0f)

        /**
         * The cloud brain: the full ramp, amber rim through magenta to violet. Warm end first —
         * band 0 is the top of the shell.
         */
        val RAMP_HOT = intArrayOf(
            0xFFFFC178.toInt(), 0xFFFFA24D.toInt(), 0xFFFF6B4A.toInt(),
            0xFFFF3355.toInt(), 0xFFFF2D9E.toInt(), 0xFFE23BE0.toInt(),
            0xFFC13BFF.toInt(), 0xFF9440FF.toInt(), 0xFF6B5BFF.toInt(),
        )

        /**
         * Local-only: the same ramp with its hot half cut away. Reads as unmistakably cooler at a
         * glance without becoming a different object.
         */
        val RAMP_COOL = intArrayOf(
            0xFFB4A0FF.toInt(), 0xFF9A86FF.toInt(), 0xFF8570FF.toInt(),
            0xFF7A63FF.toInt(), 0xFF6B5BFF.toInt(), 0xFF6152F0.toInt(),
            0xFF5748DC.toInt(), 0xFF4C3EC4.toInt(), 0xFF4235AC.toInt(),
        )
    }

    private var target = OrbState.IDLE

    // Interpolated toward the target's values so transitions are never a jump cut.
    private var glow = OrbState.IDLE.glow
    private var breath = OrbState.IDLE.breath
    private var tempo = OrbState.IDLE.tempo

    /** 0 = local ramp, 1 = cloud ramp. Interpolated, so a brain switch is a warm-up not a flash. */
    private var heat = 1f
    private var heatTarget = 1f

    /**
     * Smoothed 0..1 loudness, when something is feeding it.
     *
     * On the Live screen this replaces the separate waveform ribbon that used to sit above the
     * orb. Three things were competing to be GHOST's presence — a floor bloom, a ribbon and a ring
     * of buttons — and none of them won. Folding the meter into the orb keeps the honest half of
     * the ribbon (you can still see the mic is genuinely hearing you) and leaves one presence on
     * screen instead of three.
     *
     * Eased hard, because a single loud syllable should make her swell, not flinch.
     */
    private var level = 0f
    private var levelTarget = 0f

    /**
     * Supplies loudness as 0..1, sampled once per frame.
     *
     * A pull rather than a push for the same reason [WaveformView] used one: loudness changes about
     * ten times a second while the perception state that would carry it changes far less often, so
     * pushing shows an orb that lags well behind the voice it is drawing.
     */
    var levelProvider: (() -> Float)? = null

    private var phase = 0f
    private var radius = 0f

    // ---- Shell geometry, built once ----------------------------------------------------------
    // Unit positions, grouped by colour band so each band's points are contiguous. Grouping once
    // is what lets a frame draw a whole band in one call.
    private val px = FloatArray(POINTS)
    private val py = FloatArray(POINTS)
    private val pz = FloatArray(POINTS)

    /** Index of the first point of each band, plus a terminator. */
    private val bandStart = IntArray(BANDS + 1)

    /**
     * Per band, per depth slice, a scratch buffer of screen x/y pairs.
     *
     * Three slices rather than the two this started with, because front-versus-back only tells you
     * which half of the shell a point is on. Bucketing by how squarely a point faces the viewer is
     * a Lambert term with the light at the camera, and it is what makes the shell read as *lit*
     * rather than as two flat scatters at different opacities — the silhouette falls away, the
     * face nearest you catches the light, and the shell gains a form.
     *
     * The bucketing happens in the same per-frame loop that already projected every point, so it
     * costs three comparisons and nothing else. Draw calls go from 18 to 27, still nowhere near
     * one per particle.
     */
    private lateinit var sliceXY: Array<Array<FloatArray>>
    private val sliceCount = Array(BANDS) { IntArray(SLICES) }

    private val slicePaint = Array(BANDS) { Array(SLICES) { Paint(Paint.ANTI_ALIAS_FLAG) } }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        buildShell()
        for (b in 0 until BANDS) for (sl in 0 until SLICES) {
            slicePaint[b][sl].style = Paint.Style.STROKE
            slicePaint[b][sl].strokeCap = Paint.Cap.ROUND
        }
    }

    /**
     * Distributes points evenly over a sphere with a Fibonacci lattice, crumples the shell, then
     * sorts them into colour bands by height.
     *
     * The lattice is used instead of random placement because random points clump, and a clumped
     * shell looks like noise rather than a surface. The crumple is a sum of three sines of the
     * direction — cheap, smooth, and seamless across the sphere, which a texture-based noise would
     * not be without a lot more code.
     */
    private fun buildShell() {
        val golden = PI * (3.0 - sqrt(5.0))
        // Band each point by height first, then lay them out grouped, so no per-frame sorting.
        val tmpX = FloatArray(POINTS)
        val tmpY = FloatArray(POINTS)
        val tmpZ = FloatArray(POINTS)
        val band = IntArray(POINTS)
        val counts = IntArray(BANDS)

        for (i in 0 until POINTS) {
            // Even spacing in cos(theta) is what makes the lattice uniform by area.
            val y = 1.0 - (i / (POINTS - 1.0)) * 2.0
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = golden * i
            var vx = (cos(theta) * r)
            var vy = y
            var vz = (sin(theta) * r)

            // Crumple: push each point in or out along its own normal.
            val fold = 1.0 +
                0.17 * sin(3.1 * vx + 1.7) * cos(2.4 * vy - 0.4) +
                0.11 * sin(4.3 * vz + 2.9) +
                0.07 * cos(5.2 * vx * vy - 1.2)
            vx *= fold; vy *= fold; vz *= fold

            tmpX[i] = vx.toFloat(); tmpY[i] = vy.toFloat(); tmpZ[i] = vz.toFloat()

            // Band 0 is the top of the shell, so the ramp runs warm-over-cool like a lit object.
            val h = acos((vy / fold).coerceIn(-1.0, 1.0)) / PI   // 0 at north pole, 1 at south
            val b = (h * BANDS).toInt().coerceIn(0, BANDS - 1)
            band[i] = b
            counts[b]++
        }

        bandStart[0] = 0
        for (b in 0 until BANDS) bandStart[b + 1] = bandStart[b] + counts[b]
        val cursor = bandStart.copyOf()
        for (i in 0 until POINTS) {
            val b = band[i]
            val at = cursor[b]++
            px[at] = tmpX[i]; py[at] = tmpY[i]; pz[at] = tmpZ[i]
        }
        // Each slice is sized for the whole band: which points land where changes every frame as
        // the shell turns, so any slice can hold all of them for one frame.
        sliceXY = Array(BANDS) { b -> Array(SLICES) { FloatArray(2 * counts[b]) } }
    }

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 14_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener {
            phase = it.animatedValue as Float
            glow += (target.glow - glow) * 0.06f
            breath += (target.breath - breath) * 0.06f
            tempo += (target.tempo - tempo) * 0.06f
            heat += (heatTarget - heat) * 0.04f
            levelTarget = levelProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
            level += (levelTarget - level) * 0.14f
            invalidate()
        }
    }

    fun setOrbState(state: OrbState) {
        target = state
    }

    /**
     * Which brain is answering. `true` = a cloud model, `false` = the local 1.5B.
     * Drives the ramp; see the class comment for why this earns the whole orb.
     */
    fun setCloudBrain(cloud: Boolean) {
        heatTarget = if (cloud) 1f else 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        radius = min(w, h) * 0.33f
        bloomPaint.shader = RadialGradient(
            0f, 0f, radius * 2.1f,
            intArrayOf(0x40C13BFF, 0x18C13BFF, 0x00C13BFF),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            0f, 0f, radius * 0.75f,
            intArrayOf(0x30FF2D9E, 0x10FF2D9E, 0x00FF2D9E),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    /** Mixes the two ramps by [heat] so a brain change fades rather than cuts. */
    private fun bandColor(b: Int): Int {
        val hot = RAMP_HOT[b]
        val cool = RAMP_COOL[b]
        val t = heat.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(cool) + (Color.red(hot) - Color.red(cool)) * t).toInt(),
            (Color.green(cool) + (Color.green(hot) - Color.green(cool)) * t).toInt(),
            (Color.blue(cool) + (Color.blue(hot) - Color.blue(cool)) * t).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        val t = phase * tempo
        // Voice swells her. Additive on top of the breath rather than replacing it, so she is
        // never completely still and never stops being the same object.
        val r = radius * (1f + sin(phase) * breath + level * 0.11f)
        val lit = (glow + level * 0.4f).coerceIn(0f, 1.6f)

        // One rotation about the vertical, plus a tilt that drifts. A fixed tilt reads as a
        // turntable — the same silhouette every revolution — and the drift is what keeps it
        // looking like something hanging in the air rather than something mounted on a spindle.
        val tilt = 0.28f + 0.10f * sin(phase * 0.31f)
        val cosA = cos(t); val sinA = sin(t)
        val tiltC = cos(tilt); val tiltS = sin(tilt)

        canvas.save()
        canvas.translate(width * 0.5f, height * 0.5f)

        bloomPaint.alpha = (150 * lit).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, r * 2.1f, bloomPaint)

        for (b in 0 until BANDS) {
            val slices = sliceXY[b]
            val counts = sliceCount[b]
            counts[0] = 0; counts[1] = 0; counts[2] = 0
            val end = bandStart[b + 1]

            for (i in bandStart[b] until end) {
                val x0 = px[i]; val y0 = py[i]; val z0 = pz[i]
                // Yaw, then tilt. Only y and z need the tilt applied.
                val xr = x0 * cosA + z0 * sinA
                val zr = -x0 * sinA + z0 * cosA
                val yr = y0 * tiltC - zr * tiltS
                val zf = y0 * tiltS + zr * tiltC

                // How squarely this point faces the viewer, which with the light at the camera is
                // its shading. Three buckets is enough to read as a lit surface and cheap enough
                // to do in the loop that was already projecting the point.
                val slice = if (zf > SLICE_CUT[0]) 0 else if (zf > SLICE_CUT[1]) 1 else 2
                val at = counts[slice]
                val buf = slices[slice]
                // Screen y grows downward, so the shell's north pole — band 0, the warm end of
                // the ramp — has to be negated to land at the top of the view.
                buf[at] = xr * r
                buf[at + 1] = -yr * r
                counts[slice] = at + 2
            }

            val color = bandColor(b)
            // Drawn back to front, so the near face lands on top of what it should be hiding.
            for (sl in SLICES - 1 downTo 0) {
                if (counts[sl] == 0) continue
                val paint = slicePaint[b][sl]
                paint.color = color
                paint.strokeWidth = r * SLICE_WIDTH[sl]
                paint.alpha = (SLICE_ALPHA[sl] * lit).toInt().coerceIn(0, 255)
                canvas.drawPoints(slices[sl], 0, counts[sl], paint)
            }
        }

        // A faint interior wash, so the shell has something inside it rather than a hole.
        corePaint.alpha = (120 * lit).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, r * 0.75f, corePaint)

        canvas.restore()
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
        // The orb appears on two of four tabs; it must not animate while the user is elsewhere.
        if (visibility == VISIBLE) resumeIfVisible() else animator.pause()
    }

    private fun resumeIfVisible() {
        // Respect the system animation scale. At zero the user has asked for no animation, and an
        // infinite ValueAnimator would otherwise complete instantly and restart forever, spinning
        // the CPU on a device where battery is the whole point. Drawing one static frame is both
        // the accessible answer and the cheap one.
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
