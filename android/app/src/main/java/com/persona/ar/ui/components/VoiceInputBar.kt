package com.persona.ar.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.TouchPart
import com.persona.ar.data.model.VoiceMode

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

    // 自然对话模式下的呼吸脉冲光晕动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 快捷触碰气泡标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickTouchChip(label = "摸摸头", onClick = { onTouchPart(TouchPart.HEAD) })
            QuickTouchChip(label = "戳戳脸", onClick = { onTouchPart(TouchPart.CHEEK) })
            QuickTouchChip(label = "牵小手", onClick = { onTouchPart(TouchPart.HAND) })
            QuickTouchChip(label = "抱一抱", onClick = { onTouchPart(TouchPart.BODY) })
        }

        // 状态指示条（根据当前对讲模式与激活状态展示）
        if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive) {
            // 自然对话模式激活状态条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xD9311B92))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUserSpeaking) "你正在说话（已自动打断星野）..." else "自然对聊中 · 随时开口随时打断",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }

                // 动态跳动小声波
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0..3) {
                        val barH = (6f + micVolume * 18f * (1f - (i % 2) * 0.3f)).dp
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 1.5.dp)
                                .width(3.dp)
                                .height(barH)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Color(0xFF00E676))
                        )
                    }
                }
            }
        } else if (voiceMode == VoiceMode.HOLD_TO_TALK && isRecording) {
            // 按住说话录音条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCCFF5277))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "正在倾听中... 松开发送",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                for (i in 0..4) {
                    val barHeight = (10f + micVolume * 25f * (1f - (i % 2) * 0.4f)).dp
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

        // 主输入栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xCC201C2B))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 语音按钮（根据模式区分交互：点击 vs 按住）
            if (voiceMode == VoiceMode.CONTINUOUS_NATURAL) {
                // 模式1：自然对话模式（点击开关）
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isContinuousActive)
                                Color(0xFF00C853).copy(alpha = pulseAlpha)
                            else
                                Color(0xFF332D42)
                        )
                        .clickable { onToggleContinuous() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = if (isContinuousActive) "关闭持续对聊" else "开启自然对聊",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // 模式2：对讲机模式（按住说话，松开发送）
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) Color(0xFFFF5277) else Color(0xFF332D42))
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
                        contentDescription = "按住说话，松开发送",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 文本输入框
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (voiceMode == VoiceMode.CONTINUOUS_NATURAL && isContinuousActive)
                            "麦克风聆听中，直接说话即可..."
                        else
                            "对星野说点什么吧...",
                        color = Color(0xFF888899),
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFFFF7597)),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 发送按钮
            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isLoading) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isLoading,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank() && !isLoading) Color(0xFF7B61FF) else Color(0x337B61FF)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "发送消息",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickTouchChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x99201C2B))
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFFFFD1DC),
            fontSize = 11.sp
        )
    }
}
