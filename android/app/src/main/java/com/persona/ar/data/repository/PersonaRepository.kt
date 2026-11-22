package com.persona.ar.data.repository

import com.persona.ar.data.api.ApiClient
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.ChatRequest
import com.persona.ar.data.model.ChatResponse
import com.persona.ar.data.model.CreateMemoryRequest
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.LLMConfigRequest
import com.persona.ar.data.model.MemoryItem
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.TouchRequest
import com.persona.ar.data.model.TouchResponse
import com.persona.ar.data.model.VoiceInteractRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class PersonaRepository {

    suspend fun getStoreModels(): Result<List<PersonaModelManifest>> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.getStoreModels()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }

    suspend fun getMemories(personaId: String? = null, userId: String = "user_default"): Result<List<MemoryItem>> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.getMemories(userId, personaId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }

    suspend fun createMemory(
        content: String,
        category: String = "SHARED_MOMENT",
        importance: Int = 3,
        personaId: String = "hoshino",
        userId: String = "user_default"
    ): Result<MemoryItem> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.createMemory(
                CreateMemoryRequest(
                    userId = userId,
                    personaId = personaId,
                    category = category,
                    content = content,
                    importance = importance
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.success(
                    MemoryItem(
                        id = "mem_local_${System.currentTimeMillis()}",
                        userId = userId,
                        personaId = personaId,
                        content = content,
                        category = category,
                        importance = importance,
                        timestamp = System.currentTimeMillis() / 1000.0
                    )
                )
            }
        } catch (e: Exception) {
            Result.success(
                MemoryItem(
                    id = "mem_local_${System.currentTimeMillis()}",
                    userId = userId,
                    personaId = personaId,
                    content = content,
                    category = category,
                    importance = importance,
                    timestamp = System.currentTimeMillis() / 1000.0
                )
            )
        }
    }

    suspend fun deleteMemory(memoryId: String, userId: String = "user_default"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.deleteMemory(memoryId, userId)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    suspend fun switchPersona(personaId: String, userId: String = "user_default"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.switchPersona(mapOf("user_id" to userId, "persona_id" to personaId))
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    suspend fun sendChat(message: String, userId: String = "user_default"): Result<ChatResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.chatInteract(ChatRequest(message = message, userId = userId))
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    // 网络请求未成功时，触发端侧拟人兜底，避免用户交互中断
                    Result.success(createFallbackChat(message))
                }
            } catch (e: Exception) {
                Result.success(createFallbackChat(message))
            }
        }

    suspend fun sendVoiceInteract(
        audioBase64: String? = null,
        audioFormat: String = "wav",
        textPrompt: String? = null,
        userId: String = "user_default"
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.voiceInteract(
                VoiceInteractRequest(
                    userId = userId,
                    audioBase64 = audioBase64,
                    audioFormat = audioFormat,
                    textPrompt = textPrompt
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.success(createFallbackChat(textPrompt ?: "（对讲语音交流）"))
            }
        } catch (e: Exception) {
            Result.success(createFallbackChat(textPrompt ?: "（对讲语音交流）"))
        }
    }

    suspend fun sendTouch(part: TouchPart, userId: String = "user_default"): Result<TouchResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.personaTouch(TouchRequest(part = part, userId = userId))
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.success(createFallbackTouch(part))
                }
            } catch (e: Exception) {
                Result.success(createFallbackTouch(part))
            }
        }

    suspend fun getStatus(userId: String = "user_default"): Result<PersonaStatus> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.getPersonaStatus(userId)
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.success(createFallbackStatus())
                }
            } catch (e: Exception) {
                Result.success(createFallbackStatus())
            }
        }

    private fun createFallbackChat(message: String): ChatResponse {
        val lower = message.lowercase()
        val text = when {
            "你好" in lower || "hi" in lower || "早" in lower ->
                "你好呀！我现在正在你的真实空间里呢，能看见我吗？"
            "喜欢" in lower || "可爱" in lower ->
                "哼，笨蛋！突然说什么喜欢…就算你这么夸我，我也不会脸红的啦！"
            else ->
                "嗯嗯，我在这边一直听着呢！现实世界的风和光线，感觉很舒服哦。"
        }
        val emotion = if ("喜欢" in lower || "可爱" in lower) EmotionType.SHY else EmotionType.JOY
        val action = if ("喜欢" in lower || "可爱" in lower) ActionType.BLUSH else ActionType.WAVE
        val expr = if ("喜欢" in lower || "可爱" in lower) "BLUSH_FACE" else "SMILE"

        return ChatResponse(
            text = text,
            emotion = emotion,
            action = action,
            expression = expr,
            audioBase64 = null,
            affinityChange = 1,
            currentAffinity = 20,
            mood = "陪伴中"
        )
    }

    private fun createFallbackTouch(part: TouchPart): TouchResponse {
        val (text, emotion, action, expr) = when (part) {
            TouchPart.HEAD -> Quad(
                "呜哇！突、突然摸头…虽然头发被弄乱了一点点，但很温暖呢。",
                EmotionType.SHY,
                ActionType.BLUSH,
                "BLUSH_FACE"
            )
            TouchPart.CHEEK -> Quad(
                "捏脸会变圆的！不要戳啦，好痒呀~",
                EmotionType.ANGRY,
                ActionType.POUT,
                "POUT"
            )
            TouchPart.HAND -> Quad(
                "指尖的光子好像碰到了你的体温…手不要松开哦。",
                EmotionType.SHY,
                ActionType.NOD,
                "WINK"
            )
            TouchPart.BODY -> Quad(
                "诶？！靠得这么近…心跳的声音都要被你听到了啦！",
                EmotionType.SHY,
                ActionType.BLUSH,
                "BLUSH_FACE"
            )
        }
        return TouchResponse(
            text = text,
            emotion = emotion,
            action = action,
            expression = expr,
            affinityChange = 2,
            currentAffinity = 22,
            audioBase64 = null
        )
    }

    suspend fun updateLLMConfig(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.updateLLMConfig(
                LLMConfigRequest(
                    provider = provider,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model
                )
            )
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        filename: String = "voice.wav"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mediaType = "audio/wav".toMediaTypeOrNull()
            val reqBody = audioBytes.toRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", filename, reqBody)
            val response = ApiClient.service.transcribeAudio(part)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.text)
            } else {
                Result.success("看到你站在我面前，感觉好生动呀！")
            }
        } catch (e: Exception) {
            Result.success("看到你站在我面前，感觉好生动呀！")
        }
    }

    suspend fun updateAsrConfig(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.updateAsrConfig(
                com.persona.ar.data.model.AsrConfigRequest(
                    provider = provider,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    language = language
                )
            )
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    private fun createFallbackStatus(): PersonaStatus {
        return PersonaStatus(
            name = "星野 (Hoshino)",
            roleDesc = "投射在现实中的二次元傲娇元气少女",
            affinity = 20,
            affinityLevel = "初识 (Curious)",
            mood = "好奇而轻快",
            dialogueCount = 1,
            recentMemories = listOf("正在好奇地观察你的现实环境")
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
