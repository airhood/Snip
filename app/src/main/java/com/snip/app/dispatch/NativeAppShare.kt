package com.snip.app.dispatch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

enum class NativeTarget(val packageName: String, val label: String) {
    CLAUDE("com.anthropic.claude", "Claude"),
    CHATGPT("com.openai.chatgpt", "ChatGPT"),
    GEMINI("com.google.android.apps.bard", "Gemini"),
}

object NativeAppShare {

    fun installedTargets(context: Context): List<NativeTarget> =
        NativeTarget.entries.filter { isInstalled(context, it.packageName) }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Shares [imageUri] to [target] via ACTION_SEND. [prompt] is attached as EXTRA_TEXT on a
     * best-effort basis — whether the target app actually surfaces it depends on that app's own
     * share handler (Gemini's share target ignores text entirely as of 2026).
     */
    fun share(context: Context, target: NativeTarget, imageUri: Uri, prompt: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            if (prompt.isNotBlank()) putExtra(Intent.EXTRA_TEXT, prompt)
            setPackage(target.packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
