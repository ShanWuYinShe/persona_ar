package com.persona.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.VoiceMode

/**
 * 重点突出的二游卡通对讲控制台 (Focused Voice Action Dock)
 * 严格执行规范：全矢量图标，绝不使用任何系统 Emoji 字符代替图标。
 */
@Composable
fun VoiceInputBar(
    voiceMode: VoiceMode,
    isRecording: Boolean,
    isContinuousActive: Boolean,
    isUserSpeaking: Boolean,
    micVolume: Float,
    isLoading: Boolean,
    onSendMessage: (String) -> Unit,
    onToggleContinuous: () -> Unit,
    onStartHoldVoice: () -> Unit,
    onStopHoldVoice: () -> Unit,
    onTouchPart: (TouchPart) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTextInput by remember { mutableStateOf(false) }
    var showInteractSheet by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    val borderColor = Color(0xFF281C38) // 饱满二游深紫黑轮廓
    val brandPink = Color(0xFFFF5277)
    val brandGreen = Color(0xFF00E676)
    val creamBg = Color(0xF2FFFDF9) // 95% 半透明奶油暖白

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 次要按需展开层：触碰互动选择浮层（纯矢量图标）
        AnimatedVisibility(
            visible = showInteractSheet,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(creamBg)
                    .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VectorActionChip(icon = Icons.Filled.TouchApp, text = "摸摸头") {
                    onTouchPart(TouchPart.HEAD)
                    showInteractSheet = false
                }
                VectorActionChip(icon = Icons.Filled.Face, text = "戳戳脸") {
                    onTouchPart(TouchPart.CHEEK)
                    showInteractSheet = false
                }
                VectorActionChip(icon = Icons.Filled.PanTool, text = "牵小手") {
                    onTouchPart(TouchPart.HAND)
                    showInteractSheet = false
                }
                VectorActionChip(icon = Icons.Filled.Favorite, text = "抱一抱") {
                    onTouchPart(TouchPart.BODY)
                    showInteractSheet = false
                }
            }
        }

        // 2. 次要按需展开层：打字键盘输入栏
        AnimatedVisibility(
            visible = showTextInput,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(creamBg)
                    .border(1.5.dp, borderColor, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        color = borderColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(brandPink),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = "输入文字与纸片人交流...",
                                color = Color(0xFF9E8EA8),
                                fontSize = 12.sp
                            )
                        }
                        innerTextField()
                    }
                )

                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            onSendMessage(inputText.trim())
                            inputText = ""
                            showTextInput = false
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送消息",
                            tint = brandPink,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { showTextInput = false },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭输入栏",
                        tint = Color(0xFF887799),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 3. 常驻核心主控条：左右辅助小按钮 + 中央唯一视觉焦点（大对讲主键）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左翼辅助按键：触碰互动折叠胶囊（矢量手势图标）
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (showInteractSheet) Color(0xFFFFD54F) else creamBg)
                    .border(1.5.dp, borderColor, CircleShape)
                    .clickable {
                        showInteractSheet = !showInteractSheet
                        if (showInteractSheet) showTextInput = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.TouchApp,
                    contentDescription = "触碰互动菜单",
                    tint = borderColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 中间焦点：【绝对核心重点】大招果冻主语音对讲胶囊
            val isMainActive = (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) ||
                    (voiceMode == VoiceMode.HOLD_TO_TALK && isRecording)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .height(52.dp)
                    .scale(if (isMainActive) pulseScale else 1.0f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        when {
                            voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive ->
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color(0xFF00E676), Color(0xFF00C853))
                                )
                            voiceMode == VoiceMode.HOLD_TO_TALK && isRecording ->
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color(0xFFFF5277), Color(0xFFFF2A6D))
                                )
                            voiceMode == VoiceMode.HOLD_TO_TALK ->
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color(0xFFFF7597), Color(0xFFFF4E8E))
                                )
                            else ->
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color(0xFFFFCA28), Color(0xFFFF9800))
                                )
                        }
                    )
                    .border(2.dp, borderColor, RoundedCornerShape(26.dp))
                    .then(
                        if (voiceMode == VoiceMode.HOLD_TO_TALK) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        onStartHoldVoice()
                                        tryAwaitRelease()
                                        onStopHoldVoice()
                                    }
                                )
                            }
                        } else {
                            Modifier.clickable { onToggleContinuous() }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 顶部晶莹反光层（二次元果冻微质感）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive)
                            Icons.Default.GraphicEq
                        else
                            Icons.Default.Mic,
                        contentDescription = "对讲麦克风",
                        tint = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) borderColor else Color.White,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = when {
                            voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive ->
                                if (isUserSpeaking) "你正在发声(已打断)" else "正在连麦 · 随时开口"
                            voiceMode == VoiceMode.CONTINUOUS_NATURAL ->
                                "开启连麦 · 随时打断"
                            voiceMode == VoiceMode.HOLD_TO_TALK && isRecording ->
                                "倾听中... 松开发送"
                            else ->
                                "按住说话 · 松开发送"
                        },
                        color = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) borderColor else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )

                    // 连麦中动态 5 根律动音频音浪跳动柱 (Audio Waveform Visualizer)
                    if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val factors = listOf(0.6f, 1.0f, 1.4f, 0.9f, 0.5f)
                            factors.forEachIndexed { idx, factor ->
                                val baseHeight = if (isUserSpeaking) 12f else 6f
                                val dynamicHeight = (baseHeight + micVolume * 22f * factor).coerceIn(4f, 26f)
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(dynamicHeight.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(if (isUserSpeaking) brandPink else borderColor)
                                )
                            }
                        }
                    }
                }
            }

            // 右翼辅助按键：文字输入切换（矢量键盘图标）
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (showTextInput) Color(0xFFE8DEF8) else creamBg)
                    .border(1.5.dp, borderColor, CircleShape)
                    .clickable {
                        showTextInput = !showTextInput
                        if (showTextInput) showInteractSheet = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "切换键盘输入",
                    tint = borderColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun VectorActionChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    val borderColor = Color(0xFF281C38)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = Color(0xFFFF5277),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = borderColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
