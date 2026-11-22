package com.persona.ar.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class VoiceRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pcmOutputStream = ByteArrayOutputStream()
    private val pcmBufferLock = Any()

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

            synchronized(pcmBufferLock) {
                pcmOutputStream.reset()
            }

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
                        val byteBuf = ByteArray(read * 2)
                        for (i in 0 until read) {
                            val sample = buffer[i]
                            val a = abs(sample.toInt())
                            if (a > maxAmp) maxAmp = a
                            // 小端序存储 16-bit PCM
                            byteBuf[i * 2] = (sample.toInt() and 0xff).toByte()
                            byteBuf[i * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
                        }

                        synchronized(pcmBufferLock) {
                            pcmOutputStream.write(byteBuf)
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

    /**
     * 获取当前收集的录音音频并封装为标准 WAV 格式，同时清空当前缓冲区
     */
    fun getAndResetRecordedWav(sampleRate: Int = 16000): ByteArray? {
        val pcmData = synchronized(pcmBufferLock) {
            val bytes = pcmOutputStream.toByteArray()
            pcmOutputStream.reset()
            bytes
        }
        if (pcmData.isEmpty()) return null
        return encodeWav(pcmData, sampleRate, 1, 16)
    }

    private fun encodeWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavData = ByteArray(44 + totalAudioLen)
        System.arraycopy(header, 0, wavData, 0, 44)
        System.arraycopy(pcmData, 0, wavData, 44, totalAudioLen)
        return wavData
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
