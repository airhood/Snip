package com.snip.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.animation.ValueAnimator
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

enum class SelectionMode { RECTANGLE, PEN }

/**
 * Shows a frozen screenshot, dims it, and lets the user mark a region either by dragging a
 * rectangle or by drawing a free-form stroke (its bounding box becomes the selection either
 * way). The dragged area is redrawn at full brightness (via a clip, not a per-frame saveLayer)
 * so dragging stays smooth even while it reads as "spotlighted".
 */
class RegionSelectView(context: Context) : View(context) {

    var onSelectionChanged: (RectF?) -> Unit = {}

    var mode: SelectionMode = SelectionMode.RECTANGLE
        set(value) {
            if (field == value) return
            field = value
            clearSelection()
        }

    // The raw capture, plus a pre-dimmed copy baked once in setScreenshot() rather than every
    // frame — a per-frame saveLayer()+CLEAR here was the main source of drag jank.
    private var screenshot: Bitmap? = null
    private var dimmedScreenshot: Bitmap? = null
    private val destRect = Rect()

    private val density = resources.displayMetrics.density
    private val accentColor = Color.parseColor("#93BFF5")
    private val accentColor2 = Color.parseColor("#7EE8C7")

    // Rounded "⌐"-style corner brackets, matching the app logo: a real curved corner (quadTo),
    // not a straight-line join, and a real Gaussian blur bloom (RenderNode) instead of
    // setShadowLayer's flat drop-shadow approximation.
    private val cornerLengthPx = density * 24
    private val cornerRadiusPx = density * 7
    private val cornerPath = Path()
    private val clipPath = Path()
    private val revealPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bloomPaint = Paint().apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = density * 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val corePaint = Paint().apply {
        color = Color.argb(255, 210, 235, 255)
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    // Pen mode's stroke is thicker/glowier than the rectangle brackets — closer to the
    // reference: a fat, colorful, glowing squiggle rather than a thin guide line. Fully opaque
    // and wide, since "brighter" was the explicit ask.
    private val penBloomPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 16.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        alpha = 255
    }
    private val penCorePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = density * 3.75f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val penPath = Path()
    private var penGradient: LinearGradient? = null

    private val supportsRenderNodeBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val bloomRenderNode: RenderNode? = if (supportsRenderNodeBlur) RenderNode("selectionBloom") else null

    private var startX = 0f
    private var startY = 0f
    private var currentRect: RectF? = null
    private var isDragging = false

    // A final selection popping in at full size/alpha instantly read as lifeless — this animates
    // a quick grow-and-settle (with a touch of overshoot) whenever a selection is finalized.
    private var revealProgress = 1f
    private var revealAnimator: ValueAnimator? = null

    private fun startRevealAnimation() {
        revealAnimator?.cancel()
        revealProgress = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(2.2f)
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setScreenshot(bitmap: Bitmap) {
        screenshot = bitmap
        dimmedScreenshot = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawBitmap(bitmap, 0f, 0f, null)
            c.drawColor(Color.argb(140, 0, 0, 0))
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            penGradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), accentColor, accentColor2, Shader.TileMode.CLAMP)
        }
    }

    val selection: RectF? get() = currentRect

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                startX = event.x
                startY = event.y
                currentRect = RectF(startX, startY, startX, startY)
                if (mode == SelectionMode.PEN) {
                    penPath.reset()
                    penPath.moveTo(startX, startY)
                }
                // Hide the action panel the instant a new drag starts, not just while none is
                // selected yet — otherwise it keeps covering (and eating touches over) the
                // bottom of the screen while the user tries to draw a new box there.
                onSelectionChanged(null)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == SelectionMode.PEN) {
                    // While drawing, only the glowing stroke itself shows — no box preview yet.
                    penPath.lineTo(event.x, event.y)
                    currentRect?.union(event.x, event.y)
                } else {
                    currentRect = RectF(
                        minOf(startX, event.x), minOf(startY, event.y),
                        maxOf(startX, event.x), maxOf(startY, event.y),
                    )
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                // The pen stroke itself is done its job (defining the bounding box) — from here
                // on a pen-made selection renders exactly like a rectangle-made one.
                penPath.reset()
                val rect = currentRect
                if (rect != null && rect.width() > 24 && rect.height() > 24) {
                    onSelectionChanged(rect)
                    startRevealAnimation()
                } else {
                    currentRect = null
                    onSelectionChanged(null)
                }
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val dimmed = dimmedScreenshot ?: return
        destRect.set(0, 0, width, height)
        canvas.drawBitmap(dimmed, null, destRect, null)

        val rect = currentRect
        val bmp = screenshot
        // While actively drawing in pen mode, only the stroke itself shows (no box preview yet)
        // — the box appears once the stroke's bounding box is finalized on release, at which
        // point it renders identically to a rectangle-drag selection.
        if (rect != null && bmp != null && !(isDragging && mode == SelectionMode.PEN)) {
            val revealAlpha = (revealProgress.coerceIn(0f, 1f) * 255).toInt()

            // Rounded clip, matching the brackets' own corner radius — a rectClip here left the
            // sharp original corner peeking out past the rounded bracket curve.
            val clipR = minOf(cornerRadiusPx, rect.width() / 2, rect.height() / 2)
            clipPath.reset()
            clipPath.addRoundRect(rect, clipR, clipR, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            revealPaint.alpha = revealAlpha
            canvas.drawBitmap(bmp, null, destRect, revealPaint)
            canvas.restore()

            // Bracket arms grow in (with a slight overshoot) rather than snapping to full size.
            buildBracketsPath(rect, lenScale = revealProgress.coerceAtLeast(0f))
            bloomPaint.alpha = revealAlpha
            corePaint.alpha = revealAlpha
            drawGlow(canvas, cornerPath, bloomPaint, corePaint, density * 10f)
        }

        if (isDragging && mode == SelectionMode.PEN && !penPath.isEmpty) {
            penBloomPaint.shader = penGradient
            penCorePaint.shader = penGradient
            drawGlow(canvas, penPath, penBloomPaint, penCorePaint, density * 12f)
        }
    }

    /** Builds four independent rounded L-shaped brackets (real curved corners) into [cornerPath].
     * [lenScale] shrinks the arm length toward 0 for the reveal-in animation (can briefly exceed
     * 1 during the overshoot, which reads as a little pop). */
    private fun buildBracketsPath(rect: RectF, lenScale: Float = 1f) {
        val len = minOf(cornerLengthPx * lenScale, rect.width() / 2, rect.height() / 2)
        val r = minOf(cornerRadiusPx, len / 2)
        cornerPath.reset()

        fun bracket(cornerX: Float, cornerY: Float, dx: Int, dy: Int) {
            cornerPath.moveTo(cornerX, cornerY + len * dy)
            cornerPath.lineTo(cornerX, cornerY + r * dy)
            cornerPath.quadTo(cornerX, cornerY, cornerX + r * dx, cornerY)
            cornerPath.lineTo(cornerX + len * dx, cornerY)
        }

        bracket(rect.left, rect.top, dx = 1, dy = 1)
        bracket(rect.right, rect.top, dx = -1, dy = 1)
        bracket(rect.left, rect.bottom, dx = 1, dy = -1)
        bracket(rect.right, rect.bottom, dx = -1, dy = -1)
    }

    /** Real Gaussian blur bloom (RenderNode) behind a crisp bright core — shared by both the
     * rectangle brackets and the pen stroke. */
    private fun drawGlow(canvas: Canvas, path: Path, bloom: Paint, core: Paint, blurRadiusPx: Float) {
        val node = bloomRenderNode
        if (supportsRenderNodeBlur && node != null && canvas.isHardwareAccelerated) {
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording()
            recording.drawPath(path, bloom)
            node.endRecording()
            node.setRenderEffect(RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP))
            canvas.drawRenderNode(node)
        } else {
            bloom.setShadowLayer(blurRadiusPx, 0f, 0f, accentColor)
            canvas.drawPath(path, bloom)
        }
        canvas.drawPath(path, core)
    }

    /** Crops [screenshot] to [selection] mapped from view coordinates to bitmap coordinates. */
    fun cropSelection(): Bitmap? {
        val bmp = screenshot ?: return null
        val rect = currentRect ?: return null
        val scaleX = bmp.width / width.toFloat()
        val scaleY = bmp.height / height.toFloat()
        val left = (rect.left * scaleX).toInt().coerceIn(0, bmp.width - 1)
        val top = (rect.top * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val right = (rect.right * scaleX).toInt().coerceIn(left + 1, bmp.width)
        val bottom = (rect.bottom * scaleY).toInt().coerceIn(top + 1, bmp.height)
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }

    fun clearSelection() {
        revealAnimator?.cancel()
        currentRect = null
        penPath.reset()
        invalidate()
        onSelectionChanged(null)
    }
}
