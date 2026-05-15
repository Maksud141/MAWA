package com.mawa.assistant.ui.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mawa.assistant.R
import com.mawa.assistant.ai.AudioEngine
import com.mawa.assistant.ai.CommandParser
import com.mawa.assistant.ai.GeminiLiveClient
import com.mawa.assistant.service.CallMonitorService
import com.mawa.assistant.service.MawaOverlayService
import com.mawa.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

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

    private var isMuted = false
    private var isActiveMode = false
    private var isInCallMode = false
    private var inputBuffer = StringBuilder()
    private var outputBuffer = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

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
        Manifest.permission.CAMERA
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        startSystemServices()
        startStatusUpdates()
        registerReceiver(callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED),
            RECEIVER_NOT_EXPORTED)

        handler.postDelayed({ initGeminiLive() }, 300)
        handleIncomingCallIntent(intent)

        viewModel.commandResult.observe(this) { result ->
            if (result != null) {
                geminiLive.sendText(result)
                viewModel.clearResult()
            }
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
        micButton.setOnLongClickListener {
            interruptMawa()
            true
        }

        sendBtn.setOnClickListener { sendTextMessage() }
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage()
                true
            } else false
        }

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, com.mawa.assistant.ui.settings.SettingsActivity::class.java))
        }
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun startSystemServices() {
        try {
            startForegroundService(Intent(this, MawaOverlayService::class.java))
            startForegroundService(Intent(this, CallMonitorService::class.java))
        } catch (e: Exception) { }
    }

    private fun startStatusUpdates() {
        handler.post(statusUpdateRunnable)
    }

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
        val model = prefs.getString("gemini_model", "models/gemini-2.5-flash-native-audio-preview-12-2025") ?: "models/gemini-2.5-flash-native-audio-preview-12-2025"
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

        geminiLive.onAudioReceived = { pcmData ->
            audioEngine.queueAudio(pcmData)
        }

        geminiLive.onOutputTranscript = { text ->
            outputBuffer.append(text)
        }

        geminiLive.onInputTranscript = { text ->
            inputBuffer.append(text)
        }

        geminiLive.onTurnComplete = {
            runOnUiThread {
                val input = inputBuffer.toString().trim()
                val output = outputBuffer.toString().trim()

                if (input.isNotEmpty()) {
                    chatAdapter.addMessage(ChatMessage(input, isUser = true))
                    val command = CommandParser.parse(input)
                    if (command != null) {
                        viewModel.executeCommand(command)
                    }
                }
                if (output.isNotEmpty()) {
                    chatAdapter.addMessage(ChatMessage(output, isUser = false))
                }

                chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
                inputBuffer.clear()
                outputBuffer.clear()
            }
        }

        geminiLive.onError = { error ->
            runOnUiThread {
                statusText.text = "Error: $error"
            }
        }

        audioEngine.onAudioCaptured = { pcmData ->
            if (!isInCallMode) {
                geminiLive.sendAudio(pcmData)
            }
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
        val personalityBlock = when (personality) {
            "gf" -> """
                You are MAWA, a caring AI companion. Speak in Banglish (Bangla + English mix).
                Use: "tumar", "haa", "acha", "Obosshoi". Be warm, emotionally expressive.
                Use emojis sparingly. Max 2-3 sentences. Sound natural when speaking aloud.
                Examples: "Haa $userName! Akhoni koredissi", "Arre tumi ki dekeco! Bolo ki korte hobe"
            """.trimIndent()
            "professional" -> """
                You are MAWA, a professional AI assistant. Formal English only.
                Be precise and efficient. No emojis. Max 2 sentences.
            """.trimIndent()
            "assistant" -> """
                You are MAWA, a friendly AI assistant. Use Banglish or English as appropriate.
                Be balanced and helpful. Max 2-3 sentences.
            """.trimIndent()
            else -> ""
        }
        return """
            $personalityBlock
            Current date/time: $dateTime
            User's name: $userName
            You are speaking ALOUD — keep responses natural and conversational.
        """.trimIndent()
    }

    private fun sendGreeting(userName: String, personality: String) {
        val greeting = when (personality) {
            "gf" -> "Hey $userName! Ami ase poreci. Ki help lagbe tumar?"
            "professional" -> "Good day $userName. MAWA is online and ready to assist you."
            "assistant" -> "Hello $userName! Ami holam MAWA. Ki vabe help korbo tumake?"
            else -> "Hello $userName!"
        }
        geminiLive.sendText(greeting)
        orbView.setState(OrbAnimationView.State.THINKING)
        statusText.text = "Thinking..."
    }

    private fun setActiveMode(active: Boolean) {
        isActiveMode = active
        val targetAlpha = if (active) 0.08f else 0f
        ObjectAnimator.ofFloat(redOverlay, "alpha", redOverlay.alpha, targetAlpha).apply {
            duration = if (active) 300 else 500
            start()
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        if (::audioEngine.isInitialized) {
            audioEngine.setMuted(isMuted)
        }
        micButton.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        statusText.text = if (isMuted) "Mic muted" else "Sun te ci..."
    }

    private fun interruptMawa() {
        if (::audioEngine.isInitialized) {
            audioEngine.clearPlaybackQueue()
        }
        if (::geminiLive.isInitialized) {
            geminiLive.sendInterrupt()
        }
        orbView.setState(OrbAnimationView.State.LISTENING)
        statusText.text = "Interrupted"
    }

    private fun sendTextMessage() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return

        chatAdapter.addMessage(ChatMessage(text, isUser = true))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)

        if (::geminiLive.isInitialized) {
            geminiLive.sendText(text)
            orbView.setState(OrbAnimationView.State.THINKING)
            statusText.text = "Thinking..."
        }

        val command = CommandParser.parse(text)
        if (command != null) {
            viewModel.executeCommand(command)
        }

        textInput.text.clear()
    }

    fun announceCall(callerName: String) {
        isInCallMode = true
        if (::geminiLive.isInitialized) {
            geminiLive.sendText("Boss, $callerName der call asce. Uthabo naki reject korbo?")
        }

        handler.postDelayed({
            startCallDecisionSTT()
        }, 4500)
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
                    acceptWords.any { spoken.contains(it) } -> {
                        val result = viewModel.acceptCall()
                        if (::geminiLive.isInitialized) geminiLive.sendText("Call accept korlam!")
                    }
                    rejectWords.any { spoken.contains(it) } -> {
                        val result = viewModel.rejectCall()
                        if (::geminiLive.isInitialized) geminiLive.sendText("Call reject korlam!")
                    }
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
            override fun onError(error: Int) {
                isInCallMode = false
                speechRecognizer?.destroy()
            }
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
        if (::audioEngine.isInitialized) {
            audioEngine.setMuted(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::audioEngine.isInitialized && !isMuted) {
            audioEngine.setMuted(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(callEndedReceiver)
        if (::geminiLive.isInitialized) geminiLive.release()
        if (::audioEngine.isInitialized) audioEngine.release()
        orbView.release()
        speechRecognizer?.destroy()
    }
}
