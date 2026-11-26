package com.persona.ar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class KeyframeVisualTrackerTest {

    private fun buildTexturedDeskGray(
        gridW: Int = 96,
        gridH: Int = 208,
        scale: Float = 1f,
        shiftX: Float = 0f,
        shiftY: Float = 0f,
        noiseAmp: Int = 0,
        occludeRightHalf: Boolean = false,
        rng: Random = Random(42)
    ): IntArray {
        val cx = gridW * 0.5f
        val cy = gridH * 0.65f
        val out = IntArray(gridW * gridH)
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                if (occludeRightHalf && x >= (cx + 2).toInt() && y in (cy - 30).toInt()..(cy + 30).toInt()) {
                    // 模拟前景手掌/袖子从画面右侧半场遮挡走过
                    out[y * gridW + x] = 228
                    continue
                }
                val ux = (x - shiftX - cx) / scale + cx
                val uy = (y - shiftY - cy) / scale + cy
                // 含多频 2D 角点的桌面纹理（棋盘 + 斜向条纹 + 局部点阵）
                val pattern = 128.0 +
                    55.0 * sin(ux * 0.52) * cos(uy * 0.48) +
                    35.0 * cos(ux * 0.27 + uy * 0.31) +
                    22.0 * sin((ux - uy) * 0.63)
                val noise = if (noiseAmp > 0) rng.nextInt(-noiseAmp, noiseAmp + 1) else 0
                out[y * gridW + x] = (pattern.toInt() + noise).coerceIn(0, 255)
            }
        }
        return out
    }

    @Test
    fun `stationary noisy camera for 300 frames produces zero drift and zero keyframe churn`() {
        val tracker = KeyframeVisualTracker(gridW = 96)
        val gridH = 208
        val aspect = 1080f / 2340f
        val r = floatArrayOf(1f, 0f, 0f)
        val u = floatArrayOf(0f, cos(Math.toRadians(35.0)).toFloat(), -sin(Math.toRadians(35.0)).toFloat())
        val f = floatArrayOf(0f, -sin(Math.toRadians(35.0)).toFloat(), -cos(Math.toRadians(35.0)).toFloat())

        val initGray = buildTexturedDeskGray(gridH = gridH)
        val first = tracker.trackFrame(
            gray = initGray, gridH = gridH, aspect = aspect, vFovDeg = 62f,
            currRight = r, currUp = u, currForward = f,
            pendingReset = true, resetNormX = 0.5f, resetNormY = 0.65f
        )
        assertNotNull(first)
        assertEquals(1, tracker.keyframeId)

        val rng = Random(7)
        repeat(300) { idx ->
            val noisyGray = buildTexturedDeskGray(gridH = gridH, noiseAmp = 4, rng = rng)
            // 模拟 ±0.08° 桌面微振 IMU 噪声
            val eps = ((idx % 3) - 1) * 0.0012f
            val fNoisy = floatArrayOf(eps, f[1], f[2])
            SpatialMath3D.normalize3(fNoisy)
            val obs = tracker.trackFrame(
                gray = noisyGray, gridH = gridH, aspect = aspect, vFovDeg = 62f,
                currRight = r, currUp = u, currForward = fNoisy,
                pendingReset = false, resetNormX = 0.5f, resetNormY = 0.65f
            )
            assertNotNull(obs)
            assertFalse("frame=$idx lost at x=${obs!!.trackedScreenX}, y=${obs.trackedScreenY}, kf=${tracker.keyframeId}", obs.isLost)
            assertEquals("静止期不能反复刷新关键帧导致游走", 1, tracker.keyframeId)
            assertEquals(0.5f, obs.trackedScreenX, 0.004f)
            assertEquals(0.65f, obs.trackedScreenY, 0.004f)
            assertEquals(1.0f, obs.keyframeScaleRatio, 0.01f)
        }
    }

    @Test
    fun `moving foreground occlusion over half the ROI is rejected by 2-point RANSAC`() {
        val tracker = KeyframeVisualTracker(gridW = 96)
        val gridH = 208
        val aspect = 1080f / 2340f
        val r = floatArrayOf(1f, 0f, 0f)
        val u = floatArrayOf(0f, 0.8f, -0.6f)
        val f = floatArrayOf(0f, -0.6f, -0.8f)

        val baseGray = buildTexturedDeskGray(gridH = gridH)
        tracker.trackFrame(
            gray = baseGray, gridH = gridH, aspect = aspect, vFovDeg = 62f,
            currRight = r, currUp = u, currForward = f,
            pendingReset = true, resetNormX = 0.5f, resetNormY = 0.65f
        )

        // 右侧半区被手掌纯色遮挡，同时左侧桌面背景静止不动
        val occludedGray = buildTexturedDeskGray(gridH = gridH, occludeRightHalf = true)
        var lastObs: VisualAnchorObservation? = null
        repeat(20) {
            lastObs = tracker.trackFrame(
                gray = occludedGray, gridH = gridH, aspect = aspect, vFovDeg = 62f,
                currRight = r, currUp = u, currForward = f,
                pendingReset = false, resetNormX = 0.5f, resetNormY = 0.65f
            )
        }
        assertNotNull(lastObs)
        assertEquals("遮挡期不能腐蚀关键帧", 1, tracker.keyframeId)
        val nonNullObs = lastObs!!
        assertFalse("左半场未遮挡背景足够时 RANSAC 应继续稳固跟踪", nonNullObs.isLost)
        assertEquals(0.5f, nonNullObs.trackedScreenX, 0.015f)
        assertEquals(0.65f, nonNullObs.trackedScreenY, 0.015f)
    }

    @Test
    fun `real approach zoom produces matching keyframeScaleRatio without runaway`() {
        val tracker = KeyframeVisualTracker(gridW = 96)
        val gridH = 208
        val aspect = 1080f / 2340f
        val r = floatArrayOf(1f, 0f, 0f)
        val u = floatArrayOf(0f, 1f, 0f)
        val f = floatArrayOf(0f, 0f, -1f)

        val baseGray = buildTexturedDeskGray(gridH = gridH)
        tracker.trackFrame(
            gray = baseGray, gridH = gridH, aspect = aspect, vFovDeg = 62f,
            currRight = r, currUp = u, currForward = f,
            pendingReset = true, resetNormX = 0.5f, resetNormY = 0.65f
        )

        val zoomedGray = buildTexturedDeskGray(gridH = gridH, scale = 1.12f)
        var lastObs: VisualAnchorObservation? = null
        repeat(15) {
            lastObs = tracker.trackFrame(
                gray = zoomedGray, gridH = gridH, aspect = aspect, vFovDeg = 62f,
                currRight = r, currUp = u, currForward = f,
                pendingReset = false, resetNormX = 0.5f, resetNormY = 0.65f
            )
        }
        assertNotNull(lastObs)
        val nonNullObs = lastObs!!
        assertFalse(nonNullObs.isLost)
        assertEquals(1.12f, nonNullObs.keyframeScaleRatio, 0.04f)
    }
}
