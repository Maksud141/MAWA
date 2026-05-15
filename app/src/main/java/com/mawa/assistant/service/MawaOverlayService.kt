package com.mawa.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import com.mawa.assistant.R
import com.mawa.assistant.ui.main.OrbAnimationView

class MawaOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "mawa_overlay_channel"
        const val ACTION_SHOW = "SHOW_OVERLAY"
        var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW) {
            showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_orb, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        val orbView = overlayView?.findViewById<OrbAnimationView>(R.id.overlayOrb)
        orbView?.setState(OrbAnimationView.State.IDLE)

        val closeBtn = overlayView?.findViewById<ImageButton>(R.id.overlayCloseBtn)
        closeBtn?.setOnClickListener {
            removeOverlay()
        }

        overlayView?.findViewById<FrameLayout>(R.id.overlayRoot)?.setOnClickListener {
            val mainIntent = Intent(this, Class.forName("com.mawa.assistant.ui.main.MainActivity"))
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(mainIntent)
            removeOverlay()
        }

        setupDrag()
        windowManager?.addView(overlayView, layoutParams)
    }

    private fun setupDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MAWA Overlay", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MAWA Active")
            .setContentText("Tap to open MAWA")
            .setSmallIcon(R.drawable.ic_mawa_notif)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        isRunning = false
    }
}
