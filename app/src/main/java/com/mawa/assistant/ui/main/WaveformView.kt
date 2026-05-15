package com.mawa.assistant.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 20
    private val barHeights = FloatArray(barCount) { 0.1f }
    private val targetHeights = FloatArray(barCount) { 0.1f }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var isAnimating = false
    private val lerpFactor = 0.3f

    private val runnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            for (i in 0 until barCount) {
                barHeights[i] += (targetHeights[i] - barHeights[i]) * lerpFactor
            }
            invalidate()
            postDelayed(this, 33)
        }
    }

    fun startAnimation() {
        isAnimating = true
        post(runnable)
    }

    fun stopAnimation() {
        isAnimating = false
        removeCallbacks(runnable)
        for (i in 0 until barCount) {
            targetHeights[i] = 0.1f
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        val amp = rms.coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val variation = (Math.random() * 0.4f + 0.8f).toFloat()
            targetHeights[i] = (amp * variation).coerceAtLeast(0.05f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / (barCount * 2f)
        val gap = barWidth

        for (i in 0 until barCount) {
            val barH = max(4f, barHeights[i] * h)
            val left = i * (barWidth + gap) + gap / 2
            val top = (h - barH) / 2
            val right = left + barWidth
            val bottom = top + barH

            val alpha = (150 + barHeights[i] * 105).toInt().coerceIn(150, 255)
            barPaint.color = Color.argb(alpha, 0xFF, 0x17, 0x44)

            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2, barWidth / 2, barPaint)
        }
    }
}
