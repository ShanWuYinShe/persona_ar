package com.persona.ar.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlin.math.abs

class VoiceRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var onVolumeUpdate: ((Float) -> Unit)? = null
    /** 检测到用户开始发声（用于触发打断当前正在说话的角色） */
    var onSpeechDetected: (() -> Unit)? = null
    /** 自然说话模式下检测到断句静音（静音维持 1.2 秒以上） */
    var onSilenceCompleted: (() -> Unit)? = null

    // VAD 阈值与状态跟踪
    private val speechThreshold = 0.18f
    private var hasSpokenInCurrentTurn = false
    private var silenceStartTime = 0L

    @SuppressLint("MissingPermission")
    fun startListening(isContinuousMode: Boolean = false) {
        if (isRecording) return
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true
            hasSpokenInCurrentTurn = false
            silenceStartTime = 0L

            recordJob = scope.launch {
                val buffer = ShortArray(bufferSize)
                while (isRecording && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var maxAmp = 0
                        for (i in 0 until read) {
                            val a = abs(buffer[i].toInt())
                            if (a > maxAmp) maxAmp = a
                        }
                        val normalized = (maxAmp / 12000f).coerceIn(0f, 1f)
                        withContext(Dispatchers.Main) {
                            onVolumeUpdate?.invoke(normalized)

                            if (isContinuousMode) {
                                handleContinuousVad(normalized)
                            }
                        }
                    }
                    delay(50)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopListening()
        }
    }

    private fun handleContinuousVad(volume: Float) {
        val now = System.currentTimeMillis()
        if (volume >= speechThreshold) {
            if (!hasSpokenInCurrentTurn) {
                hasSpokenInCurrentTurn = true
                onSpeechDetected?.invoke()
            }
            silenceStartTime = 0L
        } else {
            // 音量低于阈值（静音中）
            if (hasSpokenInCurrentTurn) {
                if (silenceStartTime == 0L) {
                    silenceStartTime = now
                } else if (now - silenceStartTime > 1200L) { // 持续静音 1.2 秒判定为断句完成
                    hasSpokenInCurrentTurn = false
                    silenceStartTime = 0L
                    onSilenceCompleted?.invoke()
                }
            }
        }
    }

    fun stopListening() {
        isRecording = false
        hasSpokenInCurrentTurn = false
        silenceStartTime = 0L
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        onVolumeUpdate?.invoke(0f)
    }

    fun release() {
        stopListening()
        scope.cancel()
    }
}
