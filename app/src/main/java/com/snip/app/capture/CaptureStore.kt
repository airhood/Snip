package com.snip.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import android.util.Base64

object CaptureStore {

    private fun capturesDir(context: Context): File =
        File(context.cacheDir, "captures").apply { mkdirs() }

    /** Saves the crop as a PNG in cache and returns a sharable content:// URI. */
    fun saveAndGetUri(context: Context, bitmap: Bitmap): Uri {
        val file = File(capturesDir(context), "${UUID.randomUUID()}.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Saves the crop as a PNG in cache and returns the raw file path (for Room storage). */
    fun saveAndGetPath(context: Context, bitmap: Bitmap): String {
        val file = File(capturesDir(context), "${UUID.randomUUID()}.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }

    fun base64Png(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun base64PngFromPath(path: String): String? {
        val file = File(path)
        if (!file.exists()) return null
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }
}
