package com.snip.app.capture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * A soft, slowly-rotating glow traced just inside the screen edges — the same cue Gemini's
 * assistant overlay uses to say "I'm listening". Purely decorative: never clickable/focusable,
 * so touches fall straight through to whatever is drawn beneath it.
 *
 * Drawn as a *frame* (outer edge minus a rounded inner edge), not a stroked round-rect: the
 * outer edge is always a hard right angle sitting flush against the physical screen edge, and
 * only the inner edge curves. No device's actual bezel radius has to be matched — there's never
 * a gap between our line and the true corner, on any device, because the outer edge never
 * pretends to be rounded in the first place.
 */
class GlowBorderView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    // Only draws under a transparent status/nav bar (RegionSelectActivity makes both
    // transparent) — otherwise this thin a margin would sit hidden underneath them. As close to
    // 0 as possible: the fill-based frame's outer edge sits exactly here now (a stroke used to
    // straddle this line and bleed outward, masking any gap to the true screen edge).
    private val insetPx = density * 0.5f

    // Purely an inner chamfer now (see class doc) — free to pick whatever reads well, since the
    // outer edge staying sharp is what actually avoids the device-mismatch gap.
    private val innerCornerRadiusPx = density * 14f

    // Two-hue blue→teal blend (the app's own palette), not a full rainbow — matches the
    // reference look, which shifts gently between a couple of related cool tones.
    private val colors = intArrayOf(
        Color.parseColor("#93BFF5"),
        Color.parseColor("#7EE8C7"),
        Color.parseColor("#93BFF5"),
    )

    // setShadowLayer's color is a fixed solid (it can't follow the stroke's shader hue frame to
    // frame), so both passes glow in the same brand blue regardless of which hue the moving
    // gradient is showing at that instant — reads as one coherent light, not a mismatched halo.
    private val glowColor = Color.parseColor("#93BFF5")

    // The bloom is a real Gaussian blur (RenderNode + RenderEffect), not setShadowLayer's flat
    // drop-shadow approximation — it's recorded once per frame at full brightness/width, then
    // the whole recording is blurred as a unit, which is what actually reads as "light diffusing"
    // rather than "a slightly-fuzzy line". Falls back to setShadowLayer pre-API31.
    private val supportsRenderNodeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val bloomRenderNode: RenderNode? = if (supportsRenderNodeBlur) RenderNode("borderBloom") else null

    private val bloomBaseWidth = density * 12f
    private val bloomPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        if (!supportsRenderNodeBlur) setShadowLayer(density * 40f, 0f, 0f, glowColor)
    }
    private val coreBaseWidth = density * 2.5f
    private val corePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(density * 12f, 0f, 0f, Color.WHITE)
    }

    private var rotationDeg = 0f
    private var animator: ValueAnimator? = null

    // A slower "breathing" pulse layered on top of the rotation — Siri's listening border isn't
    // just a moving hue, it visibly swells and relaxes, which is what sells it as alive.
    private val startTimeNanos = System.nanoTime()
    private val pulsePeriodMs = 1500f

    // Entrance "wash": on start(), a soft light sweeps in from the edges and settles — the
    // difference between "a border is drawn" and "the screen just woke up and is listening".
    private var entranceProgress = 0f // 0 = just appeared, 1 = settled into steady state
    private var entranceAnimator: ValueAnimator? = null
    private val washPaint = Paint()

    private var shader: SweepGradient? = null
    private val shaderMatrix = android.graphics.Matrix()

    private val outerPath = Path()
    private val innerPath = Path()
    private val framePath = Path()

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            shader = SweepGradient(w / 2f, h / 2f, colors, null)
        }
    }

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationDeg = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        entranceProgress = 0f
        entranceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                entranceProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        entranceAnimator?.cancel()
        entranceAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = false

    /** Outer rect is always sharp; only the inner rect (outer shrunk by [thicknessPx]) is
     * rounded. Filling outer-minus-inner gives a frame whose outside never has to match any
     * device's real corner curvature. */
    private fun buildFramePath(thicknessPx: Float): Path {
        outerPath.reset()
        outerPath.addRect(insetPx, insetPx, width - insetPx, height - insetPx, Path.Direction.CW)

        val innerRadius = (innerCornerRadiusPx - thicknessPx).coerceAtLeast(0f)
        innerPath.reset()
        innerPath.addRoundRect(
            insetPx + thicknessPx,
            insetPx + thicknessPx,
            width - insetPx - thicknessPx,
            height - insetPx - thicknessPx,
            innerRadius,
            innerRadius,
            Path.Direction.CW,
        )

        framePath.reset()
        framePath.op(outerPath, innerPath, Path.Op.DIFFERENCE)
        return framePath
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeShader = shader ?: return
        if (width == 0 || height == 0) return

        val elapsedMs = (System.nanoTime() - startTimeNanos) / 1_000_000f
        val pulse = (kotlin.math.sin(elapsedMs / pulsePeriodMs * (2 * Math.PI)).toFloat() + 1f) / 2f // 0..1
        // entranceProgress: 0 right when start() fires -> 1 once settled.
        val settle = 1f - entranceProgress
        val entranceBoost = settle * settle

        // Ambient wash: a soft light filling the screen from the edges in, present at low level
        // the whole time (tied to the breathing pulse) and much brighter for the first ~900ms —
        // this is what makes it read as "the screen woke up and is glowing", not "a line was drawn".
        val maxRadius = kotlin.math.hypot(width / 2f, height / 2f)
        val washAlpha = (10 + pulse * 14 + entranceBoost * 70).toInt().coerceIn(0, 255)
        washPaint.shader = RadialGradient(
            width / 2f,
            height / 2f,
            maxRadius,
            intArrayOf(Color.TRANSPARENT, colorWithAlpha(glowColor, washAlpha)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)

        shaderMatrix.setRotate(rotationDeg, width / 2f, height / 2f)
        activeShader.setLocalMatrix(shaderMatrix)
        bloomPaint.shader = activeShader
        corePaint.shader = activeShader

        bloomPaint.alpha = (200 + pulse * 55).toInt().coerceIn(0, 255)
        val bloomWidth = bloomBaseWidth * (0.85f + pulse * 0.4f + entranceBoost * 2.4f)
        corePaint.alpha = (215 + pulse * 40).toInt().coerceIn(0, 255)

        val blurRadiusPx = density * (26f + entranceBoost * 24f)

        val node = bloomRenderNode
        val bloomFrame = buildFramePath(bloomWidth)
        if (supportsRenderNodeBlur && node != null && canvas.isHardwareAccelerated) {
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording()
            recording.drawPath(bloomFrame, bloomPaint)
            node.endRecording()
            node.setRenderEffect(RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP))
            canvas.drawRenderNode(node)
        } else {
            canvas.drawPath(bloomFrame, bloomPaint)
        }
        canvas.drawPath(buildFramePath(coreBaseWidth), corePaint)
        postInvalidateOnAnimation()
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
