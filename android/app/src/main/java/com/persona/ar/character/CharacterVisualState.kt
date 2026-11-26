package com.persona.ar.character

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
    /** 触摸反应部位与开始时刻（3D 手办专属全身反应动作） */
    val touchReaction: TouchPart? = null,
    val touchReactionStartedAtNanos: Long = 0L,
    val lipSyncAmplitude: Float = 0f,
    val scale: Float = 1.0f,
    /** 现实环境光照系数 (0.18f 昏暗 ~ 1.35f 明亮) */
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
    /** 镜头注视视线偏移 X/Y */
    val gazeShiftX: Float = 0f,
    val gazeShiftY: Float = 0f,
    /** 物理拖拽与移动惯性摆角 (度数 -18° ~ +18°) */
    val inertialSwayAngle: Float = 0f,
    /** 害羞颊晕强度 (0.0f ~ 1.0f，用于 3D 脸部柔粉特效) */
    val blushIntensity: Float = 0f,
    /** 光学辅助字段 */
    val chromaticAberrationIntensity: Float = 0f,
    val chromaticAngleRad: Float = 0f,
    val bloomIntensity: Float = 0f,
    val depthOfFieldBlur: Float = 0f,
    val isMacroProximity: Boolean = false,
    val lightDirectionX: Float = 0.0f,
    val lightDirectionY: Float = -0.5f,
    val lightDirectionalStrength: Float = 0.2f,
    val cameraPitchDegrees: Float = 45f,
    val cameraRollDegrees: Float = 0f,
    val lensVignetteFactor: Float = 1.0f,
    val isCameraFocusing: Boolean = false,
    /** 全身 3D 骨骼体态 (STANDING 站姿 / SITTING 桌沿悬垂坐姿) */
    val skeletonStance: SkeletonStance = SkeletonStance.STANDING
)
