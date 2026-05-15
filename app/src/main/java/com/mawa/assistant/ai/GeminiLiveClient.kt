package com.mawa.assistant.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(private val context: Context) {

    private val TAG = "MAWA_LIVE"
    private var isSetupComplete = false
    private var webSocket: WebSocket? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // Connection জ্যান্ত রাখার জন্য
        .build()

    private var apiKey = ""
    private var model = "models/gemini-2.0-flash-exp" // एकदम লেটেস্ট মডেল
    private var systemPrompt = ""
    private var voiceName = "Aoede"

    // MAWA-র জন্য প্রয়োজনীয় কলব্যাক (যাতে অ্যাপ ক্র্যাশ না করে)
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun configure(apiKey: String, model: String, systemPrompt: String, voiceName: String) {
        this.apiKey = apiKey
        this.model = "models/gemini-2.0-flash-exp" // জোর করে সঠিক মডেল ব্যবহার করা হচ্ছে
        this.systemPrompt = systemPrompt
        this.voiceName = voiceName
    }

    fun connect() {
        if (apiKey.isEmpty()) {
            onError?.invoke("API Key empty")
            return
        }
        isSetupComplete = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Origin", "https://generativelanguage.googleapis.com")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected ✅")
                sendSetup()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleResponse(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleResponse(bytes.utf8())
                } catch (e: Exception) {
                    Log.e(TAG, "Binary Error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSetupComplete = false
                onDisconnected?.invoke()
                onError?.invoke("Connection Failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isSetupComplete = false
                onDisconnected?.invoke()
            }
        })
    }

    private fun sendSetup() {
        try {
            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", model)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceName)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                })
            }
            webSocket?.send(setupJson.toString())
            Log.d(TAG, "Setup Sent ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Setup Error: ${e.message}")
        }
    }

    fun sendText(text: String) {
        if (!isSetupComplete) return
        try {
            val msg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                    put("turnComplete", true)
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Send Error: ${e.message}")
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isSetupComplete) return
        try {
            val base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val msg = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64)
                        })
                    })
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Audio Send Error: ${e.message}")
        }
    }

    fun sendInterrupt() {
        if (!isSetupComplete) return
        try {
            val msg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray())
                    put("turnComplete", true)
                })
            }
            webSocket?.send(msg.toString())
        } catch (e: Exception) {}
    }

    private fun handleResponse(jsonString: String) {
        try {
            val json = JSONObject(jsonString)

            if (json.has("setupComplete")) {
                isSetupComplete = true
                Log.d(TAG, "GEMINI READY ✅")
                onConnected?.invoke()
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts") ?: return
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)

                    if (part.has("inlineData")) {
                        val base64Data = part.getJSONObject("inlineData").getString("data")
                        onAudioReceived?.invoke(Base64.decode(base64Data, Base64.DEFAULT))
                    }

                    if (part.has("text")) {
                        onOutputTranscript?.invoke(part.getString("text"))
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                onTurnComplete?.invoke()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse Error: ${e.message}")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Bye")
        isSetupComplete = false
        onDisconnected?.invoke()
    }
    
    fun release() {
        disconnect()
    }

    fun isConnected(): Boolean = isSetupComplete
}
