package com.persona.ar.character.skeleton

/**
 * 伴侣二次元 3D 骨骼关节类型枚举
 * 覆盖头部注视 IK、躯干呼吸、双臂手势、下肢折叠坐姿与双马尾弹簧链
 */
enum class JointType {
    ROOT,               // 双足接地原点 (0, 0, 0)
    HIPS,               // 骨盆重心 (坐姿时下沉折叠)
    SPINE,              // 脊椎 (呼吸微升降与微俯仰)
    CHEST,              // 胸腔 (挺胸自信姿态与左右微倾)
    NECK,               // 颈部
    HEAD,               // 头部 (3D LookAt 视角注视求解)
    LEFT_SHOULDER,
    LEFT_ELBOW,
    LEFT_HAND,          // 左手 (招手/叉腰/下垂)
    RIGHT_SHOULDER,
    RIGHT_ELBOW,
    RIGHT_HAND,         // 右手 (招手/抚桌/叉腰)
    LEFT_HIP,
    LEFT_KNEE,
    LEFT_FOOT,          // 左足
    RIGHT_HIP,
    RIGHT_KNEE,
    RIGHT_FOOT,         // 右足
    HALO_ANCHOR,        // 头顶光环 3D 浮动锚点
    PIGTAIL_LEFT_BASE,  // 左马尾根部
    PIGTAIL_LEFT_TIP,   // 左马尾末端 (弹簧柔体)
    PIGTAIL_RIGHT_BASE, // 右马尾根部
    PIGTAIL_RIGHT_TIP   // 右马尾末端 (弹簧柔体)
}

/**
 * 单个 3D 骨骼关节定义
 */
data class SkeletonJoint(
    val type: JointType,
    val localPos: Vector3D,
    val localRot: Quaternion3D = Quaternion3D.IDENTITY,
    val worldPos: Vector3D = localPos,
    val parentType: JointType? = null
)

/**
 * 伴侣全身 3D 姿态体态枚举
 */
enum class SkeletonStance {
    STANDING,   // 自然站立 (微重心摆动)
    SITTING,    // 桌面边缘垂腿坐姿 (双膝折叠 90°，双腿悬垂桌沿，双脚轻晃)
    TABLE_LEAN, // 桌面托腮趴桌姿态 (身体前倾，手肘撑桌面)
    STEPPING    // 机械键盘或台阶单足跨步姿态
}

/**
 * 观察视角枚举 (360 度多视角)
 */
enum class MultiViewProfile {
    FRONT,               // 正面视角 (-20° ~ 20°)
    THREE_QUARTER_LEFT,  // 3/4 侧身偏左 (20° ~ 65°)
    THREE_QUARTER_RIGHT, // 3/4 侧身偏右 (-65° ~ -20°)
    PROFILE_LEFT,        // 正侧面偏左 (65° ~ 115°)
    PROFILE_RIGHT,       // 正侧面偏右 (-115° ~ -65°)
    BACK_LOOK_BACK       // 背面/半背面回眸 (> 115° 或 < -115°)
}

/**
 * 多视角投影空间参数
 */
data class MultiViewProjection(
    val normalizedYaw: Float,
    val profile: MultiViewProfile,
    val threeQuarterProgress: Float,
    val profileProgress: Float,
    val backProgress: Float,
    val cylinderContractionX: Float,
    val depthDisparityHead: Float,
    val depthDisparityHairBack: Float,
    val depthDisparityFace: Float,
    val depthDisparityArms: Float,
    val isFacingLeft: Boolean
)
