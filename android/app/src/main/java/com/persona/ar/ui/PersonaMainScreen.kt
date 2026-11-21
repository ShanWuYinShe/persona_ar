package com.persona.ar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.persona.ar.ar.ARMode
import com.persona.ar.ar.CameraXView
import com.persona.ar.character.Character3DRenderer
import com.persona.ar.character.CharacterCanvasRenderer
import com.persona.ar.ui.components.ChatOverlay
import com.persona.ar.ui.components.PersonaStoreDialog
import com.persona.ar.ui.components.SettingsDialog
import com.persona.ar.ui.components.StatusBadge
import com.persona.ar.ui.components.VoiceInputBar

@Composable
fun PersonaMainScreen(
    viewModel: PersonaViewModel,
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. 现实摄像头画面透传层
        if (hasCameraPermission) {
            CameraXView(modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14121A))
            )
        }

        // 2. 纸片人现实空间投射层 (支持动态模型资产包)
        when (uiState.arMode) {
            ARMode.BILLBOARD_2_5D -> {
                CharacterCanvasRenderer(
                    visualState = uiState.visualState,
                    onTouchPart = { part -> viewModel.onTouchPart(part) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            ARMode.ARCORE_3D -> {
                Character3DRenderer(
                    visualState = uiState.visualState,
                    onTouchPart = { part -> viewModel.onTouchPart(part) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. 顶部好感度与操作状态栏 (支持点击换角色与打开商城)
        StatusBadge(
            status = uiState.personaStatus,
            activeModel = uiState.activeModel,
            arMode = uiState.arMode,
            onToggleARMode = { viewModel.toggleARMode() },
            onOpenStore = { viewModel.setStoreOpen(true) },
            onOpenSettings = { viewModel.setSettingsOpen(true) },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 4. 底部聊天与输入交互区域
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ChatOverlay(
                messages = uiState.messages,
                displayMode = uiState.bubbleDisplayMode,
                isSpeaking = uiState.isSpeaking,
                modifier = Modifier.fillMaxWidth()
            )

            VoiceInputBar(
                voiceMode = uiState.voiceMode,
                isRecording = uiState.isRecording,
                isContinuousActive = uiState.isContinuousActive,
                isUserSpeaking = uiState.isUserSpeaking,
                micVolume = uiState.micVolume,
                isLoading = uiState.isLoading,
                onSendMessage = { text -> viewModel.sendMessage(text) },
                onToggleContinuous = { viewModel.toggleContinuousListening() },
                onStartHoldVoice = { viewModel.startHoldRecording() },
                onStopHoldVoice = { viewModel.stopHoldRecording() },
                onTouchPart = { part -> viewModel.onTouchPart(part) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 5. 纸片人模型商城与工坊弹窗
        if (uiState.isStoreOpen) {
            PersonaStoreDialog(
                activeModel = uiState.activeModel,
                installedModels = uiState.installedModels,
                storeModels = uiState.storeModels,
                onDismiss = { viewModel.setStoreOpen(false) },
                onSwitchModel = { id ->
                    viewModel.switchPersonaModel(id)
                    viewModel.setStoreOpen(false)
                },
                onInstallModel = { id ->
                    viewModel.installModelFromStore(id)
                }
            )
        }

        // 6. 系统与交互设置弹窗
        if (uiState.isSettingsOpen) {
            SettingsDialog(
                currentMode = uiState.arMode,
                currentVoiceMode = uiState.voiceMode,
                currentBubbleMode = uiState.bubbleDisplayMode,
                currentServerUrl = uiState.serverBaseUrl,
                onDismiss = { viewModel.setSettingsOpen(false) },
                onToggleMode = { viewModel.toggleARMode() },
                onSelectVoiceMode = { mode -> viewModel.setVoiceMode(mode) },
                onSelectBubbleMode = { mode -> viewModel.setBubbleDisplayMode(mode) },
                onUpdateServerUrl = { url -> viewModel.updateServerUrl(url) }
            )
        }
    }
}
