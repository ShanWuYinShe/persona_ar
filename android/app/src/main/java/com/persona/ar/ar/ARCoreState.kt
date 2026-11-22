package com.persona.ar.ar

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
    /** 屏幕投影归一化 X 坐标 (0f..1f) */
    val screenX: Float = 0.5f,
    /** 屏幕投影归一化 Y 坐标 (0f..1f) */
    val screenY: Float = 0.65f,
    /** 与设备镜头的实际空间物理距离 (米) */
    val distanceMeters: Float = 0.85f,
    /** 空间缩放比例 (基于手势或距离自适应，0.35f..2.5f) */
    val scaleFactor: Float = 1.0f,
    /** 面对指挥官的偏航朝向角 (度数, 0..360) */
    val rotationYaw: Float = 0.0f,
    /** 现实环境光照强度估计 (0f..1f) */
    val lightIntensity: Float = 0.8f,
    /** 是否已将角色锚定在现实平面 */
    val isAnchored: Boolean = false,
    /** 物理平面类型名称 (例如 "桌面"、"地面") */
    val planeTypeDescription: String = "书桌平面"
)

/**
 * 空间准心引导点 (AR Reticle)
 */
data class ARReticleState(
    val visible: Boolean = true,
    val screenX: Float = 0.5f,
    val screenY: Float = 0.65f,
    val surfaceNormalY: Float = 1.0f,
    val pulseProgress: Float = 0.0f
)
