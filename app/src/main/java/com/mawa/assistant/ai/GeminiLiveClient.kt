package com.mawa.assistant.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLive"
        private const val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val SESSION_RENEW_AFTER = 540_000L
        private const val KEEPALIVE_INTERVAL = 8_000L
        private const val RECONNECT_DELAY = 3_000L
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var keepaliveJob: Job? = null
    private var sessionRenewJob: Job? = null

    private var apiKey = ""
    private var model = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private var systemPrompt = ""
    private var voiceName = "Aoede"
    private var isConnected = false
    private var shouldReconnect = true

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun configure(apiKey: String, model: String, systemPrompt: String, voiceName: String) {
        this.apiKey = apiKey
        this.model = model
        this.systemPrompt = systemPrompt
        this.voiceName = voiceName
    }

    fun connect() {
        if (apiKey.isEmpty()) {
            onError?.invoke("API key not set")
            return
        }
        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        val url = "$BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                sendSetupMessage()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                onDisconnected?.invoke()
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason")
                isConnected = false
                onDisconnected?.invoke()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                onDisconnected?.invoke()
                scheduleReconnect()
            }
        })
    }

    private fun sendSetupMessage() {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply { put("AUDIO") })
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                    put("temperature", 0.9)
                })
                put("output_audio_transcription", JSONObject())
                put("input_audio_transcription", JSONObject())
            })
        }
        webSocket?.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                Log.d(TAG, "Setup complete")
                isConnected = true
                startKeepalive()
                startSessionRenewal()
                onConnected?.invoke()
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val audioData = inlineData.optString("data", "")
                            if (audioData.isNotEmpty()) {
                                val pcmBytes = Base64.decode(audioData, Base64.DEFAULT)
                                onAudioReceived?.invoke(pcmBytes)
                            }
                        }
                    }
                }
            }

            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            if (outputTranscription != null) {
                val transcriptText = outputTranscription.optString("text", "")
                if (transcriptText.isNotEmpty()) {
                    onOutputTranscript?.invoke(transcriptText)
                }
            }

            val inputTranscription = serverContent.optJSONObject("inputTranscription")
            if (inputTranscription != null) {
                val transcriptText = inputTranscription.optString("text", "")
                if (transcriptText.isNotEmpty()) {
                    onInputTranscript?.invoke(transcriptText)
                }
            }

            val turnComplete = serverContent.optBoolean("turnComplete", false)
            if (turnComplete) {
                onTurnComplete?.invoke()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected) return
        val base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", base64)
                    })
                })
            })
        }
        webSocket?.send(msg.toString())
    }

    fun sendText(message: String) {
        if (!isConnected) return
        val msg = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", message) })
                        })
                    })
                })
                put("turn_complete", true)
            })
        }
        webSocket?.send(msg.toString())
    }

    fun sendInterrupt() {
        if (!isConnected) return
        val msg = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turns", JSONArray())
                put("turn_complete", true)
            })
        }
        webSocket?.send(msg.toString())
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && isConnected) {
                delay(KEEPALIVE_INTERVAL)
                val silentChunk = ByteArray(1024)
                sendAudio(silentChunk)
            }
        }
    }

    private fun startSessionRenewal() {
        sessionRenewJob?.cancel()
        sessionRenewJob = scope.launch {
            delay(SESSION_RENEW_AFTER)
            if (isActive && shouldReconnect) {
                Log.d(TAG, "Session renewal - reconnecting")
                reconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        scope.launch {
            delay(RECONNECT_DELAY)
            if (shouldReconnect) {
                Log.d(TAG, "Reconnecting...")
                doConnect()
            }
        }
    }

    fun reconnect() {
        keepaliveJob?.cancel()
        sessionRenewJob?.cancel()
        webSocket?.close(1000, "Reconnecting")
        scope.launch {
            delay(500)
            doConnect()
        }
    }

    fun disconnect() {
        shouldReconnect = false
        isConnected = false
        keepaliveJob?.cancel()
        sessionRenewJob?.cancel()
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    fun isConnected(): Boolean = isConnected
}
