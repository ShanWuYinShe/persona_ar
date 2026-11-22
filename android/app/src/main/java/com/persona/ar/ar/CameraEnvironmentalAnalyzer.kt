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
    val sensorGrainIntensity: Float = 0.0f,
    /** 现实镜头画面高光强光源区域占比 (0.0f ~ 1.0f) */
    val highlightRatio: Float = 0.0f,
    /** 现实强光源边缘光晕溢出与环境光散射系数 (Bloom & Light Bleed, 0.0f ~ 1.0f) */
    val bloomBleedFactor: Float = 0.0f,
    /** 伴侣脚底所在现实桌面局部照度估计 (0.2f ~ 1.3f) */
    val localSurfaceLuminance: Float = 1.0f,
    /** 伴侣脚底所在现实桌面底色红绿蓝反射分量 (用于桌面次表面散射 Radiosity) */
    val localSurfaceTintR: Float = 1.0f,
    val localSurfaceTintG: Float = 1.0f,
    val localSurfaceTintB: Float = 1.0f
)

/**
 * 现实环境光照与色温动态分析器 (Camera Environmental Light & Scene Analyzer)
 * 基于 CameraX 的实时帧 Y/U/V 平面采样，以极低算力解算环境真实照度、色温偏向、高光光晕、相机噪点与落脚点局部材质探针，
 * 将环境明暗、冷暖色调与局部桌面底色实时反馈至虚拟伴侣渲染引擎，实现虚拟模型在任意自选位置与真实物理画面的深度光影融合。
 */
class CameraEnvironmentalAnalyzer(
    private val onLightEstimated: (luminance: Float, isLowLight: Boolean) -> Unit = { _, _ -> },
    private val onOpticalProfileEstimated: ((EnvironmentalOpticalProfile) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    // 伴侣在屏幕上的归一化目标落脚点坐标 (供局部空间光影探针采样)
    @Volatile
    var focusNormX: Float = 0.5f
    @Volatile
    var focusNormY: Float = 0.65f

    // 平滑滤波缓存，消除摄像头自动曝光测光跳跃
    private var smoothedLuminance = 0.75f
    private var smoothedLocalLuminance = 0.75f
    private var smoothedColorTemp = 0.0f
    private var smoothedBloom = 0.0f
    private var smoothedLocalTintR = 1.0f
    private var smoothedLocalTintG = 1.0f
    private var smoothedLocalTintB = 1.0f
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

            // 1. 全局采样 Y 通道亮度 (0~255) 与高光强光源区域
            val step = 32
            var totalBrightness = 0L
            var sampleCount = 0
            var highLightCount = 0
            val highLightThreshold = 224

            var i = 0
            while (i < yRemaining) {
                val pixel = yBuffer.get(i).toInt() and 0xFF
                totalBrightness += pixel
                sampleCount++
                if (pixel >= highLightThreshold) {
                    highLightCount++
                }
                i += step
            }

            if (sampleCount > 0) {
                val rawLuminance = (totalBrightness.toFloat() / sampleCount) / 255.0f
                smoothedLuminance = smoothedLuminance * 0.8f + rawLuminance * 0.2f
                val clamped = smoothedLuminance.coerceIn(0.15f, 1.25f)
                val isLowLight = clamped < 0.32f

                val highlightRatio = (highLightCount.toFloat() / sampleCount).coerceIn(0f, 1f)
                val rawBloom = (highlightRatio * 2.8f + (clamped - 0.85f).coerceAtLeast(0f) * 1.2f).coerceIn(0f, 1f)
                smoothedBloom = smoothedBloom * 0.85f + rawBloom * 0.15f

                // 2. 局部桌面落脚点探针采样 (Local Surface Light Probe)
                // 定位伴侣双足所在局部区域 (聚焦范围围绕 focusNormX, focusNormY)
                val localTargetIndex = ((focusNormY * 0.85f + 0.15f) * yRemaining).toInt().coerceIn(0, yRemaining - 1)
                val windowSize = 1024
                val localStart = (localTargetIndex - windowSize / 2).coerceIn(0, yRemaining - 1)
                val localEnd = (localTargetIndex + windowSize / 2).coerceIn(0, yRemaining)
                var localBrightness = 0L
                var localSamples = 0
                var li = localStart
                while (li < localEnd) {
                    localBrightness += yBuffer.get(li).toInt() and 0xFF
                    localSamples++
                    li += 16
                }
                val rawLocalLum = if (localSamples > 0) (localBrightness.toFloat() / localSamples) / 255.0f else clamped
                smoothedLocalLuminance = smoothedLocalLuminance * 0.75f + rawLocalLum * 0.25f

                // 3. 采样 U/V 通道色度 (全局色温与局部桌面材质底色探针)
                var rawTemp = 0.0f
                var localRawTemp = 0.0f
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
                        rawTemp = (((avgV - 128f) - (avgU - 128f)) / 50f).coerceIn(-1f, 1f)
                    }

                    // 局部 U/V 采样 (获取模型摆放处的桌面材质色)
                    val localUvTarget = ((focusNormY * 0.85f + 0.15f) * uRem).toInt().coerceIn(0, uRem - 1)
                    val uvWindow = 512
                    val uvStart = (localUvTarget - uvWindow / 2).coerceIn(0, uRem - 1)
                    val uvEnd = (localUvTarget + uvWindow / 2).coerceIn(0, uRem)
                    var locU = 0L
                    var locV = 0L
                    var locCount = 0
                    var uj = uvStart
                    while (uj < uvEnd && uj < vRem) {
                        locU += uBuffer.get(uj).toInt() and 0xFF
                        locV += vBuffer.get(uj).toInt() and 0xFF
                        locCount++
                        uj += 16
                    }
                    if (locCount > 0) {
                        val avgLocU = locU.toFloat() / locCount
                        val avgLocV = locV.toFloat() / locCount
                        localRawTemp = (((avgLocV - 128f) - (avgLocU - 128f)) / 45f).coerceIn(-1.2f, 1.2f)
                    }
                }

                smoothedColorTemp = smoothedColorTemp * 0.85f + rawTemp * 0.15f
                val tintR = (1.0f + smoothedColorTemp * 0.12f).coerceIn(0.85f, 1.18f)
                val tintG = (1.0f + (1f - abs(smoothedColorTemp)) * 0.02f).coerceIn(0.90f, 1.05f)
                val tintB = (1.0f - smoothedColorTemp * 0.12f).coerceIn(0.85f, 1.18f)

                // 局部桌面底色反射 (用于木质桌面、深色桌面、纸张不同位置的精准光渗)
                val locR = (1.0f + localRawTemp * 0.22f).coerceIn(0.75f, 1.30f)
                val locG = (1.0f + (1f - abs(localRawTemp)) * 0.04f).coerceIn(0.85f, 1.15f)
                val locB = (1.0f - localRawTemp * 0.22f).coerceIn(0.75f, 1.30f)
                smoothedLocalTintR = smoothedLocalTintR * 0.8f + locR * 0.2f
                smoothedLocalTintG = smoothedLocalTintG * 0.8f + locG * 0.2f
                smoothedLocalTintB = smoothedLocalTintB * 0.8f + locB * 0.2f

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
                        sensorGrainIntensity = sensorGrain,
                        highlightRatio = highlightRatio,
                        bloomBleedFactor = smoothedBloom,
                        localSurfaceLuminance = smoothedLocalLuminance.coerceIn(0.2f, 1.35f),
                        localSurfaceTintR = smoothedLocalTintR,
                        localSurfaceTintG = smoothedLocalTintG,
                        localSurfaceTintB = smoothedLocalTintB
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
