package com.persona.ar.ar

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class UniversalSensorSurfaceTrackerTest {

    private val fakeContext = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun `anchorToDesktop sets valid 3D world anchor coordinates`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)

        assertFalse(tracker.spatialAnchor.value.isAnchored)

        tracker.anchorToDesktop(0.5f, 0.65f)

        val anchor = tracker.spatialAnchor.value
        assertTrue(anchor.isAnchored)
        assertTrue(anchor.isWorldAnchored)
        assertTrue(anchor.isInView)
        assertEquals(0f, anchor.azimuthDeltaDegrees, 0.01f)
        assertTrue(anchor.worldAnchorZ < -0.2f)
        assertEquals(0.65f, anchor.distanceMeters, 0.01f)
    }

    @Test
    fun `resetAnchor clears anchored state`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)

        tracker.anchorToDesktop(0.5f, 0.65f)
        assertTrue(tracker.spatialAnchor.value.isAnchored)

        tracker.resetAnchor()
        val anchor = tracker.spatialAnchor.value
        assertFalse(anchor.isAnchored)
        assertFalse(anchor.isWorldAnchored)
        assertEquals("未锚定", anchor.planeTypeDescription)
    }

    @Test
    fun `scale and rotation updates stay within safe bounds`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)

        tracker.anchorToDesktop(0.5f, 0.65f)

        tracker.updateScale(1.5f)
        assertEquals(1.5f, tracker.spatialAnchor.value.scaleFactor, 0.01f)

        tracker.updateRotation(45f)
        assertEquals(45f, tracker.spatialAnchor.value.rotationYaw, 0.01f)

        tracker.updateRotation(360f)
        assertEquals(45f, tracker.spatialAnchor.value.rotationYaw, 0.01f)
    }

    @Test
    fun `applyOrientationFilter keeps anchor in front when orientation matches anchor pose`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        tracker.anchorToDesktop(0.5f, 0.65f)

        val identity = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
        tracker.applyOrientationFilter(identity, 0f, 45f, 0f)

        val anchor = tracker.spatialAnchor.value
        assertTrue("锚点应在视野内", anchor.isInView)
        assertEquals(0f, anchor.azimuthDeltaDegrees, 1.0f)
        assertTrue("锚点投影应在视野水平中心附近，实际 ${anchor.screenX}", anchor.screenX in 0.40f..0.60f)
        assertTrue("锚点投影应在视野中下部，实际 ${anchor.screenY}", anchor.screenY in 0.50f..0.80f)
    }

    @Test
    fun `gravity leveled tracking hides anchor when panning away and restores on return`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        // 竖屏平视朝正北：Right=(1,0,0), Up=(0,0,1), -Fwd=(0,-1,0)
        val uprightNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        // 连续喂入滤波使矩阵收敛到正北姿态
        repeat(40) { tracker.applyOrientationFilter(uprightNorth, 0f, 0f, 0f) }
        tracker.anchorToDesktop(0.5f, 0.65f)
        assertTrue("锚定瞬间应在视野内", tracker.spatialAnchor.value.isInView)

        // 右转 90° 朝向正东：Right=(0,-1,0), Up=(0,0,1), -Fwd=(-1,0,0)
        val facingEast = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f
        )
        repeat(40) { tracker.applyOrientationFilter(facingEast, 90f, 0f, 0f) }
        val panned = tracker.spatialAnchor.value
        assertFalse("镜头移开 90° 后人物应平滑出画消失", panned.isInView)
        assertTrue("移开向右应提示人物在左侧方位", panned.azimuthDeltaDegrees < -30f)

        // 转回正北原位置
        repeat(40) { tracker.applyOrientationFilter(uprightNorth, 0f, 0f, 0f) }
        val restored = tracker.spatialAnchor.value
        assertTrue("转回原位置后手办应在桌面原位重现", restored.isInView)
        assertEquals(0f, restored.azimuthDeltaDegrees, 3.0f)
        assertTrue("重现坐标应回到原点击位置附近", restored.screenX in 0.40f..0.60f && restored.screenY in 0.50f..0.80f)
    }

    @Test
    fun `sideways resting phone keeps portrait figure upright without clipping sideways`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        // 手机左侧放在桌面上 (roll = -90°)：col0=(0,0,-1), col1=(-1,0,0), -col2=(0,1,0)
        val sidewaysPhone = floatArrayOf(
            0f, -1f, 0f,
            0f, 0f, -1f,
            -1f, 0f, 0f
        )
        repeat(40) { tracker.applyOrientationFilter(sidewaysPhone, 0f, 0f, -90f) }
        tracker.anchorToDesktop(0.5f, 0.65f)

        val anchor = tracker.spatialAnchor.value
        assertTrue(anchor.isInView)
        // 相机 Up 应近似对齐世界 +Y (1.0)，确保竖屏状态下角色直立不横躺出画
        assertTrue("侧置竖屏时相机 Up.y 应大于 0.85，实际 ${anchor.cameraUpY}", anchor.cameraUpY > 0.85f)
    }
}
