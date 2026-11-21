package com.persona.ar.ui

import com.persona.ar.ar.ARMode
import com.persona.ar.character.CharacterVisualState
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.PersonaStatus

data class PersonaUiState(
    val arMode: ARMode = ARMode.BILLBOARD_2_5D,
    val visualState: CharacterVisualState = CharacterVisualState(),
    val personaStatus: PersonaStatus? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val micVolume: Float = 0f,
    val isSpeaking: Boolean = false,
    val isLoading: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val serverBaseUrl: String = "http://10.0.2.2:8000/",
    val errorMessage: String? = null
)
