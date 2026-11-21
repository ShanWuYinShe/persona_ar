package com.persona.ar.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * 卡通游戏风格（Game Action Dock）底部交互控制台
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
    var inputText by remember { mutableStateOf("") }
    val strokeColor = Color(0xFF281C38) // 饱满卡通深紫黑描边

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // 1. 快捷触碰彩色糖果胶囊 (Candy Touch Pills)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameCandyChip(label = "🍬 摸摸头", bgColor = Color(0xFFFF80AB), onClick = { onTouchPart(TouchPart.HEAD) }, modifier = Modifier.weight(1f))
            GameCandyChip(label = "🍓 戳戳脸", bgColor = Color(0xFFFFD54F), onClick = { onTouchPart(TouchPart.CHEEK) }, modifier = Modifier.weight(1f))
            GameCandyChip(label = "🌟 牵小手", bgColor = Color(0xFF40C4FF), onClick = { onTouchPart(TouchPart.HAND) }, modifier = Modifier.weight(1f))
            GameCandyChip(label = "💖 抱一抱", bgColor = Color(0xFFB388FF), onClick = { onTouchPart(TouchPart.BODY) }, modifier = Modifier.weight(1f))
        }

        // 2. 游戏状态横幅 (自然对聊 vs 按住对讲)
        if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2ED573))
                    .border(2.dp, strokeColor, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "●", color = Color(0xFF281C38), fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUserSpeaking) "你正在发声（已打断星野）..." else "自然对聊中 · 随时开口随时打断",
                        color = Color(0xFF281C38),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0..3) {
                        val barH = (6f + micVolume * 18f * (1f - (i % 2) * 0.3f)).dp
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .width(3.5.dp)
                                .height(barH)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF281C38))
                        )
                    }
                }
            }
        } else if (voiceMode == VoiceMode.HOLD_TO_TALK && isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFF5277))
                    .border(2.dp, strokeColor, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎙 正在倾听中... 松开发送给星野",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                for (i in 0..4) {
                    val barHeight = (10f + micVolume * 24f * (1f - (i % 2) * 0.4f)).dp
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .width(4.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White)
                    )
                }
            }
        }

        // 3. 卡通游戏主输入栏（奶油暖白底 + 2.5dp 深紫黑描边）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFFFFFDF9).copy(alpha = 0.98f))
                .border(2.5.dp, strokeColor, RoundedCornerShape(30.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 游戏大招水晶果冻按键 (Special Move Button)
            if (voiceMode == VoiceMode.CONTINUOUS_NATURAL) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (isContinuousActive)
                                Brush.radialGradient(listOf(Color(0xFF2ED573), Color(0xFF1ABC9C)))
                            else
                                Brush.radialGradient(listOf(Color(0xFFFFD54F), Color(0xFFFFA000)))
                        )
                        .border(2.5.dp, strokeColor, CircleShape)
                        .clickable { onToggleContinuous() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = "开启自然对话",
                        tint = Color(0xFF281C38),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecording)
                                Brush.radialGradient(listOf(Color(0xFFFF5277), Color(0xFFD81B60)))
                            else
                                Brush.radialGradient(listOf(Color(0xFFFFD54F), Color(0xFFFFA000)))
                        )
                        .border(2.5.dp, strokeColor, CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onStartHoldVoice()
                                    tryAwaitRelease()
                                    onStopHoldVoice()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "按住说话",
                        tint = if (isRecording) Color.White else Color(0xFF281C38),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 文本输入框
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive)
                            "麦克风聆听中，直接说话即可..."
                        else
                            "对星野说点什么吧...",
                        color = Color(0xFF9E8DB5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        color = Color(0xFF281C38),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(Color(0xFFFF5277)),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 发送按钮 (草莓粉圆形按键)
            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isLoading) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isLoading,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank() && !isLoading) Color(0xFFFF5277) else Color(0xFFE0D8EB)
                    )
                    .border(2.dp, strokeColor, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "发送消息",
                    tint = if (inputText.isNotBlank() && !isLoading) Color.White else Color(0xFF9E8DB5),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun GameCandyChip(
    label: String,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(2.dp, Color(0xFF281C38), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFF281C38),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black
        )
    }
}
