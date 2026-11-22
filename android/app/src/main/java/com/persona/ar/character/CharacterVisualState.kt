package com.persona.ar.character

import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.PersonaModelManifest

data class CharacterVisualState(
    val name: String = "星野",
    val model: PersonaModelManifest? = null,
    val emotion: EmotionType = EmotionType.NEUTRAL,
    val action: ActionType = ActionType.IDLE,
    val expression: String = "SMILE",
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
    val isCameraFocusing: Boolean = false
)
