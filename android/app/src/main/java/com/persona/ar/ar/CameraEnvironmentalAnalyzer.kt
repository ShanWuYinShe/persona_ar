package com.persona.ar.ar

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 现实环境光学特征剖面
 */
data class EnvironmentalOpticalProfile(
    val luminance: Float = 1.0f,
    val isLowLight: Boolean = false,
    val colorTemp: Float = 0.0f, // -1.0f(冷蓝) ~ 0.0f(中性) ~ +1.0f(暖黄)
    val tintR: Float = 1.0f,
    val tintG: Float = 1.0f,
    val tintB: Float = 1.0f,
    val sensorGrainIntensity: Float = 0.0f
)

/**
 * 现实环境光照与色温动态分析器 (Camera Environmental Light & Scene Analyzer)
 * 基于 CameraX 的实时帧 Y/U/V 平面采样，以极低算力解算环境真实照度、色温偏向与相机噪点，
 * 将环境明暗与冷暖色调实时反馈至虚拟伴侣渲染引擎，实现虚拟模型与真实物理画面的深度光影融合。
 */
class CameraEnvironmentalAnalyzer(
    private val onLightEstimated: (luminance: Float, isLowLight: Boolean) -> Unit = { _, _ -> },
    private val onOpticalProfileEstimated: ((EnvironmentalOpticalProfile) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    // 平滑滤波缓存，消除摄像头自动曝光测光跳跃
    private var smoothedLuminance = 0.75f
    private var smoothedColorTemp = 0.0f
    private var frameCounter = 0L

    override fun analyze(image: ImageProxy) {
        try {
            // 每 3 帧分析一次，控制 CPU 占用在 0.5% 以下
            if (frameCounter++ % 3L != 0L) {
                image.close()
                return
            }

            val yBuffer: ByteBuffer = image.planes[0].buffer
            val yRemaining = yBuffer.remaining()
            if (yRemaining <= 0) {
                image.close()
                return
            }

            // 1. 采样 Y 通道亮度 (0~255)
            val step = 32
            var totalBrightness = 0L
            var sampleCount = 0

            var i = 0
            while (i < yRemaining) {
                val pixel = yBuffer.get(i).toInt() and 0xFF
                totalBrightness += pixel
                sampleCount++
                i += step
            }

            if (sampleCount > 0) {
                val rawLuminance = (totalBrightness.toFloat() / sampleCount) / 255.0f
                smoothedLuminance = smoothedLuminance * 0.8f + rawLuminance * 0.2f
                val clamped = smoothedLuminance.coerceIn(0.15f, 1.25f)
                val isLowLight = clamped < 0.32f

                // 2. 采样 U/V 通道色度 (解算环境光色温与色偏)
                var rawTemp = 0.0f
                if (image.planes.size >= 3) {
                    val uBuffer = image.planes[1].buffer
                    val vBuffer = image.planes[2].buffer
                    val uRem = uBuffer.remaining()
                    val vRem = vBuffer.remaining()
                    var totalU = 0L
                    var totalV = 0L
                    var uvSamples = 0
                    var j = 0
                    while (j < uRem && j < vRem) {
                        totalU += uBuffer.get(j).toInt() and 0xFF
                        totalV += vBuffer.get(j).toInt() and 0xFF
                        uvSamples++
                        j += step
                    }
                    if (uvSamples > 0) {
                        val avgU = totalU.toFloat() / uvSamples
                        val avgV = totalV.toFloat() / uvSamples
                        // V > 128 (偏红暖) 且 U < 128 (偏黄暖) -> 暖色温
                        // U > 128 (偏蓝冷) 且 V < 128 (偏青冷) -> 冷色温
                        rawTemp = (((avgV - 128f) - (avgU - 128f)) / 50f).coerceIn(-1f, 1f)
                    }
                }

                smoothedColorTemp = smoothedColorTemp * 0.85f + rawTemp * 0.15f
                val tintR = (1.0f + smoothedColorTemp * 0.12f).coerceIn(0.85f, 1.18f)
                val tintG = (1.0f + (1f - abs(smoothedColorTemp)) * 0.02f).coerceIn(0.90f, 1.05f)
                val tintB = (1.0f - smoothedColorTemp * 0.12f).coerceIn(0.85f, 1.18f)
                val sensorGrain = ((0.38f - clamped) * 2.5f).coerceIn(0f, 1f)

                onLightEstimated(clamped, isLowLight)
                onOpticalProfileEstimated?.invoke(
                    EnvironmentalOpticalProfile(
                        luminance = clamped,
                        isLowLight = isLowLight,
                        colorTemp = smoothedColorTemp,
                        tintR = tintR,
                        tintG = tintG,
                        tintB = tintB,
                        sensorGrainIntensity = sensorGrain
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}
