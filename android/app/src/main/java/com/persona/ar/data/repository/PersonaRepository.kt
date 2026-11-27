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
import com.persona.ar.data.model.VisionInteractRequest
import com.persona.ar.data.model.VoiceInteractRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class ServerProbeReport(
    val isOnline: Boolean,
    val summary: String,
    val llmMode: String? = null,
    val ttsProvider: String? = null
)

class PersonaRepository {

    private val _isServerReachable = MutableStateFlow(false)
    val isServerReachable: StateFlow<Boolean> = _isServerReachable.asStateFlow()

    suspend fun probeServerAndEngines(
        targetUrl: String,
        llmConfig: LLMConfigRequest? = null
    ): ServerProbeReport = withContext(Dispatchers.IO) {
        try {
            val service = ApiClient.createTransientService(targetUrl)
            val healthResp = service.healthCheck()
            if (!healthResp.isSuccessful || healthResp.body() == null) {
                return@withContext ServerProbeReport(
                    isOnline = false,
                    summary = "服务端响应异常 (HTTP ${healthResp.code()})"
                )
            }
            val health = healthResp.body()!!
            var llmPart = "LLM: ${health.llmMode}"
            var llmOk = true
            if (llmConfig != null) {
                val llmResp = runCatching { service.testLLMConnection(llmConfig) }.getOrNull()
                if (llmResp != null && llmResp.isSuccessful && llmResp.body() != null) {
                    val body = llmResp.body()!!
                    llmPart = "LLM(${body.mode ?: health.llmMode}): 就绪 (${body.latencyMs}ms)"
                } else {
                    llmOk = false
                    llmPart = "LLM 连接异常 (HTTP ${llmResp?.code() ?: 0})"
                }
            }
            val asrPart = runCatching {
                val asrResp = service.testASRConnection()
                if (asrResp.isSuccessful) "ASR: ${health.asrProvider}✓" else "ASR: ${health.asrProvider}!"
            }.getOrDefault("ASR: ${health.asrProvider}")

            ServerProbeReport(
                isOnline = llmOk,
                summary = "已连通 v${health.version.ifBlank { "2.x" }} · $llmPart · TTS: ${health.ttsProvider} · $asrPart",
                llmMode = health.llmMode,
                ttsProvider = health.ttsProvider
            )
        } catch (e: Exception) {
            ServerProbeReport(
                isOnline = false,
                summary = "无法连接服务器: ${e.message ?: "请检查地址与网络"}"
            )
        }
    }

    suspend fun getStoreModels(): Result<List<PersonaModelManifest>> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.getStoreModels()
            if (response.isSuccessful && response.body() != null) {
                _isServerReachable.value = true
                Result.success(response.body()!!)
            } else {
                _isServerReachable.value = false
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(emptyList())
        }
    }

    suspend fun getMemories(personaId: String? = null, userId: String = "user_default"): Result<List<MemoryItem>> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.getMemories(userId, personaId)
            if (response.isSuccessful && response.body() != null) {
                _isServerReachable.value = true
                Result.success(response.body()!!)
            } else {
                _isServerReachable.value = false
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
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
                _isServerReachable.value = true
                Result.success(body)
            } else {
                _isServerReachable.value = false
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
            _isServerReachable.value = false
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
            _isServerReachable.value = response.isSuccessful
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(false)
        }
    }

    suspend fun switchPersona(personaId: String, userId: String = "user_default"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.switchPersona(mapOf("user_id" to userId, "persona_id" to personaId))
            _isServerReachable.value = response.isSuccessful
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(false)
        }
    }

    suspend fun sendChat(message: String, userId: String = "user_default"): Result<ChatResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.chatInteract(ChatRequest(message = message, userId = userId))
                if (response.isSuccessful && response.body() != null) {
                    _isServerReachable.value = true
                    Result.success(response.body()!!)
                } else {
                    _isServerReachable.value = false
                    // 网络请求未成功时，触发端侧拟人兜底，避免用户交互中断
                    Result.success(createFallbackChat(message))
                }
            } catch (e: Exception) {
                _isServerReachable.value = false
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
                _isServerReachable.value = true
                Result.success(response.body()!!)
            } else {
                _isServerReachable.value = false
                Result.success(createFallbackChat(textPrompt ?: "（对讲语音交流）"))
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(createFallbackChat(textPrompt ?: "（对讲语音交流）"))
        }
    }

    suspend fun sendVisionInteract(
        imageBase64: String,
        prompt: String = "",
        userId: String = "user_default",
        partnerName: String = "星野"
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.visionInteract(
                VisionInteractRequest(
                    userId = userId,
                    message = prompt,
                    imageBase64 = imageBase64
                )
            )
            if (response.isSuccessful && response.body() != null) {
                _isServerReachable.value = true
                Result.success(response.body()!!)
            } else {
                _isServerReachable.value = false
                Result.success(createFallbackChat(prompt.ifBlank { "哇！${partnerName}已经透过现实镜头看到你分享的景物啦~" }))
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(createFallbackChat(prompt.ifBlank { "哇！${partnerName}已经透过现实镜头看到你分享的景物啦~" }))
        }
    }

    suspend fun sendTouch(part: TouchPart, userId: String = "user_default"): Result<TouchResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.personaTouch(TouchRequest(part = part, userId = userId))
                if (response.isSuccessful && response.body() != null) {
                    _isServerReachable.value = true
                    Result.success(response.body()!!)
                } else {
                    _isServerReachable.value = false
                    Result.success(createFallbackTouch(part))
                }
            } catch (e: Exception) {
                _isServerReachable.value = false
                Result.success(createFallbackTouch(part))
            }
        }

    suspend fun getStatus(
        userId: String = "user_default",
        personaId: String? = null,
        fallbackPartnerName: String = "星野 (Hoshino)"
    ): Result<PersonaStatus> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.getPersonaStatus(userId, personaId)
                if (response.isSuccessful && response.body() != null) {
                    _isServerReachable.value = true
                    Result.success(response.body()!!)
                } else {
                    _isServerReachable.value = false
                    Result.success(createFallbackStatus(fallbackPartnerName))
                }
            } catch (e: Exception) {
                _isServerReachable.value = false
                Result.success(createFallbackStatus(fallbackPartnerName))
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
            _isServerReachable.value = response.isSuccessful
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            _isServerReachable.value = false
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
                _isServerReachable.value = true
                Result.success(response.body()!!.text)
            } else {
                _isServerReachable.value = false
                Result.success("看到你站在我面前，感觉好生动呀！")
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
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
            _isServerReachable.value = response.isSuccessful
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(false)
        }
    }

    private fun createFallbackStatus(partnerName: String = "星野 (Hoshino)"): PersonaStatus {
        return PersonaStatus(
            name = partnerName,
            roleDesc = "端侧陪伴模式 · 可在设置中连接情感大脑服务",
            affinity = 20,
            affinityLevel = "初识 (Curious)",
            mood = "好奇而轻快",
            dialogueCount = 1,
            recentMemories = listOf("正在好奇地观察你的现实环境")
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
