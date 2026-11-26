package com.persona.ar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SpatialMath3DTest {

    @Test
    fun `screenNormToBufferNorm and bufferNormToScreenNorm round-trip across all rotations`() {
        val rotations = listOf(0, 90, 180, 270)
        val viewAspect = 1080f / 2340f
        val testPoints = listOf(
            0.5f to 0.5f,
            0.25f to 0.35f,
            0.78f to 0.68f,
            0.35f to 0.80f
        )

        for (rot in rotations) {
            for ((sx, sy) in testPoints) {
                val (u, v) = SpatialMath3D.screenNormToBufferNorm(
                    screenX = sx,
                    screenY = sy,
                    bufferWidth = 1280,
                    bufferHeight = 720,
                    rotationDegrees = rot,
                    viewportAspect = viewAspect
                )
                val (backX, backY) = SpatialMath3D.bufferNormToScreenNorm(
                    u = u,
                    v = v,
                    bufferWidth = 1280,
                    bufferHeight = 720,
                    rotationDegrees = rot,
                    viewportAspect = viewAspect
                )
                assertEquals("rot=$rot sx", sx, backX, 1e-3f)
                assertEquals("rot=$rot sy", sy, backY, 1e-3f)
            }
        }
    }

    @Test
    fun `portrait 90 degree top-of-screen light maps near left edge of landscape buffer`() {
        // 竖屏顶部 (0.5, 0.1) 在 90° 顺时针旋转下应对应景观缓冲左侧 (u ≈ 0.1, v = 0.5)
        val (u, v) = SpatialMath3D.screenNormToBufferNorm(
            screenX = 0.5f,
            screenY = 0.1f,
            bufferWidth = 1920,
            bufferHeight = 1080,
            rotationDegrees = 90,
            viewportAspect = 1080f / 2340f
        )
        assertTrue("顶部像素在90度传感器中 u 应接近 0.1，实际 u=$u", u in 0.05f..0.15f)
        assertEquals(0.5f, v, 0.02f)
    }

    @Test
    fun `sensorMatrixToWorldBasis keeps upright gravity leveled orthogonal right-handed frame`() {
        val yaw = Math.toRadians(37.0).toFloat()
        val c = cos(yaw)
        val s = sin(yaw)
        // 竖屏平视朝向任意水平角 yaw：col0=(c, -s, 0), col1=(0,0,1), col2=(-s, -c, 0) (ENU, +Z=up)
        // col2 是从屏幕外指出 -> forward = -col2 = (s, c, 0)
        val rotEnu = floatArrayOf(
            c, 0f, -s,
            -s, 0f, -c,
            0f, 1f, 0f
        )
        val anchorYaw = SpatialMath3D.extractAnchorYawRad(rotEnu)
        val r = FloatArray(3)
        val u = FloatArray(3)
        val f = FloatArray(3)
        SpatialMath3D.sensorMatrixToWorldBasis(rotEnu, anchorYaw, r, u, f)

        assertEquals(1f, r[0], 1e-4f)
        assertEquals(0f, r[1], 1e-4f)
        assertEquals(0f, r[2], 1e-4f)

        assertEquals(0f, u[0], 1e-4f)
        assertEquals(1f, u[1], 1e-4f)
        assertEquals(0f, u[2], 1e-4f)

        assertEquals(0f, f[0], 1e-4f)
        assertEquals(0f, f[1], 1e-4f)
        assertEquals(-1f, f[2], 1e-4f)
    }

    @Test
    fun `ray plane intersection and projection round-trip on horizontal desk plane`() {
        val vFov = 62f
        val aspect = 0.5f
        val pitch = Math.toRadians(35.0).toFloat()
        val cp = cos(pitch)
        val sp = sin(pitch)
        // 相机向下俯视 35°
        val camRight = floatArrayOf(1f, 0f, 0f)
        val camForward = floatArrayOf(0f, -sp, -cp)
        val camUp = floatArrayOf(0f, cp, -sp)

        val ray = FloatArray(3)
        val hit = FloatArray(3)
        val proj = FloatArray(3)
        val planeY = -0.35f

        val targets = listOf(0.25f to 0.52f, 0.5f to 0.65f, 0.80f to 0.78f)
        for ((tx, ty) in targets) {
            SpatialMath3D.screenToWorldRay(tx, ty, vFov, aspect, camRight, camUp, camForward, ray)
            SpatialMath3D.intersectRayWithHorizontalPlane(
                camPosX = 0f,
                camPosY = 0f,
                camPosZ = 0f,
                rayDir = ray,
                planeY = planeY,
                fallbackDistance = 0.7f,
                minDistance = 0.15f,
                maxDistance = 5.0f,
                outPoint = hit
            )
            assertEquals("落点 Y 必须在水平桌面上", planeY, hit[1], 1e-4f)

            val visible = SpatialMath3D.projectWorldToScreen(
                hit[0], hit[1], hit[2],
                camPosX = 0f, camPosY = 0f, camPosZ = 0f,
                camRight = camRight, camUp = camUp, camForward = camForward,
                verticalFovDeg = vFov, aspect = aspect, outScreen = proj
            )
            assertTrue(visible)
            assertEquals("投影回来的 screenX 应严格对齐点击/拖拽处", tx, proj[0], 1e-3f)
            assertEquals("投影回来的 screenY 应严格对齐点击/拖拽处", ty, proj[1], 1e-3f)
        }
    }

    @Test
    fun `horizon drag stays finite and clamped on plane without flying to infinity`() {
        val hit = FloatArray(3)
        // 向上平视或略仰视射线 dirY > 0
        val upRay = floatArrayOf(0.2f, 0.15f, -0.968f)
        SpatialMath3D.normalize3(upRay)
        SpatialMath3D.intersectRayWithHorizontalPlane(
            camPosX = 0f,
            camPosY = 0f,
            camPosZ = 0f,
            rayDir = upRay,
            planeY = -0.25f,
            fallbackDistance = 0.65f,
            minDistance = 0.24f,
            maxDistance = 2.60f,
            outPoint = hit
        )
        assertEquals(-0.25f, hit[1], 1e-4f)
        assertTrue(!hit[0].isNaN() && !hit[2].isNaN())
        val distXZ = kotlin.math.hypot(hit[0], hit[2])
        assertTrue("平视边缘距离应在合理范围内，实际 $distXZ", distXZ in 0.24f..2.61f)
    }

    @Test
    fun `computeEffectiveVerticalFov returns realistic smartphone vertical fov`() {
        val fov = SpatialMath3D.computeEffectiveVerticalFov(
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            focalLengthMm = 4.7f,
            rotationDegrees = 90,
            viewportAspect = 1080f / 2340f
        )
        assertTrue("智能手机竖屏垂直 FOV 应在 50~85° 之间，实际 $fov", fov in 50f..85f)
        assertTrue(abs(fov - 62f) < 25f)
    }
}
