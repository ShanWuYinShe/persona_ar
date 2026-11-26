package com.persona.ar.ar

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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
    val isLost: Boolean
)

/**
 * 现实环境全光谱光学探针 & 多点 IMU 先验稀疏光流追踪器。
 *
 * 关键架构升级：
 * 1. 所有采样均经过 `SpatialMath3D.screenNormToBufferNorm`，严格校正传感器旋转 (0/90/180/270°)
 *    与 PreviewView FILL_CENTER 裁剪，消除旧版「主光源歪 90° / 桌面取样错位」的 Bug；
 * 2. 在屏幕对齐的紧凑灰度网格 (96×GH) 上每帧运行 9 点 2D 角点光流跟踪 + IMU 旋转先验预测 +
 *    2D Similarity 变换拟合 + 自适应关键帧刷新，支持用户拿着手机绕模型 360° 走动观察。
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
            hasInitializedFeatures = false
            featureCount = 0
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

    // ---- 9 点稀疏角点特征状态 ----
    private val maxFeatures = 9
    private val patchRadius = 3 // 7x7 patch
    private val patchSize = 7
    private val patchArea = patchSize * patchSize
    private var featureCount = 0
    private var hasInitializedFeatures = false
    private val featX = FloatArray(maxFeatures) // 网格坐标 (0..gridW)
    private val featY = FloatArray(maxFeatures)
    private val featPatches = Array(maxFeatures) { FloatArray(patchArea) }
    private val featPatchNorm = FloatArray(maxFeatures)
    private val obsX = FloatArray(maxFeatures)
    private val obsY = FloatArray(maxFeatures)
    private val obsOk = BooleanArray(maxFeatures)
    private var anchorGridX = gridW * 0.5f
    private var anchorGridY = gridH * 0.65f
    private var framesSinceKeyframe = 0
    private var cumulativeRotDeg = 0f

    // IMU 上一帧世界基向量缓存
    private var hasPrevImu = false
    private val prevRight = FloatArray(3)
    private val prevUp = FloatArray(3)
    private val prevForward = FloatArray(3)
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

            // 2. 视觉锚点 6DoF 稀疏光流追踪 (全帧率 30fps 运行)
            val hasImu = imuBasisProvider?.invoke(currRight, currUp, currForward) == true
            if (isTrackingActive && !isDraggingPaused && hasImu) {
                runVisualTracking(aspect, effectiveVerticalFovDeg)
            } else if (hasImu) {
                System.arraycopy(currRight, 0, prevRight, 0, 3)
                System.arraycopy(currUp, 0, prevUp, 0, 3)
                System.arraycopy(currForward, 0, prevForward, 0, 3)
                hasPrevImu = true
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
            hasInitializedFeatures = false
            featureCount = 0
        }
    }

    // ========================================================================= 9 点 IMU 先验稀疏光流

    private fun runVisualTracking(aspect: Float, vFovDeg: Float) {
        synchronized(trackerLock) {
            if (pendingResetFeatures || !hasInitializedFeatures) {
                pendingResetFeatures = false
                anchorGridX = resetTargetX * gridW
                anchorGridY = resetTargetY * gridH
                initFeaturesAround(anchorGridX, anchorGridY)
                System.arraycopy(currRight, 0, prevRight, 0, 3)
                System.arraycopy(currUp, 0, prevUp, 0, 3)
                System.arraycopy(currForward, 0, prevForward, 0, 3)
                hasPrevImu = true
                framesSinceKeyframe = 0
                cumulativeRotDeg = 0f
                if (featureCount >= 3) {
                    onVisualAnchorObserved?.invoke(
                        VisualAnchorObservation(
                            trackedScreenX = anchorGridX / gridW,
                            trackedScreenY = anchorGridY / gridH,
                            scaleRatio = 1.0f,
                            confidence = 1.0f,
                            inlierCount = featureCount,
                            isLost = false
                        ),
                        currRight.copyOf(), currUp.copyOf(), currForward.copyOf()
                    )
                }
                return
            }

            if (featureCount < 3 || !hasPrevImu) {
                System.arraycopy(currRight, 0, prevRight, 0, 3)
                System.arraycopy(currUp, 0, prevUp, 0, 3)
                System.arraycopy(currForward, 0, prevForward, 0, 3)
                hasPrevImu = true
                return
            }

            // 计算上一帧到本帧的纯旋转预测与累加旋转角
            val dotFwd = (prevForward[0] * currForward[0] + prevForward[1] * currForward[1] + prevForward[2] * currForward[2]).coerceIn(-1f, 1f)
            val stepAngleDeg = Math.toDegrees(kotlin.math.acos(dotFwd.toDouble())).toFloat()
            cumulativeRotDeg += stepAngleDeg

            val tanHalfFov = tan(Math.toRadians(vFovDeg.toDouble()) * 0.5).toFloat()
            var matchedCount = 0

            for (k in 0 until featureCount) {
                // 用前一帧 IMU 射线投影到当前帧 IMU，得到纯旋转先验位置
                val sxPrev = featX[k] / gridW
                val syPrev = featY[k] / gridH
                val ndcX = 2f * sxPrev - 1f
                val ndcY = 1f - 2f * syPrev
                val rx = ndcX * tanHalfFov * aspect
                val ry = ndcY * tanHalfFov
                val wx = rx * prevRight[0] + ry * prevUp[0] + prevForward[0]
                val wy = rx * prevRight[1] + ry * prevUp[1] + prevForward[1]
                val wz = rx * prevRight[2] + ry * prevUp[2] + prevForward[2]

                val vx = wx * currRight[0] + wy * currRight[1] + wz * currRight[2]
                val vy = wx * currUp[0] + wy * currUp[1] + wz * currUp[2]
                val vz = wx * currForward[0] + wy * currForward[1] + wz * currForward[2]

                if (vz <= 0.1f) {
                    obsOk[k] = false
                    continue
                }
                val predGridX = ((vx / (vz * tanHalfFov * aspect) + 1f) * 0.5f) * gridW
                val predGridY = ((1f - vy / (vz * tanHalfFov)) * 0.5f) * gridH

                if (predGridX < patchRadius + 2 || predGridX >= gridW - patchRadius - 2 ||
                    predGridY < patchRadius + 2 || predGridY >= gridH - patchRadius - 2
                ) {
                    obsOk[k] = false
                    continue
                }

                val matched = matchFeatureZncc(k, predGridX, predGridY)
                obsOk[k] = matched
                if (matched) matchedCount++
            }

            if (matchedCount >= 3) {
                val fit = fitSimilarityAndTransformAnchor()
                if (fit != null) {
                    val (newAx, newAy, scaleRatio, inliers, avgResidual) = fit
                    val newNormX = newAx / gridW
                    val newNormY = newAy / gridH
                    val inBounds = newNormX in -0.25f..1.25f && newNormY in -0.25f..1.25f

                    if (inliers >= 3 && inBounds) {
                        anchorGridX = newAx
                        anchorGridY = newAy
                        // 更新特征点为最新观测位置
                        for (k in 0 until featureCount) {
                            if (obsOk[k]) {
                                featX[k] = obsX[k]
                                featY[k] = obsY[k]
                            }
                        }
                        framesSinceKeyframe++

                        // Keyframe hand-off：转角超过 8° 或连续跟了 8 帧或部分特征出画时原地刷新模板，支持无限 360° 环绕
                        val outOfMarginCount = (0 until featureCount).count {
                            !obsOk[it] || featX[it] < 8f || featX[it] > gridW - 8f || featY[it] < 8f || featY[it] > gridH - 8f
                        }
                        if (avgResidual < 1.8f && (cumulativeRotDeg > 8f || framesSinceKeyframe >= 8 || outOfMarginCount >= 3)) {
                            refreshOrReplaceFeatures(anchorGridX, anchorGridY)
                            framesSinceKeyframe = 0
                            cumulativeRotDeg = 0f
                        }

                        val conf = (inliers.toFloat() / maxFeatures.toFloat()).coerceIn(0.35f, 1.0f)
                        onVisualAnchorObserved?.invoke(
                            VisualAnchorObservation(
                                trackedScreenX = newNormX,
                                trackedScreenY = newNormY,
                                scaleRatio = scaleRatio.coerceIn(0.92f, 1.08f),
                                confidence = conf,
                                inlierCount = inliers,
                                isLost = false
                            ),
                            currRight.copyOf(), currUp.copyOf(), currForward.copyOf()
                        )
                    } else {
                        emitLostObservation()
                    }
                } else {
                    emitLostObservation()
                }
            } else {
                emitLostObservation()
            }

            System.arraycopy(currRight, 0, prevRight, 0, 3)
            System.arraycopy(currUp, 0, prevUp, 0, 3)
            System.arraycopy(currForward, 0, prevForward, 0, 3)
            hasPrevImu = true
        }
    }

    private fun emitLostObservation() {
        onVisualAnchorObserved?.invoke(
            VisualAnchorObservation(
                trackedScreenX = anchorGridX / gridW,
                trackedScreenY = anchorGridY / gridH,
                scaleRatio = 1.0f,
                confidence = 0f,
                inlierCount = 0,
                isLost = true
            ),
            currRight.copyOf(), currUp.copyOf(), currForward.copyOf()
        )
    }

    private fun initFeaturesAround(cx: Float, cy: Float) {
        featureCount = 0
        hasInitializedFeatures = true

        data class Candidate(val x: Int, val y: Int, val score: Float)
        val candidates = ArrayList<Candidate>(128)
        val margin = patchRadius + 3
        val minX = max(margin, (cx - gridW * 0.36f).toInt())
        val maxX = min(gridW - margin - 1, (cx + gridW * 0.36f).toInt())
        val minY = max(margin + (gridH * 0.12f).toInt(), (cy - gridH * 0.24f).toInt())
        val maxY = min(gridH - margin - 1, (cy + gridH * 0.24f).toInt())

        for (y in minY..maxY step 2) {
            for (x in minX..maxX step 2) {
                val cornerScore = computeCornerScore(x, y)
                if (cornerScore > 140f) {
                    // 优先靠近锚点的清晰纹理点
                    val dist = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                    val weight = 1f / (1f + dist * 0.04f)
                    candidates.add(Candidate(x, y, cornerScore * weight))
                }
            }
        }
        candidates.sortByDescending { it.score }

        val minSepSq = 8f * 8f
        for (c in candidates) {
            if (featureCount >= maxFeatures) break
            var tooClose = false
            for (i in 0 until featureCount) {
                val dx = featX[i] - c.x
                val dy = featY[i] - c.y
                if (dx * dx + dy * dy < minSepSq) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) {
                extractPatch(c.x, c.y, featureCount)
                featureCount++
            }
        }
    }

    private fun refreshOrReplaceFeatures(cx: Float, cy: Float) {
        // 先保留仍在清晰范围内的有效特征更新其 patch，空缺名额补选新角点
        var kept = 0
        for (k in 0 until featureCount) {
            if (obsOk[k]) {
                val ix = obsX[k].toInt()
                val iy = obsY[k].toInt()
                if (ix in (patchRadius + 4)..(gridW - patchRadius - 5) &&
                    iy in (patchRadius + 4)..(gridH - patchRadius - 5) &&
                    computeCornerScore(ix, iy) > 100f
                ) {
                    if (kept != k) {
                        featX[kept] = obsX[k]
                        featY[kept] = obsY[k]
                    }
                    extractPatch(ix, iy, kept)
                    kept++
                }
            }
        }
        featureCount = kept
        if (featureCount < maxFeatures) {
            data class Candidate(val x: Int, val y: Int, val score: Float)
            val candidates = ArrayList<Candidate>(96)
            val margin = patchRadius + 4
            val minX = max(margin, (cx - gridW * 0.36f).toInt())
            val maxX = min(gridW - margin - 1, (cx + gridW * 0.36f).toInt())
            val minY = max(margin + (gridH * 0.12f).toInt(), (cy - gridH * 0.24f).toInt())
            val maxY = min(gridH - margin - 1, (cy + gridH * 0.24f).toInt())
            for (y in minY..maxY step 3) {
                for (x in minX..maxX step 3) {
                    val s = computeCornerScore(x, y)
                    if (s > 140f) candidates.add(Candidate(x, y, s))
                }
            }
            candidates.sortByDescending { it.score }
            val minSepSq = 8f * 8f
            for (c in candidates) {
                if (featureCount >= maxFeatures) break
                var tooClose = false
                for (i in 0 until featureCount) {
                    val dx = featX[i] - c.x
                    val dy = featY[i] - c.y
                    if (dx * dx + dy * dy < minSepSq) { tooClose = true; break }
                }
                if (!tooClose) {
                    extractPatch(c.x, c.y, featureCount)
                    featureCount++
                }
            }
        }
    }

    /** 2D Harris 风格角点响应（剔除只有单轴梯度的直线边缘，避免光流沿边缘滑动） */
    private fun computeCornerScore(cx: Int, cy: Int): Float {
        var sxx = 0f
        var syy = 0f
        for (dy in -1..1) {
            val r = (cy + dy) * gridW
            for (dx in -1..1) {
                val x = cx + dx
                val gx = (currGray[r + x + 1] - currGray[r + x - 1]).toFloat()
                val gy = (currGray[(cy + dy + 1) * gridW + x] - currGray[(cy + dy - 1) * gridW + x]).toFloat()
                sxx += gx * gx
                syy += gy * gy
            }
        }
        return min(sxx, syy)
    }

    private fun extractPatch(cx: Int, cy: Int, idx: Int) {
        featX[idx] = cx.toFloat()
        featY[idx] = cy.toFloat()
        val dst = featPatches[idx]
        var sum = 0f
        var p = 0
        for (dy in -patchRadius..patchRadius) {
            val r = (cy + dy) * gridW
            for (dx in -patchRadius..patchRadius) {
                val v = currGray[r + cx + dx].toFloat()
                dst[p++] = v
                sum += v
            }
        }
        val mean = sum / patchArea
        var normSq = 0f
        for (i in 0 until patchArea) {
            val d = dst[i] - mean
            dst[i] = d
            normSq += d * d
        }
        featPatchNorm[idx] = sqrt(normSq).coerceAtLeast(1e-3f)
    }

    /**
     * 先在 2x 粗层 ±5 格初搜，再在细层 ±2 格用 ZNCC 精匹配 + 亚像素抛物线拟合
     */
    private fun matchFeatureZncc(featIdx: Int, predX: Float, predY: Float): Boolean {
        val tmpl = featPatches[featIdx]
        val tmplNorm = featPatchNorm[featIdx]
        if (tmplNorm < 18f) return false // 纹理太弱的块直接放弃

        // 粗层搜索（在细网格以步长 2 围绕预测中心 ±10 格搜索）
        val px = predX.toInt().coerceIn(patchRadius + 1, gridW - patchRadius - 2)
        val py = predY.toInt().coerceIn(patchRadius + 1, gridH - patchRadius - 2)
        val coarseRadius = 8
        var bestCoarseX = px
        var bestCoarseY = py
        var bestCoarseScore = -2f

        for (dy in -coarseRadius..coarseRadius step 2) {
            val cy = py + dy
            if (cy < patchRadius || cy >= gridH - patchRadius) continue
            for (dx in -coarseRadius..coarseRadius step 2) {
                val cx = px + dx
                if (cx < patchRadius || cx >= gridW - patchRadius) continue
                val ncc = computeZnccAt(cx, cy, tmpl, tmplNorm)
                if (ncc > bestCoarseScore) {
                    bestCoarseScore = ncc
                    bestCoarseX = cx
                    bestCoarseY = cy
                }
            }
        }

        // 细层 ±2 格精搜
        var bestX = bestCoarseX
        var bestY = bestCoarseY
        var bestScore = bestCoarseScore
        for (dy in -2..2) {
            val cy = bestCoarseY + dy
            if (cy < patchRadius + 1 || cy >= gridH - patchRadius - 1) continue
            for (dx in -2..2) {
                val cx = bestCoarseX + dx
                if (cx < patchRadius + 1 || cx >= gridW - patchRadius - 1) continue
                val ncc = computeZnccAt(cx, cy, tmpl, tmplNorm)
                if (ncc > bestScore) {
                    bestScore = ncc
                    bestX = cx
                    bestY = cy
                }
            }
        }

        if (bestScore < 0.58f) return false

        // 亚像素抛物线拟合
        val sL = computeZnccAt(bestX - 1, bestY, tmpl, tmplNorm)
        val sR = computeZnccAt(bestX + 1, bestY, tmpl, tmplNorm)
        val sU = computeZnccAt(bestX, bestY - 1, tmpl, tmplNorm)
        val sD = computeZnccAt(bestX, bestY + 1, tmpl, tmplNorm)

        val denomX = (sL - 2f * bestScore + sR)
        val denomY = (sU - 2f * bestScore + sD)
        val subX = if (abs(denomX) > 1e-4f) (0.5f * (sL - sR) / denomX).coerceIn(-0.5f, 0.5f) else 0f
        val subY = if (abs(denomY) > 1e-4f) (0.5f * (sU - sD) / denomY).coerceIn(-0.5f, 0.5f) else 0f

        obsX[featIdx] = bestX + subX
        obsY[featIdx] = bestY + subY
        return true
    }

    private fun computeZnccAt(cx: Int, cy: Int, tmpl: FloatArray, tmplNorm: Float): Float {
        var sum = 0f
        for (dy in -patchRadius..patchRadius) {
            val r = (cy + dy) * gridW
            for (dx in -patchRadius..patchRadius) {
                sum += currGray[r + cx + dx]
            }
        }
        val mean = sum / patchArea
        var dot = 0f
        var candSq = 0f
        var p = 0
        for (dy in -patchRadius..patchRadius) {
            val r = (cy + dy) * gridW
            for (dx in -patchRadius..patchRadius) {
                val d = currGray[r + cx + dx] - mean
                dot += d * tmpl[p++]
                candSq += d * d
            }
        }
        val denom = tmplNorm * sqrt(candSq).coerceAtLeast(1e-3f)
        return dot / denom
    }

    private data class SimilarityFit(
        val anchorX: Float,
        val anchorY: Float,
        val scaleRatio: Float,
        val inliers: Int,
        val avgResidual: Float
    )

    private fun fitSimilarityAndTransformAnchor(): SimilarityFit? {
        val active = (0 until featureCount).filter { obsOk[it] }.toMutableList()
        if (active.size < 3) return null

        fun solveSubset(indices: List<Int>): FloatArray? {
            if (indices.size < 2) return null
            var mx1 = 0f; var my1 = 0f; var mx2 = 0f; var my2 = 0f
            for (i in indices) {
                mx1 += featX[i]; my1 += featY[i]
                mx2 += obsX[i]; my2 += obsY[i]
            }
            val n = indices.size.toFloat()
            mx1 /= n; my1 /= n; mx2 /= n; my2 /= n

            var a = 0f; var b = 0f; var den = 0f
            for (i in indices) {
                val px = featX[i] - mx1
                val py = featY[i] - my1
                val qx = obsX[i] - mx2
                val qy = obsY[i] - my2
                a += px * qx + py * qy
                b += px * qy - py * qx
                den += px * px + py * py
            }
            val c: Float
            val s: Float
            if (den > 1e-2f) {
                c = a / den
                s = b / den
            } else {
                c = 1f; s = 0f
            }
            val scale = sqrt(c * c + s * s)
            if (scale < 0.80f || scale > 1.25f) return null
            return floatArrayOf(mx1, my1, mx2, my2, c, s, scale)
        }

        val firstPass = solveSubset(active) ?: return null
        val residuals = active.map { i ->
            val px = featX[i] - firstPass[0]
            val py = featY[i] - firstPass[1]
            val predX = firstPass[2] + firstPass[4] * px - firstPass[5] * py
            val predY = firstPass[3] + firstPass[5] * px + firstPass[4] * py
            i to hypot((obsX[i] - predX).toDouble(), (obsY[i] - predY).toDouble()).toFloat()
        }
        val filtered = residuals.filter { it.second < 2.5f }.map { it.first }
        val finalFit = if (filtered.size >= 3) (solveSubset(filtered) ?: firstPass) else firstPass
        val inlierSet = if (filtered.size >= 3) filtered else active

        var totalRes = 0f
        for (i in inlierSet) {
            val px = featX[i] - finalFit[0]
            val py = featY[i] - finalFit[1]
            val predX = finalFit[2] + finalFit[4] * px - finalFit[5] * py
            val predY = finalFit[3] + finalFit[5] * px + finalFit[4] * py
            totalRes += hypot((obsX[i] - predX).toDouble(), (obsY[i] - predY).toDouble()).toFloat()
        }
        val avgRes = totalRes / inlierSet.size

        // 用最终 Similarity Transform 精确变换上一帧锚点
        val apx = anchorGridX - finalFit[0]
        val apy = anchorGridY - finalFit[1]
        val newAx = finalFit[2] + finalFit[4] * apx - finalFit[5] * apy
        val newAy = finalFit[3] + finalFit[5] * apx + finalFit[4] * apy

        return SimilarityFit(newAx, newAy, finalFit[6], inlierSet.size, avgRes)
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
