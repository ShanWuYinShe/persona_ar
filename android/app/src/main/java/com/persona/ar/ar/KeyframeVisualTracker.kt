package com.persona.ar.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 2D 关键帧锁定的 IMU 先验稀疏光流追踪器（纯 Kotlin，零 Android 依赖，可在 JVM 单测直接覆盖）。
 *
 * 设计要点：
 * 1. **不可变关键帧模板**：关键帧内所有角点坐标 `(keyX, keyY)`、灰度块 `keyPatches`、
 *    锚点位置 `(keyAnchorGridX, keyAnchorGridY)` 与 IMU 基 `(keyRight, keyUp, keyForward)` 保持冻结；
 *    每帧都在原关键帧与当前帧之间直接匹配，彻底消除帧间递归累加引起的无界游走与尺度收缩偏置。
 * 2. **2 点确定性 RANSAC Similarity 拟合**：对最多 9 个特征枚举全部点对 `C(9,2)=36` 求解相似变换，
 *    当桌面局部出现路过的手掌/鼠标等前景遮挡时自动剔除外点，不被前景拖拽跑偏。
 * 3. **真运动门控换帧**：仅当累计转角 > 14°、真实平移 > 14% 视口、尺度变化 > 16% 或边缘出画 >= 4 且质量优秀时刷新关键帧；
 *    静止持握或放置桌面时永不靠固定帧数定时器覆盖模板。
 */
internal class KeyframeVisualTracker(
    val gridW: Int = 96,
    private val maxFeatures: Int = 9,
    private val patchRadius: Int = 3
) {
    private val patchSize = patchRadius * 2 + 1
    private val patchArea = patchSize * patchSize

    var keyframeId: Int = 0
        private set

    private var featureCount = 0
    private var hasInitializedFeatures = false
    private var hasPrevImu = false

    // 关键帧冻结状态
    private val keyX = FloatArray(maxFeatures)
    private val keyY = FloatArray(maxFeatures)
    private val keyPatches = Array(maxFeatures) { FloatArray(patchArea) }
    private val keyPatchNorm = FloatArray(maxFeatures)
    private var keyAnchorGridX = gridW * 0.5f
    private var keyAnchorGridY = gridW * 1.2f
    private val keyRight = FloatArray(3)
    private val keyUp = FloatArray(3)
    private val keyForward = FloatArray(3)

    // 上一帧搜索与观测缓存（仅用于缩小连续大位移的粗搜窗口，绝不参与关键帧变换基准）
    private val prevRight = FloatArray(3)
    private val prevUp = FloatArray(3)
    private val prevForward = FloatArray(3)
    private val lastObsX = FloatArray(maxFeatures)
    private val lastObsY = FloatArray(maxFeatures)
    private val lastObsOk = BooleanArray(maxFeatures)

    private val obsX = FloatArray(maxFeatures)
    private val obsY = FloatArray(maxFeatures)
    private val obsOk = BooleanArray(maxFeatures)

    fun clear() {
        hasInitializedFeatures = false
        hasPrevImu = false
        featureCount = 0
        keyframeId = 0
        lastObsOk.fill(false)
    }

    fun onGridResized() {
        hasInitializedFeatures = false
        featureCount = 0
        lastObsOk.fill(false)
    }

    fun trackFrame(
        gray: IntArray,
        gridH: Int,
        aspect: Float,
        vFovDeg: Float,
        currRight: FloatArray,
        currUp: FloatArray,
        currForward: FloatArray,
        pendingReset: Boolean,
        resetNormX: Float,
        resetNormY: Float
    ): VisualAnchorObservation? {
        if (pendingReset || !hasInitializedFeatures) {
            keyAnchorGridX = resetNormX.coerceIn(0.05f, 0.95f) * gridW
            keyAnchorGridY = resetNormY.coerceIn(0.10f, 0.95f) * gridH
            initFeaturesAround(gray, gridH, keyAnchorGridX, keyAnchorGridY)
            copyBasis(currRight, currUp, currForward, keyRight, keyUp, keyForward)
            copyBasis(currRight, currUp, currForward, prevRight, prevUp, prevForward)
            hasPrevImu = true
            if (featureCount > 0) {
                keyframeId++
            }
            return if (featureCount >= 3) {
                VisualAnchorObservation(
                    trackedScreenX = keyAnchorGridX / gridW,
                    trackedScreenY = keyAnchorGridY / gridH,
                    scaleRatio = 1.0f,
                    confidence = 1.0f,
                    inlierCount = featureCount,
                    isLost = false,
                    keyframeId = keyframeId,
                    keyframeScaleRatio = 1.0f
                )
            } else null
        }

        if (featureCount < 3 || !hasPrevImu) {
            copyBasis(currRight, currUp, currForward, prevRight, prevUp, prevForward)
            hasPrevImu = true
            return null
        }

        val dotStepFwd = dot3(prevForward, currForward).coerceIn(-1f, 1f)
        val dotStepUp = dot3(prevUp, currUp).coerceIn(-1f, 1f)
        val stepAngleDeg = max(
            Math.toDegrees(acos(dotStepFwd.toDouble())).toFloat(),
            Math.toDegrees(acos(dotStepUp.toDouble())).toFloat()
        )

        val dotKeyFwd = dot3(keyForward, currForward).coerceIn(-1f, 1f)
        val dotKeyUp = dot3(keyUp, currUp).coerceIn(-1f, 1f)
        val rotFromKeyDeg = max(
            Math.toDegrees(acos(dotKeyFwd.toDouble())).toFloat(),
            Math.toDegrees(acos(dotKeyUp.toDouble())).toFloat()
        )

        val tanHalfFov = tan(Math.toRadians(vFovDeg.toDouble()) * 0.5).toFloat()
        var matchedCount = 0

        for (k in 0 until featureCount) {
            val useLastObs = lastObsOk[k]
            val srcX = if (useLastObs) lastObsX[k] else keyX[k]
            val srcY = if (useLastObs) lastObsY[k] else keyY[k]
            val srcR = if (useLastObs) prevRight else keyRight
            val srcU = if (useLastObs) prevUp else keyUp
            val srcF = if (useLastObs) prevForward else keyForward

            val sxPrev = srcX / gridW
            val syPrev = srcY / gridH
            val ndcX = 2f * sxPrev - 1f
            val ndcY = 1f - 2f * syPrev
            val rx = ndcX * tanHalfFov * aspect
            val ry = ndcY * tanHalfFov
            val wx = rx * srcR[0] + ry * srcU[0] + srcF[0]
            val wy = rx * srcR[1] + ry * srcU[1] + srcF[1]
            val wz = rx * srcR[2] + ry * srcU[2] + srcF[2]

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

            val matched = matchFeatureZncc(gray, gridH, k, predGridX, predGridY)
            obsOk[k] = matched
            if (matched) matchedCount++
        }

        for (k in 0 until featureCount) {
            lastObsOk[k] = obsOk[k]
            if (obsOk[k]) {
                lastObsX[k] = obsX[k]
                lastObsY[k] = obsY[k]
            }
        }

        val observation: VisualAnchorObservation
        if (matchedCount >= 3) {
            val fit = fitSimilarityRansac()
            if (fit != null) {
                val newNormX = fit.anchorX / gridW
                val newNormY = fit.anchorY / gridH
                val inBounds = newNormX in -0.25f..1.25f && newNormY in -0.25f..1.25f

                if (fit.inliers >= 3 && inBounds) {
                    val anchorDisp = hypot((fit.anchorX - keyAnchorGridX).toDouble(), (fit.anchorY - keyAnchorGridY).toDouble()).toFloat()
                    val scaleDev = abs(fit.scaleRatio - 1f)
                    val isStationaryToKey = rotFromKeyDeg < 0.45f &&
                        stepAngleDeg < 0.25f &&
                        anchorDisp < 0.75f &&
                        scaleDev < 0.032f

                    val outNormX = if (isStationaryToKey) keyAnchorGridX / gridW else newNormX
                    val outNormY = if (isStationaryToKey) keyAnchorGridY / gridH else newNormY
                    val outScale = if (isStationaryToKey) 1.0f else fit.scaleRatio.coerceIn(0.75f, 1.35f)
                    val conf = (fit.inliers.toFloat() / maxFeatures.toFloat()).coerceIn(0.35f, 1.0f)

                    observation = VisualAnchorObservation(
                        trackedScreenX = outNormX,
                        trackedScreenY = outNormY,
                        scaleRatio = outScale,
                        confidence = conf,
                        inlierCount = fit.inliers,
                        isLost = false,
                        keyframeId = keyframeId,
                        keyframeScaleRatio = outScale
                    )

                    val outOfMarginCount = (0 until featureCount).count {
                        !obsOk[it] || obsX[it] < 8f || obsX[it] > gridW - 8f || obsY[it] < 8f || obsY[it] > gridH - 8f
                    }
                    val needsHandoff = rotFromKeyDeg > 14f ||
                        anchorDisp > gridW * 0.14f ||
                        scaleDev > 0.16f ||
                        outOfMarginCount >= 4
                    if (needsHandoff && fit.inliers >= 4 && fit.avgResidual < 1.45f) {
                        keyAnchorGridX = fit.anchorX.coerceIn(4f, gridW - 5f)
                        keyAnchorGridY = fit.anchorY.coerceIn(4f, gridH - 5f)
                        refreshOrReplaceFeatures(gray, gridH, keyAnchorGridX, keyAnchorGridY)
                        copyBasis(currRight, currUp, currForward, keyRight, keyUp, keyForward)
                        if (featureCount > 0) keyframeId++
                    }
                } else {
                    observation = lostObservation(gridH)
                }
            } else {
                observation = lostObservation(gridH)
            }
        } else {
            observation = lostObservation(gridH)
        }

        copyBasis(currRight, currUp, currForward, prevRight, prevUp, prevForward)
        hasPrevImu = true
        return observation
    }

    private fun lostObservation(gridH: Int): VisualAnchorObservation = VisualAnchorObservation(
        trackedScreenX = keyAnchorGridX / gridW,
        trackedScreenY = keyAnchorGridY / gridH,
        scaleRatio = 1.0f,
        confidence = 0f,
        inlierCount = 0,
        isLost = true,
        keyframeId = keyframeId,
        keyframeScaleRatio = 1.0f
    )

    private fun initFeaturesAround(gray: IntArray, gridH: Int, cx: Float, cy: Float) {
        featureCount = 0
        hasInitializedFeatures = true
        lastObsOk.fill(false)

        data class Candidate(val x: Int, val y: Int, val score: Float)
        val candidates = ArrayList<Candidate>(128)
        val margin = patchRadius + 3
        val minX = max(margin, (cx - gridW * 0.36f).toInt())
        val maxX = min(gridW - margin - 1, (cx + gridW * 0.36f).toInt())
        val minY = max(margin + (gridH * 0.12f).toInt(), (cy - gridH * 0.24f).toInt())
        val maxY = min(gridH - margin - 1, (cy + gridH * 0.24f).toInt())

        for (y in minY..maxY step 2) {
            for (x in minX..maxX step 2) {
                val cornerScore = computeCornerScore(gray, gridH, x, y)
                if (cornerScore > 140f) {
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
                val dx = keyX[i] - c.x
                val dy = keyY[i] - c.y
                if (dx * dx + dy * dy < minSepSq) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) {
                extractPatch(gray, c.x, c.y, featureCount)
                lastObsX[featureCount] = c.x.toFloat()
                lastObsY[featureCount] = c.y.toFloat()
                lastObsOk[featureCount] = true
                featureCount++
            }
        }
    }

    private fun refreshOrReplaceFeatures(gray: IntArray, gridH: Int, cx: Float, cy: Float) {
        var kept = 0
        for (k in 0 until featureCount) {
            if (obsOk[k]) {
                val ix = obsX[k].toInt()
                val iy = obsY[k].toInt()
                if (ix in (patchRadius + 4)..(gridW - patchRadius - 5) &&
                    iy in (patchRadius + 4)..(gridH - patchRadius - 5) &&
                    computeCornerScore(gray, gridH, ix, iy) > 100f
                ) {
                    extractPatch(gray, ix, iy, kept)
                    lastObsX[kept] = ix.toFloat()
                    lastObsY[kept] = iy.toFloat()
                    lastObsOk[kept] = true
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
                    val s = computeCornerScore(gray, gridH, x, y)
                    if (s > 140f) candidates.add(Candidate(x, y, s))
                }
            }
            candidates.sortByDescending { it.score }
            val minSepSq = 8f * 8f
            for (c in candidates) {
                if (featureCount >= maxFeatures) break
                var tooClose = false
                for (i in 0 until featureCount) {
                    val dx = keyX[i] - c.x
                    val dy = keyY[i] - c.y
                    if (dx * dx + dy * dy < minSepSq) {
                        tooClose = true
                        break
                    }
                }
                if (!tooClose) {
                    extractPatch(gray, c.x, c.y, featureCount)
                    lastObsX[featureCount] = c.x.toFloat()
                    lastObsY[featureCount] = c.y.toFloat()
                    lastObsOk[featureCount] = true
                    featureCount++
                }
            }
        }
    }

    private fun computeCornerScore(gray: IntArray, gridH: Int, cx: Int, cy: Int): Float {
        if (cx - 2 < 0 || cx + 2 >= gridW || cy - 2 < 0 || cy + 2 >= gridH) return 0f
        var sxx = 0f
        var syy = 0f
        for (dy in -1..1) {
            val r = (cy + dy) * gridW
            for (dx in -1..1) {
                val x = cx + dx
                val gx = (gray[r + x + 1] - gray[r + x - 1]).toFloat()
                val gy = (gray[(cy + dy + 1) * gridW + x] - gray[(cy + dy - 1) * gridW + x]).toFloat()
                sxx += gx * gx
                syy += gy * gy
            }
        }
        return min(sxx, syy)
    }

    private fun extractPatch(gray: IntArray, cx: Int, cy: Int, idx: Int) {
        keyX[idx] = cx.toFloat()
        keyY[idx] = cy.toFloat()
        val dst = keyPatches[idx]
        var sum = 0f
        var p = 0
        for (dy in -patchRadius..patchRadius) {
            val r = (cy + dy) * gridW
            for (dx in -patchRadius..patchRadius) {
                val v = gray[r + cx + dx].toFloat()
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
        keyPatchNorm[idx] = sqrt(normSq).coerceAtLeast(1e-3f)
    }

    private fun matchFeatureZncc(gray: IntArray, gridH: Int, featIdx: Int, predX: Float, predY: Float): Boolean {
        val tmpl = keyPatches[featIdx]
        val tmplNorm = keyPatchNorm[featIdx]
        if (tmplNorm < 18f) return false

        val px = predX.roundToInt().coerceIn(patchRadius + 1, gridW - patchRadius - 2)
        val py = predY.roundToInt().coerceIn(patchRadius + 1, gridH - patchRadius - 2)

        // 1. 优先在 IMU 预测点 ±2 格紧邻窗口搜寻：静止/缓动期绝不跨格跳到桌面键盘/木纹等周期相似纹理上
        var bestX = px
        var bestY = py
        var bestScore = -2f
        for (dy in -2..2) {
            val cy = py + dy
            if (cy < patchRadius + 1 || cy >= gridH - patchRadius - 1) continue
            for (dx in -2..2) {
                val cx = px + dx
                if (cx < patchRadius + 1 || cx >= gridW - patchRadius - 1) continue
                val ncc = computeZnccAt(gray, cx, cy, tmpl, tmplNorm)
                if (ncc > bestScore) {
                    bestScore = ncc
                    bestX = cx
                    bestY = cy
                }
            }
        }

        // 2. 仅当紧邻窗口未命中高置信峰值 (< 0.78) 时才展开 ±10 格粗搜（用于快速平移/剧烈视差）
        if (bestScore < 0.78f) {
            val coarseRadius = 10
            var bestCoarseX = px
            var bestCoarseY = py
            var bestCoarseObjective = -2f
            for (dy in -coarseRadius..coarseRadius step 2) {
                val cy = py + dy
                if (cy < patchRadius || cy >= gridH - patchRadius) continue
                for (dx in -coarseRadius..coarseRadius step 2) {
                    val cx = px + dx
                    if (cx < patchRadius || cx >= gridW - patchRadius) continue
                    val ncc = computeZnccAt(gray, cx, cy, tmpl, tmplNorm)
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val obj = ncc - 0.003f * dist
                    if (obj > bestCoarseObjective) {
                        bestCoarseObjective = obj
                        bestCoarseX = cx
                        bestCoarseY = cy
                    }
                }
            }

            bestX = bestCoarseX
            bestY = bestCoarseY
            bestScore = computeZnccAt(gray, bestCoarseX, bestCoarseY, tmpl, tmplNorm)
            for (dy in -2..2) {
                val cy = bestCoarseY + dy
                if (cy < patchRadius + 1 || cy >= gridH - patchRadius - 1) continue
                for (dx in -2..2) {
                    val cx = bestCoarseX + dx
                    if (cx < patchRadius + 1 || cx >= gridW - patchRadius - 1) continue
                    val ncc = computeZnccAt(gray, cx, cy, tmpl, tmplNorm)
                    if (ncc > bestScore) {
                        bestScore = ncc
                        bestX = cx
                        bestY = cy
                    }
                }
            }
        }

        if (bestScore < 0.60f) return false

        val sL = computeZnccAt(gray, bestX - 1, bestY, tmpl, tmplNorm)
        val sR = computeZnccAt(gray, bestX + 1, bestY, tmpl, tmplNorm)
        val sU = computeZnccAt(gray, bestX, bestY - 1, tmpl, tmplNorm)
        val sD = computeZnccAt(gray, bestX, bestY + 1, tmpl, tmplNorm)

        val denomX = sL - 2f * bestScore + sR
        val denomY = sU - 2f * bestScore + sD
        val subX = if (abs(denomX) > 1e-4f) (0.5f * (sL - sR) / denomX).coerceIn(-0.5f, 0.5f) else 0f
        val subY = if (abs(denomY) > 1e-4f) (0.5f * (sU - sD) / denomY).coerceIn(-0.5f, 0.5f) else 0f

        obsX[featIdx] = bestX + subX
        obsY[featIdx] = bestY + subY
        return true
    }

    private fun computeZnccAt(gray: IntArray, cx: Int, cy: Int, tmpl: FloatArray, tmplNorm: Float): Float {
        var sum = 0f
        for (dy in -patchRadius..patchRadius) {
            val r = (cy + dy) * gridW
            for (dx in -patchRadius..patchRadius) {
                sum += gray[r + cx + dx]
            }
        }
        val mean = sum / patchArea
        var dot = 0f
        var candSq = 0f
        var p = 0
        for (dy in -patchRadius..patchRadius) {
            val r = (cy + dy) * gridW
            for (dx in -patchRadius..patchRadius) {
                val d = gray[r + cx + dx] - mean
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

    private fun fitSimilarityRansac(): SimilarityFit? {
        val active = (0 until featureCount).filter { obsOk[it] }
        if (active.size < 3) return null

        fun solveSubset(indices: List<Int>): FloatArray? {
            if (indices.size < 2) return null
            var mx1 = 0f; var my1 = 0f; var mx2 = 0f; var my2 = 0f
            for (i in indices) {
                mx1 += keyX[i]; my1 += keyY[i]
                mx2 += obsX[i]; my2 += obsY[i]
            }
            val n = indices.size.toFloat()
            mx1 /= n; my1 /= n; mx2 /= n; my2 /= n

            var a = 0f; var b = 0f; var den = 0f
            for (i in indices) {
                val px = keyX[i] - mx1
                val py = keyY[i] - my1
                val qx = obsX[i] - mx2
                val qy = obsY[i] - my2
                a += px * qx + py * qy
                b += px * qy - py * qx
                den += px * px + py * py
            }
            if (den < 16f) return null
            val c = a / den
            val s = b / den
            val scale = sqrt(c * c + s * s)
            if (scale < 0.72f || scale > 1.40f) return null
            return floatArrayOf(mx1, my1, mx2, my2, c, s, scale)
        }

        // 对全部点对 C(N,2) 运行确定性 RANSAC，抵抗手掌/鼠标划过造成的半场前景污染
        val inlierThreshold = 2.0f
        var bestInliers: List<Int>? = null
        var bestErrorSum = Float.MAX_VALUE

        for (aIdx in active.indices) {
            for (bIdx in (aIdx + 1) until active.size) {
                val model = solveSubset(listOf(active[aIdx], active[bIdx])) ?: continue
                val inliers = ArrayList<Int>(active.size)
                var errSum = 0f
                for (i in active) {
                    val px = keyX[i] - model[0]
                    val py = keyY[i] - model[1]
                    val predX = model[2] + model[4] * px - model[5] * py
                    val predY = model[3] + model[5] * px + model[4] * py
                    val err = hypot((obsX[i] - predX).toDouble(), (obsY[i] - predY).toDouble()).toFloat()
                    if (err <= inlierThreshold) {
                        inliers.add(i)
                        errSum += err
                    }
                }
                val prevBestCount = bestInliers?.size ?: 0
                if (inliers.size > prevBestCount || (inliers.size == prevBestCount && errSum < bestErrorSum)) {
                    bestInliers = inliers
                    bestErrorSum = errSum
                }
            }
        }

        val consensus = bestInliers ?: active
        if (consensus.size < 3) return null
        val finalFit = solveSubset(consensus) ?: return null

        var totalRes = 0f
        for (i in consensus) {
            val px = keyX[i] - finalFit[0]
            val py = keyY[i] - finalFit[1]
            val predX = finalFit[2] + finalFit[4] * px - finalFit[5] * py
            val predY = finalFit[3] + finalFit[5] * px + finalFit[4] * py
            totalRes += hypot((obsX[i] - predX).toDouble(), (obsY[i] - predY).toDouble()).toFloat()
        }
        val avgRes = totalRes / consensus.size

        val apx = keyAnchorGridX - finalFit[0]
        val apy = keyAnchorGridY - finalFit[1]
        val newAx = finalFit[2] + finalFit[4] * apx - finalFit[5] * apy
        val newAy = finalFit[3] + finalFit[5] * apx + finalFit[4] * apy

        return SimilarityFit(newAx, newAy, finalFit[6], consensus.size, avgRes)
    }

    private fun copyBasis(
        sr: FloatArray, su: FloatArray, sf: FloatArray,
        dr: FloatArray, du: FloatArray, df: FloatArray
    ) {
        System.arraycopy(sr, 0, dr, 0, 3)
        System.arraycopy(su, 0, du, 0, 3)
        System.arraycopy(sf, 0, df, 0, 3)
    }

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
