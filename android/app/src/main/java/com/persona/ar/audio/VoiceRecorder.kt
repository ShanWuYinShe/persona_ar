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

    @SuppressLint("MissingPermission")
    fun startListening() {
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
                        // 归一化到 0.0 ~ 1.0
                        val normalized = (maxAmp / 12000f).coerceIn(0f, 1f)
                        withContext(Dispatchers.Main) {
                            onVolumeUpdate?.invoke(normalized)
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

    fun stopListening() {
        isRecording = false
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
