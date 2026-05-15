package com.mawa.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.*

class OrbAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE }

    private var currentState = State.IDLE
    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var speakAmplitude = 0f

    // MYRA's Advanced Animators
    private var rotationAngle = 0f
    private var pulseScale = 1f
    private var glowAlpha = 120
    private var waveOffset = 0f
    private var thinkAngle = 0f

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { rotationAngle = it.animatedValue as Float; postInvalidateOnAnimation() }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator() // MYRA's natural breathing effect
        addUpdateListener { pulseScale = it.animatedValue as Float; postInvalidateOnAnimation() }
    }

    private val glowAnimator = ValueAnimator.ofInt(120, 220, 120).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { glowAlpha = it.animatedValue as Int; postInvalidateOnAnimation() }
    }

    private val waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { waveOffset = it.animatedValue as Float; postInvalidateOnAnimation() }
    }

    private val thinkAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { thinkAngle = it.animatedValue as Float; postInvalidateOnAnimation() }
    }

    // MYRA's Optimized Paints (Hardware Acceleration friendly)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // MYRA's Pre-calculated Smooth Particles
    private data class Particle(var angle: Float, var size: Float, var alpha: Int)
    private val particles = (0..12).map {
        Particle((it * 360f / 12f), (4..8).random().toFloat(), (100..255).random())
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null) // Hardware Acceleration for lag-free performance
        pulseAnimator.start()
        glowAnimator.start()
        rotationAnimator.start()
        waveAnimator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.45f
    }

    fun setState(state: State) {
        currentState = state
        when (state) {
            State.THINKING -> {
                if (!thinkAnimator.isStarted) thinkAnimator.start()
                waveAnimator.duration = 1200
            }
            State.SPEAKING -> {
                if (thinkAnimator.isStarted) thinkAnimator.cancel()
                waveAnimator.duration = 600 // Faster waves when speaking
            }
            else -> {
                if (thinkAnimator.isStarted) thinkAnimator.cancel()
                waveAnimator.duration = 1200
            }
        }
        postInvalidateOnAnimation()
    }

    fun setAmplitude(amp: Float) {
        speakAmplitude = (amp + 10f) / 20f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val colors = getStateColors()
        
        canvas.save()
        canvas.scale(pulseScale, pulseScale, centerX, centerY)

        drawGlow(canvas, baseRadius, colors)
        drawOrb(canvas, baseRadius, colors)
        drawRings(canvas, baseRadius, colors)

        if (currentState == State.ACTIVE || currentState == State.SPEAKING || currentState == State.LISTENING) {
            drawWaveRings(canvas, baseRadius, colors)
            drawParticles(canvas, baseRadius, colors)
        }

        if (currentState == State.THINKING) {
            drawThinkingArc(canvas, baseRadius)
        }

        canvas.restore()
        drawInnerHighlight(canvas, baseRadius * pulseScale)
    }

    private fun getStateColors(): Pair<Int, Int> {
        return when (currentState) {
            State.IDLE -> Pair(Color.parseColor("#B71C1C"), Color.parseColor("#880E4F"))
            State.LISTENING, State.ACTIVE -> Pair(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"))
            State.SPEAKING -> Pair(Color.parseColor("#E040FB"), Color.parseColor("#FF1744"))
            State.THINKING -> Pair(Color.parseColor("#40C4FF"), Color.parseColor("#00B0FF"))
        }
    }

    private fun drawGlow(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        val glowRadius = r * 1.6f
        val shader = RadialGradient(
            centerX, centerY, glowRadius,
            intArrayOf(Color.argb(glowAlpha / 3, Color.red(colors.first), Color.green(colors.first), Color.blue(colors.first)), Color.TRANSPARENT),
            floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = shader
        canvas.drawCircle(centerX, centerY, glowRadius, glowPaint)
    }

    private fun drawOrb(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        val shader = RadialGradient(
            centerX - r * 0.3f, centerY - r * 0.3f, r,
            intArrayOf(colors.first, colors.second), null, Shader.TileMode.CLAMP
        )
        orbPaint.shader = shader
        canvas.drawCircle(centerX, centerY, r, orbPaint)
    }

    private fun drawRings(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        val oval = RectF()
        for (i in 0 until 2) {
            val ringRad = r + (i + 1) * 20f
            ringPaint.color = Color.argb(150 - i * 50, Color.red(colors.first), Color.green(colors.first), Color.blue(colors.first))
            oval.set(centerX - ringRad, centerY - ringRad, centerX + ringRad, centerY + ringRad)
            
            canvas.save()
            canvas.rotate(rotationAngle + i * 45f, centerX, centerY)
            canvas.drawArc(oval, 0f, 270f, false, ringPaint)
            canvas.restore()
        }
    }

    private val wavePath = Path()
    private fun drawWaveRings(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        val waveCount = 5
        val amplitude = if (currentState == State.SPEAKING) r * 0.2f * (1f + speakAmplitude) else r * 0.1f
        val waveRadius = r + 25f

        wavePath.reset()
        for (j in 0..120 step 2) {
            val angle = j * 3f * (PI / 180f).toFloat()
            val wave = amplitude * sin(waveCount * angle + waveOffset)
            val px = centerX + (waveRadius + wave) * cos(angle)
            val py = centerY + (waveRadius + wave) * sin(angle)
            if (j == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
        }
        wavePath.close()

        wavePaint.color = Color.argb(if (currentState == State.SPEAKING) 180 else 150, Color.red(colors.second), Color.green(colors.second), Color.blue(colors.second))
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawThinkingArc(canvas: Canvas, r: Float) {
        val thinkRad = r + 40f
        val oval = RectF(centerX - thinkRad, centerY - thinkRad, centerX + thinkRad, centerY + thinkRad)
        ringPaint.color = Color.parseColor("#40C4FF")
        
        canvas.save()
        canvas.rotate(thinkAngle, centerX, centerY)
        canvas.drawArc(oval, 0f, 100f, false, ringPaint)
        canvas.drawArc(oval, 180f, 100f, false, ringPaint)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        particles.forEach { p ->
            p.angle = (p.angle + 1.5f) % 360f
            val rad = r + 35f + 10f * sin(p.angle * (PI / 180f).toFloat() * 2)
            val px = centerX + rad * cos(p.angle * (PI / 180f).toFloat())
            val py = centerY + rad * sin(p.angle * (PI / 180f).toFloat())
            
            particlePaint.color = if (currentState == State.SPEAKING) colors.first else colors.second
            particlePaint.alpha = p.alpha
            canvas.drawCircle(px, py, p.size * 0.8f, particlePaint)
        }
    }

    private fun drawInnerHighlight(canvas: Canvas, r: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centerX - r * 0.25f, centerY - r * 0.25f, r * 0.5f,
                intArrayOf(Color.argb(100, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(centerX - r * 0.2f, centerY - r * 0.2f, r * 0.4f, paint)
    }

    override fun onDetachedFromWindow() {
        // MYRA's Auto Memory Cleanup
        listOf(rotationAnimator, pulseAnimator, glowAnimator, waveAnimator, thinkAnimator).forEach { it.cancel() }
        super.onDetachedFromWindow()
    }
}
