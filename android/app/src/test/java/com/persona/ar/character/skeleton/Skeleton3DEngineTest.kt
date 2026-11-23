package com.persona.ar.character.skeleton

import org.junit.Assert.*
import org.junit.Test

class Skeleton3DEngineTest {

    @Test
    fun testVector3DOperations() {
        val v1 = Vector3D(1f, 2f, 3f)
        val v2 = Vector3D(4f, 5f, 6f)
        val sum = v1 + v2
        assertEquals(5f, sum.x, 1e-4f)
        assertEquals(7f, sum.y, 1e-4f)
        assertEquals(9f, sum.z, 1e-4f)

        val dot = v1.dot(v2)
        assertEquals(32f, dot, 1e-4f)

        val cross = Vector3D.RIGHT.cross(Vector3D.UP)
        assertEquals(Vector3D.FORWARD.x, cross.x, 1e-4f)
        assertEquals(Vector3D.FORWARD.y, cross.y, 1e-4f)
        assertEquals(Vector3D.FORWARD.z, cross.z, 1e-4f)
    }

    @Test
    fun testQuaternionRotation() {
        // 绕 Y 轴旋转 90 度
        val q = Quaternion3D.fromEulerDegrees(0f, 90f, 0f)
        val rotatedForward = q.rotate(Vector3D.FORWARD)
        assertEquals(1f, rotatedForward.x, 1e-4f)
        assertEquals(0f, rotatedForward.y, 1e-4f)
        assertEquals(0f, rotatedForward.z, 1e-4f)
    }

    @Test
    fun testMultiViewProfileCalculation() {
        val engine = Skeleton3DEngine()

        // 正面
        val front = engine.calculateMultiViewPerspective(0f, 45f)
        assertEquals(MultiViewProfile.FRONT, front.profile)
        assertEquals(1.0f, front.cylinderContractionX, 1e-3f)

        // 3/4 侧身
        val threeQuarter = engine.calculateMultiViewPerspective(45f, 45f)
        assertEquals(MultiViewProfile.THREE_QUARTER_LEFT, threeQuarter.profile)
        assertTrue(threeQuarter.threeQuarterProgress > 0.4f)
        assertTrue(threeQuarter.cylinderContractionX >= 0.65f)

        // 正侧面
        val side = engine.calculateMultiViewPerspective(90f, 45f)
        assertEquals(MultiViewProfile.PROFILE_LEFT, side.profile)
        assertTrue(side.profileProgress > 0.4f)

        // 背面回眸
        val back = engine.calculateMultiViewPerspective(140f, 45f)
        assertEquals(MultiViewProfile.BACK_LOOK_BACK, back.profile)
        assertTrue(back.backProgress > 0.5f)
    }

    @Test
    fun testSittingKinematicsSolving() {
        val engine = Skeleton3DEngine()
        // 站姿求解
        engine.solveSkeletonPose(
            stance = SkeletonStance.STANDING,
            sitProgress = 0f,
            breathCycle = 0f,
            cameraPitch = 45f,
            cameraRoll = 0f,
            cameraYaw = 0f,
            inertialSwayAngle = 0f,
            gazeTrackingX = 0f,
            gazeTrackingY = 0f
        )
        val standingHipsY = engine.currentJoints[JointType.HIPS]?.worldPos?.y ?: 0f

        // 坐姿求解
        engine.solveSkeletonPose(
            stance = SkeletonStance.SITTING,
            sitProgress = 1.0f,
            breathCycle = 0f,
            cameraPitch = 45f,
            cameraRoll = 0f,
            cameraYaw = 0f,
            inertialSwayAngle = 0f,
            gazeTrackingX = 0f,
            gazeTrackingY = 0f
        )
        val sittingHipsY = engine.currentJoints[JointType.HIPS]?.worldPos?.y ?: 0f

        // 坐下后 Hips 重心应当自然下沉折叠
        assertTrue(sittingHipsY < standingHipsY)

        // 膝盖在坐下后应当水平向前伸出 (Z > 0)
        val kneeZ = engine.currentJoints[JointType.LEFT_KNEE]?.worldPos?.z ?: 0f
        assertTrue(kneeZ > 0.15f)
    }
}
