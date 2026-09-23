package com.snip.app.assistant

import android.service.voice.VoiceInteractionService

/**
 * Marker service that lets the user set Snip as the system's default Assistant app
 * (Settings > Apps > Default apps > Digital assistant app). Once granted, the OS routes the
 * standard assist gesture (bottom-corner swipe on gesture nav, long-press home on 3-button nav)
 * to [SnipVoiceInteractionSessionService] instead of showing a floating trigger of our own.
 */
class SnipVoiceInteractionService : VoiceInteractionService()
