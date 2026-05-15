package com.mawa.assistant.ui.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mawa.assistant.R
import com.mawa.assistant.ai.AudioEngine
import com.mawa.assistant.ai.CommandParser
import com.mawa.assistant.ai.GeminiLiveClient
import com.mawa.assistant.security.BiometricManager
import com.mawa.assistant.service.AccessibilityHelperService
import com.mawa.assistant.service.CallMonitorService
import com.mawa.assistant.service.MawaOverlayService
import com.mawa.assistant.utils.ScreenReader
import com.mawa.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var geminiLive: GeminiLiveClient
    private lateinit var audioEngine: AudioEngine
    private lateinit var chatAdapter: ChatAdapter

    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var textInput: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView
    private lateinit var redOverlay: android.view.View
    private lateinit var settingsBtn: ImageButton

    private var tts: TextToSpeech? = null
    private var isMuted = false
    private var isActiveMode = false
    private var isInCallMode = false
    private var inputBuffer = StringBuilder()
    private var outputBuffer = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    companion object {
        private const val TAG = "MAWA_SUPER"
        private val INTERNAL_PATTERNS = listOf(
            "**Confirming", "**Clarifying", "**Processing", "**Analyzing",
            "I'm currently focused", "My immediate next step", "I need to ascertain",
            "I'm confirming", "seems ambiguous", "implicitly request",
            "intended recipient", "more specific contact", "misspelling of a contact",
            "informal way to describe", "chain of thought"
        )
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallMonitorService.ACTION_CALL_ENDED) {
                isInCallMode = false
                setActiveMode(false)
            }
        }
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusBar()
            handler.postDelayed(this, 30_000)
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CAMERA,
        Manifest.permission.USE_BIOMETRIC
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // MYRA's Biometric Lock
        BiometricManager.authenticate(this,
            onSuccess = {
                setupPostAuth()
            },
            onError = { msg: String ->
                Toast.makeText(this, "Auth failed: $msg", Toast.LENGTH_LONG).show()
                setupPostAuth() // Fallback to let user in for now
            },
            onFallback = {
                setupPostAuth()
            }
        )
    }

    private fun setupPostAuth() {
        initViews()
        checkPermissions()
        startSystemServices()
        startStatusUpdates()
        tts = TextToSpeech(this, this)
        checkDefaultAssistant()

        registerReceiver(callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED), RECEIVER_NOT_EXPORTED)

        handler.postDelayed({ initGeminiLive() }, 300)
        handleIncomingCallIntent(intent)

        viewModel.commandResult.observe(this) { result ->
            if (result != null) {
                if (::geminiLive.isInitialized) geminiLive.sendText(result)
                viewModel.clearResult()
            }
        }
    }

    private fun isInternalMessage(text: String) = INTERNAL_PATTERNS.any { text.contains(it, ignoreCase = true) }

    private fun getScreenContext(): String = try {
        if (!AccessibilityHelperService.isEnabled(this)) ""
        else ScreenReader.dump(AccessibilityHelperService.instance?.rootInActiveWindow)?.take(500)?.replace("\n", " ") ?: ""
    } catch (_: Exception) { "" }

    private fun checkDefaultAssistant() {
        val a = Settings.Secure.getString(contentResolver, "assistant")
        if (a == null || !a.contains(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Set MAWA as Default Assistant")
                .setMessage("Power button se activate karne ke liye set karo.")
                .setPositiveButton("Set") { _, _ -> startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("bn", "BD"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale("hi", "IN")
        }
    }

    private fun initViews() {
        orbView = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        chatRecycler = findViewById(R.id.chatRecycler)
        textInput = findViewById(R.id.textInput)
        sendBtn = findViewById(R.id.sendBtn)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        timeText = findViewById(R.id.timeText)
        redOverlay = findViewById(R.id.redOverlay)
        settingsBtn = findViewById(R.id.settingsBtn)

        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatRecycler.adapter = chatAdapter

        micButton.setOnClickListener { toggleMute() }
        micButton.setOnLongClickListener { interruptMawa(); true }

        sendBtn.setOnClickListener { sendTextMessage() }
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTextMessage(); true } else false
        }

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, com.mawa.assistant.ui.settings.SettingsActivity::class.java))
        }
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    private fun startSystemServices() {
        try {
            startForegroundService(Intent(this, MawaOverlayService::class.java))
            startForegroundService(Intent(this, CallMonitorService::class.java))
        } catch (e: Exception) { }
    }

    private fun startStatusUpdates() { handler.post(statusUpdateRunnable) }

    private fun updateStatusBar() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryText.text = "BAT: $level%"

        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val usedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        ramText.text = "RAM: ${usedMb}MB"
        timeText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun initGeminiLive() {
        val prefs = getSharedPreferences("mawa_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("gemini_model", "models/gemini-2.0-flash-exp") ?: "models/gemini-2.0-flash-exp"
        val voice = prefs.getString("gemini_voice", "Aoede") ?: "Aoede"
        val userName = prefs.getString("user_name", "Boss") ?: "Boss"
        val personality = prefs.getString("personality_mode", "gf") ?: "gf"

        if (apiKey.isEmpty()) {
            statusText.text = "Set API key in Settings"
            return
        }

        val systemPrompt = buildSystemPrompt(userName, personality)

        geminiLive = GeminiLiveClient(this)
        audioEngine = AudioEngine(this)

        geminiLive.configure(apiKey, model, systemPrompt, voice)

        geminiLive.onConnected = {
            runOnUiThread {
                statusText.text = "Connected"
                orbView.setState(OrbAnimationView.State.LISTENING)
                setActiveMode(true)
            }
            audioEngine.startRecording()
            audioEngine.startPlayback()
            handler.postDelayed({ sendGreeting(userName, personality) }, 600)
        }

        geminiLive.onDisconnected = {
            runOnUiThread {
                statusText.text = "Reconnecting..."
                orbView.setState(OrbAnimationView.State.IDLE)
                setActiveMode(false)
            }
        }

        geminiLive.onAudioReceived = { pcmData -> audioEngine.queueAudio(pcmData) }

        geminiLive.onOutputTranscript = { text ->
            // AI-এর উল্টোপাল্টা থিংকিং ফিল্টার করা হলো (MYRA-এর মতো)
            if (!isInternalMessage(text)) {
                outputBuffer.append(text)
            }
        }

        geminiLive.onInputTranscript = { text -> inputBuffer.append(text) }

        geminiLive.onTurnComplete = {
            runOnUiThread {
                val input = inputBuffer.toString().trim()
                val output = outputBuffer.toString().trim()

                if (input.isNotEmpty()) {
                    chatAdapter.addMessage(ChatMessage(input, isUser = true))
                    val command = CommandParser.parse(input)
                    if (command != null) viewModel.executeCommand(command)
                }
                if (output.isNotEmpty()) {
                    chatAdapter.addMessage(ChatMessage(output, isUser = false))
                }

                chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
                inputBuffer.clear()
                outputBuffer.clear()
            }
        }

        geminiLive.onError = { error -> runOnUiThread { statusText.text = "Error: $error" } }

        audioEngine.onAudioCaptured = { pcmData ->
            if (!isInCallMode) geminiLive.sendAudio(pcmData)
        }

        audioEngine.onAmplitudeChanged = { amp ->
            waveformView.setAmplitude(amp)
            orbView.setAmplitude(amp)
        }

        audioEngine.onSpeakingStarted = {
            runOnUiThread {
                orbView.setState(OrbAnimationView.State.SPEAKING)
                statusText.text = "Bol te ci..."
                waveformView.startAnimation()
                
                // কথা বলার সময় স্ক্রিনের ডেটা (Screen Context) জেমিনিকে পাঠানো হলো
                val ctx = getScreenContext()
                if (ctx.isNotEmpty() && ::geminiLive.isInitialized) {
                    geminiLive.sendText("[Screen Context: $ctx] Read this silently to help user.")
                }
            }
        }

        audioEngine.onSpeakingStopped = {
            runOnUiThread {
                orbView.setState(OrbAnimationView.State.LISTENING)
                statusText.text = "Sun te ci..."
                waveformView.stopAnimation()
            }
        }

        geminiLive.connect()
    }

    private fun buildSystemPrompt(userName: String, personality: String): String {
        val dateTime = SimpleDateFormat("EEEE, MMMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date())
        return """
            You are MAWA, a caring AI companion. Personality: $personality.
            Current date/time: $dateTime
            User's name: $userName
            Use Banglish (Bangla + English mix). Be warm, emotionally expressive.
            NEVER say: "Confirming", "Clarifying", "Processing". Just act immediately.
            You are speaking ALOUD — keep responses natural, conversational and SHORT.
        """.trimIndent()
    }

    private fun sendGreeting(userName: String, personality: String) {
        val greeting = if (personality == "gf") "Hey $userName! Ami ase poreci. Ki help lagbe tumar?" else "Hello $userName!"
        geminiLive.sendText(greeting)
        orbView.setState(OrbAnimationView.State.THINKING)
        statusText.text = "Thinking..."
    }

    private fun setActiveMode(active: Boolean) {
        isActiveMode = active
        val targetAlpha = if (active) 0.08f else 0f
        ObjectAnimator.ofFloat(redOverlay, "alpha", redOverlay.alpha, targetAlpha).apply { duration = if (active) 300 else 500; start() }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        if (::audioEngine.isInitialized) audioEngine.setMuted(isMuted)
        micButton.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        statusText.text = if (isMuted) "Mic muted" else "Sun te ci..."
    }

    private fun interruptMawa() {
        if (::audioEngine.isInitialized) audioEngine.clearPlaybackQueue()
        if (::geminiLive.isInitialized) geminiLive.sendInterrupt()
        orbView.setState(OrbAnimationView.State.LISTENING)
        statusText.text = "Interrupted"
    }

    private fun sendTextMessage() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return

        chatAdapter.addMessage(ChatMessage(text, isUser = true))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)

        if (::geminiLive.isInitialized) {
            val ctx = getScreenContext()
            val msgToSend = if (ctx.isNotEmpty()) "[Screen: $ctx] User: $text" else text
            geminiLive.sendText(msgToSend)
            orbView.setState(OrbAnimationView.State.THINKING)
            statusText.text = "Thinking..."
        }

        val command = CommandParser.parse(text)
        if (command != null) viewModel.executeCommand(command)

        textInput.text.clear()
    }

    fun announceCall(callerName: String) {
        isInCallMode = true
        if (::geminiLive.isInitialized) {
            geminiLive.sendText("Boss, $callerName der call asce. Uthabo naki reject korbo?")
        } else {
            tts?.speak("Boss, $callerName calling. Receive or reject?", TextToSpeech.QUEUE_FLUSH, null, "CALL")
        }
        handler.postDelayed({ startCallDecisionSTT() }, 4500)
    }

    private fun startCallDecisionSTT() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.lowercase() ?: ""
                val acceptWords = listOf("uthao", "haa", "accept", "answer", "receive", "ha", "yes")
                val rejectWords = listOf("reject", "na", "no", "bondho", "cancel", "decline")

                when {
                    acceptWords.any { spoken.contains(it) } -> { viewModel.acceptCall(); if (::geminiLive.isInitialized) geminiLive.sendText("Call accept korlam!") }
                    rejectWords.any { spoken.contains(it) } -> { viewModel.rejectCall(); if (::geminiLive.isInitialized) geminiLive.sendText("Call reject korlam!") }
                }
                isInCallMode = false
                setActiveMode(false)
                speechRecognizer?.destroy()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { isInCallMode = false; speechRecognizer?.destroy() }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("INCOMING_CALL", false) == true) {
            val callerName = intent.getStringExtra("CALLER_NAME") ?: "Unknown"
            announceCall(callerName)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::audioEngine.isInitialized) audioEngine.setMuted(true)
    }

    override fun onResume() {
        super.onResume()
        if (::audioEngine.isInitialized && !isMuted) audioEngine.setMuted(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        tts?.shutdown()
        if (::geminiLive.isInitialized) geminiLive.release()
        if (::audioEngine.isInitialized) audioEngine.release()
        orbView.release()
        speechRecognizer?.destroy()
    }
}
