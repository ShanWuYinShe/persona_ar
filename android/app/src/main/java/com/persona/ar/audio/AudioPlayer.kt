package com.persona.ar.audio

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

class AudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var onAmplitudeUpdate: ((Float) -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                isTtsInitialized = true
            }
        }
    }

    fun playAudioBase64(base64Data: String) {
        stop()
        try {
            val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
            val tempFile = File(context.cacheDir, "temp_persona_voice.mp3")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopAmplitudeSimulation()
                    onPlaybackFinished?.invoke()
                }
                start()
            }
            startAmplitudeSimulation()
        } catch (e: Exception) {
            e.printStackTrace()
            onPlaybackFinished?.invoke()
        }
    }

    fun speakTextFallback(text: String) {
        stop()
        if (isTtsInitialized) {
            val utteranceId = "persona_tts_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    startAmplitudeSimulation()
                }

                override fun onDone(utteranceId: String?) {
                    stopAmplitudeSimulation()
                    scope.launch { onPlaybackFinished?.invoke() }
                }

                override fun onError(utteranceId: String?) {
                    stopAmplitudeSimulation()
                    scope.launch { onPlaybackFinished?.invoke() }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            onPlaybackFinished?.invoke()
        }
    }

    private fun startAmplitudeSimulation() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            var step = 0f
            while (isActive) {
                step += 0.25f
                // 模拟富有节奏感的自然说话口型波动
                val amp = abs(sin(step)) * 0.8f + (Math.random().toFloat() * 0.2f)
                onAmplitudeUpdate?.invoke(amp.coerceIn(0f, 1f))
                delay(60)
            }
        }
    }

    private fun stopAmplitudeSimulation() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        onAmplitudeUpdate?.invoke(0f)
    }

    fun stop() {
        stopAmplitudeSimulation()
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        scope.cancel()
    }
}
