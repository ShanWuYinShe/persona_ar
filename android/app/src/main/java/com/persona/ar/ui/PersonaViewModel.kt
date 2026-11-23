package com.persona.ar.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.persona.ar.ar.ARMode
import com.persona.ar.ar.ARSpatialAnchor
import com.persona.ar.ar.ARTrackingStatus
import com.persona.ar.ar.SensorTiltHelper
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
import com.persona.ar.data.model.PersonaModelType
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.VoiceMode
import com.persona.ar.data.repository.PersonaRepository
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
    private val sensorTiltHelper = SensorTiltHelper(application)
    private val modelManager = PersonaModelManager(application)
    private val wsClient = PersonaWebSocketClient()

    private val _uiState = MutableStateFlow(PersonaUiState())
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    init {
        setupAudioPlayer()
        setupVoiceRecorder()
        setupWebSocket()
        setupSensors()
        setupModelManager()
        loadInitialStatus()
        loadMemories()
    }

    private fun setupModelManager() {
        viewModelScope.launch {
            modelManager.activeModel.collect { active ->
                _uiState.update { current ->
                    // 拥有真 3D 手办资产的人格默认进入 3D 模式：Filament PBR 手办 + 全动作骨骼动画 + VRM 表情/口型，
                    // 立体光影与肢体动作都优于 2.5D 立绘旋转；没有 3D 资产的人格自动回退 2.5D 逐帧立绘。
                    // 仅当用户尚未手动切换过模式 (arModeExplicitlySet=false) 时才跟随人格能力自动切换。
                    val hasTrue3D = active.modelType == PersonaModelType.GLTF_3D && !active.modelAsset.isNullOrBlank()
                    val nextMode = when {
                        current.arModeExplicitlySet -> current.arMode
                        hasTrue3D -> ARMode.ARCORE_3D
                        else -> ARMode.BILLBOARD_2_5D
                    }
                    current.copy(
                        activeModel = active,
                        arMode = nextMode,
                        arTrackingStatus = if (nextMode == ARMode.ARCORE_3D && !current.spatialAnchor.isAnchored) {
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
            if (_uiState.value.isSpeaking) {
                audioPlayer.interrupt()
            }
            if (_uiState.value.isContinuousActive) {
                wsClient.sendSpeechStart()
            }
            _uiState.update { it.copy(isUserSpeaking = true) }
        }

        voiceRecorder.onSilenceCompleted = {
            _uiState.update { it.copy(isUserSpeaking = false) }
            onContinuousSpeechFinished()
        }
    }

    private fun setupWebSocket() {
        wsClient.onInterruptAck = {
            audioPlayer.interrupt()
        }
        wsClient.onAsrResult = { userSpeechText ->
            // 将真实识别到的指挥官语音转文字作为用户发言加入聊天流
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
        wsClient.onThinkingStart = {
            _uiState.update { it.copy(isLoading = true, streamingText = "") }
        }
        wsClient.onStreamChunk = { chunk ->
            _uiState.update { current ->
                current.copy(streamingText = (current.streamingText ?: "") + chunk)
            }
        }
        wsClient.onResponseComplete = { respText, emotion, action, expr, audioBase64, affinity, mood ->
            val actionStartNanos = System.nanoTime()
            _uiState.update { current ->
                val personaMsg = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = respText,
                    emotion = emotion
                )
                val pose = when (emotion) {
                    EmotionType.SHY -> "SHY"
                    EmotionType.ANGRY -> "POUT"
                    EmotionType.SLEEPY -> "SLEEPY"
                    EmotionType.CONFIDENT -> "SMUG"
                    else -> when (action) {
                        ActionType.BLUSH -> "SHY"
                        ActionType.POUT -> "POUT"
                        ActionType.SLEEP, ActionType.YAWN -> "SLEEPY"
                        ActionType.SMUG -> "SMUG"
                        else -> "DEFAULT"
                    }
                }
                current.copy(
                    isLoading = false,
                    streamingText = null,
                    messages = current.messages + personaMsg,
                    visualState = current.visualState.copy(
                        emotion = emotion,
                        action = action,
                        actionStartedAtNanos = actionStartNanos,
                        expression = expr,
                        highResPose = pose,
                        blushIntensity = if (pose == "SHY") 0.85f else 0f,
                        poutIntensity = if (pose == "POUT") 0.90f else 0f,
                        sleepinessFactor = if (pose == "SLEEPY") 0.90f else 0f,
                        smugTiltDegrees = if (pose == "SMUG") 4.5f else 0f
                    ),
                    personaStatus = current.personaStatus?.copy(
                        affinity = affinity,
                        mood = mood
                    )
                )
            }
            playPersonaVoice(audioBase64, respText, emotion)
            scheduleActionReset(action, actionStartNanos)
            addBondExp(3)
        }
    }

    /** 动作对应的 2.5D 高精微表情差分姿态（3D 表情由 VRM morph 独立驱动） */
    private fun poseForAction(action: ActionType): String = when (action) {
        ActionType.BLUSH -> "SHY"
        ActionType.POUT -> "POUT"
        ActionType.SLEEP, ActionType.YAWN -> "SLEEPY"
        ActionType.SMUG -> "SMUG"
        else -> "DEFAULT"
    }

    /** 动作对应的情绪（让 3D 体态/表情在动作期间同步，而不是只动肢体） */
    private fun emotionForAction(action: ActionType): EmotionType? = when (action) {
        ActionType.WAVE, ActionType.JUMP, ActionType.SMUG -> EmotionType.JOY
        ActionType.BLUSH -> EmotionType.SHY
        ActionType.POUT, ActionType.SHAKE_HEAD -> EmotionType.ANGRY
        ActionType.BOW -> EmotionType.SHY
        ActionType.YAWN, ActionType.SLEEP -> EmotionType.SLEEPY
        ActionType.NOD -> EmotionType.JOY
        else -> null
    }

    /**
     * 动作自然结束后的回落：把 action 复位到 IDLE 并清掉 2.5D 微表情差分。
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
                            highResPose = "DEFAULT",
                            blushIntensity = 0f,
                            poutIntensity = 0f,
                            sleepinessFactor = 0f,
                            smugTiltDegrees = 0f
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
        if (action == ActionType.SIT) {
            setSkeletonStance(SkeletonStance.SITTING)
            return
        }
        if (action == ActionType.IDLE) {
            // 待机复位：清空动作、触摸反应与 2.5D 微表情差分，回到自然呼吸状态
            _uiState.update { current ->
                current.copy(
                    visualState = current.visualState.copy(
                        action = ActionType.IDLE,
                        actionStartedAtNanos = 0L,
                        highResPose = "DEFAULT",
                        blushIntensity = 0f,
                        poutIntensity = 0f,
                        sleepinessFactor = 0f,
                        smugTiltDegrees = 0f,
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
        val pose = poseForAction(action)
        _uiState.update { current ->
            current.copy(
                visualState = current.visualState.copy(
                    action = action,
                    actionStartedAtNanos = startedAt,
                    highResPose = pose,
                    blushIntensity = if (pose == "SHY") 0.85f else 0f,
                    poutIntensity = if (pose == "POUT") 0.9f else 0f,
                    sleepinessFactor = if (pose == "SLEEPY") 0.88f else 0f,
                    smugTiltDegrees = if (pose == "SMUG") 4.5f else 0f,
                    emotion = emotionForAction(action) ?: current.visualState.emotion,
                    expression = when (action) {
                        ActionType.WAVE, ActionType.NOD, ActionType.JUMP, ActionType.BOW -> "SMILE"
                        ActionType.BLUSH, ActionType.SMUG -> "BLUSH_FACE"
                        ActionType.POUT, ActionType.SHAKE_HEAD -> "POUT"
                        ActionType.YAWN, ActionType.SLEEP -> "SLEEPY"
                        else -> current.visualState.expression
                    }
                )
            )
        }
        scheduleActionReset(action, startedAt)
    }

    private fun setupSensors() {
        sensorTiltHelper.onTiltChanged = { pitch, roll ->
            val pitchDeg = Math.toDegrees(pitch.toDouble()).toFloat()
            val rollDeg = Math.toDegrees(roll.toDouble()).toFloat()
            val shiftX = (roll * 25f).coerceIn(-30f, 30f)
            val shiftY = (pitch * 25f).coerceIn(-30f, 30f)
            val gazeX = (roll * 1.2f).coerceIn(-1f, 1f)
            val gazeY = (pitch * 1.2f).coerceIn(-1f, 1f)
            _uiState.update { current ->
                val updatedSpatialAnchor = if (current.spatialAnchor.isAnchored) {
                    current.spatialAnchor.copy(
                        parallaxShiftX = shiftX * 0.75f,
                        parallaxShiftY = shiftY * 0.75f,
                        cameraGazeOffsetX = gazeX,
                        cameraGazeOffsetY = gazeY,
                        cameraPitchDegrees = pitchDeg,
                        cameraRollDegrees = rollDeg
                    )
                } else {
                    current.spatialAnchor.copy(
                        cameraGazeOffsetX = gazeX,
                        cameraGazeOffsetY = gazeY,
                        cameraPitchDegrees = pitchDeg,
                        cameraRollDegrees = rollDeg
                    )
                }
                current.copy(
                    spatialAnchor = updatedSpatialAnchor,
                    visualState = current.visualState.copy(
                        tiltOffsetX = shiftX,
                        tiltOffsetY = shiftY,
                        gazeShiftX = gazeX,
                        gazeShiftY = gazeY,
                        cameraPitchDegrees = pitchDeg,
                        cameraRollDegrees = rollDeg
                    )
                )
            }
        }
        sensorTiltHelper.start()
    }

    private fun loadInitialStatus() {
        viewModelScope.launch {
            val result = repository.getStatus()
            result.onSuccess { status ->
                val currentActive = modelManager.activeModel.value
                val greeting = getCircadianGreeting(currentActive.name, currentActive.greetingText)
                _uiState.update {
                    it.copy(
                        personaStatus = status,
                        messages = listOf(
                            ChatMessage(
                                sender = MessageSender.PERSONA,
                                text = greeting
                            )
                        )
                    )
                }
            }
        }
    }

    private fun getCircadianGreeting(partnerName: String, baseGreeting: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 0..5 -> "这么晚还在现实世界忙碌呀？要注意休息哦，我就在书桌前静静陪伴着你呢。"
            hour in 6..9 -> "早上好！新的一天充满元气，今天的阳光和空气感觉都很棒呢~"
            hour in 11..13 -> "已经到午休时间啦，记得按时吃顿美味的午饭补充体力哦！"
            hour in 17..19 -> "傍晚好呀，今天一整天辛苦了，和我聊聊今天遇到的趣事吧！"
            hour in 22..23 -> "夜深了呢，准备好睡个香甜的好觉了吗？晚安哦~"
            else -> baseGreeting
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = text
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true
            )
        }

        viewModelScope.launch {
            val result = repository.sendChat(text)
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
        if (cameraBitmap == null || cameraBitmap.isRecycled) {
            sendMessage(prompt.ifBlank { "星野，看看我们眼前的现实世界~" })
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val displayPrompt = prompt.ifBlank { "✦ 视野交互：看看眼前 ✦" }
            val userMessage = ChatMessage(
                sender = MessageSender.USER,
                text = displayPrompt,
                timestamp = System.currentTimeMillis()
            )
            _uiState.update { it.copy(messages = it.messages + userMessage) }

            val base64 = withContext(Dispatchers.IO) {
                val maxDim = maxOf(cameraBitmap.width, cameraBitmap.height)
                val scale = if (maxDim > 720) 720f / maxDim else 1.0f
                val targetW = (cameraBitmap.width * scale).toInt()
                val targetH = (cameraBitmap.height * scale).toInt()
                val scaled = if (scale < 1.0f) {
                    Bitmap.createScaledBitmap(cameraBitmap, targetW, targetH, true)
                } else {
                    cameraBitmap
                }
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val bytes = stream.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }

            val result = repository.sendVisionInteract(base64, prompt)
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
                            highResPose = pose,
                            blushIntensity = if (pose == "SHY") 0.85f else 0f,
                            poutIntensity = if (pose == "POUT") 0.90f else 0f,
                            sleepinessFactor = if (pose == "SLEEPY") 0.90f else 0f,
                            smugTiltDegrees = if (pose == "SMUG") 4.5f else 0f
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

        // 1. 端侧即时高精微表情与姿态反馈 (Zero GMS 毫秒级零延迟触发)
        //    3D 手办额外记录触摸反应开始时刻，由 FigureAnimator 播放专属全身反应动作
        val touchReactionStartNanos = System.nanoTime()
        val reaction = when (part) {
            TouchPart.HEAD -> TouchReaction(pose = "SHY", blush = 0.90f, pout = 0f, smug = 0f)
            TouchPart.CHEEK -> TouchReaction(pose = "POUT", blush = 0f, pout = 0.95f, smug = 0f)
            TouchPart.HAND -> TouchReaction(pose = "DEFAULT", blush = 0f, pout = 0f, smug = 0f)
            TouchPart.BODY -> TouchReaction(pose = "SMUG", blush = 0f, pout = 0f, smug = 4.5f)
        }

        _uiState.update { current ->
            current.copy(
                visualState = current.visualState.copy(
                    highResPose = reaction.pose,
                    blushIntensity = reaction.blush,
                    poutIntensity = reaction.pout,
                    smugTiltDegrees = reaction.smug,
                    // 触摸唤醒：从打瞌睡（保持型 SLEEP 动作）中被触碰时立即结束睡眠动作，避免睡眠姿态盖住触摸反应
                    action = if (current.visualState.action == ActionType.SLEEP) ActionType.IDLE
                             else current.visualState.action,
                    actionStartedAtNanos = if (current.visualState.action == ActionType.SLEEP)
                        0L else current.visualState.actionStartedAtNanos,
                    sleepinessFactor = 0f,
                    touchReaction = part,
                    touchReactionStartedAtNanos = touchReactionStartNanos
                )
            )
        }

        // 3D 触摸反应 2.6 秒后自动复位（以开始时间戳为令牌，避免连点误清）
        viewModelScope.launch {
            delay(2700L)
            _uiState.update { current ->
                if (current.visualState.touchReactionStartedAtNanos != touchReactionStartNanos) current
                else current.copy(
                    visualState = current.visualState.copy(
                        touchReaction = null,
                        touchReactionStartedAtNanos = 0L
                    )
                )
            }
        }

        // 3.5 秒后表情平滑自然恢复
        viewModelScope.launch {
            delay(3500L)
            _uiState.update { current ->
                if (current.visualState.highResPose == reaction.pose) {
                    current.copy(
                        visualState = current.visualState.copy(
                            highResPose = "DEFAULT",
                            blushIntensity = 0f,
                            poutIntensity = 0f,
                            smugTiltDegrees = 0f
                        )
                    )
                } else {
                    current
                }
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
            val result = repository.sendTouch(part)
            result.onSuccess { resp ->
                val personaMessage = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = resp.text,
                    emotion = resp.emotion
                )

                _uiState.update { current ->
                    val pose = when (resp.emotion) {
                        EmotionType.SHY -> "SHY"
                        EmotionType.ANGRY -> "POUT"
                        EmotionType.SLEEPY -> "SLEEPY"
                        EmotionType.CONFIDENT -> "SMUG"
                        else -> reaction.pose
                    }
                    current.copy(
                        messages = current.messages + personaMessage,
                        visualState = current.visualState.copy(
                            emotion = resp.emotion,
                            action = resp.action,
                            expression = resp.expression,
                            highResPose = pose
                        ),
                        personaStatus = current.personaStatus?.copy(
                            affinity = resp.currentAffinity
                        )
                    )
                }
                playPersonaVoice(resp.audioBase64, resp.text, resp.emotion)
                addBondExp(expGain)
            }
        }
    }

    /**
     * 手动触发星野慵懒大叔打瞌睡休眠姿态 (Sleepy Nap Mode)
     */
    fun triggerSleepyNap() {
        _uiState.update { current ->
            current.copy(
                visualState = current.visualState.copy(
                    highResPose = "SLEEPY",
                    sleepinessFactor = 0.92f,
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
            val newModel = modelManager.activeModel.value
            val greeting = getCircadianGreeting(newModel.name, newModel.greetingText)
            audioPlayer.interrupt()
            _uiState.update { current ->
                current.copy(
                    activeModel = newModel,
                    visualState = current.visualState.copy(
                        name = newModel.name,
                        model = newModel
                    ),
                    messages = listOf(
                        ChatMessage(
                            sender = MessageSender.PERSONA,
                            text = greeting
                        )
                    )
                )
            }
            viewModelScope.launch {
                repository.switchPersona(modelId)
            }
        }
    }

    fun installModelFromStore(modelId: String) {
        if (modelManager.installModelFromStore(modelId)) {
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

    fun toggleContinuousListening() {
        val nextActive = !_uiState.value.isContinuousActive
        _uiState.update { it.copy(isContinuousActive = nextActive, isRecording = nextActive) }
        if (nextActive) {
            wsClient.connect(_uiState.value.serverBaseUrl)
            voiceRecorder.startListening(isContinuousMode = true)
        } else {
            wsClient.disconnect()
            voiceRecorder.stopListening()
        }
    }

    private fun onContinuousSpeechFinished() {
        val wavBytes = voiceRecorder.getAndResetRecordedWav()
        if (wavBytes != null && wavBytes.isNotEmpty()) {
            val audioBase64 = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
            if (_uiState.value.isContinuousActive) {
                // 通过全双工低延迟 WebSocket 通道发送真实录音进行 ASR 识别
                wsClient.sendAudioMessage(audioBase64, "wav")
                return
            } else {
                viewModelScope.launch {
                    val result = repository.transcribeAudio(wavBytes)
                    val text = result.getOrNull() ?: "看到你站在我面前，感觉好生动呀！"
                    sendMessage(text)
                }
                return
            }
        }

        // 音频数据为空时的自然兜底短语
        val naturalPrompts = listOf(
            "看见你在这里陪着我，心情变得很放松呢。",
            "现实世界今天感觉很有趣，能多跟我说说吗？",
            "和你聊天总觉得时间过得好快呢。",
            "你今天过得怎么样？"
        )
        val selectedPrompt = naturalPrompts.random()
        if (_uiState.value.isContinuousActive) {
            wsClient.sendTextMessage(selectedPrompt)
        } else {
            sendMessage(selectedPrompt)
        }
    }

    fun startHoldRecording() {
        if (_uiState.value.voiceMode != VoiceMode.HOLD_TO_TALK) return
        _uiState.update { it.copy(isRecording = true) }
        voiceRecorder.startListening(isContinuousMode = false)
    }

    fun stopHoldRecording() {
        if (_uiState.value.voiceMode != VoiceMode.HOLD_TO_TALK) return
        voiceRecorder.stopListening()
        val wavBytes = voiceRecorder.getAndResetRecordedWav()
        _uiState.update { it.copy(isRecording = false, micVolume = 0f, isLoading = true) }

        viewModelScope.launch {
            if (wavBytes != null && wavBytes.isNotEmpty()) {
                val result = repository.transcribeAudio(wavBytes)
                val text = result.getOrNull() ?: "看到你站在我面前，感觉好生动呀！"
                sendMessage(text)
            } else {
                sendMessage("看到你站在我面前，感觉好生动呀！")
            }
        }
    }

    fun setVoiceMode(mode: VoiceMode) {
        voiceRecorder.stopListening()
        _uiState.update {
            it.copy(
                voiceMode = mode,
                isContinuousActive = false,
                isRecording = false,
                micVolume = 0f
            )
        }
    }

    fun setBubbleDisplayMode(mode: BubbleDisplayMode) {
        _uiState.update { it.copy(bubbleDisplayMode = mode) }
    }

    fun toggleARMode() {
        _uiState.update {
            val nextMode = if (it.arMode == ARMode.BILLBOARD_2_5D) ARMode.ARCORE_3D else ARMode.BILLBOARD_2_5D
            it.copy(
                arMode = nextMode,
                // 用户显式切换过模式后，切换人格不再自动改写模式
                arModeExplicitlySet = true,
                arTrackingStatus = if (nextMode == ARMode.ARCORE_3D) {
                    if (it.spatialAnchor.isAnchored) ARTrackingStatus.ANCHORED else ARTrackingStatus.PLANE_DETECTED
                } else {
                    ARTrackingStatus.ANCHORED
                }
            )
        }
    }

    private var lastMacroProximityNotifyTimestamp = 0L

    private data class OpticalCalculations(
        val chromaticAberration: Float,
        val chromaticAngle: Float,
        val bloomIntensity: Float,
        val depthOfFieldBlur: Float,
        val isMacroProximity: Boolean,
        val bottomCutoff: Float
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
        val bottomCutoff = if (screenY > 0.80f) ((screenY - 0.80f) / 0.20f).coerceIn(0f, 1f) else 0f

        return OpticalCalculations(
            chromaticAberration = chromaticAberration,
            chromaticAngle = chromaticAngle,
            bloomIntensity = bloomBleedFactor,
            depthOfFieldBlur = dofBlur,
            isMacroProximity = isMacro,
            bottomCutoff = bottomCutoff
        )
    }

    fun onTapPlaceAnchor(screenX: Float, screenY: Float) {
        audioPlayer.updateSpatialAudio(screenX, 1.0f)
        _uiState.update { current ->
            val optical = calculateOpticalParams(
                screenX = screenX,
                screenY = screenY,
                scaleFactor = current.spatialAnchor.scaleFactor,
                bloomBleedFactor = current.spatialAnchor.bloomIntensity
            )
            current.copy(
                arTrackingStatus = ARTrackingStatus.ANCHORED,
                spatialAnchor = current.spatialAnchor.copy(
                    screenX = screenX,
                    screenY = screenY,
                    isAnchored = true,
                    distanceMeters = 0.65f,
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    bloomIntensity = optical.bloomIntensity,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    cameraFocusTimestamp = System.currentTimeMillis(),
                    isCameraFocusing = true,
                    planeTypeDescription = "书桌实景 (零 GMS 通用视差)"
                ),
                visualState = current.visualState.copy(
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    bloomIntensity = optical.bloomIntensity,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    isCameraFocusing = true
                )
            )
        }
    }

    fun onTransformAnchor(scaleMultiplier: Float, rotationYawDelta: Float) {
        var triggeredMacroCare = false
        _uiState.update { current ->
            val anchor = current.spatialAnchor
            val nextScale = (anchor.scaleFactor * scaleMultiplier).coerceIn(0.4f, 2.2f)
            val nextYaw = (anchor.rotationYaw + rotationYawDelta) % 360f
            val optical = calculateOpticalParams(
                screenX = anchor.screenX,
                screenY = anchor.screenY,
                scaleFactor = nextScale,
                bloomBleedFactor = anchor.bloomIntensity
            )
            audioPlayer.updateSpatialAudio(anchor.screenX, nextScale)

            if (!anchor.isMacroProximity && optical.isMacroProximity) {
                val now = System.currentTimeMillis()
                if (now - lastMacroProximityNotifyTimestamp > 20_000L) {
                    lastMacroProximityNotifyTimestamp = now
                    triggeredMacroCare = true
                }
            }

            current.copy(
                spatialAnchor = anchor.copy(
                    scaleFactor = nextScale,
                    rotationYaw = nextYaw,
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff
                ),
                visualState = current.visualState.copy(
                    scale = nextScale,
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    emotion = if (optical.isMacroProximity) EmotionType.SHY else current.visualState.emotion
                )
            )
        }

        if (triggeredMacroCare) {
            val now = System.currentTimeMillis()
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
                        expression = "BLUSH"
                    )
                )
            }
        }
    }

    fun onResetAnchor() {
        audioPlayer.updateSpatialAudio(0.5f, 1.0f)
        _uiState.update { current ->
            current.copy(
                arTrackingStatus = ARTrackingStatus.PLANE_DETECTED,
                spatialAnchor = current.spatialAnchor.copy(
                    isAnchored = false,
                    scaleFactor = 1.0f,
                    rotationYaw = 0f,
                    parallaxShiftX = 0f,
                    parallaxShiftY = 0f,
                    chromaticAberrationIntensity = 0f,
                    bloomIntensity = 0f,
                    depthOfFieldBlur = 0f,
                    isMacroProximity = false,
                    surfaceOcclusionBottomCutoff = 0f,
                    planeTypeDescription = "书桌实景 (零 GMS 通用视差)"
                ),
                visualState = current.visualState.copy(
                    chromaticAberrationIntensity = 0f,
                    bloomIntensity = 0f,
                    depthOfFieldBlur = 0f,
                    isMacroProximity = false,
                    surfaceOcclusionBottomCutoff = 0f
                )
            )
        }
    }

    /** 直接设置 3D 骨骼体态（设置页「站立姿态 / 桌沿垂腿坐」按钮与动作预览共用） */
    fun setSkeletonStance(stance: SkeletonStance) {
        _uiState.update { current ->
            current.copy(
                spatialAnchor = current.spatialAnchor.copy(skeletonStance = stance),
                visualState = current.visualState.copy(skeletonStance = stance)
            )
        }
    }

    /**
     * 切换 3D 骨骼姿态 (站立 STANDING / 桌沿垂腿坐姿 SITTING)
     */
    fun onToggleSkeletonStance() {
        _uiState.update { current ->
            val nextStance = if (current.spatialAnchor.skeletonStance == SkeletonStance.STANDING) {
                SkeletonStance.SITTING
            } else {
                SkeletonStance.STANDING
            }
            current.copy(
                spatialAnchor = current.spatialAnchor.copy(skeletonStance = nextStance),
                visualState = current.visualState.copy(skeletonStance = nextStance)
            )
        }
    }

    // 真实书桌实景物理边界拓扑定义 (基于实景几何标定，零 GMS 依赖)
    companion object {
        private const val DESK_CLIFF_BOTTOM_Y = 0.80f      // 桌面前沿实木悬崖分界线
        private const val DESK_CLIFF_WARNING_Y = 0.74f     // 接近桌沿的预警阈值
        private const val DESK_EDGE_LEFT_X = 0.12f         // 左侧桌面边缘
        private const val DESK_EDGE_RIGHT_X = 0.88f        // 右侧桌面边缘
        private const val KEYBOARD_MIN_X = 0.18f           // 机械键盘凸起刚体 X 范围
        private const val KEYBOARD_MAX_X = 0.90f
        private const val KEYBOARD_MIN_Y = 0.52f           // 机械键盘凸起刚体 Y 范围
        private const val KEYBOARD_MAX_Y = 0.72f
    }

    /**
     * 在现实画面中自由拖拽滑动伴侣模型 (Surface Drag Repositioning & Cliff Physics)
     */
    fun onDragAnchor(deltaNormX: Float, deltaNormY: Float) {
        _uiState.update { current ->
            val anchor = current.spatialAnchor
            val nextX = (anchor.screenX + deltaNormX).coerceIn(0.06f, 0.94f)
            val nextY = (anchor.screenY + deltaNormY).coerceIn(0.20f, 0.92f)

            // 透视几何：Y 坐标越小 (靠近画面上方远端)，距离越远 (0.8m ~ 1.5m)
            val depthFactor = (1.0f + (0.65f - nextY) * 0.5f).coerceIn(0.55f, 1.4f)
            val currentScale = anchor.scaleFactor

            val optical = calculateOpticalParams(
                screenX = nextX,
                screenY = nextY,
                scaleFactor = currentScale,
                bloomBleedFactor = anchor.bloomIntensity
            )
            audioPlayer.updateSpatialAudio(nextX, currentScale)

            // 1. 机械键盘物理碰撞刚体与台阶高度抬升 (Keyboard Step Collider)
            val isOverKeyboard = nextX in KEYBOARD_MIN_X..KEYBOARD_MAX_X && nextY in KEYBOARD_MIN_Y..KEYBOARD_MAX_Y
            val stepHeightOffset = if (isOverKeyboard) 14f * currentScale else 0f

            // 2. 真实桌面悬崖边缘感知与下垂物理解算 (Desk Cliff Edge Solver)
            val isBeyondCliffBottom = nextY >= DESK_CLIFF_BOTTOM_Y
            val isNearCliffBottom = nextY > DESK_CLIFF_WARNING_Y && !isBeyondCliffBottom
            val isNearCliffSide = nextX < DESK_EDGE_LEFT_X || nextX > DESK_EDGE_RIGHT_X

            val cliffIntensity = when {
                isBeyondCliffBottom -> 1.0f
                isNearCliffBottom -> ((nextY - DESK_CLIFF_WARNING_Y) / (DESK_CLIFF_BOTTOM_Y - DESK_CLIFF_WARNING_Y)).coerceIn(0f, 1f)
                isNearCliffSide -> {
                    val distLeft = (DESK_EDGE_LEFT_X - nextX) / 0.06f
                    val distRight = (nextX - DESK_EDGE_RIGHT_X) / 0.06f
                    maxOf(distLeft, distRight).coerceIn(0f, 1f)
                }
                else -> 0f
            }

            val isDangling = isBeyondCliffBottom
            val airDist = if (isDangling) ((nextY - DESK_CLIFF_BOTTOM_Y) / 0.12f).coerceIn(0f, 1f) else 0f
            val supportConfidence = if (isDangling) (1.0f - airDist * 0.92f).coerceIn(0.06f, 1.0f) else 1.0f

            // 3. 物理质量-弹簧惯性摆动与悬崖平衡反冲 (Mass-Spring-Damper Sway & Cliff Balance)
            val baseSwayAngle = (-deltaNormX * 220f).coerceIn(-16f, 16f)
            // 靠近悬崖时，伴侣身体重心微仰自平衡，防止前倾摔倒
            val balancePitchBack = if (nextY > DESK_CLIFF_WARNING_Y) -cliffIntensity * 7.5f else 0f
            val totalSwayAngle = (baseSwayAngle + balancePitchBack).coerceIn(-18f, 18f)

            // 纵向弹性形变：地面拖拽弹性挤压 vs 悬空时的重力下垂拉伸 (Leg Droop Stretch)
            val baseSquishY = (1.0f - deltaNormY * 0.9f).coerceIn(0.92f, 1.08f)
            val totalSquishY = if (isDangling) (1.0f + airDist * 0.07f).coerceIn(1.0f, 1.10f) else baseSquishY

            // 4. 动态视线追踪 (Eye Gaze Tracking) + 悬崖探查微视线
            val baseGazeX = ((0.5f - nextX) * 14f).coerceIn(-8f, 8f)
            val baseGazeY = ((0.42f - nextY) * 10f).coerceIn(-6f, 6f)
            // 逼近悬崖时，眼睛产生探头下瞥动作
            val cliffPeekingGazeY = (baseGazeY + cliffIntensity * 4.0f).coerceIn(-6f, 8f)

            // 5. 前景键盘遮挡切片比例 (当伴侣接近或穿过键盘后沿 Y > 0.60f 时产生物理遮挡)
            val fgOcclusion = if (nextY > 0.60f) ((nextY - 0.60f) / 0.22f).coerceIn(0f, 0.45f) else 0f

            current.copy(
                spatialAnchor = anchor.copy(
                    screenX = nextX,
                    screenY = nextY,
                    isDragging = true,
                    distanceMeters = ((0.65f * depthFactor) * 10f).toInt() / 10f,
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    inertialSwayAngle = totalSwayAngle,
                    inertialSquishY = totalSquishY,
                    gazeTrackingEyeX = baseGazeX,
                    gazeTrackingEyeY = cliffPeekingGazeY,
                    foregroundOcclusionRatio = fgOcclusion,
                    isNearCliffEdge = cliffIntensity > 0.05f,
                    cliffEdgeIntensity = cliffIntensity,
                    isDanglingOverCliff = isDangling,
                    dangleAirDistance = airDist,
                    surfaceSupportConfidence = supportConfidence,
                    surfaceStepHeightOffset = stepHeightOffset
                ),
                visualState = current.visualState.copy(
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    inertialSwayAngle = totalSwayAngle,
                    inertialSquishY = totalSquishY,
                    gazeTrackingEyeX = baseGazeX,
                    gazeTrackingEyeY = cliffPeekingGazeY,
                    foregroundOcclusionRatio = fgOcclusion,
                    isNearCliffEdge = cliffIntensity > 0.05f,
                    cliffEdgeIntensity = cliffIntensity,
                    isDanglingOverCliff = isDangling,
                    dangleAirDistance = airDist,
                    surfaceSupportConfidence = supportConfidence,
                    surfaceStepHeightOffset = stepHeightOffset
                )
            )
        }
    }

    fun onDragStateChanged(isDragging: Boolean) {
        val currentAnchor = _uiState.value.spatialAnchor
        val wasDangling = currentAnchor.isDanglingOverCliff

        _uiState.update { current ->
            current.copy(
                spatialAnchor = current.spatialAnchor.copy(
                    isDragging = isDragging,
                    isCameraFocusing = !isDragging,
                    cameraFocusTimestamp = if (!isDragging) System.currentTimeMillis() else current.spatialAnchor.cameraFocusTimestamp,
                    inertialSwayAngle = if (!isDragging) 0f else current.spatialAnchor.inertialSwayAngle,
                    inertialSquishY = if (!isDragging && !wasDangling) 1.0f else current.spatialAnchor.inertialSquishY
                ),
                visualState = current.visualState.copy(
                    isCameraFocusing = !isDragging,
                    inertialSwayAngle = if (!isDragging) 0f else current.visualState.inertialSwayAngle,
                    inertialSquishY = if (!isDragging && !wasDangling) 1.0f else current.visualState.inertialSquishY
                )
            )
        }

        // 6. 悬空脱离自救与安全着陆回弹机制 (Auto-Catch & Safe Landing Recovery)
        if (!isDragging && wasDangling) {
            viewModelScope.launch {
                // 平滑回弹吸附至安全桌垫前沿
                val startY = currentAnchor.screenY
                val targetY = DESK_CLIFF_WARNING_Y
                val steps = 8
                for (step in 1..steps) {
                    kotlinx.coroutines.delay(20L)
                    val progress = step.toFloat() / steps
                    val easedProgress = (1.0f - kotlin.math.cos(progress * kotlin.math.PI.toFloat())) / 2.0f
                    val currentY = startY + (targetY - startY) * easedProgress
                    _uiState.update { state ->
                        state.copy(
                            spatialAnchor = state.spatialAnchor.copy(
                                screenY = currentY,
                                isDanglingOverCliff = false,
                                dangleAirDistance = 0f,
                                surfaceSupportConfidence = 1.0f,
                                cliffEdgeIntensity = (1.0f - progress) * 0.4f,
                                isNearCliffEdge = progress < 0.8f,
                                inertialSquishY = 0.90f + progress * 0.10f // 触地弹性缓冲
                            ),
                            visualState = state.visualState.copy(
                                isDanglingOverCliff = false,
                                dangleAirDistance = 0f,
                                surfaceSupportConfidence = 1.0f,
                                cliffEdgeIntensity = (1.0f - progress) * 0.4f,
                                isNearCliffEdge = progress < 0.8f,
                                inertialSquishY = 0.90f + progress * 0.10f
                            )
                        )
                    }
                }
                // 最终稳固站立在桌面上
                _uiState.update { state ->
                    state.copy(
                        spatialAnchor = state.spatialAnchor.copy(
                            screenY = targetY,
                            isDanglingOverCliff = false,
                            isNearCliffEdge = false,
                            cliffEdgeIntensity = 0f,
                            surfaceSupportConfidence = 1.0f,
                            inertialSquishY = 1.0f
                        ),
                        visualState = state.visualState.copy(
                            isDanglingOverCliff = false,
                            isNearCliffEdge = false,
                            cliffEdgeIntensity = 0f,
                            surfaceSupportConfidence = 1.0f,
                            inertialSquishY = 1.0f
                        )
                    )
                }
            }
        }
    }

    fun onCameraFocusStateChanged(isFocusing: Boolean) {
        _uiState.update { current ->
            current.copy(
                spatialAnchor = current.spatialAnchor.copy(isCameraFocusing = isFocusing),
                visualState = current.visualState.copy(isCameraFocusing = isFocusing)
            )
        }
    }

    private var lastLightNotifyTimestamp = 0L

    /**
     * 现实环境全光谱光学特征实时注入 (照度、色温、三原色增益、强光光晕、相机高 ISO 噪点与落脚点局部材质底色)
     */
    fun onOpticalProfileChanged(profile: com.persona.ar.ar.EnvironmentalOpticalProfile) {
        val prevLowLight = _uiState.value.spatialAnchor.isLowLight
        _uiState.update { current ->
            val anchor = current.spatialAnchor
            val optical = calculateOpticalParams(
                screenX = anchor.screenX,
                screenY = anchor.screenY,
                scaleFactor = anchor.scaleFactor,
                bloomBleedFactor = profile.bloomBleedFactor
            )

            // 综合全局环境照度与伴侣落脚点局部桌面照度 (40% 全局 + 60% 局部)
            val effectiveLighting = (profile.luminance * 0.4f + profile.localSurfaceLuminance * 0.6f).coerceIn(0.2f, 1.35f)

            current.copy(
                spatialAnchor = anchor.copy(
                    lightIntensity = effectiveLighting,
                    isLowLight = profile.isLowLight,
                    ambientColorTemp = profile.colorTemp,
                    ambientTintRed = profile.tintR,
                    ambientTintGreen = profile.tintG,
                    ambientTintBlue = profile.tintB,
                    sensorGrainIntensity = profile.sensorGrainIntensity,
                    bloomIntensity = optical.bloomIntensity,
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    localSurfaceTintRed = profile.localSurfaceTintR,
                    localSurfaceTintGreen = profile.localSurfaceTintG,
                    localSurfaceTintBlue = profile.localSurfaceTintB,
                    localSurfaceLuminance = profile.localSurfaceLuminance,
                    lightDirectionX = profile.lightDirectionX,
                    lightDirectionY = profile.lightDirectionY,
                    lightDirectionalStrength = profile.lightDirectionalStrength,
                    deskTextureConfidence = profile.deskTextureConfidence,
                    visualFlowOffsetX = profile.visualFlowOffsetX,
                    visualFlowOffsetY = profile.visualFlowOffsetY,
                    lensVignetteFactor = profile.lensVignetteFactor
                ),
                visualState = current.visualState.copy(
                    environmentalLighting = (effectiveLighting * profile.lensVignetteFactor).coerceIn(0.18f, 1.35f),
                    isLowLight = profile.isLowLight,
                    ambientColorTemp = profile.colorTemp,
                    ambientTintRed = (profile.tintR * 0.7f + profile.localSurfaceTintR * 0.3f).coerceIn(0.8f, 1.25f),
                    ambientTintGreen = (profile.tintG * 0.7f + profile.localSurfaceTintG * 0.3f).coerceIn(0.8f, 1.25f),
                    ambientTintBlue = (profile.tintB * 0.7f + profile.localSurfaceTintB * 0.3f).coerceIn(0.8f, 1.25f),
                    sensorGrainIntensity = profile.sensorGrainIntensity,
                    bloomIntensity = optical.bloomIntensity,
                    chromaticAberrationIntensity = optical.chromaticAberration,
                    chromaticAngleRad = optical.chromaticAngle,
                    depthOfFieldBlur = optical.depthOfFieldBlur,
                    isMacroProximity = optical.isMacroProximity,
                    surfaceOcclusionBottomCutoff = optical.bottomCutoff,
                    lightDirectionX = profile.lightDirectionX,
                    lightDirectionY = profile.lightDirectionY,
                    lightDirectionalStrength = profile.lightDirectionalStrength,
                    cameraPitchDegrees = anchor.cameraPitchDegrees,
                    cameraRollDegrees = anchor.cameraRollDegrees,
                    visualFlowOffsetX = profile.visualFlowOffsetX,
                    visualFlowOffsetY = profile.visualFlowOffsetY,
                    lensVignetteFactor = profile.lensVignetteFactor
                )
            )
        }

        // 现实光照突变时触发伴侣拟人感知关怀 (防抖 30 秒)
        val now = System.currentTimeMillis()
        if (!prevLowLight && profile.isLowLight && now - lastLightNotifyTimestamp > 30_000L) {
            lastLightNotifyTimestamp = now
            val careMessage = ChatMessage(
                sender = MessageSender.PERSONA,
                text = "唔... 周围光线变暗了呢，指挥官在暗处看屏幕要当心眼睛哦，星野会一直陪着你的~ ✨",
                emotion = EmotionType.SURPRISED,
                timestamp = now
            )
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + careMessage,
                    visualState = state.visualState.copy(
                        emotion = EmotionType.SURPRISED,
                        expression = "SURPRISED"
                    )
                )
            }
        }
    }

    /**
     * 现实环境光照与色度动态反馈
     * 将摄像头采集到的环境明暗与暗光状态实时注入虚拟伴侣与空间锚点，
     * 并在现实光照突变时触发二次元伴侣的主动拟人关怀。
     */
    fun onEnvironmentalLightChanged(luminance: Float, isLowLight: Boolean) {
        val prevLowLight = _uiState.value.spatialAnchor.isLowLight
        _uiState.update { current ->
            current.copy(
                spatialAnchor = current.spatialAnchor.copy(
                    lightIntensity = luminance,
                    isLowLight = isLowLight
                ),
                visualState = current.visualState.copy(
                    environmentalLighting = luminance,
                    isLowLight = isLowLight
                )
            )
        }

        // 现实光照突变时触发伴侣拟人感知关怀 (防抖 30 秒)
        val now = System.currentTimeMillis()
        if (!prevLowLight && isLowLight && now - lastLightNotifyTimestamp > 30_000L) {
            lastLightNotifyTimestamp = now
            val careMessage = ChatMessage(
                sender = MessageSender.PERSONA,
                text = "唔... 周围光线变暗了呢，指挥官在暗处看屏幕要当心眼睛哦，星野会一直陪着你的~ ✨",
                emotion = EmotionType.SURPRISED,
                timestamp = now
            )
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + careMessage,
                    visualState = state.visualState.copy(
                        emotion = EmotionType.SURPRISED,
                        expression = "SURPRISED"
                    )
                )
            }
        }
    }

    fun setSettingsOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = isOpen) }
    }

    fun updateServerUrl(newUrl: String) {
        ApiClient.baseUrl = newUrl
        _uiState.update { it.copy(serverBaseUrl = newUrl) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(isMemoryLoading = true) }
            val result = repository.getMemories()
            result.onSuccess { memoryList ->
                _uiState.update { it.copy(memories = memoryList, isMemoryLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(isMemoryLoading = false) }
            }
        }
    }

    fun createMemory(content: String, category: String = "general", importance: Int = 3) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val result = repository.createMemory(content, category, importance)
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
        sensorTiltHelper.stop()
    }
}

private data class TouchReaction(
    val pose: String,
    val blush: Float,
    val pout: Float,
    val smug: Float
)
