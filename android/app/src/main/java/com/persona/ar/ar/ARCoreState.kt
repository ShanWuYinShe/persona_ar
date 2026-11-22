package com.persona.ar.ar

/**
 * AR 空间追踪器底层驱动类型
 */
enum class ARTrackerType(val displayName: String, val gmsRequired: Boolean) {
    /** 通用实景桌面视差追踪器 (基于原生 IMU 传感器与相机视差，0 依赖，全机型 100% 离线可用) */
    UNIVERSAL_SENSOR("通用实景视差 (零 GMS 依赖)", false),
    /** 契约阵法召唤模式 (带二游旋转符文光环与召唤光柱) */
    SUMMONING_CIRCLE("契约阵法召唤 (二游专属)", false),
    /** Google ARCore 官方 SLAM 引擎 (需要 Google Play AR 服务支持) */
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
    /** 纸片人角色已成功物理锚定在现实空间中 */
    ANCHORED,
    /** 空间追踪受阻或特征丢失（光线过暗或镜头遮挡） */
    TRACKING_LOST
}

/**
 * 物理空间锚点数据模型 (AR Spatial Anchor)
 */
data class ARSpatialAnchor(
    /** 当前生效的追踪器类型 */
    val trackerType: ARTrackerType = ARTrackerType.UNIVERSAL_SENSOR,
    /** 屏幕投影归一化 X 坐标 (0f..1f) */
    val screenX: Float = 0.5f,
    /** 屏幕投影归一化 Y 坐标 (0f..1f) */
    val screenY: Float = 0.65f,
    /** 与设备镜头的实际空间物理距离 (米) */
    val distanceMeters: Float = 0.65f,
    /** 空间缩放比例 (基于手势或距离自适应，0.35f..2.5f) */
    val scaleFactor: Float = 1.0f,
    /** 面对指挥官的偏航朝向角 (度数, 0..360) */
    val rotationYaw: Float = 0.0f,
    /** 现实环境光照强度估计 (0f..1.2f) */
    val lightIntensity: Float = 0.8f,
    /** 实时视差动态偏移量 X (像素 DP) */
    val parallaxShiftX: Float = 0f,
    /** 实时视差动态偏移量 Y (像素 DP) */
    val parallaxShiftY: Float = 0f,
    /** 是否处于昏暗环境 (触发伴侣温馨关怀与暗调环境光) */
    val isLowLight: Boolean = false,
    /** 现实环境色调倾向 (ARGB) */
    val ambientColorTint: Long = 0xFFFFFFFF,
    /** 镜头注视微动偏移 X (-1f..1f) */
    val cameraGazeOffsetX: Float = 0f,
    /** 镜头注视微动偏移 Y (-1f..1f) */
    val cameraGazeOffsetY: Float = 0f,
    /** 是否已将角色锚定在现实平面 */
    val isAnchored: Boolean = false,
    /** 物理平面类型名称 (例如 "桌面"、"地面") */
    val planeTypeDescription: String = "书桌实景 (零 GMS 通用视差)"
)

/**
 * 空间准心引导点 (AR Reticle)
 */
data class ARReticle(
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.65f,
    val isTrackingPlane: Boolean = false,
    val planeType: String = "平面"
)
