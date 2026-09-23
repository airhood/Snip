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

    /** Freehand markup (notes/highlights) baked into the final image, independent of region
     * selection — drawn in a solid user-chosen color rather than the selection tools' glow. */
    var drawModeEnabled: Boolean = false
    var annotationColor: Int = Color.parseColor("#6FA8F0")
        set(value) {
            field = value
            annotationPaint.color = value
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

    // Annotation layer: same pixel dimensions as [screenshot], drawn incrementally (persisted
    // strokes, not a replayed Path) and composited both on-screen and into the final crop.
    private var annotationBitmap: Bitmap? = null
    private var annotationCanvas: Canvas? = null
    private val annotationPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        strokeWidth = resources.displayMetrics.density * 3.75f // matches the selection pen's core stroke
        color = Color.parseColor("#6FA8F0")
    }
    private var lastAnnX = 0f
    private var lastAnnY = 0f

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
        val annotation = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        annotationBitmap = annotation
        annotationCanvas = Canvas(annotation)
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
        if (drawModeEnabled) return handleDrawTouch(event)
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
                // A new selection is a fresh start — any markup drawn for the previous one
                // no longer applies to whatever region ends up chosen this time.
                annotationCanvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
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

    private fun toBitmapX(viewX: Float): Float {
        val bmp = screenshot ?: return viewX
        return viewX * (bmp.width / width.toFloat())
    }

    private fun toBitmapY(viewY: Float): Float {
        val bmp = screenshot ?: return viewY
        return viewY * (bmp.height / height.toFloat())
    }

    private fun handleDrawTouch(event: MotionEvent): Boolean {
        val annCanvas = annotationCanvas ?: return true
        val bx = toBitmapX(event.x)
        val by = toBitmapY(event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastAnnX = bx
                lastAnnY = by
                // A dot for a tap-and-release, not nothing.
                annCanvas.drawCircle(bx, by, annotationPaint.strokeWidth / 2f, annotationPaint)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                annCanvas.drawLine(lastAnnX, lastAnnY, bx, by, annotationPaint)
                lastAnnX = bx
                lastAnnY = by
                invalidate()
            }
        }
        return true
    }

    /** Clears any drawn annotations (e.g. when starting over on a fresh capture). */
    fun clearAnnotations() {
        annotationCanvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        invalidate()
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

            // Pen-mode only: the box starts a bit smaller than its target size and grows into
            // it, rather than snapping straight to full size like a rectangle drag does.
            val drawnRect = if (mode == SelectionMode.PEN) scaledRect(rect, revealProgress) else rect

            // Rounded clip, matching the brackets' own corner radius — a rectClip here left the
            // sharp original corner peeking out past the rounded bracket curve.
            val clipR = minOf(cornerRadiusPx, drawnRect.width() / 2, drawnRect.height() / 2)
            clipPath.reset()
            clipPath.addRoundRect(drawnRect, clipR, clipR, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            revealPaint.alpha = revealAlpha
            canvas.drawBitmap(bmp, null, destRect, revealPaint)
            canvas.restore()

            // Bracket arms grow in (with a slight overshoot) rather than snapping to full size.
            buildBracketsPath(drawnRect, lenScale = revealProgress.coerceAtLeast(0f))
            bloomPaint.alpha = revealAlpha
            corePaint.alpha = revealAlpha
            drawGlow(canvas, cornerPath, bloomPaint, corePaint, density * 10f)
        }

        if (isDragging && mode == SelectionMode.PEN && !penPath.isEmpty) {
            penBloomPaint.shader = penGradient
            penCorePaint.shader = penGradient
            drawGlow(canvas, penPath, penBloomPaint, penCorePaint, density * 12f)
        }

        // Annotations always show, dimmed area or not — they're markup on the capture itself,
        // not part of the selection UI.
        annotationBitmap?.let { canvas.drawBitmap(it, null, destRect, null) }
    }

    /** Shrinks [rect] toward its own center by up to [minScale] at progress 0, growing to full
     * size at progress 1 (can briefly overshoot past 1 for a little pop, since revealProgress
     * itself overshoots). Pen-only — a rectangle drag already "grows" naturally as you drag it. */
    private fun scaledRect(rect: RectF, progress: Float, minScale: Float = 0.82f): RectF {
        val scale = minScale + (1f - minScale) * progress
        val cx = rect.centerX()
        val cy = rect.centerY()
        val hw = rect.width() / 2f * scale
        val hh = rect.height() / 2f * scale
        return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
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

    /** Crops [screenshot] to [selection] mapped from view coordinates to bitmap coordinates,
     * with any annotation strokes over that region baked in — what gets sent to the AI is
     * exactly what's on screen, markup included. */
    fun cropSelection(): Bitmap? {
        val bmp = screenshot ?: return null
        val rect = currentRect ?: return null
        val scaleX = bmp.width / width.toFloat()
        val scaleY = bmp.height / height.toFloat()
        val left = (rect.left * scaleX).toInt().coerceIn(0, bmp.width - 1)
        val top = (rect.top * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val right = (rect.right * scaleX).toInt().coerceIn(left + 1, bmp.width)
        val bottom = (rect.bottom * scaleY).toInt().coerceIn(top + 1, bmp.height)
        val cropW = right - left
        val cropH = bottom - top
        val srcRect = Rect(left, top, right, bottom)
        val dstRect = Rect(0, 0, cropW, cropH)

        val result = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        c.drawBitmap(bmp, srcRect, dstRect, null)
        annotationBitmap?.let { c.drawBitmap(it, srcRect, dstRect, null) }
        return result
    }

    fun clearSelection() {
        revealAnimator?.cancel()
        currentRect = null
        penPath.reset()
        invalidate()
        onSelectionChanged(null)
    }
}
