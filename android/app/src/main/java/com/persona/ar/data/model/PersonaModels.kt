package com.persona.ar.data.model

import com.google.gson.annotations.SerializedName

enum class EmotionType {
    NEUTRAL,
    JOY,
    SHY,
    SAD,
    SURPRISED,
    THINKING,
    ANGRY,
    SLEEPY,
    CONFIDENT
}

enum class ActionType {
    IDLE,
    WAVE,
    BLUSH,
    NOD,
    SHAKE_HEAD,
    POUT,
    BOW,
    JUMP,
    YAWN,
    SLEEP,
    SMUG,
    SIT
}

enum class TouchPart {
    HEAD,
    CHEEK,
    HAND,
    BODY
}

/**
 * 语音对讲交互模式
 */
enum class VoiceMode {
    /** 模式1：自然对话模式（类似真人聊天，随时打断，静音自动断句发送） */
    CONTINUOUS_NATURAL,
    /** 模式2：对讲机模式（按住说话，松开发送） */
    HOLD_TO_TALK
}

/**
 * 对话气泡显示策略
 */
enum class BubbleDisplayMode {
    /** 模式1：停留常驻（保留历史记录，可回滚） */
    PERSISTENT,
    /** 模式2：延迟 5 秒自动淡出隐藏（保持现实画面整洁） */
    AUTO_FADE_OUT,
    /** 模式3：完全不显示（极致现实沉浸，全靠语音与肢体神态交流） */
    HIDDEN
}

data class ChatRequest(
    val message: String,
    @SerializedName("user_id") val userId: String = "user_default",
    @SerializedName("session_id") val sessionId: String? = null
)

data class VisionInteractRequest(
    @SerializedName("user_id") val userId: String = "user_default",
    val message: String = "",
    @SerializedName("image_base64") val imageBase64: String
)

data class VoiceInteractRequest(
    @SerializedName("user_id") val userId: String = "user_default",
    @SerializedName("audio_base64") val audioBase64: String? = null,
    @SerializedName("audio_format") val audioFormat: String = "wav",
    @SerializedName("text_prompt") val textPrompt: String? = null
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

data class MemoryItem(
    val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("persona_id") val personaId: String,
    val category: String,
    val content: String,
    val importance: Int = 3,
    val timestamp: Double = 0.0
)

data class CreateMemoryRequest(
    @SerializedName("user_id") val userId: String = "user_default",
    @SerializedName("persona_id") val personaId: String = "hoshino",
    val category: String = "SHARED_MOMENT",
    val content: String,
    val importance: Int = 3
)

data class LLMConfigRequest(
    val provider: String = "openai",
    @SerializedName("base_url") val baseUrl: String = "https://api.deepseek.com",
    @SerializedName("api_key") val apiKey: String = "",
    val model: String = "deepseek-chat"
)

data class AsrResponse(
    val text: String,
    val provider: String? = null,
    @SerializedName("duration_seconds") val durationSeconds: Double? = null
)

data class AsrConfigRequest(
    val provider: String = "whisper",
    @SerializedName("base_url") val baseUrl: String = "https://api.openai.com/v1",
    @SerializedName("api_key") val apiKey: String = "",
    val model: String = "whisper-1",
    val language: String = "zh"
)

data class ServerHealthResponse(
    val status: String = "",
    val service: String = "",
    val version: String = "",
    @SerializedName("llm_mode") val llmMode: String = "heuristic",
    @SerializedName("tts_provider") val ttsProvider: String = "edge",
    @SerializedName("asr_provider") val asrProvider: String = "mock"
)

data class ServiceTestResponse(
    val status: String = "",
    val mode: String? = null,
    val message: String? = null,
    val error: String? = null,
    @SerializedName("latency_ms") val latencyMs: Long = 0L
)
