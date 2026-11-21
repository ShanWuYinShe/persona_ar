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

        // 2. 纸片人现实空间投射层 (2.5D 与 3D 模式)
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

        // 3. 顶部好感度与操作状态栏
        StatusBadge(
            status = uiState.personaStatus,
            arMode = uiState.arMode,
            onToggleARMode = { viewModel.toggleARMode() },
            onOpenSettings = { viewModel.setSettingsOpen(true) },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 4. 底部聊天与输入交互区域
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // 聊天气泡层（受三种气泡显示策略控制：停留常驻 / 5秒淡出 / 不显示）
            ChatOverlay(
                messages = uiState.messages,
                displayMode = uiState.bubbleDisplayMode,
                isSpeaking = uiState.isSpeaking,
                modifier = Modifier.fillMaxWidth()
            )

            // 底部输入与操作栏（支持自然随时打断模式与按住说话模式）
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

        // 5. 设置弹窗（含对讲模式和气泡策略设置）
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
