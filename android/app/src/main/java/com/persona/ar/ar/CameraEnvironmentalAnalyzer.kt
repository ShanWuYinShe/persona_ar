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
    val localSurfaceTintB: Float = 1.0f,
    /** 主光源二维方向向量 (X: -1.0 左侧光源 ~ +1.0 右侧光源, Y: -1.0 上方光源 ~ +1.0 下方光源) */
    val lightDirectionX: Float = 0.0f,
    val lightDirectionY: Float = -0.5f,
    /** 主光源定向强度 (0.0f 全向漫射 ~ 1.0f 强直射台灯/日光，用于地面斜拉阴影与边缘高光) */
    val lightDirectionalStrength: Float = 0.2f,
    /** 落脚点桌面特征纹理置信度 (0.0f ~ 1.0f, 用于端侧零 GMS 视觉稳定性与光流融合) */
    val deskTextureConfidence: Float = 0.5f,
    /** 端侧零 GMS 局部桌面纹理光流防抖微位移 X/Y (像素，用于抵消手持微平移漂移) */
    val visualFlowOffsetX: Float = 0.0f,
    val visualFlowOffsetY: Float = 0.0f,
    /** 真实广角镜头边缘暗角自然失光衰减系数 (0.72f 边缘 ~ 1.0f 镜头中心) */
    val lensVignetteFactor: Float = 1.0f
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
    private var smoothedLightDirX = 0.0f
    private var smoothedLightDirY = -0.5f
    private var smoothedLightStrength = 0.2f
    private var smoothedTextureConfidence = 0.5f
    private var smoothedFlowX = 0f
    private var smoothedFlowY = 0f

    // 零 GMS 局部块匹配光流稳定器缓存 (Zero-GMS Visual Block-Matching Flow Stabilizer)
    private val tplDim = 8
    private val refTemplate = IntArray(tplDim * tplDim)
    private var hasRefTemplate = false
    private var lastRecordedAnchorX = 0.5f
    private var lastRecordedAnchorY = 0.65f
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

            // 1. 全局采样 Y 通道亮度 (0~255) 与高光强光源区域质心解算
            val step = 32
            var totalBrightness = 0L
            var sampleCount = 0
            var highLightCount = 0
            val highLightThreshold = 224
            var sumHighLightNormX = 0f
            var sumHighLightNormY = 0f

            val imgWidth = image.width.coerceAtLeast(1)
            val imgHeight = image.height.coerceAtLeast(1)

            var i = 0
            while (i < yRemaining) {
                val pixel = yBuffer.get(i).toInt() and 0xFF
                totalBrightness += pixel
                sampleCount++
                if (pixel >= highLightThreshold) {
                    highLightCount++
                    // 解算该高光像素在摄像头帧中的归一化二维坐标
                    val pixelX = ((i % imgWidth).toFloat() / imgWidth).coerceIn(0f, 1f)
                    val pixelY = ((i / imgWidth).toFloat() / imgHeight).coerceIn(0f, 1f)
                    sumHighLightNormX += pixelX
                    sumHighLightNormY += pixelY
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

                // 主光源方位解算：若检测到明显强光源 (台灯/窗户/显示器过曝点)，解算相对伴侣的入射矢量
                var rawLightDirX = 0.0f
                var rawLightDirY = -0.5f
                var rawLightStrength = 0.15f
                if (highLightCount > 4) {
                    val centroidX = sumHighLightNormX / highLightCount
                    val centroidY = sumHighLightNormY / highLightCount
                    val deltaX = (centroidX - focusNormX).coerceIn(-1.0f, 1.0f)
                    val deltaY = (centroidY - focusNormY).coerceIn(-1.0f, 1.0f)
                    rawLightDirX = deltaX
                    rawLightDirY = deltaY
                    rawLightStrength = (highlightRatio * 3.5f + 0.2f).coerceIn(0.2f, 1.0f)
                }
                smoothedLightDirX = smoothedLightDirX * 0.82f + rawLightDirX * 0.18f
                smoothedLightDirY = smoothedLightDirY * 0.82f + rawLightDirY * 0.18f
                smoothedLightStrength = smoothedLightStrength * 0.85f + rawLightStrength * 0.15f

                // 2. 局部桌面落脚点探针采样 (Local Surface Light Probe & Texture Variance)
                // 定位伴侣双足所在局部区域 (聚焦范围围绕 focusNormX, focusNormY)
                val localTargetIndex = ((focusNormY * 0.85f + 0.15f) * yRemaining).toInt().coerceIn(0, yRemaining - 1)
                val windowSize = 1024
                val localStart = (localTargetIndex - windowSize / 2).coerceIn(0, yRemaining - 1)
                val localEnd = (localTargetIndex + windowSize / 2).coerceIn(0, yRemaining)
                var localBrightness = 0L
                var localSamples = 0
                var textureGradientSum = 0L
                var prevPixel = -1
                var li = localStart
                while (li < localEnd) {
                    val p = yBuffer.get(li).toInt() and 0xFF
                    localBrightness += p
                    if (prevPixel != -1) {
                        textureGradientSum += kotlin.math.abs(p - prevPixel)
                    }
                    prevPixel = p
                    localSamples++
                    li += 16
                }
                val rawLocalLum = if (localSamples > 0) (localBrightness.toFloat() / localSamples) / 255.0f else clamped
                smoothedLocalLuminance = smoothedLocalLuminance * 0.75f + rawLocalLum * 0.25f

                // 计算落脚点桌面纹理置信度 (梯度越大，说明木纹、桌垫边缘、书本文理越清晰，空间锁死置信度越高)
                val avgGradient = if (localSamples > 1) textureGradientSum.toFloat() / (localSamples - 1) else 0f
                val rawTextureConf = (avgGradient / 35f).coerceIn(0.2f, 1.0f)
                smoothedTextureConfidence = smoothedTextureConfidence * 0.8f + rawTextureConf * 0.2f

                // 零 GMS 局部块匹配光流跟踪 (Zero-GMS Local Block-Matching Optical Flow)
                // 若用户挪动了锚点坐标，重置参考模板
                if (kotlin.math.abs(focusNormX - lastRecordedAnchorX) > 0.02f ||
                    kotlin.math.abs(focusNormY - lastRecordedAnchorY) > 0.02f) {
                    hasRefTemplate = false
                    lastRecordedAnchorX = focusNormX
                    lastRecordedAnchorY = focusNormY
                }

                var rawShiftX = 0f
                var rawShiftY = 0f
                val anchorCenterX = (focusNormX * imgWidth).toInt().coerceIn(tplDim, imgWidth - tplDim - 1)
                val anchorCenterY = (focusNormY * imgHeight).toInt().coerceIn(tplDim, imgHeight - tplDim - 1)

                if (!hasRefTemplate) {
                    // 记录当前落脚点的基准微模板
                    for (ty in 0 until tplDim) {
                        for (tx in 0 until tplDim) {
                            val idx = (anchorCenterY - tplDim / 2 + ty) * imgWidth + (anchorCenterX - tplDim / 2 + tx)
                            if (idx in 0 until yRemaining) {
                                refTemplate[ty * tplDim + tx] = yBuffer.get(idx).toInt() and 0xFF
                            }
                        }
                    }
                    hasRefTemplate = true
                    smoothedFlowX = 0f
                    smoothedFlowY = 0f
                } else {
                    // 在中心周围 ±4 像素内做 SAD 块匹配搜索
                    var minSAD = Long.MAX_VALUE
                    var bestDx = 0
                    var bestDy = 0
                    val searchRadius = 4
                    for (dy in -searchRadius..searchRadius) {
                        for (dx in -searchRadius..searchRadius) {
                            var currentSAD = 0L
                            for (ty in 0 until tplDim) {
                                val py = anchorCenterY - tplDim / 2 + ty + dy
                                val rowOffset = py * imgWidth
                                for (tx in 0 until tplDim) {
                                    val px = anchorCenterX - tplDim / 2 + tx + dx
                                    val idx = rowOffset + px
                                    if (idx in 0 until yRemaining) {
                                        val curVal = yBuffer.get(idx).toInt() and 0xFF
                                        currentSAD += kotlin.math.abs(curVal - refTemplate[ty * tplDim + tx])
                                    }
                                }
                            }
                            if (currentSAD < minSAD) {
                                minSAD = currentSAD
                                bestDx = dx
                                bestDy = dy
                            }
                        }
                    }
                    rawShiftX = bestDx.toFloat()
                    rawShiftY = bestDy.toFloat()
                }

                // 结合桌面纹理置信度进行平滑滤波
                val flowWeight = (smoothedTextureConfidence * 0.45f).coerceIn(0.1f, 0.45f)
                smoothedFlowX = smoothedFlowX * (1f - flowWeight) + rawShiftX * flowWeight
                smoothedFlowY = smoothedFlowY * (1f - flowWeight) + rawShiftY * flowWeight

                // 真实广角镜头边缘暗角自然失光 (Lens Optical Vignetting)
                val radialDistNorm = kotlin.math.sqrt(
                    (focusNormX - 0.5f) * (focusNormX - 0.5f) + (focusNormY - 0.5f) * (focusNormY - 0.5f)
                ) * 2.0f
                val vignetteFactor = (1.0f - radialDistNorm * radialDistNorm * 0.18f).coerceIn(0.75f, 1.0f)

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
                        localSurfaceTintB = smoothedLocalTintB,
                        lightDirectionX = smoothedLightDirX,
                        lightDirectionY = smoothedLightDirY,
                        lightDirectionalStrength = smoothedLightStrength,
                        deskTextureConfidence = smoothedTextureConfidence,
                        visualFlowOffsetX = smoothedFlowX,
                        visualFlowOffsetY = smoothedFlowY,
                        lensVignetteFactor = vignetteFactor
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
