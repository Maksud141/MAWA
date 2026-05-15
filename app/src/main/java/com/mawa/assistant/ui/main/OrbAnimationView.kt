package com.mawa.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

class OrbAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE }

    private var currentState = State.IDLE
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val thinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var pulseScale = 1f
    private var glowAlpha = 120
    private var rotationAngle = 0f
    private var waveOffset = 0f
    private var thinkAngle = 0f
    private var amplitude = 0f

    private val particles = Array(12) { ParticleData(0f, 0f) }

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { pulseScale = it.animatedValue as Float; invalidate() }
    }

    private val glowAnimator = ValueAnimator.ofInt(120, 220, 120).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { glowAlpha = it.animatedValue as Int }
    }

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { rotationAngle = it.animatedValue as Float; invalidate() }
    }

    private val waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { waveOffset = it.animatedValue as Float }
    }

    private val thinkAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { thinkAngle = it.animatedValue as Float; invalidate() }
    }

    init {
        pulseAnimator.start()
        glowAnimator.start()
        rotationAnimator.start()
        waveAnimator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f * 0.35f
    }

    fun setState(state: State) {
        currentState = state
        when (state) {
            State.THINKING -> {
                if (!thinkAnimator.isRunning) thinkAnimator.start()
            }
            else -> {
                if (state != State.THINKING && thinkAnimator.isRunning) thinkAnimator.cancel()
            }
        }
        invalidate()
    }

    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val colors = getStateColors()
        val scaledRadius = radius * pulseScale

        drawGlow(canvas, scaledRadius, colors)
        drawOrb(canvas, scaledRadius, colors)
        drawRings(canvas, scaledRadius, colors)
        drawWaveRings(canvas, scaledRadius, colors)

        if (currentState == State.THINKING) {
            drawThinkingArc(canvas, scaledRadius)
        }

        if (currentState == State.ACTIVE || currentState == State.SPEAKING) {
            drawParticles(canvas, scaledRadius, colors)
        }

        drawHighlight(canvas, scaledRadius)
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
            intArrayOf(Color.argb(glowAlpha, Color.red(colors.first), Color.green(colors.first), Color.blue(colors.first)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        glowPaint.shader = shader
        canvas.drawCircle(centerX, centerY, glowRadius, glowPaint)
    }

    private fun drawOrb(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        val shader = RadialGradient(
            centerX - r * 0.2f, centerY - r * 0.2f, r * 1.2f,
            intArrayOf(colors.first, colors.second, Color.parseColor("#1A1A1A")),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        orbPaint.shader = shader
        canvas.drawCircle(centerX, centerY, r, orbPaint)
    }

    private fun drawRings(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        for (i in 0..2) {
            val ringRadius = r + 20f + i * 18f
            val alpha = (80 - i * 20).coerceAtLeast(20)
            ringPaint.color = Color.argb(alpha, Color.red(colors.first), Color.green(colors.first), Color.blue(colors.first))

            canvas.save()
            canvas.rotate(rotationAngle + i * 120f, centerX, centerY)
            val rect = RectF(centerX - ringRadius, centerY - ringRadius, centerX + ringRadius, centerY + ringRadius)
            canvas.drawArc(rect, 0f, 240f, false, ringPaint)
            canvas.restore()
        }
    }

    private fun drawWaveRings(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        if (currentState == State.IDLE) return

        val waveCount = 3
        for (w in 0 until waveCount) {
            val baseRadius = r + 60f + w * 14f
            val path = Path()
            val amp = (amplitude * 8f + 3f) * (1f - w * 0.2f)
            val segments = 60

            for (i in 0..segments) {
                val angle = (i.toFloat() / segments) * 2 * PI
                val wave = sin(angle * 6 + waveOffset + w * 0.5f).toFloat() * amp
                val px = centerX + cos(angle).toFloat() * (baseRadius + wave)
                val py = centerY + sin(angle).toFloat() * (baseRadius + wave)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()

            val alpha = (60 - w * 15).coerceAtLeast(15)
            wavePaint.color = Color.argb(alpha, Color.red(colors.second), Color.green(colors.second), Color.blue(colors.second))
            canvas.drawPath(path, wavePaint)
        }
    }

    private fun drawThinkingArc(canvas: Canvas, r: Float) {
        val thinkRadius = r + 30f
        val rect = RectF(centerX - thinkRadius, centerY - thinkRadius, centerX + thinkRadius, centerY + thinkRadius)
        thinkPaint.color = Color.parseColor("#40C4FF")

        canvas.save()
        canvas.rotate(thinkAngle, centerX, centerY)
        canvas.drawArc(rect, 0f, 90f, false, thinkPaint)
        canvas.drawArc(rect, 180f, 90f, false, thinkPaint)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas, r: Float, colors: Pair<Int, Int>) {
        val time = System.currentTimeMillis() / 1000.0
        for (i in particles.indices) {
            val angle = (i.toFloat() / particles.size) * 2 * PI + time * 0.5
            val orbitR = r + 50f + sin(time * 2 + i).toFloat() * 10f
            val px = centerX + cos(angle).toFloat() * orbitR
            val py = centerY + sin(angle).toFloat() * orbitR

            particlePaint.color = if (i % 2 == 0) colors.first else colors.second
            particlePaint.alpha = (150 + sin(time * 3 + i).toFloat() * 80).toInt().coerceIn(80, 230)
            canvas.drawCircle(px, py, 3f, particlePaint)
        }
    }

    private fun drawHighlight(canvas: Canvas, r: Float) {
        val hlRadius = r * 0.6f
        val hlX = centerX - r * 0.3f
        val hlY = centerY - r * 0.3f
        val shader = RadialGradient(
            hlX, hlY, hlRadius,
            intArrayOf(Color.argb(60, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        highlightPaint.shader = shader
        canvas.drawCircle(hlX, hlY, hlRadius, highlightPaint)
    }

    fun release() {
        pulseAnimator.cancel()
        glowAnimator.cancel()
        rotationAnimator.cancel()
        waveAnimator.cancel()
        thinkAnimator.cancel()
    }

    private data class ParticleData(var x: Float, var y: Float)
}
