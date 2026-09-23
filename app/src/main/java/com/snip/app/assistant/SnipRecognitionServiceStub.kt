package com.snip.app.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * The Assistant-role framework requires a RecognitionService to be declared, but Snip never
 * does speech recognition — it only cares about the screenshot handoff in
 * [SnipVoiceInteractionSession]. This stub exists purely to satisfy that manifest requirement.
 */
class SnipRecognitionServiceStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
