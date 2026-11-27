package com.persona.ar.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.persona.ar.ar.ARTrackingStatus
import com.persona.ar.ar.EnvironmentalOpticalProfile
import com.persona.ar.ar.UniversalSensorSurfaceTracker
import com.persona.ar.ar.VisualAnchorObservation
import com.persona.ar.audio.AudioPlayer
import com.persona.ar.audio.VoiceRecorder
import com.persona.ar.character.animation.ActionTiming
import com.persona.ar.character.loader.PersonaModelManager
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.api.ApiClient
import com.persona.ar.data.api.PersonaWebSocketClient
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.MessageSender
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.LLMConfigRequest
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.VoiceMode
import com.persona.ar.data.preferences.PersonaPreferences
import com.persona.ar.data.preferences.PersonaSettingsCodec
import com.persona.ar.data.preferences.PersonaSettingsSnapshot
import com.persona.ar.data.repository.PersonaRepository
import com.persona.ar.util.MemoryCategoryMapper
import com.persona.ar.util.PersonaTextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class PersonaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PersonaRepository()
    private val audioPlayer = AudioPlayer(application)
    private val voiceRecorder = VoiceRecorder()
    private val universalTracker = UniversalSensorSurfaceTracker(application)
    private val modelManager = PersonaModelManager(application)
    private val preferences = PersonaPreferences(application)
    private val wsClient = PersonaWebSocketClient()
    private val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        application.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

    private val _uiState = MutableStateFlow(PersonaUiState())
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    private val createdAtMillis = System.currentTimeMillis()
    private var interactionEpoch = 0L
    private var voiceTurnEpoch = 0L
    private var pendingWsTurnEpoch = 0L
    private var rawStreamingText = ""

    private fun activePersonaId(): String =
        _uiState.value.activeModel?.id ?: modelManager.activeModel.value.id

    init {
        restorePersistedSettings()
        setupAudioPlayer()
        setupVoiceRecorder()
        setupWebSocket()
        setupSensors()
        setupModelManager()
        observeServerReachability()
        loadInitialStatus()
        loadMemories()
    }

    private fun restorePersistedSettings() {
        val snapshot = preferences.load()
        ApiClient.baseUrl = snapshot.serverBaseUrl
        modelManager.restorePersistedState(snapshot.activeModelId, snapshot.installedModelIds)
        val restoredActive = modelManager.activeModel.value
        _uiState.update {
            it.copy(
                serverBaseUrl = snapshot.serverBaseUrl,
                llmProvider = snapshot.llmProvider,
                llmBaseUrl = snapshot.llmBaseUrl,
                llmApiKey = snapshot.llmApiKey,
                llmModel = snapshot.llmModel,
                asrProvider = snapshot.asrProvider,
                asrBaseUrl = snapshot.asrBaseUrl,
                asrApiKey = snapshot.asrApiKey,
                asrModel = snapshot.asrModel,
                asrLanguage = snapshot.asrLanguage,
                voiceMode = snapshot.resolvedVoiceMode(),
                bubbleDisplayMode = snapshot.resolvedBubbleDisplayMode(),
                activeModel = restoredActive,
                installedModels = modelManager.installedModels.value,
                storeModels = modelManager.storeModels.value,
                visualState = it.visualState.copy(
                    name = restoredActive.name,
                    model = restoredActive
                ),
                bondLevel = snapshot.bondLevel,
                bondExp = snapshot.bondExp,
                bondExpNext = snapshot.bondExpNext,
                bondLevelTitle = getBondTitleForLevel(snapshot.bondLevel)
            )
        }
    }

    private fun persistCurrentSettings() {
        val state = _uiState.value
        preferences.save(
            PersonaSettingsSnapshot(
                serverBaseUrl = state.serverBaseUrl,
                llmProvider = state.llmProvider,
                llmBaseUrl = state.llmBaseUrl,
                llmApiKey = state.llmApiKey,
                llmModel = state.llmModel,
                asrProvider = state.asrProvider,
                asrBaseUrl = state.asrBaseUrl,
                asrApiKey = state.asrApiKey,
                asrModel = state.asrModel,
                asrLanguage = state.asrLanguage,
                voiceMode = state.voiceMode.name,
                bubbleDisplayMode = state.bubbleDisplayMode.name,
                activeModelId = state.activeModel?.id ?: modelManager.activeModel.value.id,
                installedModelIds = modelManager.installedModels.value.map { it.id },
                bondLevel = state.bondLevel,
                bondExp = state.bondExp,
                bondExpNext = state.bondExpNext
            )
        )
    }

    private fun observeServerReachability() {
        viewModelScope.launch {
            repository.isServerReachable.collect { reachable ->
                _uiState.update { it.copy(isServerReachable = reachable) }
            }
        }
    }

    private fun setupModelManager() {
        viewModelScope.launch {
            modelManager.activeModel.collect { active ->
                _uiState.update { current ->
                    current.copy(
                        activeModel = active,
                        arTrackingStatus = if (!current.spatialAnchor.isAnchored) {
                            ARTrackingStatus.PLANE_DETECTED
                        } else {
                            current.arTrackingStatus
                        },
                        visualState = current.visualState.copy(
                            name = active.name,
                            model = active
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            modelManager.installedModels.collect { installed ->
                _uiState.update { it.copy(installedModels = installed) }
            }
        }
        viewModelScope.launch {
            modelManager.storeModels.collect { store ->
                _uiState.update { it.copy(storeModels = store) }
            }
        }
    }

    private fun playPersonaVoice(audioBase64: String?, fallbackText: String, emotion: EmotionType = EmotionType.NEUTRAL) {
        val anchor = _uiState.value.spatialAnchor
        audioPlayer.updateSpatialAudio(anchor.screenX, anchor.scaleFactor)
        if (!audioBase64.isNullOrEmpty()) {
            // 服务端真人神经网络语音；音频损坏/播放失败时 AudioPlayer 自动用 fallbackText 回退端侧 TTS
            audioPlayer.playAudioBase64(audioBase64, emotion.name, fallbackText)
        } else {
            audioPlayer.speakTextFallback(fallbackText, emotion.name)
        }
    }

    private fun setupAudioPlayer() {
        // 服务端语音播放失败（网络返回损坏音频/解码失败）时，AudioPlayer 已自动回退端侧 TTS，这里只记录
        audioPlayer.onPlaybackError = { reason ->
            android.util.Log.w("PersonaViewModel", "服务端语音播放失败，已回退端侧 TTS: " + reason)
        }
        audioPlayer.onAmplitudeUpdate = { amplitude ->
            _uiState.update { current ->
                current.copy(
                    visualState = current.visualState.copy(lipSyncAmplitude = amplitude),
                    isSpeaking = amplitude > 0.05f
                )
            }
        }
        audioPlayer.onPlaybackFinished = {
            _uiState.update { current ->
                current.copy(
                    visualState = current.visualState.copy(lipSyncAmplitude = 0f),
                    isSpeaking = false
                )
            }
        }
    }

    private fun setupVoiceRecorder() {
        voiceRecorder.onVolumeUpdate = { volume ->
            _uiState.update { it.copy(micVolume = volume) }
        }

        voiceRecorder.onSpeechDetected = {
            voiceTurnEpoch++
            rawStreamingText = ""
            if (_uiState.value.isSpeaking) {
                audioPlayer.interrupt()
            }
            if (_uiState.value.isContinuousActive) {
                wsClient.sendSpeechStart()
            }
            _uiState.update {
                it.copy(
                    isUserSpeaking = true,
                    isLoading = false,
                    streamingText = null
                )
            }
        }

        voiceRecorder.onSilenceCompleted = {
            _uiState.update { it.copy(isUserSpeaking = false) }
            onContinuousSpeechFinished()
        }
    }

    private fun setupWebSocket() {
        wsClient.onInterruptAck = {
            rawStreamingText = ""
            audioPlayer.interrupt()
            _uiState.update { it.copy(isLoading = false, streamingText = null) }
        }
        wsClient.onAsrEmpty = {
            _uiState.update { it.copy(isLoading = false, isUserSpeaking = false) }
        }
        wsClient.onAsrResult = { userSpeechText ->
            if (_uiState.value.isContinuousActive && pendingWsTurnEpoch == voiceTurnEpoch) {
                val userMsg = ChatMessage(
                    sender = MessageSender.USER,
                    text = userSpeechText
                )
                _uiState.update { current ->
                    current.copy(
                        messages = current.messages + userMsg,
                        isLoading = true,
                        streamingText = ""
                    )
                }
            }
        }
        wsClient.onThinkingStart = {
            if (_uiState.value.isContinuousActive && pendingWsTurnEpoch == voiceTurnEpoch) {
                rawStreamingText = ""
                _uiState.update { it.copy(isLoading = true, streamingText = "", errorMessage = null) }
            }
        }
        wsClient.onStreamChunk = { chunk ->
            if (_uiState.value.isContinuousActive && pendingWsTurnEpoch == voiceTurnEpoch) {
                rawStreamingText += chunk
                val sanitized = PersonaTextSanitizer.stripControlTags(rawStreamingText)
                _uiState.update { current ->
                    current.copy(streamingText = sanitized)
                }
            }
        }
        wsClient.onError = { err ->
            rawStreamingText = ""
            val wasContinuous = _uiState.value.isContinuousActive
            if (wasContinuous) {
                voiceTurnEpoch++
                voiceRecorder.stopListening()
            }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    streamingText = null,
                    isUserSpeaking = false,
                    isContinuousActive = false,
                    isRecording = false,
                    micVolume = 0f,
                    errorMessage = if (wasContinuous || current.isLoading) {
                        "连麦通道已中断：$err"
                    } else {
                        current.errorMessage
                    }
                )
            }
        }
        wsClient.onDisconnected = { _ ->
            rawStreamingText = ""
            if (_uiState.value.isContinuousActive) {
                voiceTurnEpoch++
                voiceRecorder.stopListening()
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        streamingText = null,
                        isUserSpeaking = false,
                        isContinuousActive = false,
                        isRecording = false,
                        micVolume = 0f
                    )
                }
            }
        }
        wsClient.onResponseComplete = { respText, emotion, action, expr, audioBase64, affinity, mood ->
            if (!_uiState.value.isContinuousActive || pendingWsTurnEpoch != voiceTurnEpoch) {
                rawStreamingText = ""
                _uiState.update { it.copy(isLoading = false, streamingText = null) }
            } else {
                rawStreamingText = ""
                val cleanText = PersonaTextSanitizer.stripControlTags(respText, stripTrailingPartial = false)
                val actionStartNanos = System.nanoTime()
                _uiState.update { current ->
                    val personaMsg = ChatMessage(
                        sender = MessageSender.PERSONA,
                        text = cleanText,
                        emotion = emotion
                    )
                    val isShy = emotion == EmotionType.SHY || action == ActionType.BLUSH
                    current.copy(
                        isLoading = false,
                        streamingText = null,
                        errorMessage = null,
                        messages = current.messages + personaMsg,
                        visualState = current.visualState.copy(
                            emotion = emotion,
                            action = action,
                            actionStartedAtNanos = actionStartNanos,
                            expression = expr,
                            blushIntensity = if (isShy) 0.85f else 0f
                        ),
                        personaStatus = current.personaStatus?.copy(
                            affinity = affinity,
                            mood = mood
                        )
                    )
                }
                playPersonaVoice(audioBase64, cleanText, emotion)
                scheduleActionReset(action, actionStartNanos)
                addBondExp(3)
            }
        }
    }

    /** 动作对应的情绪（让 3D 体态/表情在动作期间同步，而不是只动肢体） */
    private fun emotionForAction(action: ActionType): EmotionType? = when (action) {
        ActionType.WAVE, ActionType.JUMP, ActionType.NOD -> EmotionType.JOY
        ActionType.SMUG -> EmotionType.CONFIDENT
        ActionType.BLUSH, ActionType.BOW -> EmotionType.SHY
        ActionType.POUT, ActionType.SHAKE_HEAD -> EmotionType.ANGRY
        ActionType.YAWN, ActionType.SLEEP -> EmotionType.SLEEPY
        else -> null
    }

    /**
     * 动作自然结束后的回落：把 action 复位到 IDLE，并恢复温和情绪与待机表情。
     * 以「动作开始时间戳」作为令牌，避免连续触发动作时旧协程把新动作清掉。
     */
    private fun scheduleActionReset(action: ActionType, startedAtNanos: Long) {
        val duration = ActionTiming.durationOf(action)
        if (duration <= 0f) return
        viewModelScope.launch {
            delay((duration * 1000f).toLong() + 120L)
            _uiState.update { current ->
                if (current.visualState.actionStartedAtNanos != startedAtNanos) {
                    current
                } else {
                    current.copy(
                        visualState = current.visualState.copy(
                            action = ActionType.IDLE,
                            actionStartedAtNanos = 0L,
                            emotion = EmotionType.NEUTRAL,
                            expression = "SMILE",
                            blushIntensity = 0f
                        )
                    )
                }
            }
        }
    }

    /**
     * 手动触发一次动作演出：设置页「3D 动作预览」与对话/触摸共用。
     * 既方便用户直接欣赏 3D 手办的全部动作，也作为真机动作回归验证入口。
     */
    fun previewAction(action: ActionType) {
        interactionEpoch++
        if (action == ActionType.SIT) {
            setSkeletonStance(SkeletonStance.SITTING)
            return
        }
        if (action == ActionType.IDLE) {
            // 待机复位：清空动作与触摸反应，平滑回到自然呼吸状态
            _uiState.update { current ->
                current.copy(
                    visualState = current.visualState.copy(
                        action = ActionType.IDLE,
                        actionStartedAtNanos = 0L,
                        blushIntensity = 0f,
                        emotion = EmotionType.NEUTRAL,
                        expression = "SMILE",
                        touchReaction = null,
                        touchReactionStartedAtNanos = 0L
                    )
                )
            }
            return
        }
        val startedAt = System.nanoTime()
        _uiState.update { current ->
            current.copy(
                visualState = current.visualState.copy(
                    action = action,
                    actionStartedAtNanos = startedAt,
                    blushIntensity = if (action == ActionType.BLUSH) 0.85f else 0f,
                    emotion = emotionForAction(action) ?: current.visualState.emotion,
                    expression = when (action) {
                        ActionType.WAVE, ActionType.NOD, ActionType.JUMP, ActionType.BOW -> "SMILE"
                        ActionType.BLUSH -> "BLUSH_FACE"
                        ActionType.SMUG -> "SMUG"
                        ActionType.POUT, ActionType.SHAKE_HEAD -> "POUT"
                        ActionType.YAWN, ActionType.SLEEP -> "SLEEPY"
                        ActionType.IDLE, ActionType.SIT -> current.visualState.expression
                    }
                )
            )
        }
        scheduleActionReset(action, startedAt)
    }

    private fun setupSensors() {
        universalTracker.start()
        viewModelScope.launch {
            universalTracker.spatialAnchor.collect { tracked ->
                _uiState.update { current ->
                    val optical = calculateOpticalParams(
                        screenX = tracked.screenX,
                        screenY = tracked.screenY,
                        scaleFactor = tracked.scaleFactor,
                        bloomBleedFactor = tracked.bloomIntensity
                    )
                    val mergedAnchor = tracked.copy(
                        chromaticAberrationIntensity = optical.chromaticAberration,
                        chromaticAngleRad = optical.chromaticAngle,
                        depthOfFieldBlur = optical.depthOfFieldBlur,
                        isMacroProximity = optical.isMacroProximity
                    )
                    val effectiveEnvLight = (tracked.lightIntensity * tracked.lensVignetteFactor).coerceIn(0.18f, 1.35f)
                    val mergedVisual = current.visualState.copy(
                        scale = tracked.scaleFactor,
                        environmentalLighting = effectiveEnvLight,
                        isLowLight = tracked.isLowLight,
                        ambientColorTemp = tracked.ambientColorTemp,
                        ambientTintRed = (tracked.ambientTintRed * 0.7f + tracked.localSurfaceTintRed * 0.3f).coerceIn(0.75f, 1.30f),
                        ambientTintGreen = (tracked.ambientTintGreen * 0.7f + tracked.localSurfaceTintGreen * 0.3f).coerceIn(0.75f, 1.30f),
                        ambientTintBlue = (tracked.ambientTintBlue * 0.7f + tracked.localSurfaceTintBlue * 0.3f).coerceIn(0.75f, 1.30f),
                        sensorGrainIntensity = tracked.sensorGrainIntensity,
                        gazeShiftX = tracked.cameraGazeOffsetX,
                        gazeShiftY = tracked.cameraGazeOffsetY,
                        inertialSwayAngle = tracked.inertialSwayAngle,
                        chromaticAberrationIntensity = optical.chromaticAberration,
                        chromaticAngleRad = optical.chromaticAngle,
                        bloomIntensity = optical.bloomIntensity,
                        depthOfFieldBlur = optical.depthOfFieldBlur,
                        isMacroProximity = optical.isMacroProximity,
                        lightDirectionX = tracked.lightDirectionX,
                        lightDirectionY = tracked.lightDirectionY,
                        lightDirectionalStrength = tracked.lightDirectionalStrength,
                        cameraPitchDegrees = tracked.cameraPitchDegrees,
                        cameraRollDegrees = tracked.cameraRollDegrees,
                        lensVignetteFactor = tracked.lensVignetteFactor,
                        isCameraFocusing = tracked.isCameraFocusing,
                        skeletonStance = tracked.skeletonStance
                    )
                    if (current.spatialAnchor == mergedAnchor && current.visualState == mergedVisual) {
                        current
                    } else {
                        current.copy(
                            spatialAnchor = mergedAnchor,
                            visualState = mergedVisual
                        )
                    }
                }

                // 空间声场方位实时联动（随镜头平移/绕圈公转同步平移声相）
                if (tracked.isAnchored) {
                    val pan = if (tracked.isInView) {
                        ((tracked.screenX - 0.5f) * 2f).coerceIn(-1f, 1f)
                    } else {
                        (tracked.azimuthDeltaDegrees / 45f).coerceIn(-1f, 1f)
                    }
                    audioPlayer.updateSpatialAudio(
                        normalizedScreenX = (pan + 1f) / 2f,
                        scaleFactor = tracked.scaleFactor
                    )
                }
            }
        }
    }

    fun snapshotImuWorldBasis(outRight: FloatArray, outUp: FloatArray, outForward: FloatArray): Boolean {
        return universalTracker.snapshotImuWorldBasis(outRight, outUp, outForward)
    }

    fun onVisualAnchorObserved(
        obs: VisualAnchorObservation,
        basisRight: FloatArray,
        basisUp: FloatArray,
        basisForward: FloatArray
    ) {
        universalTracker.onVisualAnchorObserved(obs, basisRight, basisUp, basisForward)
    }

    fun onCameraIntrinsicsChanged(effectiveVerticalFovDeg: Float, viewportAspect: Float) {
        universalTracker.updateCameraIntrinsics(effectiveVerticalFovDeg, viewportAspect)
    }

    private fun loadInitialStatus() {
        viewModelScope.launch {
            val currentActive = modelManager.activeModel.value
            // 确保服务端切换至本地持久化的当前伙伴
            repository.switchPersona(currentActive.id)
            val currentState = _uiState.value
            if (currentState.llmProvider != "mock") {
                repository.updateLLMConfig(
                    currentState.llmProvider,
                    currentState.llmBaseUrl,
                    currentState.llmApiKey,
                    currentState.llmModel
                )
            }
            if (currentState.asrProvider != "mock") {
                repository.updateAsrConfig(
                    currentState.asrProvider,
                    currentState.asrBaseUrl,
                    currentState.asrApiKey,
                    currentState.asrModel,
                    currentState.asrLanguage
                )
            }
            val result = repository.getStatus(
                personaId = currentActive.id,
                fallbackPartnerName = currentActive.name
            )
            result.onSuccess { status ->
                val greeting = getCircadianGreeting(currentActive.id, currentActive.name, currentActive.greetingText)
                _uiState.update { state ->
                    val nextMessages = if (state.messages.isEmpty()) {
                        listOf(
                            ChatMessage(
                                sender = MessageSender.PERSONA,
                                text = greeting
                            )
                        )
                    } else {
                        state.messages
                    }
                    state.copy(
                        personaStatus = status,
                        messages = nextMessages
                    )
                }
            }
        }
    }

    private fun getCircadianGreeting(personaId: String, partnerName: String, baseGreeting: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (personaId) {
            "luna" -> when (hour) {
                in 0..5 -> "哼，这个点还不睡？本魔女可不是因为担心你才亮着魔导灯陪你熬夜的哦。"
                in 6..9 -> "早安，愚蠢又准时的使魔。今天也要乖乖在本魔女身边报到才行。"
                in 11..13 -> "喂，都午休时间了还在发呆？赶紧去补充能量，别饿晕在本魔女桌上。"
                in 17..19 -> "夕阳不错吧？今天的烦心事就统统交给本魔女替你清零好了。"
                in 22..23 -> "夜深了哦，笨蛋使魔。勉强准你早点去休息，明天也要继续回应我。"
                else -> baseGreeting
            }
            "airi" -> when (hour) {
                in 0..5 -> "这么晚学长还在忙碌吗…累了的话一定要早点休息，艾莉会一直守着你的~"
                in 6..9 -> "学长早上好呀！迎着晨光和你开启新的一天，觉得心里满满的都是干劲呢~"
                in 11..13 -> "已经是中午啦，学长记得去吃一顿暖呼呼的午饭，好好犒劳一下自己哦！"
                in 17..19 -> "学长今天辛苦了，傍晚的时候就稍微放慢节奏，跟我聊聊天吧~"
                in 22..23 -> "夜深了呢，今天也辛苦学长啦，早点盖好被子做个甜美的梦吧~ 晚安。"
                else -> baseGreeting
            }
            "cyber_k" -> when (hour) {
                in 0..5 -> "深夜模式已激活，指挥官。人体生物钟提醒建议尽快进入休眠，本机继续待命护航。"
                in 6..9 -> "早安，指挥官。全息环境校准与今日日常模块初始化完毕，祝您今日行动高效。"
                in 11..13 -> "已进入正午能量补给窗口，建议指挥官暂时离线用膳，本机持续保持桌面警戒。"
                in 17..19 -> "日暮低功耗巡航开启。指挥官，今日任务已近尾声，可开启放松对话。"
                in 22..23 -> "夜间静音守护协议启动，请指挥官准备休息，明晨本机将第一时间唤醒。"
                else -> baseGreeting
            }
            else -> when (hour) {
                in 0..5 -> "这么晚还在现实世界忙碌呀？要注意休息哦，${partnerName}就在书桌前静静陪着你呢。"
                in 6..9 -> "早上好！新的一天充满元气，今天的阳光和空气感觉都很棒呢~"
                in 11..13 -> "已经到午休时间啦，记得按时吃顿美味的午饭补充体力哦！"
                in 17..19 -> "傍晚好呀，今天一整天辛苦了，和我聊聊今天遇到的趣事吧！"
                in 22..23 -> "夜深了呢，准备好睡个香甜的好觉了吗？晚安哦~"
                else -> baseGreeting
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val personaId = activePersonaId()

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = text
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val result = repository.sendChat(text, personaId = personaId)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess { resp ->
                val actionStartNanos = System.nanoTime()
                val personaMessage = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = resp.text,
                    emotion = resp.emotion
                )

                _uiState.update { current ->
                    current.copy(
                        messages = current.messages + personaMessage,
                        visualState = current.visualState.copy(
                            emotion = resp.emotion,
                            action = resp.action,
                            actionStartedAtNanos = actionStartNanos,
                            expression = resp.expression
                        ),
                        personaStatus = current.personaStatus?.copy(
                            affinity = resp.currentAffinity,
                            mood = resp.mood
                        )
                    )
                }
                playPersonaVoice(resp.audioBase64, resp.text, resp.emotion)
                scheduleActionReset(resp.action, actionStartNanos)
                addBondExp(3)
            }
        }
    }

    /**
     * 实景多模态视野交互 (Vision AI)
     * 将物理摄像头采集的当前画面帧传输至视觉大脑，让二次元伴侣看懂指挥官面前的现实世界并给出治愈反馈。
     */
    fun onVisionInteract(cameraBitmap: Bitmap?, prompt: String = "") {
        val partnerName = _uiState.value.activeModel?.name ?: "星野"
        if (cameraBitmap == null || cameraBitmap.isRecycled) {
            sendMessage(prompt.ifBlank { "${partnerName}，看看我们眼前的现实世界~" })
            return
        }

        viewModelScope.launch {
            val personaId = activePersonaId()
            _uiState.update { it.copy(isLoading = true) }
            val displayPrompt = prompt.ifBlank { "✦ 视野交互：看看眼前 ✦" }
            val userMessage = ChatMessage(
                sender = MessageSender.USER,
                text = displayPrompt,
                timestamp = System.currentTimeMillis()
            )
            _uiState.update { it.copy(messages = it.messages + userMessage) }

            val base64 = withContext(Dispatchers.IO) {
                var scaled: Bitmap? = null
                try {
                    val maxDim = maxOf(cameraBitmap.width, cameraBitmap.height)
                    val scale = if (maxDim > 720) 720f / maxDim else 1.0f
                    val targetW = (cameraBitmap.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (cameraBitmap.height * scale).toInt().coerceAtLeast(1)
                    val targetBmp = if (scale < 1.0f) {
                        Bitmap.createScaledBitmap(cameraBitmap, targetW, targetH, true).also { scaled = it }
                    } else {
                        cameraBitmap
                    }
                    val stream = ByteArrayOutputStream()
                    targetBmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val bytes = stream.toByteArray()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } finally {
                    scaled?.takeIf { it !== cameraBitmap && !it.isRecycled }?.recycle()
                    cameraBitmap.takeIf { !it.isRecycled }?.recycle()
                }
            }

            val result = repository.sendVisionInteract(base64, prompt, partnerName = partnerName, personaId = personaId)
            result.onSuccess { resp ->
                val personaMessage = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = resp.text,
                    emotion = resp.emotion,
                    timestamp = System.currentTimeMillis()
                )

                val actionStartNanos = System.nanoTime()
                _uiState.update { current ->
                    val pose = when (resp.emotion) {
                        EmotionType.SHY -> "SHY"
                        EmotionType.ANGRY -> "POUT"
                        EmotionType.SLEEPY -> "SLEEPY"
                        EmotionType.CONFIDENT -> "SMUG"
                        else -> when (resp.action) {
                            ActionType.BLUSH -> "SHY"
                            ActionType.POUT -> "POUT"
                            ActionType.SLEEP, ActionType.YAWN -> "SLEEPY"
                            ActionType.SMUG -> "SMUG"
                            else -> "DEFAULT"
                        }
                    }
                    current.copy(
                        messages = current.messages + personaMessage,
                        isLoading = false,
                        visualState = current.visualState.copy(
                            emotion = resp.emotion,
                            action = resp.action,
                            actionStartedAtNanos = actionStartNanos,
                            expression = resp.expression,
                            blushIntensity = if (resp.emotion == EmotionType.SHY || resp.action == ActionType.BLUSH) 0.85f else 0f
                        ),
                        personaStatus = current.personaStatus?.copy(
                            affinity = resp.currentAffinity,
                            mood = resp.mood
                        )
                    )
                }
                playPersonaVoice(resp.audioBase64, resp.text, resp.emotion)
                scheduleActionReset(resp.action, actionStartNanos)
                addBondExp(4)
            }.onFailure {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onTouchPart(part: TouchPart, touchX: Float = 0.5f, touchY: Float = 0.45f) {
        val (expGain, fxText) = when (part) {
            TouchPart.HEAD -> Pair(3, "+3 羁绊 摸摸头 ❤️")
            TouchPart.CHEEK -> Pair(2, "+2 羁绊 戳戳脸 ✨")
            TouchPart.HAND -> Pair(4, "+4 羁绊 牵小手 🤝")
            TouchPart.BODY -> Pair(5, "+5 羁绊 拥抱 💕")
        }

        // 触感微震动联动
        triggerHapticFeedback(part)

        // 1. 端侧即时 3D 骨骼动作与 VRM 表情反馈 (Zero GMS 毫秒级零延迟触发)
        val touchReactionStartNanos = System.nanoTime()
        val myEpoch = ++interactionEpoch
        val touchBlush = when (part) {
            TouchPart.HEAD -> 0.72f
            TouchPart.CHEEK -> 0f
            TouchPart.HAND -> 0.35f
            TouchPart.BODY -> 0.85f
        }
        val touchAction = when (part) {
            TouchPart.HEAD -> ActionType.NOD
            TouchPart.CHEEK -> ActionType.POUT
            TouchPart.HAND -> ActionType.WAVE
            TouchPart.BODY -> ActionType.BLUSH
        }
        val touchEmotion = when (part) {
            TouchPart.HEAD -> EmotionType.JOY
            TouchPart.CHEEK -> EmotionType.ANGRY
            TouchPart.HAND -> EmotionType.SHY
            TouchPart.BODY -> EmotionType.SHY
        }
        val touchExpr = when (part) {
            TouchPart.HEAD -> "JOY"
            TouchPart.CHEEK -> "POUT"
            TouchPart.HAND -> "WINK"
            TouchPart.BODY -> "BLUSH_FACE"
        }

        _uiState.update { current ->
            current.copy(
                visualState = current.visualState.copy(
                    blushIntensity = touchBlush,
                    action = touchAction,
                    emotion = touchEmotion,
                    expression = touchExpr,
                    actionStartedAtNanos = touchReactionStartNanos,
                    touchReaction = part,
                    touchReactionStartedAtNanos = touchReactionStartNanos
                )
            )
        }

        // 触摸反应动作与表情 2.6 秒后自动同步复位（以开始时间戳为令牌，避免连点误清）
        viewModelScope.launch {
            delay(2700L)
            _uiState.update { current ->
                if (current.visualState.touchReactionStartedAtNanos != touchReactionStartNanos) current
                else current.copy(
                    visualState = current.visualState.copy(
                        touchReaction = null,
                        touchReactionStartedAtNanos = 0L,
                        emotion = EmotionType.NEUTRAL,
                        action = if (current.visualState.action == touchAction) ActionType.IDLE else current.visualState.action,
                        actionStartedAtNanos = if (current.visualState.action == touchAction) 0L else current.visualState.actionStartedAtNanos,
                        blushIntensity = if (current.visualState.blushIntensity == touchBlush) 0f else current.visualState.blushIntensity
                    )
                )
            }
        }

        // 触发即时浮空动画粒子
        val fxItem = FloatingTouchFxItem(
            text = fxText,
            x = touchX,
            y = touchY
        )
        _uiState.update { current ->
            current.copy(floatingTouchFx = current.floatingTouchFx + fxItem)
        }
        viewModelScope.launch {
            delay(1200L)
            _uiState.update { current ->
                current.copy(floatingTouchFx = current.floatingTouchFx.filter { it.id != fxItem.id })
            }
        }

        viewModelScope.launch {
            val result = repository.sendTouch(part, personaId = activePersonaId())
            result.onSuccess { resp ->
                val personaMessage = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = resp.text,
                    emotion = resp.emotion
                )

                // 若端侧 2.6s 专属触摸动作仍在播放，保留本地专属触摸动作与开始时刻不被异步回复打断/跳帧，
                // 仅同步气泡文本、语音、好感度与非冲突表情状态。
                val actionStartNanos = System.nanoTime()
                val staleInteraction = interactionEpoch != myEpoch
                var keepTouchPoseActive = false
                _uiState.update { current ->
                    val stillInThisTouch = !staleInteraction &&
                        current.visualState.touchReactionStartedAtNanos == touchReactionStartNanos
                    keepTouchPoseActive = stillInThisTouch
                    when {
                        staleInteraction -> current.copy(
                            messages = current.messages + personaMessage,
                            personaStatus = current.personaStatus?.copy(affinity = resp.currentAffinity)
                        )
                        stillInThisTouch -> current.copy(
                            messages = current.messages + personaMessage,
                            visualState = current.visualState.copy(
                                emotion = resp.emotion
                            ),
                            personaStatus = current.personaStatus?.copy(
                                affinity = resp.currentAffinity
                            )
                        )
                        else -> current.copy(
                            messages = current.messages + personaMessage,
                            visualState = current.visualState.copy(
                                emotion = resp.emotion,
                                action = resp.action,
                                actionStartedAtNanos = actionStartNanos,
                                expression = resp.expression
                            ),
                            personaStatus = current.personaStatus?.copy(
                                affinity = resp.currentAffinity
                            )
                        )
                    }
                }
                playPersonaVoice(resp.audioBase64, resp.text, resp.emotion)
                if (!staleInteraction && !keepTouchPoseActive) {
                    scheduleActionReset(resp.action, actionStartNanos)
                }
                addBondExp(expGain)
            }
        }
    }

    private fun triggerHapticFeedback(part: TouchPart) {
        try {
            if (vibrator?.hasVibrator() == true) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val effect = when (part) {
                        // 摸头：顺毛轻抚柔波微振
                        TouchPart.HEAD -> android.os.VibrationEffect.createWaveform(
                            longArrayOf(0, 18, 45, 14),
                            intArrayOf(0, 110, 0, 75),
                            -1
                        )
                        // 戳脸：如果冻般弹性双弹跳微颤
                        TouchPart.CHEEK -> android.os.VibrationEffect.createWaveform(
                            longArrayOf(0, 15, 30, 22),
                            intArrayOf(0, 180, 0, 130),
                            -1
                        )
                        // 牵手：指尖持续温热微颤
                        TouchPart.HAND -> android.os.VibrationEffect.createWaveform(
                            longArrayOf(0, 24, 38, 24),
                            intArrayOf(0, 130, 0, 130),
                            -1
                        )
                        // 拥抱：双拍心跳律动 (Lub-dub 心跳感)
                        TouchPart.BODY -> android.os.VibrationEffect.createWaveform(
                            longArrayOf(0, 32, 70, 48),
                            intArrayOf(0, 210, 0, 190),
                            -1
                        )
                    }
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25L)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 手动触发星野慵懒打瞌睡休眠姿态 (Sleepy Nap Mode)
     */
    fun triggerSleepyNap() {
        _uiState.update { current ->
            current.copy(
                visualState = current.visualState.copy(
                    emotion = EmotionType.SLEEPY,
                    action = ActionType.SLEEP,
                    actionStartedAtNanos = System.nanoTime()
                )
            )
        }
    }

    // --- 动态模型与商城系统 ---

    fun switchPersonaModel(modelId: String) {
        if (modelManager.switchModel(modelId)) {
            interactionEpoch++
            voiceTurnEpoch++
            rawStreamingText = ""
            val newModel = modelManager.activeModel.value
            val greeting = getCircadianGreeting(newModel.id, newModel.name, newModel.greetingText)
            audioPlayer.interrupt()
            val wasContinuous = _uiState.value.isContinuousActive
            _uiState.update { current ->
                current.copy(
                    activeModel = newModel,
                    isLoading = false,
                    streamingText = null,
                    visualState = current.visualState.copy(
                        name = newModel.name,
                        model = newModel,
                        emotion = EmotionType.NEUTRAL,
                        action = ActionType.IDLE,
                        actionStartedAtNanos = 0L,
                        expression = "SMILE",
                        blushIntensity = 0f,
                        touchReaction = null,
                        touchReactionStartedAtNanos = 0L
                    ),
                    messages = listOf(
                        ChatMessage(
                            sender = MessageSender.PERSONA,
                            text = greeting
                        )
                    )
                )
            }
            if (wasContinuous) {
                wsClient.disconnect()
                wsClient.connect(_uiState.value.serverBaseUrl, personaId = modelId)
            }
            persistCurrentSettings()
            viewModelScope.launch {
                repository.switchPersona(modelId)
                repository.getStatus(personaId = modelId, fallbackPartnerName = newModel.name)
                    .onSuccess { status ->
                        _uiState.update { it.copy(personaStatus = status) }
                    }
                loadMemories()
            }
        }
    }

    fun installModelFromStore(modelId: String) {
        if (modelManager.installModelFromStore(modelId)) {
            persistCurrentSettings()
            switchPersonaModel(modelId)
        }
    }

    fun setStoreOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isStoreOpen = isOpen) }
    }

    fun setPreviewModel(model: PersonaModelManifest?) {
        _uiState.update { it.copy(previewModel = model) }
    }

    // --- 对讲模式控制 ---

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleContinuousListening() {
        val nextActive = !_uiState.value.isContinuousActive
        if (nextActive && !hasRecordAudioPermission()) {
            _uiState.update {
                it.copy(errorMessage = "需要麦克风权限才能开启全双工连麦，请授权后重试")
            }
            return
        }
        voiceTurnEpoch++
        rawStreamingText = ""
        if (nextActive) {
            _uiState.update {
                it.copy(
                    isContinuousActive = true,
                    isRecording = true,
                    errorMessage = null
                )
            }
            wsClient.connect(_uiState.value.serverBaseUrl, personaId = activePersonaId())
            voiceRecorder.startListening(isContinuousMode = true)
        } else {
            audioPlayer.interrupt()
            wsClient.disconnect()
            voiceRecorder.stopListening()
            _uiState.update {
                it.copy(
                    isContinuousActive = false,
                    isRecording = false,
                    isUserSpeaking = false,
                    isLoading = false,
                    streamingText = null,
                    micVolume = 0f
                )
            }
        }
    }

    private fun onContinuousSpeechFinished() {
        val wavBytes = voiceRecorder.getAndResetRecordedWav()
        if (!_uiState.value.isContinuousActive) return
        if (wavBytes != null && wavBytes.size > 44 + 3200) {
            val audioBase64 = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
            pendingWsTurnEpoch = voiceTurnEpoch
            wsClient.sendAudioMessage(audioBase64, "wav", personaId = activePersonaId())
        }
    }

    fun startHoldRecording() {
        if (_uiState.value.voiceMode != VoiceMode.HOLD_TO_TALK) return
        if (!hasRecordAudioPermission()) {
            _uiState.update {
                it.copy(errorMessage = "需要麦克风权限才能按住对讲，请授权后重试")
            }
            return
        }
        if (_uiState.value.isSpeaking) {
            audioPlayer.interrupt()
        }
        _uiState.update { it.copy(isRecording = true, errorMessage = null) }
        voiceRecorder.startListening(isContinuousMode = false)
    }

    fun stopHoldRecording() {
        if (_uiState.value.voiceMode != VoiceMode.HOLD_TO_TALK) return
        if (!_uiState.value.isRecording) return
        voiceRecorder.stopListening()
        val wavBytes = voiceRecorder.getAndResetRecordedWav()
        // 至少约 300ms 有效录音（44B WAV 头 + 9600B 16kHz 16-bit PCM）
        if (wavBytes == null || wavBytes.size <= 44 + 9600) {
            _uiState.update {
                it.copy(
                    isRecording = false,
                    micVolume = 0f,
                    isLoading = false,
                    errorMessage = "按住说话时间太短啦，请再多说一点哦~"
                )
            }
            return
        }

        _uiState.update { it.copy(isRecording = false, micVolume = 0f, isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = repository.transcribeAudio(wavBytes)
            result.onSuccess { text ->
                if (text.isBlank()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "未识别到清晰语音，请靠近麦克风重试"
                        )
                    }
                } else {
                    sendMessage(text)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "语音识别暂不可用：${err.message ?: "请检查网络或 ASR 配置"}"
                    )
                }
            }
        }
    }

    fun setVoiceMode(mode: VoiceMode) {
        voiceTurnEpoch++
        rawStreamingText = ""
        audioPlayer.interrupt()
        wsClient.disconnect()
        voiceRecorder.stopListening()
        _uiState.update {
            it.copy(
                voiceMode = mode,
                isContinuousActive = false,
                isRecording = false,
                isUserSpeaking = false,
                isLoading = false,
                streamingText = null,
                micVolume = 0f
            )
        }
        persistCurrentSettings()
    }

    fun setBubbleDisplayMode(mode: BubbleDisplayMode) {
        _uiState.update { it.copy(bubbleDisplayMode = mode) }
        persistCurrentSettings()
    }

    private var lastMacroProximityNotifyTimestamp = 0L

    private data class OpticalCalculations(
        val chromaticAberration: Float,
        val chromaticAngle: Float,
        val bloomIntensity: Float,
        val depthOfFieldBlur: Float,
        val isMacroProximity: Boolean
    )

    private fun calculateOpticalParams(
        screenX: Float,
        screenY: Float,
        scaleFactor: Float,
        bloomBleedFactor: Float
    ): OpticalCalculations {
        val dx = (screenX - 0.5f) * 2f
        val dy = (screenY - 0.5f) * 2f
        val radialDist = kotlin.math.sqrt(dx * dx + dy * dy).coerceIn(0f, 1.414f)
        val chromaticAngle = kotlin.math.atan2(dy, dx)
        val chromaticAberration = (radialDist * 0.72f).coerceIn(0f, 1f)
        val isMacro = scaleFactor >= 1.50f
        val dofBlur = (kotlin.math.abs(scaleFactor - 1.0f) * 0.38f).coerceIn(0f, 0.65f)

        return OpticalCalculations(
            chromaticAberration = chromaticAberration,
            chromaticAngle = chromaticAngle,
            bloomIntensity = bloomBleedFactor,
            depthOfFieldBlur = dofBlur,
            isMacroProximity = isMacro
        )
    }

    fun onTapPlaceAnchor(screenX: Float, screenY: Float) {
        audioPlayer.updateSpatialAudio(screenX, 1.0f)
        universalTracker.anchorToDesktop(screenX, screenY)
        universalTracker.updateCameraFocus(true, System.currentTimeMillis())
        val anchored = universalTracker.spatialAnchor.value
        _uiState.update { current ->
            current.copy(
                arTrackingStatus = ARTrackingStatus.ANCHORED,
                spatialAnchor = anchored,
                resetFeaturesSignal = System.nanoTime()
            )
        }
    }

    fun onTransformAnchor(scaleMultiplier: Float, rotationYawDelta: Float) {
        val wasMacro = universalTracker.spatialAnchor.value.isMacroProximity
        universalTracker.updateScale(scaleMultiplier)
        universalTracker.updateRotation(rotationYawDelta)
        val anchor = universalTracker.spatialAnchor.value
        audioPlayer.updateSpatialAudio(anchor.screenX, anchor.scaleFactor)

        val nowMacro = anchor.scaleFactor >= 1.50f
        if (!wasMacro && nowMacro) {
            val now = System.currentTimeMillis()
            if (now - lastMacroProximityNotifyTimestamp > 20_000L) {
                lastMacroProximityNotifyTimestamp = now
                val careMessage = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = "指挥官突然靠得好近呀... 稍微有点心跳加速呢 (⁄ ⁄•⁄ω⁄•⁄ ⁄) 💕",
                    emotion = EmotionType.SHY,
                    timestamp = now
                )
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + careMessage,
                        visualState = state.visualState.copy(
                            emotion = EmotionType.SHY,
                            expression = "BLUSH_FACE"
                        )
                    )
                }
            }
        }
    }

    fun onResetAnchor() {
        universalTracker.resetAnchor()
        audioPlayer.updateSpatialAudio(0.5f, 1.0f)
        _uiState.update { current ->
            current.copy(
                arTrackingStatus = ARTrackingStatus.PLANE_DETECTED,
                resetFeaturesSignal = 0L
            )
        }
    }

    /** 设置 3D 骨骼体态（站立 ↔ 桌沿垂腿坐姿） */
    fun setSkeletonStance(stance: SkeletonStance) {
        universalTracker.updateSkeletonStance(stance)
        val startedAt = System.nanoTime()
        _uiState.update { current ->
            val nextAction = when {
                stance == SkeletonStance.SITTING -> ActionType.SIT
                current.visualState.action == ActionType.SIT -> ActionType.IDLE
                else -> current.visualState.action
            }
            val nextStarted = if (nextAction != current.visualState.action || nextAction == ActionType.SIT) {
                startedAt
            } else {
                current.visualState.actionStartedAtNanos
            }
            current.copy(
                spatialAnchor = current.spatialAnchor.copy(skeletonStance = stance),
                visualState = current.visualState.copy(
                    skeletonStance = stance,
                    action = nextAction,
                    actionStartedAtNanos = nextStarted
                )
            )
        }
    }

    /**
     * 切换骨骼姿态 (站立 STANDING / 坐姿 SITTING)
     */
    fun onToggleSkeletonStance() {
        val nextStance = if (_uiState.value.spatialAnchor.skeletonStance == SkeletonStance.STANDING) {
            SkeletonStance.SITTING
        } else {
            SkeletonStance.STANDING
        }
        setSkeletonStance(nextStance)
    }

    /**
     * 开始拖拽 3D 模型重定位（射线-桌面水平面求交，零回弹零乱跑）
     */
    fun onDragStart(screenX: Float, screenY: Float) {
        universalTracker.beginDrag(screenX, screenY)
        universalTracker.updateCameraFocus(false)
    }

    /**
     * 拖拽移动 3D 模型（严格落在手指所在物理桌面水平面射线交点）
     */
    fun onDragTo(screenX: Float, screenY: Float) {
        val updated = universalTracker.dragTo(screenX, screenY)
        audioPlayer.updateSpatialAudio(updated.screenX, updated.scaleFactor)
    }

    /**
     * 结束拖拽：稳停在用户松手位置，刷新该处特征点并对焦测光
     */
    fun onDragEnd() {
        val ended = universalTracker.endDrag()
        universalTracker.updateCameraFocus(true, System.currentTimeMillis())
        audioPlayer.updateSpatialAudio(ended.screenX, ended.scaleFactor)
        _uiState.update { current ->
            current.copy(
                spatialAnchor = ended,
                resetFeaturesSignal = System.nanoTime()
            )
        }
    }

    fun onCameraFocusStateChanged(isFocusing: Boolean) {
        universalTracker.updateCameraFocus(isFocusing)
    }

    private var lastLightNotifyTimestamp = 0L

    /**
     * 现实环境全光谱光学特征实时注入（6 方向环境光色、局部桌面底色、锁定世界主光与相机参数）
     */
    fun onOpticalProfileChanged(profile: EnvironmentalOpticalProfile) {
        val prevLowLight = universalTracker.spatialAnchor.value.isLowLight
        universalTracker.updateOpticalMetadata(profile)

        // 现实光照突变时触发伴侣温柔关怀 (防抖 30 秒，且屏蔽启动前 5 秒相机测光初始化抖动)
        val now = System.currentTimeMillis()
        if (!prevLowLight && profile.isLowLight && now - createdAtMillis > 5_000L && now - lastLightNotifyTimestamp > 30_000L) {
            lastLightNotifyTimestamp = now
            val partnerName = _uiState.value.activeModel?.name ?: "星野"
            val careMessage = ChatMessage(
                sender = MessageSender.PERSONA,
                text = "唔... 周围光线变暗了呢，在暗处看屏幕要当心眼睛哦，${partnerName}会一直陪着你的~",
                emotion = EmotionType.SHY,
                timestamp = now
            )
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + careMessage,
                    visualState = state.visualState.copy(
                        emotion = EmotionType.SHY,
                        expression = "SMILE"
                    )
                )
            }
        }
    }

    /**
     * 现实环境光照突变辅助钩子（真实光学与低光提醒已统一由 onOpticalProfileChanged 触发，避免重复消息）
     */
    fun onEnvironmentalLightChanged(luminance: Float, isLowLight: Boolean) {
        // 仅保留接口兼容；低光状态统一由 onOpticalProfileChanged 原子注入并触发一次提醒
    }

    fun applySettings(
        serverUrl: String,
        llmProvider: String,
        llmBaseUrl: String,
        llmApiKey: String,
        llmModel: String,
        asrProvider: String,
        asrBaseUrl: String,
        asrApiKey: String,
        asrModel: String,
        asrLanguage: String = "zh"
    ) {
        val normalizedUrl = PersonaSettingsCodec.normalizeServerUrl(serverUrl)
        val serverChanged = normalizedUrl != _uiState.value.serverBaseUrl
        ApiClient.baseUrl = normalizedUrl
        _uiState.update {
            it.copy(
                serverBaseUrl = normalizedUrl,
                llmProvider = llmProvider,
                llmBaseUrl = llmBaseUrl.trim(),
                llmApiKey = llmApiKey,
                llmModel = llmModel.trim(),
                asrProvider = asrProvider,
                asrBaseUrl = asrBaseUrl.trim(),
                asrApiKey = asrApiKey,
                asrModel = asrModel.trim(),
                asrLanguage = asrLanguage
            )
        }
        persistCurrentSettings()
        if (serverChanged && _uiState.value.isContinuousActive) {
            wsClient.disconnect()
            wsClient.connect(normalizedUrl, personaId = activePersonaId())
        }
        viewModelScope.launch {
            repository.updateLLMConfig(llmProvider, llmBaseUrl.trim(), llmApiKey, llmModel.trim())
            repository.updateAsrConfig(asrProvider, asrBaseUrl.trim(), asrApiKey, asrModel.trim(), asrLanguage)
            if (serverChanged) {
                val active = modelManager.activeModel.value
                repository.switchPersona(active.id)
                repository.getStatus(personaId = active.id, fallbackPartnerName = active.name)
                    .onSuccess { status -> _uiState.update { it.copy(personaStatus = status) } }
                loadMemories()
            }
        }
    }

    fun setSettingsOpen(isOpen: Boolean) {
        _uiState.update {
            it.copy(
                isSettingsOpen = isOpen,
                serverProbeStatus = if (!isOpen) null else it.serverProbeStatus
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun updateServerUrl(newUrl: String) {
        val normalized = PersonaSettingsCodec.normalizeServerUrl(newUrl)
        val changed = normalized != _uiState.value.serverBaseUrl
        ApiClient.baseUrl = normalized
        _uiState.update { it.copy(serverBaseUrl = normalized) }
        persistCurrentSettings()
        if (changed) {
            if (_uiState.value.isContinuousActive) {
                wsClient.disconnect()
                wsClient.connect(normalized)
            }
            loadInitialStatus()
            loadMemories()
        }
    }

    fun testServerConnection(
        candidateUrl: String,
        llmProvider: String,
        llmBaseUrl: String,
        llmApiKey: String,
        llmModel: String
    ) {
        val normalized = PersonaSettingsCodec.normalizeServerUrl(candidateUrl)
        _uiState.update {
            it.copy(
                isServerProbing = true,
                serverProbeOk = null,
                serverProbeStatus = "正在探测 $normalized ..."
            )
        }
        viewModelScope.launch {
            val report = repository.probeServerAndEngines(
                targetUrl = normalized,
                llmConfig = LLMConfigRequest(
                    provider = llmProvider,
                    baseUrl = llmBaseUrl,
                    apiKey = llmApiKey,
                    model = llmModel
                )
            )
            _uiState.update {
                it.copy(
                    isServerProbing = false,
                    serverProbeOk = report.isOnline,
                    serverProbeStatus = report.summary
                )
            }
        }
    }

    fun updateLlmConfig(provider: String, baseUrl: String, apiKey: String, model: String) {
        _uiState.update {
            it.copy(
                llmProvider = provider,
                llmBaseUrl = baseUrl,
                llmApiKey = apiKey,
                llmModel = model
            )
        }
        persistCurrentSettings()
        viewModelScope.launch {
            repository.updateLLMConfig(provider, baseUrl, apiKey, model)
        }
    }

    fun updateAsrConfig(provider: String, baseUrl: String, apiKey: String, model: String, language: String = "zh") {
        _uiState.update {
            it.copy(
                asrProvider = provider,
                asrBaseUrl = baseUrl,
                asrApiKey = apiKey,
                asrModel = model,
                asrLanguage = language
            )
        }
        persistCurrentSettings()
        viewModelScope.launch {
            repository.updateAsrConfig(provider, baseUrl, apiKey, model, language)
        }
    }

    // --- 专属情感回忆手账管理 ---

    fun setMemoryNotebookOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isMemoryNotebookOpen = isOpen) }
        if (isOpen) {
            loadMemories()
        }
    }

    fun loadMemories() {
        val activePersonaId = modelManager.activeModel.value.id
        viewModelScope.launch {
            _uiState.update { it.copy(isMemoryLoading = true) }
            val result = repository.getMemories(personaId = activePersonaId)
            result.onSuccess { memoryList ->
                _uiState.update { it.copy(memories = memoryList, isMemoryLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(isMemoryLoading = false) }
            }
        }
    }

    fun createMemory(content: String, category: String = "daily", importance: Int = 3) {
        if (content.isBlank()) return
        val activePersonaId = modelManager.activeModel.value.id
        val backendCategory = MemoryCategoryMapper.toBackendCategory(category)
        viewModelScope.launch {
            val result = repository.createMemory(
                content = content,
                category = backendCategory,
                importance = importance,
                personaId = activePersonaId
            )
            result.onSuccess { newMemory ->
                _uiState.update { it.copy(memories = listOf(newMemory) + it.memories) }
                addBondExp(15) // 手动书写回忆大幅提升羁绊经验
            }
        }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            val result = repository.deleteMemory(memoryId)
            result.onSuccess {
                _uiState.update { current ->
                    current.copy(memories = current.memories.filterNot { it.id == memoryId })
                }
            }
        }
    }

    // --- 羁绊成长与升级体系 ---

    fun addBondExp(points: Int) {
        _uiState.update { current ->
            var newExp = current.bondExp + points
            var newLevel = current.bondLevel
            var nextThreshold = current.bondExpNext
            var levelUpNotice: String? = null

            while (newExp >= nextThreshold && newLevel < 10) {
                newExp -= nextThreshold
                newLevel += 1
                nextThreshold = (nextThreshold * 1.5).toInt()
                val title = getBondTitleForLevel(newLevel)
                levelUpNotice = "🎉 羁绊升级！达到 Lv.$newLevel【$title】"
            }

            current.copy(
                bondLevel = newLevel,
                bondExp = newExp,
                bondExpNext = nextThreshold,
                bondLevelTitle = getBondTitleForLevel(newLevel),
                bondLevelUpNotice = levelUpNotice ?: current.bondLevelUpNotice
            )
        }
        persistCurrentSettings()
    }

    fun dismissBondNotice() {
        _uiState.update { it.copy(bondLevelUpNotice = null) }
    }

    private fun getBondTitleForLevel(level: Int): String {
        return when (level) {
            1 -> "初遇契约"
            2 -> "朝夕相伴"
            3 -> "心有灵犀"
            4 -> "并肩同行"
            5 -> "灵魂共鸣"
            6 -> "情深意重"
            7 -> "命运相连"
            8 -> "无言默契"
            9 -> "深邃羁绊"
            else -> "至臻誓约"
        }
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
        audioPlayer.release()
        voiceRecorder.release()
        universalTracker.stop()
    }
}
