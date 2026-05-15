package com.mawa.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.mawa.assistant.R

class CallMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "mawa_call_monitor"
        const val ACTION_CALL_ENDED = "com.mawa.CALL_ENDED"
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, buildNotification())
        setupPhoneListener()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun setupPhoneListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val callerName = resolveCallerName(phoneNumber ?: "")
                        val intent = Intent(this@CallMonitorService, Class.forName("com.mawa.assistant.ui.main.MainActivity"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        intent.putExtra("INCOMING_CALL", true)
                        intent.putExtra("CALLER_NAME", callerName)
                        intent.putExtra("CALLER_NUMBER", phoneNumber)
                        startActivity(intent)
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        val broadcast = Intent(ACTION_CALL_ENDED)
                        sendBroadcast(broadcast)
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> { }
                }
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun resolveCallerName(number: String): String {
        if (number.isEmpty()) return "Unknown"
        val digits = number.takeLast(7)
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

        var name = "Unknown"
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val contactName = it.getString(0)
                val numberCol = it.columnCount
                name = contactName ?: "Unknown"
                break
            }
        }

        val cursor2: Cursor? = contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )
        cursor2?.use {
            while (it.moveToNext()) {
                val contactNumber = it.getString(1)?.replace("[^0-9]".toRegex(), "") ?: ""
                if (contactNumber.endsWith(digits) || digits.endsWith(contactNumber.takeLast(7))) {
                    name = it.getString(0) ?: "Unknown"
                    break
                }
            }
        }
        return name
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Call Monitor", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MAWA Call Monitor")
            .setContentText("Monitoring incoming calls")
            .setSmallIcon(R.drawable.ic_mawa_notif)
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        super.onDestroy()
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }
}
