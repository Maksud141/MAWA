package com.mawa.assistant.ai

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        private const val MIC_SAMPLE_RATE = 16000
        private const val SPEAKER_SAMPLE_RATE = 24000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 1024
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isRecording = false
    private var isPlaying = false
    private var isMuted = false
    @Volatile
    private var isSpeaking = false

    var onAudioCaptured: ((ByteArray) -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null

    fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, CHANNEL_IN, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MIC_SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize.coerceAtLeast(CHUNK_SIZE * 2)
        )

        audioRecord?.startRecording()
        isRecording = true

        recordingJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isActive && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                if (bytesRead > 0 && !isMuted && !isSpeaking) {
                    val rms = calculateRMS(buffer, bytesRead)
                    withContext(Dispatchers.Main) {
                        onAmplitudeChanged?.invoke(rms)
                    }
                    onAudioCaptured?.invoke(buffer.copyOf(bytesRead))
                }
            }
        }
    }

    fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(SPEAKER_SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SPEAKER_SAMPLE_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(ENCODING)
            .build()

        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSize.coerceAtLeast(CHUNK_SIZE * 4),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioTrack?.play()
        isPlaying = true

        playbackJob = scope.launch {
            while (isActive && isPlaying) {
                val chunk = playbackQueue.poll()
                if (chunk != null) {
                    if (!isSpeaking) {
                        isSpeaking = true
                        withContext(Dispatchers.Main) {
                            onSpeakingStarted?.invoke()
                        }
                    }
                    val rms = calculateRMS(chunk, chunk.size)
                    withContext(Dispatchers.Main) {
                        onAmplitudeChanged?.invoke(rms)
                    }
                    audioTrack?.write(chunk, 0, chunk.size)
                } else {
                    if (isSpeaking) {
                        isSpeaking = false
                        withContext(Dispatchers.Main) {
                            onSpeakingStopped?.invoke()
                        }
                    }
                    delay(10)
                }
            }
        }
    }

    fun queueAudio(pcmData: ByteArray) {
        playbackQueue.add(pcmData)
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
        audioTrack?.flush()
        if (isSpeaking) {
            isSpeaking = false
            scope.launch(Dispatchers.Main) {
                onSpeakingStopped?.invoke()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    fun isMuted(): Boolean = isMuted

    fun isSpeaking(): Boolean = isSpeaking

    private fun calculateRMS(buffer: ByteArray, size: Int): Float {
        var sum = 0.0
        val samples = size / 2
        for (i in 0 until samples) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            val normalized = sample.toShort().toFloat() / Short.MAX_VALUE
            sum += normalized * normalized
        }
        return sqrt(sum / samples).toFloat().coerceIn(0f, 1f)
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun release() {
        stopRecording()
        stopPlayback()
        scope.cancel()
    }
}
