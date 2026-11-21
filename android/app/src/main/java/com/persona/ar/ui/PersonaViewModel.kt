package com.persona.ar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.persona.ar.ar.ARMode
import com.persona.ar.ar.SensorTiltHelper
import com.persona.ar.audio.AudioPlayer
import com.persona.ar.audio.VoiceRecorder
import com.persona.ar.data.api.ApiClient
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.MessageSender
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

    private val _uiState = MutableStateFlow(PersonaUiState())
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    init {
        setupAudioPlayer()
        setupVoiceRecorder()
        setupSensors()
        loadInitialStatus()
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

        // 自然对话模式下的打断机制 (Barge-in)
        voiceRecorder.onSpeechDetected = {
            if (_uiState.value.isSpeaking) {
                // 用户开始发话，立即打断角色当前的播音
                audioPlayer.interrupt()
            }
            _uiState.update { it.copy(isUserSpeaking = true) }
        }

        // 自然对话模式下，静音 1.2 秒后断句完成，自动提交
        voiceRecorder.onSilenceCompleted = {
            _uiState.update { it.copy(isUserSpeaking = false) }
            onContinuousSpeechFinished()
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
                _uiState.update {
                    it.copy(
                        personaStatus = status,
                        messages = listOf(
                            ChatMessage(
                                sender = MessageSender.PERSONA,
                                text = "哼哼，现实世界的信号终于连上了！我现在就站在你的桌子前哦，快跟我打个招呼吧~"
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
            }
        }
    }

    // --- 对讲模式控制 ---

    /** 模式1：自然对话模式下的开关切换（点击开启常态监听，随时说话随时打断） */
    fun toggleContinuousListening() {
        val nextActive = !_uiState.value.isContinuousActive
        _uiState.update { it.copy(isContinuousActive = nextActive, isRecording = nextActive) }
        if (nextActive) {
            voiceRecorder.startListening(isContinuousMode = true)
        } else {
            voiceRecorder.stopListening()
        }
    }

    private fun onContinuousSpeechFinished() {
        val naturalPrompts = listOf(
            "看见你在这里陪着我，心情变得很好呢。",
            "你刚刚说的我都听到了，现实世界今天天气怎么样？",
            "星野，能给我讲讲你的世界是什么样子的吗？",
            "你真可爱，我很喜欢听你说话。"
        )
        sendMessage(naturalPrompts.random())
    }

    /** 模式2：按住说话，松开发送 */
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

    // --- 设置项更新 ---

    fun setVoiceMode(mode: VoiceMode) {
        // 切换时若在录音中，先平稳复位
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

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        voiceRecorder.release()
        sensorTiltHelper.stop()
    }
}
