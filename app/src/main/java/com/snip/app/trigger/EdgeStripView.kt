package com.snip.app.trigger

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View

/**
 * A thin, near-invisible strip anchored to a screen edge. A horizontal drag starting near the
 * edge and moving inward past [triggerDistancePx] fires [onTriggered].
 *
 * Gesture-nav devices reserve the very edge for the system back gesture, which would otherwise
 * swallow our touches before [onTouchEvent] ever sees them. [setSystemGestureExclusionRects]
 * tells the system to yield that specific strip of screen to us instead.
 */
class EdgeStripView(context: Context, private val fromLeft: Boolean) : View(context) {

    var onTriggered: () -> Unit = {}

    private val triggerDistancePx = resources.displayMetrics.density * 56
    private var startX = 0f
    private var consumed = false

    init {
        setBackgroundColor(Color.argb(18, 255, 255, 255))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w > 0 && h > 0) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                consumed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!consumed) {
                    val delta = if (fromLeft) event.x - startX else startX - event.x
                    if (delta > triggerDistancePx) {
                        consumed = true
                        onTriggered()
                    }
                }
            }
        }
        return true
    }
}
