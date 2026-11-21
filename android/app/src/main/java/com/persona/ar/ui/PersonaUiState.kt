package com.persona.ar.ui

import com.persona.ar.ar.ARMode
import com.persona.ar.character.CharacterVisualState
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus
import com.persona.ar.data.model.VoiceMode

data class PersonaUiState(
    val arMode: ARMode = ARMode.BILLBOARD_2_5D,
    val visualState: CharacterVisualState = CharacterVisualState(),
    val personaStatus: PersonaStatus? = null,
    val messages: List<ChatMessage> = emptyList(),
    // 动态纸片人模型系统 (商城与工坊资产包)
    val activeModel: PersonaModelManifest? = null,
    val installedModels: List<PersonaModelManifest> = emptyList(),
    val storeModels: List<PersonaModelManifest> = emptyList(),
    val isStoreOpen: Boolean = false,
    val previewModel: PersonaModelManifest? = null,
    // 对讲模式与气泡策略（设置中可调）
    val voiceMode: VoiceMode = VoiceMode.CONTINUOUS_NATURAL,
    val bubbleDisplayMode: BubbleDisplayMode = BubbleDisplayMode.PERSISTENT,
    // 实时录音/对讲状态
    val isRecording: Boolean = false,
    val isContinuousActive: Boolean = false,
    val micVolume: Float = 0f,
    val isSpeaking: Boolean = false,
    val isUserSpeaking: Boolean = false,
    val isLoading: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val serverBaseUrl: String = "http://10.0.2.2:8000/",
    val errorMessage: String? = null
)
