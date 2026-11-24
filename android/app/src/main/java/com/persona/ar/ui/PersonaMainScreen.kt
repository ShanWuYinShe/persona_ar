package com.persona.ar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.ar.ARMode
import com.persona.ar.ar.CameraXView
import com.persona.ar.character.Character3DRenderer
import com.persona.ar.character.CharacterCanvasRenderer
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.ui.components.ChatOverlay
import com.persona.ar.ui.components.MemoryNotebookDialog
import com.persona.ar.ui.components.PersonaStoreDialog
import com.persona.ar.ui.components.SettingsDialog
import com.persona.ar.ui.components.StatusBadge
import com.persona.ar.ui.components.VoiceInputBar
import com.persona.ar.util.SnapshotHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persona AR 核心主界面（深度融入用户日常使用场景）
 * 核心升级：
 * 1. 沉浸陪伴模式：单击空白现实背景一键隐藏所有 UI 控件，专享静默桌宠陪伴
 * 2. 纯净 AR 快门合影：一键截取现实无 UI 纯净合影并带有快门闪光反馈
 * 3. 拟人思考态反馈：AI 推理延迟时浮现萌系思考泡泡，杜绝“以为卡死”的焦虑
 * 4. 空间摆放引导：角色脚底带有轻量落桌与缩放手势指引
 */
@Composable
fun PersonaMainScreen(
    viewModel: PersonaViewModel,
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isUiVisible by remember { mutableStateOf(true) } // 沉浸陪伴模式开关
    var isShutterFlashing by remember { mutableStateOf(false) } // 快门白闪效果
    var showSnapshotToast by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // 双击现实背景空白处：切换“沉浸陪伴模式 / 交互模式” (避免与单点桌面重定位冲突)
                        isUiVisible = !isUiVisible
                    }
                )
            }
    ) {
        // 1. 现实摄像头画面透传层 (集成环境光照感知、局部材质探针与真实相机帧抓取)
        var cameraFrameProvider by remember { mutableStateOf<(() -> android.graphics.Bitmap?)?>(null) }

        if (hasCameraPermission) {
            CameraXView(
                modifier = Modifier.fillMaxSize(),
                focusAnchorNormX = uiState.spatialAnchor.screenX,
                focusAnchorNormY = uiState.spatialAnchor.screenY,
                isFocusTriggered = uiState.spatialAnchor.isCameraFocusing,
                onFocusStateChanged = { isFocusing ->
                    viewModel.onCameraFocusStateChanged(isFocusing)
                },
                onEnvironmentLightChanged = { lum, isLow ->
                    viewModel.onEnvironmentalLightChanged(lum, isLow)
                },
                onOpticalProfileChanged = { profile ->
                    viewModel.onOpticalProfileChanged(profile)
                },
                onFrameCaptureReady = { provider ->
                    cameraFrameProvider = provider
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14121A))
            )
        }

        // 2. 纸片人现实空间投射层
        when (uiState.arMode) {
            ARMode.BILLBOARD_2_5D -> {
                CharacterCanvasRenderer(
                    visualState = uiState.visualState,
                    onTouchPart = { part, x, y -> viewModel.onTouchPart(part, x, y) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            ARMode.ARCORE_3D -> {
                Character3DRenderer(
                    visualState = uiState.visualState,
                    trackingStatus = uiState.arTrackingStatus,
                    spatialAnchor = uiState.spatialAnchor,
                    partnerName = uiState.activeModel?.name ?: "星野",
                    onTapPlace = { x, y -> viewModel.onTapPlaceAnchor(x, y) },
                    onTransformGesture = { scale, yaw -> viewModel.onTransformAnchor(scale, yaw) },
                    onDragAnchor = { dx, dy -> viewModel.onDragAnchor(dx, dy) },
                    onDragStateChanged = { isDragging -> viewModel.onDragStateChanged(isDragging) },
                    onResetAnchor = { viewModel.onResetAnchor() },
                    onTouchPart = { part, x, y -> viewModel.onTouchPart(part, x, y) },
                    onToggleStance = { viewModel.onToggleSkeletonStance() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. 拟人思考态反馈泡泡 (当后端 AI 推理延迟时展示，化解用户等待焦虑，带韵律思考光点)
        AnimatedVisibility(
            visible = uiState.isLoading,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-150).dp)
        ) {
            val thinkTransition = rememberInfiniteTransition(label = "thinkDots")
            val dot1Offset by thinkTransition.animateFloat(
                initialValue = 0f, targetValue = -3.5f,
                animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "d1"
            )
            val dot2Offset by thinkTransition.animateFloat(
                initialValue = 0f, targetValue = -3.5f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 120, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "d2"
            )
            val dot3Offset by thinkTransition.animateFloat(
                initialValue = 0f, targetValue = -3.5f,
                animationSpec = infiniteRepeatable(tween(380, delayMillis = 240, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                label = "d3"
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Transparent,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xF5FFFDF9), Color(0xEEF8F0FF))
                            )
                        )
                        .border(
                            1.5.dp,
                            Brush.linearGradient(
                                listOf(Color(0xFFFF5277), Color(0xFF9C27B0))
                            ),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = Color(0xFFFF5277),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "唔... 让星野想想看哦",
                        color = Color(0xFF281C38),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(y = dot1Offset.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5277))
                        )
                        Box(
                            modifier = Modifier
                                .offset(y = dot2Offset.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF7597))
                        )
                        Box(
                            modifier = Modifier
                                .offset(y = dot3Offset.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFB300))
                        )
                    }
                }
            }
        }

        // 4. 顶部 HUD (支持沉浸陪伴淡出)
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            StatusBadge(
                status = uiState.personaStatus,
                activeModel = uiState.activeModel,
                arMode = uiState.arMode,
                bondLevel = uiState.bondLevel,
                bondExp = uiState.bondExp,
                bondExpNext = uiState.bondExpNext,
                bondTitle = uiState.bondLevelTitle,
                onToggleARMode = { viewModel.toggleARMode() },
                onOpenStore = { viewModel.setStoreOpen(true) },
                onOpenMemoryNotebook = { viewModel.setMemoryNotebookOpen(true) },
                onOpenSettings = { viewModel.setSettingsOpen(true) }
            )
        }

        // 5. 右侧悬浮功能组：实景视野感知 (Vision AI) 与纯净 AR 合影
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // A. 实景视野多模态交互纽扣 (让二次元伴侣看懂眼前真实的桌面与物体)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xE6FFFDF9))
                        .border(1.8.dp, Color(0xFF00E5FF), CircleShape)
                        .clickable {
                            val realCameraBitmap = cameraFrameProvider?.invoke()
                            viewModel.onVisionInteract(realCameraBitmap)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = "实景视野感知",
                        tint = Color(0xFF0091EA),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // B. 纯净 AR 快照合影纽扣
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xE6FFFDF9))
                        .border(1.8.dp, Color(0xFF281C38), CircleShape)
                        .clickable {
                            // 触发快门闪光动画并执行真实拍立得合成保存
                            isShutterFlashing = true
                            showSnapshotToast = true
                            coroutineScope.launch {
                                val quote = uiState.messages.lastOrNull { it.sender != com.persona.ar.data.model.MessageSender.USER }?.text
                                    ?: "我就站在你的现实世界中哦~"
                                val realCameraBitmap = cameraFrameProvider?.invoke()
                                SnapshotHelper.captureAndSavePolaroid(
                                    context = context,
                                    visualState = uiState.visualState,
                                    quote = quote.take(30),
                                    cameraFrameBitmap = realCameraBitmap
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "纯净 AR 合影拍摄",
                        tint = Color(0xFF281C38),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 6. 底部聊天对白与主对讲区域 (支持沉浸陪伴淡出)
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ChatOverlay(
                    messages = uiState.messages,
                    displayMode = uiState.bubbleDisplayMode,
                    isSpeaking = uiState.isSpeaking,
                    partnerName = uiState.visualState.name.ifBlank { "星野 Hoshino" },
                    partnerColorHex = uiState.visualState.model?.previewColorHex ?: "#FF5277",
                    streamingText = uiState.streamingText,
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
        }

        // 7. 快门白闪特效与拍摄成功提示
        if (isShutterFlashing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.85f))
            )
            LaunchedEffect(Unit) {
                delay(120)
                isShutterFlashing = false
            }
        }

        if (showSnapshotToast) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6281C38))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "📸 拍立得合影已保存至系统相册 (Pictures/PersonaAR)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            LaunchedEffect(Unit) {
                delay(2000)
                showSnapshotToast = false
            }
        }

        // 8. 羁绊进阶升级祝贺横幅
        uiState.bondLevelUpNotice?.let { notice ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFFF0F5))
                    .border(2.dp, Color(0xFFFF4E8E), RoundedCornerShape(16.dp))
                    .clickable { viewModel.dismissBondNotice() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = notice,
                    color = Color(0xFF281C38),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
            LaunchedEffect(notice) {
                delay(3500)
                viewModel.dismissBondNotice()
            }
        }

        // 9. 触碰互动即时浮空动画粒子 (Floating Touch & Bond FX - 严格在点击处绽放并随风浮空)
        uiState.floatingTouchFx.forEach { fx ->
            val animProgress = remember(fx.id) { Animatable(0f) }
            LaunchedEffect(fx.id) {
                animProgress.animateTo(1f, animationSpec = tween(1100, easing = LinearOutSlowInEasing))
            }
            val targetX = (maxWidth * fx.x).coerceIn(50.dp, maxWidth - 50.dp)
            val baseY = (maxHeight * fx.y).coerceIn(80.dp, maxHeight - 120.dp)
            val offsetY = baseY - (70.dp * animProgress.value)
            val alpha = (1f - animProgress.value).coerceIn(0f, 1f)
            val scale = (0.7f + (1f - animProgress.value) * 0.35f).coerceIn(0.5f, 1.05f)

            Box(
                modifier = Modifier
                    .offset(x = targetX - 45.dp, y = offsetY)
                    .alpha(alpha)
                    .scale(scale)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFFF5277), Color(0xFFFF2A6D))
                        )
                    )
                    .border(1.5.dp, Color(0xFF281C38), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = fx.text,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // 10. 弹窗层
        if (uiState.isMemoryNotebookOpen) {
            MemoryNotebookDialog(
                partnerName = uiState.activeModel?.name ?: uiState.personaStatus?.name ?: "星野",
                bondLevel = uiState.bondLevel,
                bondTitle = uiState.bondLevelTitle,
                memories = uiState.memories,
                isLoading = uiState.isMemoryLoading,
                onDismiss = { viewModel.setMemoryNotebookOpen(false) },
                onCreateMemory = { content, category, importance ->
                    viewModel.createMemory(content, category, importance)
                },
                onDeleteMemory = { id ->
                    viewModel.deleteMemory(id)
                }
            )
        }

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

        if (uiState.isSettingsOpen) {
            SettingsDialog(
                currentMode = uiState.arMode,
                currentVoiceMode = uiState.voiceMode,
                currentBubbleMode = uiState.bubbleDisplayMode,
                currentServerUrl = uiState.serverBaseUrl,
                currentLlmProvider = uiState.llmProvider,
                currentLlmBaseUrl = uiState.llmBaseUrl,
                currentLlmApiKey = uiState.llmApiKey,
                currentLlmModel = uiState.llmModel,
                currentAsrProvider = uiState.asrProvider,
                currentAsrBaseUrl = uiState.asrBaseUrl,
                currentAsrApiKey = uiState.asrApiKey,
                currentAsrModel = uiState.asrModel,
                onDismiss = { viewModel.setSettingsOpen(false) },
                onToggleMode = { viewModel.toggleARMode() },
                onSelectVoiceMode = { mode -> viewModel.setVoiceMode(mode) },
                onSelectBubbleMode = { mode -> viewModel.setBubbleDisplayMode(mode) },
                onUpdateServerUrl = { url -> viewModel.updateServerUrl(url) },
                onUpdateLlmConfig = { provider, baseUrl, apiKey, model ->
                    viewModel.updateLlmConfig(provider, baseUrl, apiKey, model)
                },
                onUpdateAsrConfig = { provider, baseUrl, apiKey, model ->
                    viewModel.updateAsrConfig(provider, baseUrl, apiKey, model)
                },
                onPreviewAction = { action -> viewModel.previewAction(action) },
                onStand = { viewModel.setSkeletonStance(SkeletonStance.STANDING) },
                onSit = { viewModel.setSkeletonStance(SkeletonStance.SITTING) }
            )
        }
    }
}