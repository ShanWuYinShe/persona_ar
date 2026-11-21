package com.persona.ar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.persona.ar.ar.ARMode
import com.persona.ar.ar.SensorTiltHelper
import com.persona.ar.audio.AudioPlayer
import com.persona.ar.audio.VoiceRecorder
import com.persona.ar.data.api.ApiClient
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.MessageSender
import com.persona.ar.data.model.TouchPart
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
    }

    private fun setupSensors() {
        sensorTiltHelper.onTiltChanged = { pitch, roll ->
            // 将重力倾斜映射为轻微视差偏移（最大 30 像素）
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

                // 播放语音
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

    fun startVoiceRecording() {
        _uiState.update { it.copy(isRecording = true) }
        voiceRecorder.startListening()
    }

    fun stopVoiceRecording() {
        voiceRecorder.stopListening()
        _uiState.update { it.copy(isRecording = false, micVolume = 0f) }
        // 模拟语音转文字或快捷问候触发
        sendMessage("看到你站在我面前，感觉好生动呀！")
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
