package com.persona.ar.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "PersonaAudio"

        // 与后端 tts.MinAudioBytes 保持一致：小于 2KB 的音频一定是 5 字节假帧一类的占位数据
        private const val MIN_VALID_AUDIO_BYTES = 2048

        // Visualizer 采集窗口：必须为 2 的幂且在 getCaptureSizeRange() 范围内，512 点约 21ms@24kHz，够画口型
        private const val VISUALIZER_CAPTURE_SIZE = 512

        // 语音 RMS 通常只有 0.03~0.25，放大后映射到 0~1，避免虚拟形象张不开嘴
        private const val AMPLITUDE_GAIN = 4.2f
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var amplitudeJob: Job? = null
    private var visualizer: Visualizer? = null
    private var smoothedAmplitude = 0f
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var onAmplitudeUpdate: ((Float) -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null

    /**
     * 播放失败回调（服务端音频无效 / MediaPlayer prepare/start 异常 / 播放中断错误）。
     * 上层可据此回退系统 TTS 或提示用户；参数为失败原因摘要，便于日志排查。
     */
    var onPlaybackError: ((String) -> Unit)? = null

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

    /**
     * 播放服务端返回的 base64 音频（mp3）。
     * @param emotion 情绪标签（JOY/SHY/...），播放失败回退端侧 TTS 时用于匹配朗读韵律
     * @param fallbackText 播放失败时用于端侧 TTS 的兜底文本；为空则只触发 onPlaybackError
     */
    fun playAudioBase64(base64Data: String, emotion: String = "NEUTRAL", fallbackText: String? = null) {
        stop()
        val audioBytes = try {
            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "base64 音频解码失败", e)
            handlePlaybackFailure("base64_decode_failed: " + e.message, emotion, fallbackText)
            return
        }

        // 服务端历史版本曾返回 5 字节假 MP3 帧：提前拦截，避免依赖各机型 MediaPlayer 的差异化报错
        if (!looksLikePlayableAudio(audioBytes)) {
            Log.w(TAG, "音频数据无效（" + audioBytes.size + " 字节），触发端侧回退")
            handlePlaybackFailure("invalid_audio_bytes: " + audioBytes.size, emotion, fallbackText)
            return
        }

        try {
            val tempFile = File(context.cacheDir, "temp_persona_voice.mp3")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            val player = MediaPlayer()
            player.setDataSource(tempFile.absolutePath)
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer 异步播放错误 what=" + what + " extra=" + extra)
                handlePlaybackFailure("media_player_error: what=" + what + " extra=" + extra, emotion, fallbackText)
                true
            }
            player.setOnCompletionListener {
                stopAmplitudeTracking()
                onPlaybackFinished?.invoke()
            }
            player.prepare()
            applySpatialVolume()
            player.start()
            mediaPlayer = player
            // 音频会话 ID 在 prepare 后才有效，此时再绑定 Visualizer 读取真实波形
            startAmplitudeTracking()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer 播放失败", e)
            handlePlaybackFailure("media_player_exception: " + e.message, emotion, fallbackText)
        }
    }

    /**
     * 播放失败统一处理：释放播放器 -> 通知上层 -> 有兜底文本时用端侧 TTS 朗读。
     */
    private fun handlePlaybackFailure(reason: String, emotion: String, fallbackText: String?) {
        stopAmplitudeTracking()
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // 释放异常可忽略
        }
        mediaPlayer = null
        onPlaybackError?.invoke(reason)
        if (!fallbackText.isNullOrBlank()) {
            speakTextFallback(fallbackText, emotion)
        } else {
            onPlaybackFinished?.invoke()
        }
    }

    /**
     * 端侧系统 TTS 兜底朗读。服务端音频不可用时由上层调用（或 playAudioBase64 自动回退）。
     * 系统 TTS 无法使用 SSML，这里按情绪调整 setPitch/setSpeechRate 近似情绪韵律。
     * @param emotion 情绪标签，缺省 NEUTRAL 保持自然语速音高
     */
    fun speakTextFallback(text: String, emotion: String = "NEUTRAL") {
        stop()
        if (!isTtsInitialized) {
            Log.w(TAG, "系统 TTS 未初始化，无法朗读兜底文本")
            onPlaybackFinished?.invoke()
            return
        }
        applyTtsProsody(emotion)
        val utteranceId = "persona_tts_" + System.currentTimeMillis()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 系统 TTS 不暴露可绑定的音频会话，口型只能用模拟波形近似驱动
                startAmplitudeSimulation()
            }

            override fun onDone(utteranceId: String?) {
                stopAmplitudeTracking()
                scope.launch { onPlaybackFinished?.invoke() }
            }

            // API 21 起 onError(String) 已废弃，新版本系统走 onError(String, Int)；两个都实现保证全机型可回调
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                handleTtsError()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleTtsError()
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** 系统 TTS 朗读异常收尾：停止口型并通知播放结束，避免 UI 卡在说话态。 */
    private fun handleTtsError() {
        stopAmplitudeTracking()
        scope.launch { onPlaybackFinished?.invoke() }
    }

    /**
     * 系统 TTS 情绪韵律表：pitch(1.0=原声) / speechRate(1.0=原速)。
     * 与后端 Edge TTS 的情绪韵律设计保持同向：喜悦加快上扬、害羞略升收敛、
     * 难过放慢压低、慵懒明显放慢压低、惊讶短促高扬、生气急促、思考沉吟。
     */
    private fun applyTtsProsody(emotion: String) {
        val (pitch, rate) = when (emotion.uppercase(Locale.ROOT)) {
            "JOY" -> 1.12f to 1.08f
            "SHY" -> 1.08f to 0.95f
            "SAD" -> 0.90f to 0.86f
            "SURPRISED" -> 1.22f to 1.15f
            "THINKING" -> 0.96f to 0.90f
            "ANGRY" -> 1.06f to 1.12f
            "SLEEPY" -> 0.88f to 0.78f
            "CONFIDENT" -> 0.96f to 1.02f
            else -> 1.0f to 1.0f
        }
        try {
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
        } catch (e: Exception) {
            Log.w(TAG, "设置系统 TTS 韵律失败", e)
        }
    }

    // ===== 口型振幅：优先真实波形，失败回退模拟 =====

    private fun startAmplitudeTracking() {
        if (startVisualizerAmplitude()) {
            return
        }
        Log.w(TAG, "Visualizer 不可用，回退模拟口型波形")
        startAmplitudeSimulation()
    }

    /**
     * 绑定 MediaPlayer.audioSessionId 创建 Visualizer，用真实 waveform RMS 驱动口型。
     * 任何一步失败（机型不支持/权限/会话 ID 无效）都返回 false，由调用方回退模拟波形。
     */
    private fun startVisualizerAmplitude(): Boolean {
        val player = mediaPlayer ?: return false
        return try {
            val sessionId = player.audioSessionId
            if (sessionId == 0) {
                return false
            }
            val captureRange = Visualizer.getCaptureSizeRange()
            val captureSize = VISUALIZER_CAPTURE_SIZE.coerceIn(captureRange[0], captureRange[1])
            val vis = Visualizer(sessionId)
            // 注意：Visualizer 的 setCaptureSize / setEnabled / setDataCaptureListener 均返回 int 状态码，
            // 不是 Unit，不能当 Kotlin 属性赋值，必须显式调用并检查返回值。
            val captureStatus = vis.setCaptureSize(captureSize)
            if (captureStatus != Visualizer.SUCCESS) {
                vis.release()
                return false
            }
            val listener = object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int
                ) {
                    val amplitude = computeWaveformAmplitude(waveform)
                    smoothedAmplitude = smoothedAmplitude * 0.55f + amplitude * 0.45f
                    scope.launch { onAmplitudeUpdate?.invoke(smoothedAmplitude.coerceIn(0f, 1f)) }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int
                ) {
                    // 只用 waveform 计算 RMS，FFT 回调不需要
                }
            }
            val listenerStatus = vis.setDataCaptureListener(
                listener,
                Visualizer.getMaxCaptureRate() / 2,
                true,
                false
            )
            if (listenerStatus != Visualizer.SUCCESS) {
                vis.release()
                return false
            }
            val enableStatus = vis.setEnabled(true)
            if (enableStatus != Visualizer.SUCCESS) {
                vis.release()
                return false
            }
            visualizer = vis
            true
        } catch (e: Exception) {
            Log.w(TAG, "初始化 Visualizer 失败，回退模拟波形", e)
            releaseVisualizer()
            false
        }
    }

    /**
     * 计算 8bit 无符号 waveform（中心值 128）的 RMS 并映射到 0~1。
     */
    private fun computeWaveformAmplitude(waveform: ByteArray?): Float {
        if (waveform == null || waveform.isEmpty()) {
            return 0f
        }
        var sumSquares = 0.0
        for (sample in waveform) {
            val centered = ((sample.toInt() and 0xFF) - 128).toDouble()
            sumSquares += centered * centered
        }
        val rms = sqrt(sumSquares / waveform.size) / 128.0
        return (rms * AMPLITUDE_GAIN).coerceIn(0.0, 1.0).toFloat()
    }

    /** 模拟波形兜底：系统 TTS 或 Visualizer 不可用时保证口型仍有反馈。 */
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

    private fun stopAmplitudeTracking() {
        releaseVisualizer()
        amplitudeJob?.cancel()
        amplitudeJob = null
        smoothedAmplitude = 0f
        onAmplitudeUpdate?.invoke(0f)
    }

    private fun releaseVisualizer() {
        val vis = visualizer ?: return
        visualizer = null
        try {
            vis.setEnabled(false)
        } catch (e: Exception) {
            // 已释放或会话失效时忽略
        }
        try {
            vis.release()
        } catch (e: Exception) {
            // 已释放时忽略
        }
    }

    /**
     * 音频有效性预检：既拦截服务端历史 5 字节假 MP3，也拦截截断/空数据。
     * 条件：长度 >= 2KB 且文件头是 MP3 帧同步/ID3，或其它常见容器（OggS/RIFF/ftyp）。
     */
    private fun looksLikePlayableAudio(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_VALID_AUDIO_BYTES) {
            return false
        }
        // MP3 帧同步字：11 位全 1
        if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return true
        }
        if (bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) {
            return true
        }
        val head = String(bytes, 0, 4, Charsets.ISO_8859_1)
        if (head == "OggS" || head == "RIFF") {
            return true
        }
        if (bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
            return true
        }
        return false
    }

    /**
     * 随时打断当前语音播放（用于自然对话中的 Barge-in 用户插话打断）
     */
    fun interrupt() {
        stop()
        onPlaybackFinished?.invoke()
    }

    fun stop() {
        stopAmplitudeTracking()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
            } catch (e: Exception) {
                // 已释放/未初始化时忽略
            }
            try {
                it.release()
            } catch (e: Exception) {
                // 已释放时忽略
            }
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
