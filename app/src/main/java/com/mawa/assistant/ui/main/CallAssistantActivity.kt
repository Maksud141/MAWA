package com.mawa.assistant.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mawa.assistant.R
import com.mawa.assistant.ai.GeminiLiveClient
import com.mawa.assistant.service.CallMonitorService
import com.mawa.assistant.utils.LiveAudioManager
import java.util.Locale

class CallAssistantActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var callerNameText: TextView
    private lateinit var statusText: TextView
    private var waveformView: WaveformView? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var callerName = "Unknown Caller"
    private var phoneNumber = ""
    private var userName = "Boss"
    private var personality = "gf"
    private var isWhatsAppCall = false

    // State management
    private var isDecisionMade = false
    private var isListening = false
    private var announcementPlayed = false
    private var isSpeaking = false
    private var isCallAnswered = false

    private lateinit var liveClient: GeminiLiveClient
    private lateinit var liveAudioManager: LiveAudioManager
    private var isLiveConnected = false

    private val TAG = "MAWA_CALL_UI"

    // Broadcast receiver: call cut hone par finish
    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CallMonitorService.ACTION_CALL_ENDED -> {
                    Log.d(TAG, "Call ended → closing assistant UI")
                    if (!isDecisionMade) {
                        safeFinish()
                    }
                }
                CallMonitorService.ACTION_CALL_ACTIVE -> {
                    Log.d(TAG, "Call active → closing assistant UI")
                    isCallAnswered = true
                    safeFinish()
                }
                CallMonitorService.ACTION_CALL_RINGING -> {
                    Log.d(TAG, "Call ringing received")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock screen e dekhate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_call_assistant)

        // Intent theke data neya
        callerName  = intent.getStringExtra("CALLER_NAME") ?: "Unknown Caller"
        phoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: ""
        userName    = intent.getStringExtra("USER_NAME") ?: getPrefsValue("user_name", "Boss")
        personality = intent.getStringExtra("PERSONALITY") ?: getPrefsValue("personality_mode", "gf")
        isWhatsAppCall = intent.getBooleanExtra("IS_WHATSAPP_CALL", false)

        Log.d(TAG, "CallAssistant started: caller=$callerName, whatsapp=$isWhatsAppCall, personality=$personality")

        initViews()

        liveAudioManager = LiveAudioManager(this)
        setupGeminiLive()
        tts = TextToSpeech(this, this)

        val filter = IntentFilter().apply {
            addAction(CallMonitorService.ACTION_CALL_ENDED)
            addAction(CallMonitorService.ACTION_CALL_ACTIVE)
            addAction(CallMonitorService.ACTION_CALL_RINGING)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(callStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(callStateReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receiver register failed: ${e.message}")
        }

        startCallStateMonitor()
    }

    private fun initViews() {
        callerNameText = findViewById(R.id.callerNameText)
        statusText     = findViewById(R.id.callStatusText)
        waveformView   = findViewById<WaveformView?>(R.id.callWaveform)

        callerNameText.text = callerName
        statusText.text = "MAWA preparing..."
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.ENGLISH
            }
            isTtsReady = true
        } else {
            Log.e(TAG, "TTS init failed")
        }
    }

    private fun startAnnouncement() {
        if (isDecisionMade || announcementPlayed) {
            if (!isDecisionMade) startListening()
            return
        }

        announcementPlayed = true
        val msg = buildAnnouncementText()

        statusText.text = "Announcing..."
        Log.d(TAG, "Announcing via WebSocket: $msg")

        speakViaWebSocket(msg)

        val estimatedDuration = (msg.length * 80L).coerceIn(3000L, 8000L)
        handler.postDelayed({
            if (!isDecisionMade && !isSpeaking) startListening()
        }, estimatedDuration)
    }

    private fun buildAnnouncementText(): String {
        val isKnownContact = !isNumberLike(callerName)

        return if (isWhatsAppCall) {
            when (personality) {
                "gf" -> when {
                    isKnownContact ->
                        "$userName, $callerName er WhatsApp call asche. Uthabo naki kete dibo?"
                    else ->
                        "$userName, ekta anjaan number theke WhatsApp call asche. Uthabo naki kete dibo?"
                }
                "professional" -> when {
                    isKnownContact ->
                        "$userName, incoming WhatsApp call from $callerName. Should I answer or decline?"
                    else ->
                        "$userName, unknown WhatsApp caller. Should I answer or decline?"
                }
                else ->
                    "$userName, $callerName er WhatsApp call asche. Ki korbo?"
            }
        } else {
            when (personality) {
                "gf" -> when {
                    isKnownContact ->
                        "$userName, $callerName er call asche. Uthabo naki kete dibo?"
                    else ->
                        "$userName, ekta anjaan number theke call asche. Uthabo naki kete dibo?"
                }
                "professional" -> when {
                    isKnownContact ->
                        "$userName, incoming call from $callerName. Should I answer or decline?"
                    else ->
                        "$userName, unknown caller. Should I answer or decline?"
                }
                else ->
                    "$userName, $callerName er call asche. Ki korbo?"
            }
        }
    }

    private fun setupGeminiLive() {
        val prefs = getSharedPreferences("mawa_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Log.e(TAG, "No API key found")
            return
        }

        val callType = if (isWhatsAppCall) "WhatsApp call" else "phone call"

        val prompt = """
            You are MAWA, a caring AI assistant for $userName. Personality: $personality.

            CURRENT SITUATION: Incoming $callType from $callerName.

            TASK: Announce the incoming call naturally in Banglish (Bangla + English mix).
            Speak warmly and clearly. Keep it short (2-3 sentences max).

            AFTER ANNOUNCEMENT: Listen for user command and respond.

            LANGUAGE: Always respond in Banglish regardless of user language.
            VOICE: Natural, warm, human-like tone.
        """.trimIndent()

        liveClient = GeminiLiveClient(this)
        liveClient.configure(apiKey, "models/gemini-2.0-flash-exp", prompt, "Aoede")
        
        liveClient.onAudioReceived = { data ->
            isSpeaking = true
            liveAudioManager.playChunk(data)
            runOnUiThread {
                statusText.text = "Speaking... 💬"
                waveformView?.startAnimation()
            }
        }
        
        liveClient.onConnected = {
            isLiveConnected = true
            Log.d(TAG, "Gemini Live Connected ✅")
            handler.postDelayed({ startAnnouncement() }, 500)
        }
        
        liveClient.onTurnComplete = {
            isSpeaking = false
            runOnUiThread {
                waveformView?.stopAnimation()
                if (!isDecisionMade) startListening()
            }
        }
        
        liveClient.onError = { msg ->
            isLiveConnected = false
            Log.e(TAG, "Gemini Error: $msg")
            handler.postDelayed({
                if (!announcementPlayed) startAnnouncement()
            }, 500)
        }
        
        liveClient.connect()
    }

    private fun speakViaWebSocket(text: String) {
        if (isLiveConnected) {
            isSpeaking = true
            liveClient.sendText(text)
            Log.d(TAG, "Speaking via WebSocket: $text")
            return
        }
        speakTTS(text)
    }

    private fun speakTTS(text: String) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready for: $text")
            return
        }
        isSpeaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CALL_${System.currentTimeMillis()}")
        Log.d(TAG, "TTS fallback: $text")

        val duration = (text.length * 80L).coerceIn(1000L, 5000L)
        handler.postDelayed({ isSpeaking = false }, duration)
    }

    private fun startListening() {
        if (isDecisionMade || isListening || isSpeaking) return
        isListening = true

        statusText.text = "Sunteci... (bolo: Uthao / Reject)"
        Log.d(TAG, "Starting voice recognition")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            handler.postDelayed({ repeatAnnouncement() }, 1000)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                isListening = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = texts?.firstOrNull()?.lowercase()?.trim() ?: ""
                Log.d(TAG, "User said: '$spoken'")

                if (spoken.isEmpty()) {
                    if (!isDecisionMade) repeatAnnouncement()
                    return
                }
                processCommand(spoken)
            }

            override fun onError(errorCode: Int) {
                isListening = false
                val errorMsg = when (errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "TIMEOUT"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                    else -> "ERROR_$errorCode"
                }
                Log.w(TAG, "Speech error: $errorMsg")

                if (!isDecisionMade) {
                    handler.postDelayed({ if (!isDecisionMade) startListening() }, 1500)
                }
            }

            override fun onReadyForSpeech(p0: Bundle?) {
                statusText.text = "Bolo... 🎙️"
            }
            override fun onBeginningOfSpeech() {
                statusText.text = "Sunteci... 👂"
                waveformView?.startAnimation()
            }
            override fun onRmsChanged(rmsdB: Float) {
                waveformView?.setAmplitude(rmsdB)
            }
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Processing..."
                waveformView?.stopAnimation()
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("bn-BD", "en-US", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processCommand(spoken: String) {
        val lower = spoken.lowercase().trim()
        Log.d(TAG, "Processing command: '$lower'")

        val answerWords = listOf(
            "utha", "uthao", "uthau", "answer", "pick", "receive", "accept",
            "haa", "ha", "yes", "thik", "thik ache", "ok", "okay",
            "phone uthao", "uthao phone", "receive koro", "dhoro",
            "kotha bolbo", "hello", "halo", "bolo", "kotha bolo"
        )

        val rejectWords = listOf(
            "reject", "decline", "cut", "kat", "kete", "na", "no",
            "bad dao", "kete dao", "drop", "dismiss", "uthabo na",
            "busy", "pore", "later", "ruk", "darao", "cancel"
        )

        val hasAnswer = answerWords.any { lower.contains(it) }
        val hasReject = rejectWords.any { lower.contains(it) }

        val answerIndex = answerWords.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
        val rejectIndex = rejectWords.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE

        val isAnswer = hasAnswer && (!hasReject || answerIndex < rejectIndex)
        val isReject = hasReject && (!hasAnswer || rejectIndex < answerIndex)

        Log.d(TAG, "Decision: isAnswer=$isAnswer, isReject=$isReject, answerPos=$answerIndex, rejectPos=$rejectIndex")

        when {
            isAnswer && !isReject -> performAnswer()
            isReject -> performReject()
            else -> {
                val confusion = when (personality) {
                    "gf"  -> "Bujhte parini. 'Uthao' naki 'Kete dao' bolo."
                    else  -> "Please say 'Answer' or 'Reject'."
                }
                speakViaWebSocket(confusion)
                handler.postDelayed({ if (!isDecisionMade) repeatAnnouncement() }, 4000)
            }
        }
    }

    private fun performAnswer() {
        if (isDecisionMade) return
        isDecisionMade = true
        isCallAnswered = true
        stopListening()

        val confirmMsg = when (personality) {
            "gf"  -> "Haa $userName, call uthacchi! 📞"
            else  -> "Answering the call, $userName."
        }
        speakViaWebSocket(confirmMsg)
        statusText.text = "Answering... 📞"
        Log.d(TAG, "=== ANSWERING CALL ===")

        handler.postDelayed({
            var success = false

            if (isWhatsAppCall) {
                success = answerWhatsAppCall()
            } else {
                success = answerNormalCall()
            }

            if (!success) {
                speakViaWebSocket("Sorry $userName, call uthate parini.")
            }

            handler.postDelayed({ safeFinish() }, 2000)
        }, 1500)
    }

    private fun answerNormalCall(): Boolean {
        var success = false

        try {
            if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    telecom.acceptRingingCall()
                    Log.d(TAG, "✅ Call accepted via TelecomManager.acceptRingingCall()")
                    success = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager answer failed: ${e.message}")
        }

        if (!success) {
            try {
                val answered = com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Answer") || 
                               com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Answer") || 
                               com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Accept") || 
                               com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Accept")
                Log.d(TAG, "Accessibility answer: $answered")
                success = answered
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility answer failed: ${e.message}")
            }
        }

        return success
    }

    private fun answerWhatsAppCall(): Boolean {
        var success = false

        try {
            success = com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Answer") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Answer") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Accept") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Accept call") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(id = "com.whatsapp:id/incoming_call_answer") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(id = "com.whatsapp.w4b:id/incoming_call_answer")

            Log.d(TAG, "WhatsApp call answer: $success")
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp answer failed: ${e.message}")
        }

        return success
    }

    private fun performReject() {
        if (isDecisionMade) return
        isDecisionMade = true
        stopListening()

        val confirmMsg = when (personality) {
            "gf"  -> "Thik ache $userName, call kete dilam. ❌"
            else  -> "Call declined, $userName."
        }
        speakViaWebSocket(confirmMsg)
        statusText.text = "Rejecting... ❌"
        Log.d(TAG, "=== REJECTING CALL ===")

        handler.postDelayed({
            var success = false

            if (isWhatsAppCall) {
                success = rejectWhatsAppCall()
            } else {
                success = rejectNormalCall()
            }

            if (!success) {
                speakViaWebSocket("Sorry $userName, call katte parini.")
            }

            handler.postDelayed({ safeFinish() }, 2000)
        }, 1500)
    }

    private fun rejectNormalCall(): Boolean {
        var success = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    @Suppress("DEPRECATION")
                    telecom.endCall()
                    Log.d(TAG, "✅ Call rejected via TelecomManager.endCall()")
                    success = true
                } else {
                    Log.w(TAG, "Permission ANSWER_PHONE_CALLS missing")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager reject failed: ${e.message}")
        }

        if (!success) {
            try {
                val rejected = com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Decline") || 
                               com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Reject") || 
                               com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Decline") || 
                               com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Reject call")
                Log.d(TAG, "Accessibility reject: $rejected")
                success = rejected
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility reject failed: ${e.message}")
            }
        }

        return success
    }

    private fun rejectWhatsAppCall(): Boolean {
        var success = false

        try {
            success = com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Decline") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(contentDesc = "Decline") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(text = "Reject") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(id = "com.whatsapp:id/incoming_call_decline") || 
                      com.mawa.assistant.service.SmartAccessibilityEngine.click(id = "com.whatsapp.w4b:id/incoming_call_decline")

            Log.d(TAG, "WhatsApp call reject: $success")
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp reject failed: ${e.message}")
        }

        return success
    }

    private fun repeatAnnouncement() {
        announcementPlayed = false
        startAnnouncement()
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        waveformView?.stopAnimation()
    }

    private fun startCallStateMonitor() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val monitor = object : Runnable {
            override fun run() {
                if (isDecisionMade || isCallAnswered) return
                if (tm.callState == TelephonyManager.CALL_STATE_IDLE && !isWhatsAppCall) {
                    Log.d(TAG, "Phone idle — closing call assistant")
                    safeFinish()
                    return
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(monitor, 3000)
    }

    private fun safeFinish() {
        if (!isFinishing && !isDestroyed) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            try { speechRecognizer?.destroy() } catch (_: Exception) {}

            isDecisionMade = false
            isListening = false
            announcementPlayed = false
            isSpeaking = false

            finish()
        }
    }

    private fun isNumberLike(str: String) =
        str.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }

    private fun getPrefsValue(key: String, default: String): String {
        return getSharedPreferences("mawa_prefs", Context.MODE_PRIVATE)
            .getString(key, default) ?: default
    }

    override fun onBackPressed() {
        speakViaWebSocket("Age bolo uthabo naki katbo!")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(callStateReceiver) } catch (_: Exception) {}
        tts?.shutdown()
        if (::liveClient.isInitialized) liveClient.release()
        liveAudioManager.stop()
        speechRecognizer?.destroy()

        isDecisionMade = false
        isListening = false
        announcementPlayed = false
        isSpeaking = false
        isCallAnswered = false

        Log.d(TAG, "CallAssistantActivity destroyed")
    }
}
