package com.persona.ar.data.model

import com.google.gson.annotations.SerializedName

enum class EmotionType {
    NEUTRAL,
    JOY,
    SHY,
    SAD,
    SURPRISED,
    THINKING,
    ANGRY
}

enum class ActionType {
    IDLE,
    WAVE,
    BLUSH,
    NOD,
    SHAKE_HEAD,
    POUT,
    BOW,
    JUMP
}

enum class TouchPart {
    HEAD,
    CHEEK,
    HAND,
    BODY
}

data class ChatRequest(
    val message: String,
    @SerializedName("user_id") val userId: String = "user_default",
    @SerializedName("session_id") val sessionId: String? = null
)

data class ChatResponse(
    val text: String,
    val emotion: EmotionType,
    val action: ActionType,
    val expression: String,
    @SerializedName("audio_base64") val audioBase64: String?,
    @SerializedName("audio_format") val audioFormat: String = "mp3",
    @SerializedName("affinity_change") val affinityChange: Int,
    @SerializedName("current_affinity") val currentAffinity: Int,
    val mood: String
)

data class TouchRequest(
    val part: TouchPart,
    @SerializedName("user_id") val userId: String = "user_default"
)

data class TouchResponse(
    val text: String,
    val emotion: EmotionType,
    val action: ActionType,
    val expression: String,
    @SerializedName("affinity_change") val affinityChange: Int,
    @SerializedName("current_affinity") val currentAffinity: Int,
    @SerializedName("audio_base64") val audioBase64: String?
)

data class PersonaStatus(
    val name: String,
    @SerializedName("role_desc") val roleDesc: String,
    val affinity: Int,
    @SerializedName("affinity_level") val affinityLevel: String,
    val mood: String,
    @SerializedName("dialogue_count") val dialogueCount: Int,
    @SerializedName("recent_memories") val recentMemories: List<String> = emptyList()
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val emotion: EmotionType? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageSender {
    USER,
    PERSONA
}
