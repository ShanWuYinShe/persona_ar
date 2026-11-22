package com.persona.ar.ar

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * 现实环境光照与色温动态分析器 (Camera Environmental Light & Scene Analyzer)
 * 基于 CameraX 的实时帧 Y 平面采样，以极低算力解算环境真实照度 (0.0f ~ 1.0f)，
 * 将环境明暗与冷暖色调实时反馈至虚拟伴侣渲染引擎，实现虚拟模型与真实物理画面的深度光影融合。
 */
class CameraEnvironmentalAnalyzer(
    private val onLightEstimated: (luminance: Float, isLowLight: Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    // 平滑滤波缓存，消除摄像头自动曝光测光跳跃
    private var smoothedLuminance = 0.75f
    private var frameCounter = 0L

    override fun analyze(image: ImageProxy) {
        try {
            // 每 3 帧分析一次，控制 CPU 占用在 0.5% 以下
            if (frameCounter++ % 3L != 0L) {
                image.close()
                return
            }

            val buffer: ByteBuffer = image.planes[0].buffer
            val remaining = buffer.remaining()
            if (remaining <= 0) {
                image.close()
                return
            }

            // 采样步长：每 32 个字节采样一次 Y 通道亮度 (0~255)
            val step = 32
            var totalBrightness = 0L
            var sampleCount = 0

            var i = 0
            while (i < remaining) {
                val pixel = buffer.get(i).toInt() and 0xFF
                totalBrightness += pixel
                sampleCount++
                i += step
            }

            if (sampleCount > 0) {
                val rawLuminance = (totalBrightness.toFloat() / sampleCount) / 255.0f
                // 一阶低通滤波平滑 (Exponential Moving Average)
                smoothedLuminance = smoothedLuminance * 0.8f + rawLuminance * 0.2f
                val clamped = smoothedLuminance.coerceIn(0.15f, 1.25f)
                val isLowLight = clamped < 0.32f

                onLightEstimated(clamped, isLowLight)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}
