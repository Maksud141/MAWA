package com.mawa.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mawa.assistant.R
import java.util.Locale

class CallMonitorService : Service(), TextToSpeech.OnInitListener {

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var hasAnnouncedThisRing = false
    private var isProcessingCall = false

    companion object {
        var isRunning = false
        const val ACTION_CALL_ACTIVE   = "com.mawa.assistant.CALL_ACTIVE"
        const val ACTION_CALL_ENDED    = "com.mawa.assistant.CALL_ENDED"
        const val ACTION_CALL_RINGING  = "com.mawa.assistant.CALL_RINGING"
        private const val TAG = "MAWA_CALL"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()

        // TTS ব্যাকআপ হিসেবে চালু করা হলো
        tts = TextToSpeech(this, this)

        setupPhoneListener()
        Log.d(TAG, "CallMonitorService started")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Bangla TTS not available, using English")
                tts?.language = Locale.ENGLISH
            }
            isTtsReady = true
            Log.d(TAG, "TTS ready")
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    private fun setupPhoneListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, rawNumber: String?) {
                Log.d(TAG, "Call state: $state, number: $rawNumber, lastState: $lastState")

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        if (lastState != TelephonyManager.CALL_STATE_RINGING && !hasAnnouncedThisRing && !isProcessingCall) {
                            isProcessingCall = true
                            hasAnnouncedThisRing = true
                            handleIncomingCall(rawNumber, isWhatsAppCall = false)
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        tts?.stop()
                        if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                            sendBroadcast(Intent(ACTION_CALL_ACTIVE))
                            Log.d(TAG, "Call answered → MAWA paused")
                        }
                        isProcessingCall = false
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        hasAnnouncedThisRing = false
                        tts?.stop()
                        if (lastState != TelephonyManager.CALL_STATE_IDLE) {
                            sendBroadcast(Intent(ACTION_CALL_ENDED))
                            Log.d(TAG, "Call ended → MAWA resuming")
                        }
                        isProcessingCall = false
                    }
                }
                lastState = state
            }
        }

        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleIncomingCall(rawNumber: String?, isWhatsAppCall: Boolean) {
        val prefs = getSharedPreferences("mawa_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("call_announce_enabled", true)) {
            Log.d(TAG, "Call announce off ache, skip korlam")
            isProcessingCall = false
            return
        }

        // পারমিশন চেক
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE permission nai")
            isProcessingCall = false
            return
        }

        val callerName = resolveCallerName(rawNumber)
        val userName = prefs.getString("user_name", "Boss") ?: "Boss"
        val personality = prefs.getString("personality_mode", "gf") ?: "gf"

        Log.d(TAG, "Caller resolved: '$callerName' (raw: $rawNumber, whatsapp: $isWhatsAppCall)")

        // CallAssistantActivity ওপেন করা হলো (MYRA এর মতো)
        launchCallAssistant(callerName, rawNumber ?: "", userName, personality, isWhatsAppCall)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isProcessingCall = false
        }, 12000)
    }

    private fun launchCallAssistant(callerName: String, number: String, userName: String, personality: String, isWhatsAppCall: Boolean) {
        try {
            val intent = Intent(this, Class.forName("com.mawa.assistant.ui.main.CallAssistantActivity")).apply {
                putExtra("CALLER_NAME", callerName)
                putExtra("PHONE_NUMBER", number)
                putExtra("USER_NAME", userName)
                putExtra("PERSONALITY", personality)
                putExtra("IS_WHATSAPP_CALL", isWhatsAppCall)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(intent)
            Log.d(TAG, "CallAssistantActivity launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch CallAssistant: ${e.message}")
        }
    }

    // MYRA এর ফাস্ট ও নিখুঁত কন্টাক্ট খোঁজার কোড
    private fun resolveCallerName(number: String?): String {
        if (number.isNullOrEmpty()) return "Unknown Caller"

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Contacts permission nai")
            return "Unknown Caller"
        }

        val cleanNumber = number.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")

        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(cleanNumber)
            )
            val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)

            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    Log.d(TAG, "Contact found: $name")
                    name.ifBlank { "Unknown Caller" }
                } else {
                    Log.d(TAG, "No contact for: $cleanNumber")
                    "Unknown Caller"
                }
            } ?: "Unknown Caller"
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup error: ${e.message}")
            "Unknown Caller"
        }
    }

    private fun startForegroundCompat() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1002, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "call_monitor_channel",
                "MAWA Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "call_monitor_channel")
            .setContentTitle("MAWA Call Monitor")
            .setContentText("Incoming call er jnno check kora hocche")
            // আপনার MAWA এর আইকন দিন, যদি ic_mawa_notif না থাকে তবে @mipmap/ic_launcher দিন
            .setSmallIcon(R.mipmap.ic_launcher) 
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        tts?.stop()
        tts?.shutdown()
        Log.d(TAG, "CallMonitorService destroyed")
        super.onDestroy()
    }
}
