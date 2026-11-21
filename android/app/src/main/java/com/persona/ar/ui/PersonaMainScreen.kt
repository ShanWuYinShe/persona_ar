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
            // 权限未获得时的暗色现实空间备用背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14121A))
            )
        }

        // 2. 纸片人现实空间投射层 (支持 2.5D 精致立绘与 3D 空间模式切换)
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
            // 聊天气泡消息覆盖层
            ChatOverlay(
                messages = uiState.messages,
                isSpeaking = uiState.isSpeaking,
                modifier = Modifier.fillMaxWidth()
            )

            // 底部输入与操作栏
            VoiceInputBar(
                isRecording = uiState.isRecording,
                micVolume = uiState.micVolume,
                isLoading = uiState.isLoading,
                onSendMessage = { text -> viewModel.sendMessage(text) },
                onStartVoice = { viewModel.startVoiceRecording() },
                onStopVoice = { viewModel.stopVoiceRecording() },
                onTouchPart = { part -> viewModel.onTouchPart(part) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 5. 设置弹窗
        if (uiState.isSettingsOpen) {
            SettingsDialog(
                currentMode = uiState.arMode,
                currentServerUrl = uiState.serverBaseUrl,
                onDismiss = { viewModel.setSettingsOpen(false) },
                onToggleMode = { viewModel.toggleARMode() },
                onUpdateServerUrl = { url -> viewModel.updateServerUrl(url) }
            )
        }
    }
}
