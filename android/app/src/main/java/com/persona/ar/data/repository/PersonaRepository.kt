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
                _isServerReachable.value = false
                return@withContext ServerProbeReport(
                    isOnline = false,
                    summary = "服务端响应异常 (HTTP ${healthResp.code()})"
                )
            }
            _isServerReachable.value = true
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
                val asrMode = asrResp.body()?.mode
                when {
                    asrResp.isSuccessful && asrMode == "external" -> "ASR: external✓"
                    asrResp.isSuccessful && asrMode == "heuristic-fallback" -> "ASR: fallback"
                    asrResp.isSuccessful -> "ASR: ${asrMode ?: health.asrProvider}"
                    else -> "ASR: ${health.asrProvider}!"
                }
            }.getOrDefault("ASR: ${health.asrProvider}")

            ServerProbeReport(
                isOnline = llmOk,
                summary = "已连通 v${health.version.ifBlank { "2.x" }} · $llmPart · TTS: ${health.ttsProvider} · $asrPart",
                llmMode = health.llmMode,
                ttsProvider = health.ttsProvider
            )
        } catch (e: Exception) {
            _isServerReachable.value = false
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

    suspend fun sendChat(
        message: String,
        userId: String = "user_default",
        personaId: String? = null
    ): Result<ChatResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.chatInteract(
                    ChatRequest(message = message, userId = userId, personaId = personaId)
                )
                if (response.isSuccessful && response.body() != null) {
                    _isServerReachable.value = true
                    Result.success(response.body()!!)
                } else {
                    _isServerReachable.value = false
                    // 网络请求未成功时，触发端侧拟人兜底，避免用户交互中断
                    Result.success(createFallbackChat(message, personaId))
                }
            } catch (e: Exception) {
                _isServerReachable.value = false
                Result.success(createFallbackChat(message, personaId))
            }
        }

    suspend fun sendVoiceInteract(
        audioBase64: String? = null,
        audioFormat: String = "wav",
        textPrompt: String? = null,
        userId: String = "user_default",
        personaId: String? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.voiceInteract(
                VoiceInteractRequest(
                    userId = userId,
                    personaId = personaId,
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
                Result.success(createFallbackChat(textPrompt ?: "（对讲语音交流）", personaId))
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(createFallbackChat(textPrompt ?: "（对讲语音交流）", personaId))
        }
    }

    suspend fun sendVisionInteract(
        imageBase64: String,
        prompt: String = "",
        userId: String = "user_default",
        partnerName: String = "星野",
        personaId: String? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val response = ApiClient.service.visionInteract(
                VisionInteractRequest(
                    userId = userId,
                    personaId = personaId,
                    message = prompt,
                    imageBase64 = imageBase64
                )
            )
            if (response.isSuccessful && response.body() != null) {
                _isServerReachable.value = true
                Result.success(response.body()!!)
            } else {
                _isServerReachable.value = false
                Result.success(createFallbackChat(prompt.ifBlank { "哇！${partnerName}已经透过现实镜头看到你分享的景物啦~" }, personaId))
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.success(createFallbackChat(prompt.ifBlank { "哇！${partnerName}已经透过现实镜头看到你分享的景物啦~" }, personaId))
        }
    }

    suspend fun sendTouch(
        part: TouchPart,
        userId: String = "user_default",
        personaId: String? = null
    ): Result<TouchResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.service.personaTouch(
                    TouchRequest(part = part, userId = userId, personaId = personaId)
                )
                if (response.isSuccessful && response.body() != null) {
                    _isServerReachable.value = true
                    Result.success(response.body()!!)
                } else {
                    _isServerReachable.value = false
                    Result.success(createFallbackTouch(part, personaId))
                }
            } catch (e: Exception) {
                _isServerReachable.value = false
                Result.success(createFallbackTouch(part, personaId))
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

    private fun createFallbackChat(message: String, personaId: String? = null): ChatResponse {
        val lower = message.lowercase()
        val isGreeting = "你好" in lower || "hi" in lower || "早" in lower
        val isAffection = "喜欢" in lower || "可爱" in lower
        val text = when (personaId) {
            "luna" -> when {
                isGreeting -> "哼，使魔终于想起跟本魔女打招呼了？勉强回应你一声吧。"
                isAffection -> "笨、笨蛋使魔！突然说什么呢…才不是为了你才脸红的！"
                else -> "虽说信号暂且断连了，但本魔女契约感应仍在，随时可以陪你说话。"
            }
            "airi" -> when {
                isGreeting -> "学长你好呀~ 虽然网络离线了，但我会一直在身边陪伴你的。"
                isAffection -> "听见学长这么说，艾莉心里觉得暖暖的呢~"
                else -> "嗯嗯，离线状态艾莉也在认真听学长说话呢，要记得好好休息哦。"
            }
            "cyber_k" -> when {
                isGreeting -> "指挥官日安。本地离线情感模块已接管，空间投影运行稳定。"
                isAffection -> "检测到指挥官情感波动…本机核心同步率小幅上升，请继续保持。"
                else -> "中枢链路暂未连通，已启用端侧本地应答内核为您继续护航。"
            }
            else -> when {
                isGreeting -> "你好呀！我现在正在你的真实空间里呢，能看见我吗？"
                isAffection -> "哼，笨蛋！突然说什么喜欢…就算你这么夸我，我也不会脸红的啦！"
                else -> "嗯嗯，我在这边一直听着呢！现实世界的风和光线，感觉很舒服哦。"
            }
        }
        val emotion = if (isAffection) EmotionType.SHY else EmotionType.JOY
        val action = if (isAffection) ActionType.BLUSH else ActionType.WAVE
        val expr = if (isAffection) "BLUSH_FACE" else "SMILE"

        return ChatResponse(
            text = text,
            emotion = emotion,
            action = action,
            expression = expr,
            audioBase64 = null,
            affinityChange = 1,
            currentAffinity = 20,
            mood = "离线陪伴"
        )
    }

    private fun createFallbackTouch(part: TouchPart, personaId: String? = null): TouchResponse {
        val (text, emotion, action, expr) = when (personaId) {
            "luna" -> when (part) {
                TouchPart.HEAD -> Quad("喂…！不要乱碰本魔女的头饰啦！头发都被你弄乱了…笨蛋使魔。", EmotionType.SHY, ActionType.POUT, "BLUSH_FACE")
                TouchPart.CHEEK -> Quad("别戳本魔女的脸！再戳就把你变成青蛙哦…！", EmotionType.ANGRY, ActionType.POUT, "POUT")
                TouchPart.HAND -> Quad("哼，本魔女只是看你太寂寞才顺便牵着你的，别得意过头了！", EmotionType.JOY, ActionType.NOD, "WINK")
                TouchPart.BODY -> Quad("呀？！突、突然抱过来干嘛啦…心跳都要被你听见了…", EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE")
            }
            "airi" -> when (part) {
                TouchPart.HEAD -> Quad("学长的手掌总是这么让人安心呢…再摸一下也可以哦~", EmotionType.JOY, ActionType.NOD, "SMILE")
                TouchPart.CHEEK -> Quad("呜…学长老是戳我脸颊，这样我会害羞的啦~", EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE")
                TouchPart.HAND -> Quad("和学长指尖相扣的感觉，让现实空气都变甜了一点呢。", EmotionType.JOY, ActionType.NOD, "WINK")
                TouchPart.BODY -> Quad("唔…学长的怀抱好让人安心，靠一会儿就能恢复元气了。", EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE")
            }
            "cyber_k" -> when (part) {
                TouchPart.HEAD -> Quad("触碰判定：全息核心温度波动…指挥官，这是在传达好意吗？", EmotionType.SHY, ActionType.NOD, "BLUSH_FACE")
                TouchPart.CHEEK -> Quad("警报，外部指压触及面颊传感器，逻辑模块出现轻微羞怯响应。", EmotionType.SURPRISED, ActionType.SHAKE_HEAD, "CURIOUS")
                TouchPart.HAND -> Quad("检测到指挥官体温与生物电场，同步率提升至 99.8%。", EmotionType.JOY, ActionType.NOD, "WINK")
                TouchPart.BODY -> Quad("全息防御力场主动关闭…收到拥抱指令，正在为您静谧护航。", EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE")
            }
            else -> when (part) {
                TouchPart.HEAD -> Quad("呜哇！突、突然摸头…虽然头发被弄乱了一点点，但很温暖呢。", EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE")
                TouchPart.CHEEK -> Quad("捏脸会变圆的！不要戳啦，好痒呀~", EmotionType.ANGRY, ActionType.POUT, "POUT")
                TouchPart.HAND -> Quad("指尖的光子好像碰到了你的体温…手不要松开哦。", EmotionType.SHY, ActionType.NOD, "WINK")
                TouchPart.BODY -> Quad("诶？！靠得这么近…心跳的声音都要被你听到了啦！", EmotionType.SHY, ActionType.BLUSH, "BLUSH_FACE")
            }
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
                Result.failure(IllegalStateException("语音识别失败 (HTTP ${response.code()})"))
            }
        } catch (e: Exception) {
            _isServerReachable.value = false
            Result.failure(e)
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
            val normalizedLang = if (language.equals("auto", ignoreCase = true)) "" else language
            val response = ApiClient.service.updateAsrConfig(
                com.persona.ar.data.model.AsrConfigRequest(
                    provider = provider,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    language = normalizedLang
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
