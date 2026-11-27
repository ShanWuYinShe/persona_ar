package com.persona.ar.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class VoiceRecorder {

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    @Volatile
    private var continuousMode = false
    private var recordJob: Job? = null
    private val recordLock = Any()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioBuffer = VoiceAudioBuffer()

    var onVolumeUpdate: ((Float) -> Unit)? = null
    /** 检测到用户开始发声（用于触发打断当前正在说话的角色） */
    var onSpeechDetected: (() -> Unit)? = null
    /** 自然说话模式下检测到断句静音（静音维持 1.2 秒以上，或达到单轮最长录制上限） */
    var onSilenceCompleted: (() -> Unit)? = null

    // VAD 阈值与状态跟踪
    private val speechThreshold = 0.18f
    private var hasSpokenInCurrentTurn = false
    private var silenceStartTime = 0L

    @SuppressLint("MissingPermission")
    fun startListening(isContinuousMode: Boolean = false) {
        if (isRecording) return
        val sampleRate = 16000
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            scope.launch(Dispatchers.Main) { onVolumeUpdate?.invoke(0f) }
            return
        }
        val readChunkShorts = (minBufBytes / 2).coerceIn(320, 1024)
        val recordBufBytes = (minBufBytes * 2).coerceAtLeast(readChunkShorts * 4)

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufBytes
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                scope.launch(Dispatchers.Main) { onVolumeUpdate?.invoke(0f) }
                return
            }

            synchronized(recordLock) {
                audioRecord = recorder
                continuousMode = isContinuousMode
                audioBuffer.reset(usePreRollRing = isContinuousMode)
                recorder.startRecording()
                isRecording = true
                hasSpokenInCurrentTurn = false
                silenceStartTime = 0L
            }

            recordJob = scope.launch {
                val buffer = ShortArray(readChunkShorts)
                while (isRecording && isActive) {
                    val activeRecorder = synchronized(recordLock) { audioRecord } ?: break
                    val read = activeRecorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val (byteBuf, normalized) = VoiceAudioBuffer.pcm16ToLeBytesAndPeak(buffer, read)

                        val hitMaxCap = audioBuffer.appendPcm(byteBuf, byteBuf.size)

                        withContext(Dispatchers.Main) {
                            if (!isRecording) return@withContext
                            onVolumeUpdate?.invoke(normalized)

                            if (isContinuousMode) {
                                if (hitMaxCap && hasSpokenInCurrentTurn) {
                                    hasSpokenInCurrentTurn = false
                                    silenceStartTime = 0L
                                    onSilenceCompleted?.invoke()
                                } else {
                                    handleContinuousVad(normalized)
                                }
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
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
                audioBuffer.markSpeechStarted()
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

    /**
     * 获取当前收集的录音音频并封装为标准 WAV 格式，同时清空当前缓冲区
     */
    fun getAndResetRecordedWav(sampleRate: Int = 16000): ByteArray? {
        return audioBuffer.takeWavAndReset(
            sampleRate = sampleRate,
            keepPreRollMode = continuousMode && isRecording
        )
    }

    fun getLastRecordedByteCount(): Int = audioBuffer.utteranceByteCount()

    fun stopListening() {
        val recorderToRelease: AudioRecord?
        synchronized(recordLock) {
            isRecording = false
            continuousMode = false
            hasSpokenInCurrentTurn = false
            silenceStartTime = 0L
            recordJob?.cancel()
            recordJob = null
            recorderToRelease = audioRecord
            audioRecord = null
        }
        try {
            recorderToRelease?.stop()
        } catch (_: Exception) {
        }
        try {
            recorderToRelease?.release()
        } catch (_: Exception) {
        }
        scope.launch(Dispatchers.Main) {
            onVolumeUpdate?.invoke(0f)
        }
    }

    fun release() {
        stopListening()
        scope.cancel()
    }
}
