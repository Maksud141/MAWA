package com.mawa.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private var lastScreenOffTime = 0L
        private const val DOUBLE_PRESS_INTERVAL = 600L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                val now = System.currentTimeMillis()
                if (now - lastScreenOffTime < DOUBLE_PRESS_INTERVAL) {
                    val serviceIntent = Intent(context, MawaOverlayService::class.java)
                    serviceIntent.action = MawaOverlayService.ACTION_SHOW
                    context.startForegroundService(serviceIntent)
                    lastScreenOffTime = 0L
                } else {
                    lastScreenOffTime = now
                }
            }
        }
    }
}
