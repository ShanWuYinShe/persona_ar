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

    // AR 空间立体声声相平衡 (-1.0f 左声道极化, 0.0f 居中, +1.0f 右声道极化)
    private var currentPan: Float = 0f
    private var currentDistanceScale: Float = 1.0f

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                isTtsInitialized = true
            }
        }
    }

    /**
     * 更新 AR 空间声场方位与距离
     * @param normalizedScreenX 伴侣在屏幕上的水平归一化坐标 (0.0 左侧 ~ 1.0 右侧)
     * @param scaleFactor 伴侣在 AR 空间中的缩放比例 (手办 micro ~ 等身 macro)
     */
    fun updateSpatialAudio(normalizedScreenX: Float, scaleFactor: Float = 1.0f) {
        val pan = ((normalizedScreenX - 0.5f) * 2f).coerceIn(-1f, 1f)
        currentPan = pan
        currentDistanceScale = scaleFactor
        applySpatialVolume()
    }

    private fun applySpatialVolume() {
        val player = mediaPlayer ?: return
        try {
            // 距离感音量增益：手办微缩 (0.5x) 音量稍收敛轻盈 (0.75)，近身等身 (2.0x) 饱满亲密 (1.0)
            val distGain = (0.72f + (currentDistanceScale - 0.5f) * 0.14f).coerceIn(0.65f, 1.0f)

            // 双耳立体声平移：pan = -1.0 时 left=1.0, right=0.30; pan = +1.0 时 left=0.30, right=1.0
            val leftVol = (distGain * (1.0f - (currentPan * 0.45f).coerceAtLeast(0f))).coerceIn(0.20f, 1.0f)
            val rightVol = (distGain * (1.0f + (currentPan * 0.45f).coerceAtMost(0f))).coerceIn(0.20f, 1.0f)
            player.setVolume(leftVol, rightVol)
        } catch (e: Exception) {
            // 忽略播放器状态异常
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
                applySpatialVolume()
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

    /**
     * 随时打断当前语音播放（用于自然对话中的 Barge-in 用户插话打断）
     */
    fun interrupt() {
        stop()
        onPlaybackFinished?.invoke()
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
