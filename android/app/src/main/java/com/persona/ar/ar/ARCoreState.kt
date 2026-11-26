package com.persona.ar.ar

import com.persona.ar.character.skeleton.SkeletonStance

/**
 * AR 空间追踪器底层驱动类型
 */
enum class ARTrackerType(val displayName: String, val gmsRequired: Boolean) {
    /** 视觉-惯性 6DoF 空间追踪器 (基于 IMU 融合与稀疏光流锚点重投影，零 GMS 依赖，全机型离线可用) */
    UNIVERSAL_SENSOR("视觉惯性 6DoF 追踪 (零 GMS)", false),
    /** Google ARCore 官方 SLAM 引擎 (需 Google Play AR 服务支持) */
    GOOGLE_ARCORE("Google ARCore (需 GMS)", true)
}

/**
 * AR 空间追踪与平面检测状态枚举
 */
enum class ARTrackingStatus {
    /** 正在初始化传感器与相机 */
    INITIALIZING,
    /** 正在搜寻水平桌面/地面，提示用户移动镜头 */
    SEARCHING_PLANE,
    /** 已检测到平坦表面，准心已就绪，提示用户点击放置 */
    PLANE_DETECTED,
    /** 3D 角色已成功物理锚定在现实空间中 */
    ANCHORED,
    /** 空间追踪受阻或特征丢失（光线过暗或镜头遮挡） */
    TRACKING_LOST
}

/**
 * 物理空间锚点数据模型 (AR Spatial Anchor)
 * 完整承载 6DoF 相机位姿、世界锚点坐标、真实光学 FOV 与多方位环境光照探针。
 */
data class ARSpatialAnchor(
    val trackerType: ARTrackerType = ARTrackerType.UNIVERSAL_SENSOR,
    /** 模型脚底在屏幕上的归一化投影坐标 (0f..1f，左上角原点) */
    val screenX: Float = 0.5f,
    val screenY: Float = 0.65f,
    /** 相机到模型脚底的实际空间物理距离 (米) */
    val distanceMeters: Float = 0.65f,
    /** 空间缩放比例 (基于双指手势，0.4f..2.5f) */
    val scaleFactor: Float = 1.0f,
    /** 角色在世界水平面上的自身偏航朝向角 (度数, 0 为锚定瞬间正面朝向用户) */
    val rotationYaw: Float = 0.0f,
    /** 现实环境光照强度估计 (0.18f..1.35f) */
    val lightIntensity: Float = 0.8f,
    /** 是否处于昏暗环境 */
    val isLowLight: Boolean = false,
    /** 现实环境色温偏向 (-1.0f 冷蓝 ~ +1.0f 暖黄) */
    val ambientColorTemp: Float = 0f,
    /** 全局环境光红绿蓝三原色增益 */
    val ambientTintRed: Float = 1.0f,
    val ambientTintGreen: Float = 1.0f,
    val ambientTintBlue: Float = 1.0f,
    /** 6 方向环境光色调（天顶、桌面底面、左、右），驱动动态 IBL 球谐与环境反射贴图 */
    val envTopRed: Float = 1.0f,
    val envTopGreen: Float = 1.0f,
    val envTopBlue: Float = 1.0f,
    val envBottomRed: Float = 1.0f,
    val envBottomGreen: Float = 0.96f,
    val envBottomBlue: Float = 0.92f,
    val envLeftRed: Float = 1.0f,
    val envLeftGreen: Float = 1.0f,
    val envLeftBlue: Float = 1.0f,
    val envRightRed: Float = 1.0f,
    val envRightGreen: Float = 1.0f,
    val envRightBlue: Float = 1.0f,
    /** 现实相机暗光 ISO 微噪点强度 (0.0f ~ 1.0f) */
    val sensorGrainIntensity: Float = 0f,
    /** 镜头注视微动偏移 (-1f..1f) */
    val cameraGazeOffsetX: Float = 0f,
    val cameraGazeOffsetY: Float = 0f,
    val chromaticAberrationIntensity: Float = 0f,
    val chromaticAngleRad: Float = 0f,
    val bloomIntensity: Float = 0f,
    val depthOfFieldBlur: Float = 0f,
    val isMacroProximity: Boolean = false,
    /** 相机相对物理桌面的 3D 俯仰倾角 (度数, 0° 平视 ~ 90° 垂直俯视桌面) */
    val cameraPitchDegrees: Float = 45f,
    /** 相机左右横滚倾角 (度数, -90° ~ 90°) */
    val cameraRollDegrees: Float = 0f,
    /** 当前落脚点局部桌面底色红绿蓝反射分量 */
    val localSurfaceTintRed: Float = 1.0f,
    val localSurfaceTintGreen: Float = 1.0f,
    val localSurfaceTintBlue: Float = 1.0f,
    val localSurfaceLuminance: Float = 1.0f,
    /** 主光源在屏幕空间的二维方向与强度 */
    val lightDirectionX: Float = 0.0f,
    val lightDirectionY: Float = -0.5f,
    val lightDirectionalStrength: Float = 0.25f,
    /** 锁定在物理世界坐标系中的主方向光矢量（绕模型走动时影子稳定不转圈） */
    val hasWorldLightDir: Boolean = false,
    val worldLightDirX: Float = 0.25f,
    val worldLightDirY: Float = -0.85f,
    val worldLightDirZ: Float = 0.45f,
    /** 桌面特征纹理置信度 (0.0f ~ 1.0f) */
    val deskTextureConfidence: Float = 0.5f,
    val visualTrackingConfidence: Float = 0f,
    val lensVignetteFactor: Float = 1.0f,
    /** 物理相机自动对焦提示状态 */
    val isCameraFocusing: Boolean = false,
    val cameraFocusTimestamp: Long = 0L,
    /** 用户是否正在拖拽模型重定位 */
    val isDragging: Boolean = false,
    /** 物理拖拽与移动惯性摆角 (度数) */
    val inertialSwayAngle: Float = 0f,
    /** 全身 3D 骨骼体态 (站姿 / 坐姿) */
    val skeletonStance: SkeletonStance = SkeletonStance.STANDING,
    /** 是否已将角色锚定在现实平面 */
    val isAnchored: Boolean = false,
    val planeTypeDescription: String = "桌面实景 6DoF 空间追踪",
    /** 真实 3D 物理世界坐标系下的锚点位置与 6DoF 相机位姿 */
    val isWorldAnchored: Boolean = false,
    val worldAnchorX: Float = 0f,
    val worldAnchorY: Float = -0.12f,
    val worldAnchorZ: Float = -0.65f,
    val cameraPosX: Float = 0f,
    val cameraPosY: Float = 0f,
    val cameraPosZ: Float = 0f,
    val cameraRightX: Float = 1f,
    val cameraRightY: Float = 0f,
    val cameraRightZ: Float = 0f,
    val cameraForwardX: Float = 0f,
    val cameraForwardY: Float = 0f,
    val cameraForwardZ: Float = -1f,
    val cameraUpX: Float = 0f,
    val cameraUpY: Float = 1f,
    val cameraUpZ: Float = 0f,
    /** 当前真实屏幕有效垂直视场角 (度) */
    val effectiveVerticalFovDegrees: Float = 62f,
    /** 手办是否处于当前相机视锥体范围内 */
    val isInView: Boolean = true,
    /** 相对相机中心的水平方位偏角 (负=左侧，正=右侧，用于边缘寻回提示与回眸注视) */
    val azimuthDeltaDegrees: Float = 0f,
    /** 绕模型观察时的相对轨道偏航角（度，用于角色在合理范围内侧目注视相机） */
    val orbitRelativeYawDegrees: Float = 0f
)

data class ARReticle(
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.65f,
    val isTrackingPlane: Boolean = false,
    val planeType: String = "平面"
)
