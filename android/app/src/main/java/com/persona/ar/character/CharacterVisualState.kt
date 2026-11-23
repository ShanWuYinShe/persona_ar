package com.persona.ar.character

import com.persona.ar.character.skeleton.MultiViewProfile
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.TouchPart

data class CharacterVisualState(
    val name: String = "星野",
    val model: PersonaModelManifest? = null,
    val emotion: EmotionType = EmotionType.NEUTRAL,
    val action: ActionType = ActionType.IDLE,
    /** 动作开始时刻 (System.nanoTime)；3D 动画按真实时间推进，与帧率解耦 */
    val actionStartedAtNanos: Long = 0L,
    val expression: String = "SMILE",
    /** 触摸反应部位与开始时刻（3D 手办专属全身反应动作；2.5D 用微表情差分） */
    val touchReaction: TouchPart? = null,
    val touchReactionStartedAtNanos: Long = 0L,
    val lipSyncAmplitude: Float = 0f,
    val isBlinking: Boolean = false,
    val breathingOffset: Float = 0f,
    val jumpOffset: Float = 0f,
    val tiltOffsetX: Float = 0f,
    val tiltOffsetY: Float = 0f,
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** 现实环境光照系数 (0.2f 昏暗 ~ 1.2f 明亮) */
    val environmentalLighting: Float = 1.0f,
    /** 是否处于低光照暗室环境 */
    val isLowLight: Boolean = false,
    /** 环境光色温偏向 (-1.0f 冷蓝 ~ +1.0f 暖黄) */
    val ambientColorTemp: Float = 0f,
    /** 环境光红绿蓝三原色反射调和增益 */
    val ambientTintRed: Float = 1.0f,
    val ambientTintGreen: Float = 1.0f,
    val ambientTintBlue: Float = 1.0f,
    /** 真实相机暗光 ISO 微噪点强度 (0.0f ~ 1.0f) */
    val sensorGrainIntensity: Float = 0f,
    /** 镜头注视视线偏移 X */
    val gazeShiftX: Float = 0f,
    /** 镜头注视视线偏移 Y */
    val gazeShiftY: Float = 0f,
    /** 真实广角镜头边缘径向色散强度 (Chromatic Aberration Radial Fringe, 0.0f ~ 1.0f) */
    val chromaticAberrationIntensity: Float = 0f,
    /** 镜头色散径向倾角弧度 (相对光轴原点) */
    val chromaticAngleRad: Float = 0f,
    /** 强光逆光环境下的镜头光渗与边缘光晕 (Atmospheric Bloom & Rim Light Bleed, 0.0f ~ 1.0f) */
    val bloomIntensity: Float = 0f,
    /** 镜头景深焦外弥散度 (Depth-of-Field Blur, 0.0f ~ 1.0f) */
    val depthOfFieldBlur: Float = 0f,
    /** 镜头微距超近特写状态 (Macro Proximity 亲密交互) */
    val isMacroProximity: Boolean = false,
    /** 桌面边缘深度遮挡与水平面切片比例 (Surface Horizon Edge Occlusion, 0.0f ~ 1.0f) */
    val surfaceOcclusionBottomCutoff: Float = 0f,
    /** 主光源二维方向向量 (X: -1.0 左侧 ~ +1.0 右侧, Y: -1.0 上方 ~ +1.0 下方) */
    val lightDirectionX: Float = 0.0f,
    val lightDirectionY: Float = -0.5f,
    /** 主光源定向直射强度 (用于立体边缘轮廓光与高光渲染) */
    val lightDirectionalStrength: Float = 0.2f,
    /** 相机 3D 俯仰角与横滚角 (用于多层 3D 视差分层空间错位) */
    val cameraPitchDegrees: Float = 45f,
    val cameraRollDegrees: Float = 0f,
    /** 端侧零 GMS 视觉光流防抖微偏移 (像素) */
    val visualFlowOffsetX: Float = 0f,
    val visualFlowOffsetY: Float = 0f,
    /** 镜头边缘暗角自然失光衰减系数 (0.72f 边缘 ~ 1.0f 中心) */
    val lensVignetteFactor: Float = 1.0f,
    /** 物理相机是否正在自动对焦中 */
    val isCameraFocusing: Boolean = false,
    /** 是否启用高精度二次元角色全模渲染 (彻底替代简易几何 Chibi，达到商业级精细画质) */
    val useHighResolutionModel: Boolean = true,
    /** 镜头注视微偏移 (视线跟踪 Eye Contact，基于相机与角色的空间几何相对夹角) */
    val gazeTrackingEyeX: Float = 0f,
    val gazeTrackingEyeY: Float = 0f,
    /** 物理重力与拖拽惯性摆角 (Spring-Damper Inertial Sway，度数 -15° ~ +15°) */
    val inertialSwayAngle: Float = 0f,
    /** 物理惯性纵向弹性系数 */
    val inertialSquishY: Float = 1.0f,
    /** 桌面/键盘前景遮挡切片比例 (0.0f 无遮挡 ~ 0.35f 键盘前景真实遮挡双脚) */
    val foregroundOcclusionRatio: Float = 0f,
    /** 是否正逼近真实桌面悬崖边缘 */
    val isNearCliffEdge: Boolean = false,
    /** 悬崖边缘警惕强度 (0.0f ~ 1.0f) */
    val cliffEdgeIntensity: Float = 0f,
    /** 是否已滑出桌面边缘处于悬空状态 (Dangling Over Cliff) */
    val isDanglingOverCliff: Boolean = false,
    /** 悬空离地空气高度比例 (0.0f ~ 1.0f) */
    val dangleAirDistance: Float = 0f,
    /** 桌面物理支撑度 (1.0f 坚实桌面 ~ 0.0f 完全脱离悬空) */
    val surfaceSupportConfidence: Float = 1.0f,
    /** 机械键盘等立体障碍物台阶垂直抬升量 (0.0f ~ 18f dp) */
    val surfaceStepHeightOffset: Float = 0f,
    /** 高精动作姿态与微表情枚举 (DEFAULT / SLEEPY / SHY / POUT / SMUG / SURPRISED) */
    val highResPose: String = "DEFAULT",
    /** 害羞腮红强度 (0.0f ~ 1.0f) */
    val blushIntensity: Float = 0f,
    /** 打瞌睡困倦程度 (0.0f 清醒 ~ 1.0f 深度困倦点头) */
    val sleepinessFactor: Float = 0f,
    /** 鼓嘴傲娇程度 (0.0f ~ 1.0f) */
    val poutIntensity: Float = 0f,
    /** 自信得意挺胸角度 (度数 -5° ~ +5°) */
    val smugTiltDegrees: Float = 0f,
    /** 星穹光环高亮闪烁星芒爆发系数 (0.0f ~ 1.0f) */
    val haloSparkleIntensity: Float = 0f,
    /** 全身 3D 骨骼体态 (STANDING 站姿 / SITTING 桌沿悬垂坐姿 / TABLE_LEAN 趴桌托腮) */
    val skeletonStance: SkeletonStance = SkeletonStance.STANDING,
    /** 桌面边缘折腿坐姿过渡平滑系数 (0.0f 站立 ~ 1.0f 垂腿坐下) */
    val sitProgress: Float = 0f,
    /** 当前 360 度视角剖面 (正面 / 3/4 侧身 / 正侧面 / 背面回眸) */
    val multiViewProfile: MultiViewProfile = MultiViewProfile.FRONT,
    /** 3/4 侧身多视角过渡进度 (0.0f ~ 1.0f) */
    val threeQuarterProfileProgress: Float = 0f,
    /** 背面回眸多视角过渡进度 (0.0f ~ 1.0f) */
    val backProfileProgress: Float = 0f,
    /** 3D 圆柱体横向空间压缩比 (保持真实立体厚度，避免纯 2D 平面旋转时露馅成纸片) */
    val cylinderContractionX: Float = 1.0f,
    /** 头部与五官多层空间视差位移 (像素) */
    val depthDisparityHead: Float = 0f,
    /** 后发层反向立体纵深视差位移 (像素) */
    val depthDisparityHairBack: Float = 0f,
    /** 面部五官视差位移 (像素) */
    val depthDisparityFace: Float = 0f,
    /** 双马尾柔体弹簧骨骼摆动角 (左/右度数) */
    val pigtailSwayAngleLeft: Float = 0f,
    val pigtailSwayAngleRight: Float = 0f
)