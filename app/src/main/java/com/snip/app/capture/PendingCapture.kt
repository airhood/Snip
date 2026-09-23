package com.snip.app.capture

import android.graphics.Bitmap

/**
 * In-memory handoff of the raw full-screen screenshot from whichever trigger fired
 * (accessibility service or assistant session) to [RegionSelectActivity]. Bitmaps are
 * too large to pass through Intent extras reliably, and both triggers run in this
 * process, so a single-slot holder is simplest.
 */
object PendingCapture {
    @Volatile
    var fullScreenshot: Bitmap? = null

    fun clear() {
        fullScreenshot = null
    }
}
