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
    /** 镜头注视视线偏移 X */
    val gazeShiftX: Float = 0f,
    /** 镜头注视视线偏移 Y */
    val gazeShiftY: Float = 0f
)
