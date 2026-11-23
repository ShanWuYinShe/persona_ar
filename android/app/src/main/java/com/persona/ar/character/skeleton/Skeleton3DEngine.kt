package com.persona.ar.character.skeleton

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 伴侣 3D 骨骼逆运动学与多视角透视求解引擎
 * 纯端侧零外部依赖，驱动头颈注视 LookAt、待机呼吸、桌沿悬垂坐姿、双马尾弹簧链与 360 度圆柱视差
 */
class Skeleton3DEngine {

    // 默认标准 T-Pose 关节基准位姿 (米)
    private val baseJoints = mapOf(
        JointType.ROOT to Vector3D(0f, 0f, 0f),
        JointType.HIPS to Vector3D(0f, 0.72f, 0f),
        JointType.SPINE to Vector3D(0f, 0.90f, 0f),
        JointType.CHEST to Vector3D(0f, 1.08f, 0f),
        JointType.NECK to Vector3D(0f, 1.25f, 0f),
        JointType.HEAD to Vector3D(0f, 1.38f, 0f),
        JointType.HALO_ANCHOR to Vector3D(0f, 1.62f, 0f),
        JointType.LEFT_SHOULDER to Vector3D(-0.16f, 1.18f, 0f),
        JointType.LEFT_ELBOW to Vector3D(-0.22f, 0.92f, 0f),
        JointType.LEFT_HAND to Vector3D(-0.20f, 0.68f, 0f),
        JointType.RIGHT_SHOULDER to Vector3D(0.16f, 1.18f, 0f),
        JointType.RIGHT_ELBOW to Vector3D(0.22f, 0.92f, 0f),
        JointType.RIGHT_HAND to Vector3D(0.20f, 0.68f, 0f),
        JointType.LEFT_HIP to Vector3D(-0.09f, 0.70f, 0f),
        JointType.LEFT_KNEE to Vector3D(-0.09f, 0.38f, 0f),
        JointType.LEFT_FOOT to Vector3D(-0.09f, 0.02f, 0f),
        JointType.RIGHT_HIP to Vector3D(0.09f, 0.70f, 0f),
        JointType.RIGHT_KNEE to Vector3D(0.09f, 0.38f, 0f),
        JointType.RIGHT_FOOT to Vector3D(0.09f, 0.02f, 0f),
        JointType.PIGTAIL_LEFT_BASE to Vector3D(-0.15f, 1.35f, -0.05f),
        JointType.PIGTAIL_LEFT_TIP to Vector3D(-0.22f, 0.96f, -0.08f),
        JointType.PIGTAIL_RIGHT_BASE to Vector3D(0.15f, 1.35f, -0.05f),
        JointType.PIGTAIL_RIGHT_TIP to Vector3D(0.22f, 0.96f, -0.08f)
    )

    // 当前计算后的全局世界坐标关节集合
    val currentJoints = mutableMapOf<JointType, SkeletonJoint>()

    // 发束弹簧物理速度历史 (Verlet 弹性模拟)
    private var leftPigtailVel = Vector3D.ZERO
    private var rightPigtailVel = Vector3D.ZERO
    private var prevLeftPigtailPos = Vector3D.ZERO
    private var prevRightPigtailPos = Vector3D.ZERO

    init {
        resetToDefault()
    }

    fun resetToDefault() {
        baseJoints.forEach { (type, pos) ->
            currentJoints[type] = SkeletonJoint(
                type = type,
                localPos = pos,
                localRot = Quaternion3D.IDENTITY,
                worldPos = pos
            )
        }
        prevLeftPigtailPos = baseJoints[JointType.PIGTAIL_LEFT_TIP] ?: Vector3D.ZERO
        prevRightPigtailPos = baseJoints[JointType.PIGTAIL_RIGHT_TIP] ?: Vector3D.ZERO
    }

    /**
     * 求解全身 3D 骨骼运动学与空间姿态
     * @param stance 当前体态 (站姿、坐姿、托腮趴桌)
     * @param sitProgress 坐姿平滑过渡插值 (0f 站立 ~ 1f 桌面折腿垂挂坐下)
     * @param breathCycle 呼吸周期相位 (弧度)
     * @param cameraPitch 相机俯仰角 (度数)
     * @param cameraRoll 相机横滚角 (度数)
     * @param cameraYaw 相对偏航角 (度数)
     * @param inertialSwayAngle 物理惯性摆角 (度数)
     * @param gazeTrackingX 视线注视微动 X (-1f ~ 1f)
     * @param gazeTrackingY 视线注视微动 Y (-1f ~ 1f)
     */
    fun solveSkeletonPose(
        stance: SkeletonStance,
        sitProgress: Float,
        breathCycle: Float,
        cameraPitch: Float,
        cameraRoll: Float,
        cameraYaw: Float,
        inertialSwayAngle: Float,
        gazeTrackingX: Float,
        gazeTrackingY: Float
    ) {
        val sitP = sitProgress.coerceIn(0f, 1f)

        // 1. 待机胸腹呼吸微动动力学
        val breathHeave = sin(breathCycle) * 0.012f // 垂直起伏 ~1.2cm
        val breathExpand = cos(breathCycle) * 0.006f // 胸腔横向扩张

        // 2. 3D Head LookAt 头部注视逆解算
        // 头部跟随相机注视，限制在生理自然活动角范围 (水平 ±38°，俯仰 ±22°)
        val targetHeadYaw = (cameraYaw * 0.45f + gazeTrackingX * 20f).coerceIn(-38f, 38f)
        val targetHeadPitch = ((cameraPitch - 45f) * 0.35f + gazeTrackingY * 18f).coerceIn(-22f, 26f)
        val headRotation = Quaternion3D.fromEulerDegrees(targetHeadPitch, targetHeadYaw, -cameraRoll * 0.3f)

        // 3. 坐姿骨骼下肢折叠运动学解算 (Sitting Kinematics)
        // 坐下时：Hips 盆骨下沉 30cm，膝关节前突并折叠 90°，双足垂直悬挂在桌沿下方
        val hipsBase = baseJoints[JointType.HIPS] ?: Vector3D(0f, 0.72f, 0f)
        val hipsWorld = Vector3D(
            x = hipsBase.x,
            y = hipsBase.y - sitP * 0.28f + breathHeave * 0.4f,
            z = hipsBase.z - sitP * 0.06f
        )
        currentJoints[JointType.HIPS] = SkeletonJoint(JointType.HIPS, hipsBase, Quaternion3D.IDENTITY, hipsWorld)

        // 脊柱与胸腔
        val chestBase = baseJoints[JointType.CHEST] ?: Vector3D(0f, 1.08f, 0f)
        val chestWorld = Vector3D(
            x = chestBase.x,
            y = chestBase.y - sitP * 0.26f + breathHeave,
            z = chestBase.z - sitP * 0.03f
        )
        currentJoints[JointType.CHEST] = SkeletonJoint(JointType.CHEST, chestBase, Quaternion3D.IDENTITY, chestWorld)

        // 颈部与头部
        val headBase = baseJoints[JointType.HEAD] ?: Vector3D(0f, 1.38f, 0f)
        val headWorld = Vector3D(
            x = headBase.x,
            y = headBase.y - sitP * 0.25f + breathHeave * 1.1f,
            z = headBase.z
        )
        currentJoints[JointType.HEAD] = SkeletonJoint(JointType.HEAD, headBase, headRotation, headWorld)

        // 头顶光环 3D 浮动跟随
        val haloBase = baseJoints[JointType.HALO_ANCHOR] ?: Vector3D(0f, 1.62f, 0f)
        val haloWorld = Vector3D(
            x = headWorld.x,
            y = headWorld.y + 0.24f + sin(breathCycle * 0.8f) * 0.015f,
            z = headWorld.z
        )
        currentJoints[JointType.HALO_ANCHOR] = SkeletonJoint(JointType.HALO_ANCHOR, haloBase, Quaternion3D.IDENTITY, haloWorld)

        // 双膝与双腿
        // 站立态：Knee y=0.38, Foot y=0.02
        // 坐姿态：Knee 弯曲水平伸展 (y=0.44, z=0.22), Foot 悬挂垂下 (y=0.10, z=0.22)
        val leftKneeStand = baseJoints[JointType.LEFT_KNEE] ?: Vector3D(-0.09f, 0.38f, 0f)
        val leftKneeSit = Vector3D(-0.09f, 0.44f, 0.22f)
        val leftKneeWorld = leftKneeStand.lerp(leftKneeSit, sitP)
        currentJoints[JointType.LEFT_KNEE] = SkeletonJoint(JointType.LEFT_KNEE, leftKneeStand, Quaternion3D.IDENTITY, leftKneeWorld)

        val rightKneeStand = baseJoints[JointType.RIGHT_KNEE] ?: Vector3D(0.09f, 0.38f, 0f)
        val rightKneeSit = Vector3D(0.09f, 0.44f, 0.22f)
        val rightKneeWorld = rightKneeStand.lerp(rightKneeSit, sitP)
        currentJoints[JointType.RIGHT_KNEE] = SkeletonJoint(JointType.RIGHT_KNEE, rightKneeStand, Quaternion3D.IDENTITY, rightKneeWorld)

        val leftFootStand = baseJoints[JointType.LEFT_FOOT] ?: Vector3D(-0.09f, 0.02f, 0f)
        val leftFootSit = Vector3D(-0.09f, 0.12f, 0.24f)
        val leftFootWorld = leftFootStand.lerp(leftFootSit, sitP)
        currentJoints[JointType.LEFT_FOOT] = SkeletonJoint(JointType.LEFT_FOOT, leftFootStand, Quaternion3D.IDENTITY, leftFootWorld)

        val rightFootStand = baseJoints[JointType.RIGHT_FOOT] ?: Vector3D(0.09f, 0.02f, 0f)
        val rightFootSit = Vector3D(0.09f, 0.12f, 0.24f)
        val rightFootWorld = rightFootStand.lerp(rightFootSit, sitP)
        currentJoints[JointType.RIGHT_FOOT] = SkeletonJoint(JointType.RIGHT_FOOT, rightFootStand, Quaternion3D.IDENTITY, rightFootWorld)

        // 4. 双马尾柔体弹簧骨骼 (Spring-Bone Dynamics)
        solveSpringHair(headWorld, inertialSwayAngle, breathExpand)
    }

    private fun solveSpringHair(headWorld: Vector3D, inertialSwayAngle: Float, breathExpand: Float) {
        val swayRad = (inertialSwayAngle * PI / 180f).toFloat()
        val inertiaOffset = sin(swayRad) * 0.04f

        val leftBase = Vector3D(headWorld.x - 0.15f - breathExpand, headWorld.y - 0.03f, headWorld.z - 0.05f)
        val rightBase = Vector3D(headWorld.x + 0.15f + breathExpand, headWorld.y - 0.03f, headWorld.z - 0.05f)

        currentJoints[JointType.PIGTAIL_LEFT_BASE] = SkeletonJoint(JointType.PIGTAIL_LEFT_BASE, leftBase, Quaternion3D.IDENTITY, leftBase)
        currentJoints[JointType.PIGTAIL_RIGHT_BASE] = SkeletonJoint(JointType.PIGTAIL_RIGHT_BASE, rightBase, Quaternion3D.IDENTITY, rightBase)

        // 弹簧回弹模拟：末端受惯性漂移与弹力恢复
        val targetLeftTip = Vector3D(leftBase.x - 0.07f - inertiaOffset, leftBase.y - 0.38f, leftBase.z - 0.03f)
        val targetRightTip = Vector3D(rightBase.x + 0.07f - inertiaOffset, rightBase.y - 0.38f, rightBase.z - 0.03f)

        val currentLeftTip = prevLeftPigtailPos.lerp(targetLeftTip, 0.25f)
        val currentRightTip = prevRightPigtailPos.lerp(targetRightTip, 0.25f)

        prevLeftPigtailPos = currentLeftTip
        prevRightPigtailPos = currentRightTip

        currentJoints[JointType.PIGTAIL_LEFT_TIP] = SkeletonJoint(JointType.PIGTAIL_LEFT_TIP, targetLeftTip, Quaternion3D.IDENTITY, currentLeftTip)
        currentJoints[JointType.PIGTAIL_RIGHT_TIP] = SkeletonJoint(JointType.PIGTAIL_RIGHT_TIP, targetRightTip, Quaternion3D.IDENTITY, currentRightTip)
    }

    /**
     * 计算 360 度多视角投影参数与空间视差
     * @param rotationYaw 空间偏航角 (度数)
     * @param cameraPitch 空间俯仰角 (度数)
     */
    fun calculateMultiViewPerspective(rotationYaw: Float, cameraPitch: Float): MultiViewProjection {
        // 归一化为 -180° ~ +180°
        var yaw = rotationYaw % 360f
        if (yaw > 180f) yaw -= 360f
        if (yaw < -180f) yaw += 360f

        val absYaw = abs(yaw)
        val isLeft = yaw > 0f

        val profile = when {
            absYaw <= 22f -> MultiViewProfile.FRONT
            absYaw <= 68f -> if (isLeft) MultiViewProfile.THREE_QUARTER_LEFT else MultiViewProfile.THREE_QUARTER_RIGHT
            absYaw <= 118f -> if (isLeft) MultiViewProfile.PROFILE_LEFT else MultiViewProfile.PROFILE_RIGHT
            else -> MultiViewProfile.BACK_LOOK_BACK
        }

        // 3/4 侧身过渡系数 (0f 正面 ~ 1f 完全侧身)
        val threeQuarterProgress = ((absYaw - 18f) / 45f).coerceIn(0f, 1f)

        // 正侧面过渡系数
        val profileProgress = ((absYaw - 65f) / 45f).coerceIn(0f, 1f)

        // 背面回眸过渡系数
        val backProgress = ((absYaw - 110f) / 40f).coerceIn(0f, 1f)

        // 3D 躯干圆柱体横向收缩系数 (避免平片缩成纸片，圆柱体保留最小厚度 ~0.65)
        val yawRad = (absYaw * PI / 180f).toFloat()
        val cylinderContractionX = (cos(yawRad)).coerceIn(0.62f, 1.0f)

        // 各部件多层视差位移 (Parallax Disparity，左右旋转时前层凸起、后层反向位移)
        val sign = if (isLeft) 1f else -1f
        val depthDisparityHead = sign * sin(yawRad) * 14f
        val depthDisparityHairBack = -sign * sin(yawRad) * 18f // 后发反向位移产生立体纵深
        val depthDisparityFace = sign * sin(yawRad) * 10f
        val depthDisparityArms = sign * sin(yawRad) * 16f

        return MultiViewProjection(
            normalizedYaw = yaw,
            profile = profile,
            threeQuarterProgress = threeQuarterProgress,
            profileProgress = profileProgress,
            backProgress = backProgress,
            cylinderContractionX = cylinderContractionX,
            depthDisparityHead = depthDisparityHead,
            depthDisparityHairBack = depthDisparityHairBack,
            depthDisparityFace = depthDisparityFace,
            depthDisparityArms = depthDisparityArms,
            isFacingLeft = isLeft
        )
    }
}
