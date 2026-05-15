package com.mawa.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private var pressCount = 0
        private var lastPressTime = 0L
        private const val DOUBLE_PRESS_INTERVAL = 600L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF ||
            intent.action == Intent.ACTION_SCREEN_ON) {

            val now = System.currentTimeMillis()
            
            if (now - lastPressTime < DOUBLE_PRESS_INTERVAL) {
                pressCount++
                if (pressCount >= 2) {
                    pressCount = 0 // কাউন্ট রিসেট করা হলো
                    
                    // MAWA ওভারলে চালু করার কোড
                    val overlayIntent = Intent(context, MawaOverlayService::class.java).apply {
                        action = "ACTION_SHOW" // MAWA er default action
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, overlayIntent)
                    } else {
                        context.startService(overlayIntent)
                    }
                }
            } else {
                pressCount = 1 // নতুন করে গোনা শুরু
            }
            lastPressTime = now
        }
    }
}
