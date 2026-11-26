package com.persona.ar.ar

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 现实环境光学特征剖面（基于屏幕对齐坐标精确采样）
 */
data class EnvironmentalOpticalProfile(
    val luminance: Float = 1.0f,
    val isLowLight: Boolean = false,
    val colorTemp: Float = 0.0f, // -1.0f(冷蓝) ~ 0.0f(中性) ~ +1.0f(暖黄)
    val tintR: Float = 1.0f,
    val tintG: Float = 1.0f,
    val tintB: Float = 1.0f,
    /** 6 方向真实环境光色（用于动态 IBL 球谐与环境反射贴图） */
    val envTopR: Float = 1.0f,
    val envTopG: Float = 1.0f,
    val envTopB: Float = 1.0f,
    val envBottomR: Float = 1.0f,
    val envBottomG: Float = 0.96f,
    val envBottomB: Float = 0.92f,
    val envLeftR: Float = 1.0f,
    val envLeftG: Float = 1.0f,
    val envLeftB: Float = 1.0f,
    val envRightR: Float = 1.0f,
    val envRightG: Float = 1.0f,
    val envRightB: Float = 1.0f,
    val sensorGrainIntensity: Float = 0.0f,
    val highlightRatio: Float = 0.0f,
    val bloomBleedFactor: Float = 0.0f,
    /** 伴侣脚底所在现实桌面局部照度与 RGB 反射色 */
    val localSurfaceLuminance: Float = 1.0f,
    val localSurfaceTintR: Float = 1.0f,
    val localSurfaceTintG: Float = 1.0f,
    val localSurfaceTintB: Float = 1.0f,
    /** 屏幕空间主光源方向向量 (-1..+1) 与定向强度 */
    val lightDirectionX: Float = 0.0f,
    val lightDirectionY: Float = -0.5f,
    val lightDirectionalStrength: Float = 0.25f,
    val deskTextureConfidence: Float = 0.5f,
    val lensVignetteFactor: Float = 1.0f
)

/**
 * 单帧视觉稀疏光流锚点追踪观测结果
 */
data class VisualAnchorObservation(
    val trackedScreenX: Float,
    val trackedScreenY: Float,
    val scaleRatio: Float,
    val confidence: Float,
    val inlierCount: Int,
    val isLost: Boolean,
    val keyframeId: Int = 0,
    val keyframeScaleRatio: Float = scaleRatio
)

/**
 * 现实环境全光谱光学探针 & 多点 IMU 先验稀疏光流追踪器。
 *
 * 关键架构升级：
 * 1. 所有采样均经过 `SpatialMath3D.screenNormToBufferNorm`，严格校正传感器旋转 (0/90/180/270°)
 *    与 PreviewView FILL_CENTER 裁剪，消除旧版「主光源歪 90° / 桌面取样错位」的 Bug；
 * 2. 委托 `KeyframeVisualTracker` 在紧凑灰度网格 (96×GH) 上运行关键帧锁定 + 2 点 RANSAC 稀疏光流，
 *    静止持握或桌面支架放置时零累加漂移，支持绕模型 360° 走动观察。
 */
class CameraEnvironmentalAnalyzer(
    private val onLightEstimated: (luminance: Float, isLowLight: Boolean) -> Unit = { _, _ -> },
    private val onOpticalProfileEstimated: ((EnvironmentalOpticalProfile) -> Unit)? = null,
    private val imuBasisProvider: ((FloatArray, FloatArray, FloatArray) -> Boolean)? = null,
    private val onVisualAnchorObserved: ((VisualAnchorObservation, FloatArray, FloatArray, FloatArray) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    @Volatile var focusNormX: Float = 0.5f
    @Volatile var focusNormY: Float = 0.65f
    @Volatile var viewportAspect: Float = 1080f / 2340f
    @Volatile var effectiveVerticalFovDeg: Float = 62f
    @Volatile var isTrackingActive: Boolean = false
    @Volatile var isDraggingPaused: Boolean = false

    @Volatile private var pendingResetFeatures: Boolean = false
    @Volatile private var resetTargetX: Float = 0.5f
    @Volatile private var resetTargetY: Float = 0.65f

    /** 请求在下一帧围绕屏幕坐标 (normX, normY) 重新初始化桌面视觉特征点 */
    fun requestResetFeatures(normX: Float, normY: Float) {
        resetTargetX = normX.coerceIn(0.05f, 0.95f)
        resetTargetY = normY.coerceIn(0.10f, 0.95f)
        pendingResetFeatures = true
    }

    /** 清空视觉追踪特征（重置锚点时调用） */
    fun clearFeatures() {
        synchronized(trackerLock) {
            keyframeTracker.clear()
            pendingResetFeatures = false
        }
    }

    private val trackerLock = Any()

    // ---- 屏幕对齐紧凑灰度金字塔缓存 ----
    private val gridW = 96
    private var gridH = 208
    private var coarseW = 48
    private var coarseH = 104
    private var currGray = IntArray(gridW * gridH)
    private var coarseGray = IntArray(coarseW * coarseH)

    // ---- 关键帧锁定稀疏光流引擎 ----
    private val keyframeTracker = KeyframeVisualTracker(gridW = gridW)

    // IMU 当前帧世界基向量缓存
    private val currRight = FloatArray(3)
    private val currUp = FloatArray(3)
    private val currForward = FloatArray(3)

    // ---- 光学环境平滑滤波缓存 ----
    private var frameCounter = 0L
    private var smoothedLum = 0.78f
    private var smoothedLocalLum = 0.78f
    private var smoothedTemp = 0.0f
    private var smoothedBloom = 0.0f
    private var smoothedLightDirX = 0.0f
    private var smoothedLightDirY = -0.5f
    private var smoothedLightStrength = 0.25f
    private var smoothedTextureConf = 0.5f

    private var smTopR = 1f; private var smTopG = 1f; private var smTopB = 1f
    private var smBotR = 1f; private var smBotG = 0.96f; private var smBotB = 0.92f
    private var smLeftR = 1f; private var smLeftG = 1f; private var smLeftB = 1f
    private var smRightR = 1f; private var smRightG = 1f; private var smRightB = 1f
    private var smLocalR = 1f; private var smLocalG = 1f; private var smLocalB = 1f

    override fun analyze(image: ImageProxy) {
        try {
            val imgW = image.width.coerceAtLeast(1)
            val imgH = image.height.coerceAtLeast(1)
            val rotation = image.imageInfo.rotationDegrees
            val aspect = viewportAspect.coerceIn(0.35f, 2.5f)
            val targetGridH = (gridW / aspect).toInt().coerceIn(110, 224)
            ensureGridSize(targetGridH)

            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            val yCap = yBuf.limit()

            // 1. 构建屏幕对齐灰度图 (每帧执行，极低开销 <0.5ms)
            for (gy in 0 until gridH) {
                val sy = (gy + 0.5f) / gridH
                val rowOff = gy * gridW
                for (gx in 0 until gridW) {
                    val sx = (gx + 0.5f) / gridW
                    val (u, v) = SpatialMath3D.screenNormToBufferNorm(sx, sy, imgW, imgH, rotation, aspect)
                    val bx = (u * (imgW - 1)).toInt().coerceIn(0, imgW - 1)
                    val by = (v * (imgH - 1)).toInt().coerceIn(0, imgH - 1)
                    val idx = by * yRowStride + bx * yPixStride
                    currGray[rowOff + gx] = if (idx in 0 until yCap) (yBuf.get(idx).toInt() and 0xFF) else 128
                }
            }

            // 生成 2x2 粗金字塔
            for (cy in 0 until coarseH) {
                val y0 = (cy * 2).coerceAtMost(gridH - 1)
                val y1 = (y0 + 1).coerceAtMost(gridH - 1)
                val r0 = y0 * gridW
                val r1 = y1 * gridW
                val cr = cy * coarseW
                for (cx in 0 until coarseW) {
                    val x0 = (cx * 2).coerceAtMost(gridW - 1)
                    val x1 = (x0 + 1).coerceAtMost(gridW - 1)
                    coarseGray[cr + cx] = (currGray[r0 + x0] + currGray[r0 + x1] + currGray[r1 + x0] + currGray[r1 + x1]) shr 2
                }
            }

            // 2. 视觉锚点 6DoF 关键帧锁定稀疏光流追踪 (全帧率 30fps 运行)
            val hasImu = imuBasisProvider?.invoke(currRight, currUp, currForward) == true
            if (isTrackingActive && !isDraggingPaused && hasImu) {
                runVisualTracking(aspect, effectiveVerticalFovDeg)
            }

            // 3. 环境光照、主光源方位与 6 向色彩探针 (每 3 帧采样一次，10Hz 平滑更新)
            if (frameCounter++ % 3L == 0L) {
                analyzeEnvironmentOptics(image, imgW, imgH, rotation, aspect)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun ensureGridSize(targetH: Int) {
        if (targetH == gridH) return
        synchronized(trackerLock) {
            gridH = targetH
            coarseH = targetH / 2
            currGray = IntArray(gridW * gridH)
            coarseGray = IntArray(coarseW * coarseH)
            keyframeTracker.onGridResized()
        }
    }

    // ========================================================================= 关键帧锁定 IMU 先验稀疏光流

    private fun runVisualTracking(aspect: Float, vFovDeg: Float) {
        synchronized(trackerLock) {
            val resetNow = pendingResetFeatures
            pendingResetFeatures = false
            val obs = keyframeTracker.trackFrame(
                gray = currGray,
                gridH = gridH,
                aspect = aspect,
                vFovDeg = vFovDeg,
                currRight = currRight,
                currUp = currUp,
                currForward = currForward,
                pendingReset = resetNow,
                resetNormX = resetTargetX,
                resetNormY = resetTargetY
            )
            if (obs != null) {
                onVisualAnchorObserved?.invoke(
                    obs,
                    currRight.copyOf(),
                    currUp.copyOf(),
                    currForward.copyOf()
                )
            }
        }
    }

    // ========================================================================= 屏幕对齐光学与 6 向色度分析

    private fun analyzeEnvironmentOptics(
        image: ImageProxy,
        imgW: Int,
        imgH: Int,
        rotation: Int,
        aspect: Float
    ) {
        var sumY = 0L
        var countY = 0
        var hiCount = 0
        var hiSumX = 0f
        var hiSumY = 0f
        val hiThreshold = 222

        // 局部桌面纹理与亮度采样（以锚点屏幕坐标为中心 ±10%）
        var localY = 0L
        var localGrad = 0L
        var localSamples = 0
        val fx = focusNormX.coerceIn(0.08f, 0.92f)
        val fy = focusNormY.coerceIn(0.12f, 0.92f)

        for (gy in 0 until gridH step 2) {
            val sy = (gy + 0.5f) / gridH
            val rowOff = gy * gridW
            val inLocalY = abs(sy - fy) <= 0.10f
            for (gx in 0 until gridW step 2) {
                val sx = (gx + 0.5f) / gridW
                val p = currGray[rowOff + gx]
                sumY += p
                countY++
                if (p >= hiThreshold) {
                    hiCount++
                    hiSumX += sx
                    hiSumY += sy
                }
                if (inLocalY && abs(sx - fx) <= 0.12f) {
                    localY += p
                    if (gx + 2 < gridW) localGrad += abs(currGray[rowOff + gx + 2] - p)
                    localSamples++
                }
            }
        }

        if (countY == 0) return
        val rawLum = (sumY.toFloat() / countY) / 255f
        smoothedLum = smoothedLum * 0.80f + rawLum * 0.20f
        val clampedLum = smoothedLum.coerceIn(0.16f, 1.30f)
        val isLowLight = clampedLum < 0.34f

        val rawLocalLum = if (localSamples > 0) (localY.toFloat() / localSamples) / 255f else clampedLum
        smoothedLocalLum = smoothedLocalLum * 0.75f + rawLocalLum * 0.25f

        val avgGrad = if (localSamples > 0) localGrad.toFloat() / localSamples else 0f
        val rawTexConf = (avgGrad / 24f).coerceIn(0.18f, 1.0f)
        smoothedTextureConf = smoothedTextureConf * 0.80f + rawTexConf * 0.20f

        val highlightRatio = (hiCount.toFloat() / countY).coerceIn(0f, 1f)
        val rawBloom = (highlightRatio * 2.5f + (clampedLum - 0.85f).coerceAtLeast(0f) * 1.1f).coerceIn(0f, 1f)
        smoothedBloom = smoothedBloom * 0.85f + rawBloom * 0.15f

        // 屏幕空间主光源方向（现已完全对齐屏幕方向，不会歪 90°！）
        var rawDirX = 0f
        var rawDirY = -0.55f
        var rawStrength = 0.20f
        if (hiCount > 4) {
            val cx = hiSumX / hiCount
            val cy = hiSumY / hiCount
            rawDirX = (cx - fx).coerceIn(-1f, 1f)
            rawDirY = (cy - fy).coerceIn(-1f, 1f)
            rawStrength = (highlightRatio * 3.8f + 0.22f).coerceIn(0.22f, 1f)
        }
        smoothedLightDirX = smoothedLightDirX * 0.82f + rawDirX * 0.18f
        smoothedLightDirY = smoothedLightDirY * 0.82f + rawDirY * 0.18f
        smoothedLightStrength = smoothedLightStrength * 0.85f + rawStrength * 0.15f

        // 采样 U/V 计算真实 RGB 色渗：全局 + 顶/底/左/右 + 局部桌面落脚区
        var gR = 0f; var gG = 0f; var gB = 0f; var gN = 0
        var tR = 0f; var tG = 0f; var tB = 0f; var tN = 0
        var bR = 0f; var bG = 0f; var bB = 0f; var bN = 0
        var lR = 0f; var lG = 0f; var lB = 0f; var lN = 0
        var rR = 0f; var rG = 0f; var rB = 0f; var rN = 0
        var locR = 0f; var locG = 0f; var locB = 0f; var locN = 0
        var tempSum = 0f

        if (image.planes.size >= 3) {
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val uRowStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride
            val uLimit = uBuf.limit()
            val vLimit = vBuf.limit()

            for (gy in 2 until gridH - 2 step 4) {
                val sy = (gy + 0.5f) / gridH
                val rowOff = gy * gridW
                for (gx in 2 until gridW - 2 step 4) {
                    val sx = (gx + 0.5f) / gridW
                    val (nu, nv) = SpatialMath3D.screenNormToBufferNorm(sx, sy, imgW, imgH, rotation, aspect)
                    val bx = (nu * (imgW - 1)).toInt().coerceIn(0, imgW - 1)
                    val by = (nv * (imgH - 1)).toInt().coerceIn(0, imgH - 1)
                    val uvX = bx / 2
                    val uvY = by / 2
                    val uIdx = uvY * uRowStride + uvX * uPixStride
                    val vIdx = uvY * vRowStride + uvX * vPixStride
                    if (uIdx !in 0 until uLimit || vIdx !in 0 until vLimit) continue

                    val yVal = currGray[rowOff + gx].toFloat()
                    val uVal = (uBuf.get(uIdx).toInt() and 0xFF) - 128f
                    val vVal = (vBuf.get(vIdx).toInt() and 0xFF) - 128f

                    // BT.601 YUV -> RGB (0..255)
                    val c = yVal - 16f
                    val rr = (1.164f * c + 1.596f * vVal).coerceIn(1f, 255f)
                    val gg = (1.164f * c - 0.392f * uVal - 0.813f * vVal).coerceIn(1f, 255f)
                    val bb = (1.164f * c + 2.017f * uVal).coerceIn(1f, 255f)

                    // 归一化色度增益（剥离纯亮度，只保留色彩倾向）
                    val avg = ((rr + gg + bb) / 3f).coerceAtLeast(18f)
                    val nr = (rr / avg).coerceIn(0.65f, 1.40f)
                    val ng = (gg / avg).coerceIn(0.65f, 1.40f)
                    val nb = (bb / avg).coerceIn(0.65f, 1.40f)

                    gR += nr; gG += ng; gB += nb; gN++
                    tempSum += ((vVal - uVal) / 55f).coerceIn(-1f, 1f)

                    if (sy < 0.30f) { tR += nr; tG += ng; tB += nb; tN++ }
                    if (sy > 0.65f) { bR += nr; bG += ng; bB += nb; bN++ }
                    if (sx < 0.30f) { lR += nr; lG += ng; lB += nb; lN++ }
                    if (sx > 0.70f) { rR += nr; rG += ng; rB += nb; rN++ }
                    if (abs(sx - fx) <= 0.14f && abs(sy - fy) <= 0.12f) {
                        locR += nr; locG += ng; locB += nb; locN++
                    }
                }
            }
        }

        if (gN > 0) {
            val rawTemp = (tempSum / gN).coerceIn(-1f, 1f)
            smoothedTemp = smoothedTemp * 0.84f + rawTemp * 0.16f

            fun blendTint(old: Float, sum: Float, cnt: Int, fallback: Float): Float {
                val target = if (cnt > 0) (sum / cnt).coerceIn(0.68f, 1.38f) else fallback
                return old * 0.80f + target * 0.20f
            }
            val globR = (gR / gN).coerceIn(0.75f, 1.30f)
            val globG = (gG / gN).coerceIn(0.75f, 1.30f)
            val globB = (gB / gN).coerceIn(0.75f, 1.30f)

            smTopR = blendTint(smTopR, tR, tN, globR)
            smTopG = blendTint(smTopG, tG, tN, globG)
            smTopB = blendTint(smTopB, tB, tN, globB)

            smBotR = blendTint(smBotR, bR, bN, globR)
            smBotG = blendTint(smBotG, bG, bN, globG)
            smBotB = blendTint(smBotB, bB, bN, globB)

            smLeftR = blendTint(smLeftR, lR, lN, globR)
            smLeftG = blendTint(smLeftG, lG, lN, globG)
            smLeftB = blendTint(smLeftB, lB, lN, globB)

            smRightR = blendTint(smRightR, rR, rN, globR)
            smRightG = blendTint(smRightG, rG, rN, globG)
            smRightB = blendTint(smRightB, rB, rN, globB)

            smLocalR = blendTint(smLocalR, locR, locN, smBotR)
            smLocalG = blendTint(smLocalG, locG, locN, smBotG)
            smLocalB = blendTint(smLocalB, locB, locN, smBotB)

            val radialDist = sqrt((fx - 0.5f) * (fx - 0.5f) + (fy - 0.5f) * (fy - 0.5f)) * 2f
            val vignette = (1f - radialDist * radialDist * 0.16f).coerceIn(0.78f, 1f)
            val sensorGrain = ((0.38f - clampedLum) * 2.4f).coerceIn(0f, 1f)

            onLightEstimated(clampedLum, isLowLight)
            onOpticalProfileEstimated?.invoke(
                EnvironmentalOpticalProfile(
                    luminance = clampedLum,
                    isLowLight = isLowLight,
                    colorTemp = smoothedTemp,
                    tintR = globR,
                    tintG = globG,
                    tintB = globB,
                    envTopR = smTopR,
                    envTopG = smTopG,
                    envTopB = smTopB,
                    envBottomR = smBotR,
                    envBottomG = smBotG,
                    envBottomB = smBotB,
                    envLeftR = smLeftR,
                    envLeftG = smLeftG,
                    envLeftB = smLeftB,
                    envRightR = smRightR,
                    envRightG = smRightG,
                    envRightB = smRightB,
                    sensorGrainIntensity = sensorGrain,
                    highlightRatio = highlightRatio,
                    bloomBleedFactor = smoothedBloom,
                    localSurfaceLuminance = smoothedLocalLum.coerceIn(0.2f, 1.35f),
                    localSurfaceTintR = smLocalR,
                    localSurfaceTintG = smLocalG,
                    localSurfaceTintB = smLocalB,
                    lightDirectionX = smoothedLightDirX,
                    lightDirectionY = smoothedLightDirY,
                    lightDirectionalStrength = smoothedLightStrength,
                    deskTextureConfidence = smoothedTextureConf,
                    lensVignetteFactor = vignette
                )
            )
        }
    }
}
