package com.snip.app.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

internal val sseHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
}

internal val jsonMediaType = "application/json".toMediaType()

/** Opens a POST SSE stream, calling [onEvent] for every `data:` payload (raw JSON string). */
internal fun streamSse(
    url: String,
    headers: Map<String, String>,
    bodyJson: String,
    onEvent: (data: String) -> Unit,
    onFailure: (Throwable, Response?) -> Unit,
    onClosed: () -> Unit,
) {
    val requestBuilder = Request.Builder().url(url)
    headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
    requestBuilder.post(bodyJson.toRequestBody(jsonMediaType))

    val factory = EventSources.createFactory(sseHttpClient)
    factory.newEventSource(requestBuilder.build(), object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            onEvent(data)
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            onFailure(t ?: IllegalStateException("SSE failure"), response)
        }

        override fun onClosed(eventSource: EventSource) {
            onClosed()
        }
    })
}
