package com.snip.app.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.service.voice.VoiceInteractionSession
import androidx.annotation.RequiresApi
import com.snip.app.capture.PendingCapture
import com.snip.app.capture.RegionSelectActivity

/**
 * Assist-gesture entry point. When the OS routes the system assist gesture to us (because Snip
 * is set as the default assistant app), it hands us a screenshot of whatever was on screen via
 * [onHandleScreenshot] (API 34+). We forward that straight into [RegionSelectActivity] and end
 * the session immediately — we never show our own assistant UI.
 */
class SnipVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) {
            PendingCapture.fullScreenshot = screenshot
            startAssistantActivity(
                Intent(context, RegionSelectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        hide()
    }

    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Screenshot handoff isn't available below Android 14; nothing we can do here.
            hide()
        }
    }
}
