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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.VoiceMode

/**
 * 重点突出的二游卡通对讲控制台 (Focused Voice Action Dock)
 * 核心原则：
 * 1. 唯一焦点：中央超大、清晰明确的主对讲胶囊按钮（按住说话 / 连麦状态，一眼即达）
 * 2. 辅助收拢：触碰道具与文字输入收拢为左右两侧极简小圆钮，按需浮层展开，绝不抢占视觉中心
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
        // 1. 次要按需展开层：触碰互动选择浮层（仅点击✨时轻量呼出，绝不常驻乱屏）
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
                QuickActionChip("🍬 摸摸头") { onTouchPart(TouchPart.HEAD); showInteractSheet = false }
                QuickActionChip("🍓 戳戳脸") { onTouchPart(TouchPart.CHEEK); showInteractSheet = false }
                QuickActionChip("🌟 牵小手") { onTouchPart(TouchPart.HAND); showInteractSheet = false }
                QuickActionChip("💖 抱一抱") { onTouchPart(TouchPart.BODY); showInteractSheet = false }
            }
        }

        // 2. 次要按需展开层：打字键盘输入栏（仅点击⌨️时呼出）
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
                        color = Color(0xFF281C38),
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
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
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
                        contentDescription = "关闭输入",
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
            // 左翼辅助按键：触碰互动折叠胶囊
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
                Text(text = "✨", fontSize = 18.sp)
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
                            voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive -> brandGreen
                            voiceMode == VoiceMode.HOLD_TO_TALK && isRecording -> brandPink
                            voiceMode == VoiceMode.HOLD_TO_TALK -> brandPink
                            else -> Color(0xFFFFB300) // 连麦未开启时：活力金黄
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "对讲麦克风",
                        tint = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) borderColor else Color.White,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = when {
                            voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive ->
                                if (isUserSpeaking) "你正在说话(已打断)" else "正在连麦 · 随时开口"
                            voiceMode == VoiceMode.CONTINUOUS_NATURAL ->
                                "开启连麦 · 随时打断"
                            voiceMode == VoiceMode.HOLD_TO_TALK && isRecording ->
                                "松开发送给纸片人..."
                            else ->
                                "按住说话 · 松开发送"
                        },
                        color = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) borderColor else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )

                    // 连麦中动态微跳声波
                    if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for (i in 0..2) {
                                val barH = (6f + micVolume * 14f * (1f - (i % 2) * 0.4f)).dp
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 1.5.dp)
                                        .width(3.dp)
                                        .height(barH)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(borderColor)
                                )
                            }
                        }
                    }
                }
            }

            // 右翼辅助按键：文字输入切换
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
                    contentDescription = "文字输入",
                    tint = borderColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    text: String,
    onClick: () -> Unit
) {
    val borderColor = Color(0xFF281C38)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = borderColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
