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
    /** 现实环境色温偏向 (-1.0f 冷蓝 ~ +1.0f 暖黄) */
    val ambientColorTemp: Float = 0f,
    /** 环境光红绿蓝三原色增益 */
    val ambientTintRed: Float = 1.0f,
    val ambientTintGreen: Float = 1.0f,
    val ambientTintBlue: Float = 1.0f,
    /** 现实相机暗光 ISO 微噪点强度 (0.0f ~ 1.0f) */
    val sensorGrainIntensity: Float = 0f,
    /** 镜头注视微动偏移 X (-1f..1f) */
    val cameraGazeOffsetX: Float = 0f,
    /** 镜头注视微动偏移 Y (-1f..1f) */
    val cameraGazeOffsetY: Float = 0f,
    /** 真实广角镜头边缘径向色散强度 (0.0f ~ 1.0f) */
    val chromaticAberrationIntensity: Float = 0f,
    /** 镜头色散径向倾角弧度 */
    val chromaticAngleRad: Float = 0f,
    /** 强光逆光环境下的镜头光渗与边缘光晕 (0.0f ~ 1.0f) */
    val bloomIntensity: Float = 0f,
    /** 镜头景深焦外弥散度 (0.0f ~ 1.0f) */
    val depthOfFieldBlur: Float = 0f,
    /** 镜头微距特写状态 */
    val isMacroProximity: Boolean = false,
    /** 桌面边缘深度遮挡切片比例 (0.0f ~ 1.0f) */
    val surfaceOcclusionBottomCutoff: Float = 0f,
    /** 相机相对物理桌面的 3D 俯仰倾角 (度数, 0° 垂直正立 ~ 90° 水平俯视桌面) */
    val cameraPitchDegrees: Float = 45f,
    /** 相机左右横滚倾角 (度数, -90° ~ 90°，用于重力铅垂锁定) */
    val cameraRollDegrees: Float = 0f,
    /** 3D 空间透视投影虚拟相机视距 (Focal Distance) */
    val cameraFocalDistance: Float = 10f,
    /** 伴侣脚底采样到的现实桌面材质底色 (ARGB) */
    val localSurfaceTintHex: Long = 0xFFFFFFFF,
    /** 当前落脚点局部桌面底色红绿蓝反射分量 (用于木质桌面、深色桌面精细漫反射) */
    val localSurfaceTintRed: Float = 1.0f,
    val localSurfaceTintGreen: Float = 1.0f,
    val localSurfaceTintBlue: Float = 1.0f,
    /** 当前落脚点局部照度 */
    val localSurfaceLuminance: Float = 1.0f,
    /** 主光源二维方向向量 (X: -1.0 左侧光源 ~ +1.0 右侧光源, Y: -1.0 上方光源 ~ +1.0 下方光源) */
    val lightDirectionX: Float = 0.0f,
    val lightDirectionY: Float = -0.5f,
    /** 主光源定向直射强度 (用于地面斜向定向阴影与发丝立体轮廓光) */
    val lightDirectionalStrength: Float = 0.2f,
    /** 桌面特征纹理置信度 (0.0f ~ 1.0f) */
    val deskTextureConfidence: Float = 0.5f,
    /** 端侧零 GMS 视觉光流防抖微偏移 (像素) */
    val visualFlowOffsetX: Float = 0f,
    val visualFlowOffsetY: Float = 0f,
    /** 镜头边缘暗角自然失光衰减系数 (0.72f 边缘 ~ 1.0f 中心) */
    val lensVignetteFactor: Float = 1.0f,
    /** 物理相机是否正在自动对焦中 (触发相机黄色专业对焦环动画) */
    val isCameraFocusing: Boolean = false,
    /** 触发对焦时间戳 (供合焦对焦框动画显示) */
    val cameraFocusTimestamp: Long = 0L,
    /** 用户是否正在地面自由拖拽伴侣滑行中 */
    val isDragging: Boolean = false,
    /** 镜头注视微偏移 (视线跟踪 Eye Contact) */
    val gazeTrackingEyeX: Float = 0f,
    val gazeTrackingEyeY: Float = 0f,
    /** 物理拖拽与重力加速度惯性摆角 */
    val inertialSwayAngle: Float = 0f,
    val inertialSquishY: Float = 1.0f,
    /** 前景键盘/桌面切片遮挡比例 (0.0f ~ 0.45f) */
    val foregroundOcclusionRatio: Float = 0f,
    /** 是否正逼近真实桌面悬崖边缘 (例如靠近木质桌沿或桌垫边缘) */
    val isNearCliffEdge: Boolean = false,
    /** 悬崖边缘警惕强度 (0.0f 远离边缘安全 ~ 1.0f 濒临跌落边缘) */
    val cliffEdgeIntensity: Float = 0f,
    /** 悬崖边缘法线方向向量 (默认桌面前沿向近景 Y > 0) */
    val cliffNormalX: Float = 0f,
    val cliffNormalY: Float = 1.0f,
    /** 是否已滑出桌面边缘处于悬空状态 (Dangling Over Cliff) */
    val isDanglingOverCliff: Boolean = false,
    /** 悬空离地高度比例 (0.0f 贴地 ~ 1.0f 高度悬空) */
    val dangleAirDistance: Float = 0f,
    /** 桌面物理支撑度 (1.0f 坚实桌面支撑 ~ 0.0f 完全悬空) */
    val surfaceSupportConfidence: Float = 1.0f,
    /** 机械键盘等立体障碍物台阶垂直抬升量 (0.0f ~ 18f dp) */
    val surfaceStepHeightOffset: Float = 0f,
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
