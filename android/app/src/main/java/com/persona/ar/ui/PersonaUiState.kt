package com.persona.ar.ui

import com.persona.ar.ar.ARMode
import com.persona.ar.character.CharacterVisualState
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.MemoryItem
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus
import com.persona.ar.data.model.VoiceMode

data class PersonaUiState(
    val arMode: ARMode = ARMode.BILLBOARD_2_5D,
    val visualState: CharacterVisualState = CharacterVisualState(),
    val personaStatus: PersonaStatus? = null,
    val messages: List<ChatMessage> = emptyList(),
    // 专属情感回忆手账系统
    val isMemoryNotebookOpen: Boolean = false,
    val memories: List<MemoryItem> = emptyList(),
    val isMemoryLoading: Boolean = false,
    // 羁绊与进阶成长经验体系 (Bond EXP & Level)
    val bondLevel: Int = 1,
    val bondExp: Int = 45,
    val bondExpNext: Int = 100,
    val bondLevelTitle: String = "初遇契约",
    val bondLevelUpNotice: String? = null,
    // 动态纸片人模型系统 (商城与工坊资产包)
    val activeModel: PersonaModelManifest? = null,
    val installedModels: List<PersonaModelManifest> = emptyList(),
    val storeModels: List<PersonaModelManifest> = emptyList(),
    val isStoreOpen: Boolean = false,
    val previewModel: PersonaModelManifest? = null,
    // 对讲模式与气泡策略（设置中可调）
    val voiceMode: VoiceMode = VoiceMode.CONTINUOUS_NATURAL,
    val bubbleDisplayMode: BubbleDisplayMode = BubbleDisplayMode.PERSISTENT,
    // 实时录音/对讲状态与音浪跳动柱
    val isRecording: Boolean = false,
    val isContinuousActive: Boolean = false,
    val micVolume: Float = 0f,
    val isSpeaking: Boolean = false,
    val isUserSpeaking: Boolean = false,
    val isLoading: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val serverBaseUrl: String = "http://10.0.2.2:8000/",
    // 大语言模型接入配置 (OpenAI / DeepSeek / 本地 Ollama)
    val llmProvider: String = "mock",
    val llmBaseUrl: String = "https://api.deepseek.com",
    val llmApiKey: String = "",
    val llmModel: String = "deepseek-chat",
    val errorMessage: String? = null
)
