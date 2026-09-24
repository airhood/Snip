package com.snip.app.trigger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.snip.app.SnipApplication
import com.snip.app.capture.PendingCapture
import com.snip.app.capture.RegionSelectActivity
import com.snip.app.settings.ActivationMode
import com.snip.app.settings.EdgePosition
import com.snip.app.settings.EdgeSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SnipAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main)

    private var stripView: EdgeStripView? = null
    private var stripSide: EdgeSide? = null
    private var stripPosition: EdgePosition? = null

    private val windowManager: WindowManager by lazy { getSystemService(WindowManager::class.java) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as SnipApplication
        scope.launch {
            app.settingsRepository.settings.collect { settings ->
                if (settings.activationMode == ActivationMode.EDGE_SWIPE) {
                    showStrip(settings.edgeSide, settings.edgePosition)
                } else {
                    hideStrip()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun showStrip(side: EdgeSide, position: EdgePosition) {
        if (stripView != null && stripSide == side && stripPosition == position) return
        hideStrip()
        stripSide = side
        stripPosition = position

        val view = EdgeStripView(this, fromLeft = side == EdgeSide.LEFT)
        view.onTriggered = { captureAndOpen() }
        stripView = view

        // Deliberately short (not MATCH_PARENT): this strip both eats touches and excludes the
        // system back-gesture over its own bounds (see EdgeStripView). A full-height strip would
        // kill edge-swipe-back everywhere, in every app, for as long as Snip is running.
        val density = resources.displayMetrics.density
        val widthPx = (density * 14).toInt()
        val heightPx = (density * 160).toInt()
        // Top/bottom placements sit flush against the true edge otherwise, which overlaps the
        // status bar / gesture-nav bar area on most devices.
        val edgeMarginPx = (density * 32).toInt()
        val verticalGravity = when (position) {
            EdgePosition.TOP -> android.view.Gravity.TOP
            EdgePosition.CENTER -> android.view.Gravity.CENTER_VERTICAL
            EdgePosition.BOTTOM -> android.view.Gravity.BOTTOM
        }
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = verticalGravity or if (side == EdgeSide.LEFT) android.view.Gravity.START else android.view.Gravity.END
            if (position != EdgePosition.CENTER) y = edgeMarginPx
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e("Snip", "failed to add edge strip", it) }
    }

    private fun hideStrip() {
        stripView?.let { runCatching { windowManager.removeView(it) } }
        stripView = null
        stripSide = null
        stripPosition = null
    }

    private fun captureAndOpen() {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) {
                    Log.e("Snip", "failed to wrap screenshot buffer", t)
                    null
                } finally {
                    result.hardwareBuffer.close()
                }
                if (bitmap == null) return
                PendingCapture.fullScreenshot = bitmap
                startActivity(
                    Intent(this@SnipAccessibilityService, RegionSelectActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            override fun onFailure(errorCode: Int) {
                Log.e("Snip", "takeScreenshot failed: $errorCode")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        hideStrip()
        serviceJob.cancel()
    }
}
