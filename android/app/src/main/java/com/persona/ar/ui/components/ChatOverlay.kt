package com.persona.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.MessageSender
import kotlinx.coroutines.delay

/**
 * 卡通二游 / Galgame 风格剧情对话框 (Visual Novel Dialogue Box)
 * 严格执行规范：名牌与文本绝对垂直隔离，文本框自适应撑高，防遮挡防截断，支持大模型流式打字机动画。
 */
@Composable
fun ChatOverlay(
    messages: List<ChatMessage>,
    displayMode: BubbleDisplayMode,
    isSpeaking: Boolean,
    partnerName: String = "星野 Hoshino",
    partnerColorHex: String = "#FF5277",
    streamingText: String? = null,
    modifier: Modifier = Modifier
) {
    if (displayMode == BubbleDisplayMode.HIDDEN) {
        return
    }

    val listState = rememberLazyListState()
    var isAutoFadeVisible by remember { mutableStateOf(false) }
    val latestMessage = messages.lastOrNull()

    val partnerColor = remember(partnerColorHex) {
        try {
            Color(android.graphics.Color.parseColor(partnerColorHex))
        } catch (e: Exception) {
            Color(0xFFFF5277)
        }
    }

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }

        if (displayMode == BubbleDisplayMode.AUTO_FADE_OUT && (latestMessage != null || streamingText != null)) {
            isAutoFadeVisible = true
            delay(5000L)
            isAutoFadeVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        when (displayMode) {
            BubbleDisplayMode.PERSISTENT -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(messages, key = { it.id }) { msg ->
                        AnimeGameDialogueItem(
                            msg = msg,
                            partnerName = partnerName,
                            partnerColor = partnerColor
                        )
                    }

                    // 实时流式生成打字机气泡
                    if (!streamingText.isNullOrEmpty()) {
                        item(key = "streaming_live") {
                            AnimeGameStreamingDialogueItem(
                                streamingText = streamingText,
                                partnerName = partnerName,
                                partnerColor = partnerColor
                            )
                        }
                    }
                }
            }

            BubbleDisplayMode.AUTO_FADE_OUT -> {
                AnimatedVisibility(
                    visible = (isAutoFadeVisible && latestMessage != null) || !streamingText.isNullOrEmpty(),
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(600)),
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    if (!streamingText.isNullOrEmpty()) {
                        AnimeGameStreamingDialogueItem(
                            streamingText = streamingText,
                            partnerName = partnerName,
                            partnerColor = partnerColor
                        )
                    } else {
                        latestMessage?.let {
                            AnimeGameDialogueItem(
                                msg = it,
                                partnerName = partnerName,
                                partnerColor = partnerColor
                            )
                        }
                    }
                }
            }

            BubbleDisplayMode.HIDDEN -> Unit
        }
    }
}

@Composable
private fun AnimeGameStreamingDialogueItem(
    streamingText: String,
    partnerName: String,
    partnerColor: Color
) {
    val strokeColor = Color(0xFF281C38)
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor"
    )

    Column(modifier = Modifier.widthIn(max = 330.dp)) {
        // 角色萌系名牌（附带流式生成呼吸态标识）
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(partnerColor)
                .border(1.8.dp, strokeColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✦ $partnerName ✦",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "流式生成中",
                    tint = Color.White.copy(alpha = cursorAlpha),
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 打字机流式正文框
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFFFDF9).copy(alpha = 0.98f))
                .border(2.dp, strokeColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = streamingText,
                    color = Color(0xFF281C38),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = " ▌",
                    color = partnerColor.copy(alpha = cursorAlpha),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun AnimeGameDialogueItem(
    msg: ChatMessage,
    partnerName: String,
    partnerColor: Color
) {
    val isUser = msg.sender == MessageSender.USER
    val strokeColor = Color(0xFF281C38) // 饱满卡通深紫黑描边

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // 角色发言：Galgame 对话框（名牌与文本采用绝对垂直隔离，零裁切、零重叠）
            Column(modifier = Modifier.widthIn(max = 330.dp)) {
                // 1. 角色萌系名牌 (置于上方，y=0 独立层级，绝对不侵占正文区域)
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(partnerColor)
                        .border(1.8.dp, strokeColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "✦ $partnerName ✦",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 2. 主对话文本框（充裕内边距，文字绝对不被遮挡）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFFFFDF9).copy(alpha = 0.98f))
                        .border(2.dp, strokeColor, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column {
                        Text(
                            text = msg.text,
                            color = Color(0xFF281C38),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // 矢量翻页小箭头（右下角指示，避免 Emoji）
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "继续",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier
                                .align(Alignment.End)
                                .size(16.dp)
                        )
                    }
                }
            }
        } else {
            // 用户发言：天蓝/马卡龙紫贴纸气泡
            Column(
                modifier = Modifier.widthIn(max = 290.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 用户名牌
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF00B0FF))
                        .border(1.8.dp, strokeColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✦ 指挥官 Commander ✦",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 4.dp,
                                bottomStart = 18.dp,
                                bottomEnd = 18.dp
                            )
                        )
                        .background(Color(0xFF7B61FF))
                        .border(2.dp, strokeColor, RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = msg.text,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}
