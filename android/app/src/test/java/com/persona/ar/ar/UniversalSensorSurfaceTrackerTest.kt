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

    @Test
    fun `dragTo moves anchor onto horizontal plane under cursor with zero bounce-back`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        tracker.updateCameraIntrinsics(62f, 0.5f)
        // 向下俯视桌面约 30°：Right=(1,0,0), Up=(0, cos30, sin30), Fwd=(0, -sin30, -cos30) => -Fwd=(0, sin30, cos30)
        // ENU cols: col0=(1,0,0), col1=(0, sin30, cos30), col2=(0, -cos30, sin30) => up_world = col1 -> Fwd in world? Wait:
        // 直接喂入标准朝正北略向下俯视 35° 的旋转矩阵
        val p = Math.toRadians(35.0).toFloat()
        val cp = kotlin.math.cos(p)
        val sp = kotlin.math.sin(p)
        // ENU col0=(1,0,0), col1(devY)=(0, sp, cp), col2(devZ)=(0, -cp, sp) => forward_enu=-col2=(0, cp, -sp)
        val tiltedNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, sp, -cp,
            0f, cp, sp
        )
        repeat(40) { tracker.applyOrientationFilter(tiltedNorth, 0f, 35f, 0f) }
        tracker.anchorToDesktop(0.5f, 0.65f)
        val startPlaneY = tracker.spatialAnchor.value.worldAnchorY

        tracker.beginDrag(0.5f, 0.65f)
        assertTrue(tracker.spatialAnchor.value.isDragging)

        // 手指拖向画面左上角 (0.28f, 0.52f)
        val duringLeft = tracker.dragTo(0.28f, 0.52f)
        assertEquals("拖拽时模型必须在同一水平桌面上移动", startPlaneY, duringLeft.worldAnchorY, 1e-4f)
        assertEquals("屏幕投影 X 必须紧跟手指", 0.28f, duringLeft.screenX, 0.02f)
        assertEquals("屏幕投影 Y 必须紧跟手指", 0.52f, duringLeft.screenY, 0.02f)

        // 再拖到屏幕最下缘接近桌边 (0.76f, 0.85f)，确认绝不乱跑或弹回
        val duringEdge = tracker.dragTo(0.76f, 0.85f)
        assertEquals(startPlaneY, duringEdge.worldAnchorY, 1e-4f)
        assertEquals(0.76f, duringEdge.screenX, 0.02f)
        assertEquals(0.85f, duringEdge.screenY, 0.02f)

        val ended = tracker.endDrag()
        assertFalse(ended.isDragging)
        assertEquals("松手后零回弹，保持在拖放点 X", duringEdge.screenX, ended.screenX, 0.005f)
        assertEquals("松手后零回弹，保持在拖放点 Y", duringEdge.screenY, ended.screenY, 0.005f)
        assertEquals(duringEdge.worldAnchorX, ended.worldAnchorX, 1e-4f)
        assertEquals(duringEdge.worldAnchorZ, ended.worldAnchorZ, 1e-4f)
    }

    @Test
    fun `visual inertial fusion enables 360 orbit around fixed world anchor`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        tracker.updateCameraIntrinsics(62f, 0.5f)
        val uprightNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        repeat(40) { tracker.applyOrientationFilter(uprightNorth, 0f, 0f, 0f) }
        tracker.anchorToDesktop(0.5f, 0.65f)
        val initialAnchorX = tracker.spatialAnchor.value.worldAnchorX
        val initialAnchorY = tracker.spatialAnchor.value.worldAnchorY
        val initialAnchorZ = tracker.spatialAnchor.value.worldAnchorZ

        // 模拟用户持手机走到模型右侧 90°（面向正西，继续把镜头对着模型所在屏幕中心）
        val facingWest = floatArrayOf(
            0f, 0f, 1f,
            1f, 0f, 0f,
            0f, 1f, 0f
        )
        val basisR = FloatArray(3)
        val basisU = FloatArray(3)
        val basisF = FloatArray(3)
        repeat(40) {
            tracker.applyOrientationFilter(facingWest, -90f, 0f, 0f)
            tracker.snapshotImuWorldBasis(basisR, basisU, basisF)
            tracker.onVisualAnchorObserved(
                VisualAnchorObservation(
                    trackedScreenX = 0.5f,
                    trackedScreenY = 0.65f,
                    scaleRatio = 1.0f,
                    confidence = 0.9f,
                    inlierCount = 8,
                    isLost = false
                ),
                basisR, basisU, basisF
            )
        }

        val orbited = tracker.spatialAnchor.value
        // 世界锚点坐标绝对不能漂移
        assertEquals(initialAnchorX, orbited.worldAnchorX, 1e-4f)
        assertEquals(initialAnchorY, orbited.worldAnchorY, 1e-4f)
        assertEquals(initialAnchorZ, orbited.worldAnchorZ, 1e-4f)
        // 相机必须绕到了锚点的右侧 (+X 方向) 观察模型侧面
        assertTrue("绕到右侧时相机位置 cameraPosX 应显著为正，实际 ${orbited.cameraPosX}", orbited.cameraPosX > 0.35f)
        assertTrue("绕圈时模型保持在画面视野中", orbited.isInView)
    }

    @Test
    fun `stationary noisy visual observations over 300 frames do not shrink or drift anchor`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        tracker.updateCameraIntrinsics(62f, 1080f / 2340f)
        val p = Math.toRadians(35.0).toFloat()
        val cp = kotlin.math.cos(p)
        val sp = kotlin.math.sin(p)
        val tiltedNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, sp, -cp,
            0f, cp, sp
        )
        repeat(40) { tracker.applyOrientationFilter(tiltedNorth, 0f, 35f, 0f) }
        tracker.anchorToDesktop(0.5f, 0.65f)
        val initialDist = tracker.spatialAnchor.value.distanceMeters
        val initialAnchorX = tracker.spatialAnchor.value.worldAnchorX
        val initialAnchorZ = tracker.spatialAnchor.value.worldAnchorZ

        val basisR = FloatArray(3)
        val basisU = FloatArray(3)
        val basisF = FloatArray(3)
        tracker.snapshotImuWorldBasis(basisR, basisU, basisF)

        // 模拟传统会拖垮尺度的帧间亚像素缩小噪声 (0.985) 与微弱漂移 (0.006)
        repeat(300) { idx ->
            val jitter = if (idx % 2 == 0) 0.006f else -0.005f
            tracker.onVisualAnchorObserved(
                VisualAnchorObservation(
                    trackedScreenX = 0.5f + jitter,
                    trackedScreenY = 0.65f - jitter * 0.5f,
                    scaleRatio = 0.985f,
                    confidence = 0.85f,
                    inlierCount = 7,
                    isLost = false,
                    keyframeId = 1,
                    keyframeScaleRatio = 0.985f
                ),
                basisR, basisU, basisF
            )
            tracker.applyOrientationFilter(tiltedNorth, 0f, 35f, 0f)
        }

        val after = tracker.spatialAnchor.value
        assertEquals("静止噪声下相机距人偶距离绝不能被拉远缩成小人", initialDist, after.distanceMeters, 0.015f)
        assertEquals(0.5f, after.screenX, 0.01f)
        assertEquals(0.65f, after.screenY, 0.01f)
        assertEquals(initialAnchorX, after.worldAnchorX, 1e-4f)
        assertEquals(initialAnchorZ, after.worldAnchorZ, 1e-4f)
    }

    @Test
    fun `keyframeScaleRatio converges once without exponential compounding across repeated frames`() {
        val tracker = UniversalSensorSurfaceTracker(fakeContext)
        tracker.updateCameraIntrinsics(62f, 0.5f)
        val uprightNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        repeat(40) { tracker.applyOrientationFilter(uprightNorth, 0f, 0f, 0f) }
        tracker.anchorToDesktop(0.5f, 0.65f)

        val basisR = FloatArray(3)
        val basisU = FloatArray(3)
        val basisF = FloatArray(3)
        tracker.snapshotImuWorldBasis(basisR, basisU, basisF)

        // 同一关键帧持续汇报靠近 1.25x：距离应收敛至 0.65 / 1.25 = 0.52m，绝不指数塌缩到 min 0.24m
        repeat(60) {
            tracker.onVisualAnchorObserved(
                VisualAnchorObservation(
                    trackedScreenX = 0.5f,
                    trackedScreenY = 0.65f,
                    scaleRatio = 1.25f,
                    confidence = 0.9f,
                    inlierCount = 8,
                    isLost = false,
                    keyframeId = 1,
                    keyframeScaleRatio = 1.25f
                ),
                basisR, basisU, basisF
            )
            tracker.applyOrientationFilter(uprightNorth, 0f, 0f, 0f)
        }

        val after = tracker.spatialAnchor.value
        assertEquals(0.65f / 1.25f, after.distanceMeters, 0.04f)
        assertTrue("不应指数连除压到下限 0.24m，实际 ${after.distanceMeters}", after.distanceMeters > 0.45f)
    }
}
