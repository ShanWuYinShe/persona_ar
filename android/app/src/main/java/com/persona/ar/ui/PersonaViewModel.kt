package com.persona.ar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.persona.ar.ar.ARMode
import com.persona.ar.ar.SensorTiltHelper
import com.persona.ar.audio.AudioPlayer
import com.persona.ar.audio.VoiceRecorder
import com.persona.ar.character.loader.PersonaModelManager
import com.persona.ar.data.api.ApiClient
import com.persona.ar.data.api.PersonaWebSocketClient
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.MessageSender
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.VoiceMode
import com.persona.ar.data.repository.PersonaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
                    current.copy(
                        activeModel = active,
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

    private fun setupAudioPlayer() {
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
        wsClient.onThinkingStart = {
            _uiState.update { it.copy(isLoading = true) }
        }
        wsClient.onResponseComplete = { respText, emotion, action, expr, audioBase64, affinity, mood ->
            _uiState.update { current ->
                val personaMsg = ChatMessage(
                    sender = MessageSender.PERSONA,
                    text = respText,
                    emotion = emotion
                )
                current.copy(
                    isLoading = false,
                    messages = current.messages + personaMsg,
                    visualState = current.visualState.copy(
                        emotion = emotion,
                        action = action,
                        expression = expr
                    ),
                    personaStatus = current.personaStatus?.copy(
                        affinity = affinity,
                        mood = mood
                    )
                )
            }
            if (!audioBase64.isNullOrEmpty()) {
                audioPlayer.playAudioBase64(audioBase64)
            } else {
                audioPlayer.speakTextFallback(respText)
            }
            addBondExp(3)
        }
    }

    private fun setupSensors() {
        sensorTiltHelper.onTiltChanged = { pitch, roll ->
            val shiftX = (roll * 25f).coerceIn(-30f, 30f)
            val shiftY = (pitch * 25f).coerceIn(-30f, 30f)
            _uiState.update { current ->
                current.copy(
                    visualState = current.visualState.copy(
                        tiltOffsetX = shiftX,
                        tiltOffsetY = shiftY
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
                _uiState.update {
                    it.copy(
                        personaStatus = status,
                        messages = listOf(
                            ChatMessage(
                                sender = MessageSender.PERSONA,
                                text = currentActive.greetingText
                            )
                        )
                    )
                }
            }
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
                            expression = resp.expression
                        ),
                        personaStatus = current.personaStatus?.copy(
                            affinity = resp.currentAffinity,
                            mood = resp.mood
                        )
                    )
                }

                if (!resp.audioBase64.isNullOrEmpty()) {
                    audioPlayer.playAudioBase64(resp.audioBase64)
                } else {
                    audioPlayer.speakTextFallback(resp.text)
                }
                addBondExp(3)
            }
        }
    }

    fun onTouchPart(part: TouchPart) {
        viewModelScope.launch {
            val result = repository.sendTouch(part)
            result.onSuccess { resp ->
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
                            expression = resp.expression
                        ),
                        personaStatus = current.personaStatus?.copy(
                            affinity = resp.currentAffinity
                        )
                    )
                }

                if (!resp.audioBase64.isNullOrEmpty()) {
                    audioPlayer.playAudioBase64(resp.audioBase64)
                } else {
                    audioPlayer.speakTextFallback(resp.text)
                }
                addBondExp(3)
            }
        }
    }

    // --- 动态模型与商城系统 ---

    fun switchPersonaModel(modelId: String) {
        if (modelManager.switchModel(modelId)) {
            val newModel = modelManager.activeModel.value
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
                            text = newModel.greetingText
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
        val activeName = _uiState.value.activeModel?.name ?: "你"
        val naturalPrompts = listOf(
            "看见你在这里陪着我，心情变得很放松呢。",
            "现实世界今天感觉很有趣，能多跟我说说吗？",
            "和你聊天总觉得时间过得好快呢。",
            "你今天过得怎么样？"
        )
        val selectedPrompt = naturalPrompts.random()
        if (_uiState.value.isContinuousActive) {
            // 通过全双工低延迟 WebSocket 通道发送
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
        _uiState.update { it.copy(isRecording = false, micVolume = 0f) }
        sendMessage("看到你站在我面前，感觉好生动呀！")
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
            it.copy(arMode = nextMode)
        }
    }

    fun setSettingsOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = isOpen) }
    }

    fun updateServerUrl(newUrl: String) {
        ApiClient.baseUrl = newUrl
        _uiState.update { it.copy(serverBaseUrl = newUrl) }
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
