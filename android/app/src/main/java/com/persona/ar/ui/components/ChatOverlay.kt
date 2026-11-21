package com.persona.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
 * 严格执行规范：名牌与文本绝对不重叠，文本框自适应撑高，防遮挡、防截断，全矢量翻页图标。
 */
@Composable
fun ChatOverlay(
    messages: List<ChatMessage>,
    displayMode: BubbleDisplayMode,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    if (displayMode == BubbleDisplayMode.HIDDEN) {
        return
    }

    val listState = rememberLazyListState()
    var isAutoFadeVisible by remember { mutableStateOf(false) }
    val latestMessage = messages.lastOrNull()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }

        if (displayMode == BubbleDisplayMode.AUTO_FADE_OUT && latestMessage != null) {
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
                        AnimeGameDialogueItem(msg = msg)
                    }
                }
            }

            BubbleDisplayMode.AUTO_FADE_OUT -> {
                AnimatedVisibility(
                    visible = isAutoFadeVisible && latestMessage != null,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(600)),
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    latestMessage?.let {
                        AnimeGameDialogueItem(msg = it)
                    }
                }
            }

            BubbleDisplayMode.HIDDEN -> Unit
        }
    }
}

@Composable
private fun AnimeGameDialogueItem(msg: ChatMessage) {
    val isUser = msg.sender == MessageSender.USER
    val strokeColor = Color(0xFF281C38) // 饱满卡通深紫黑描边

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // 角色发言：Galgame 对话框（名牌与文本采用绝对安全层级堆叠）
            Column(modifier = Modifier.widthIn(max = 320.dp)) {
                // 1. 角色萌系名牌 (置于上方，不侵占正文区域)
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF5277))
                        .border(1.5.dp, strokeColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✦ 星野 Hoshino ✦",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 2. 主对话文本框（充裕内边距，文字绝对不被遮挡）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFFFDF9).copy(alpha = 0.96f))
                        .border(1.8.dp, strokeColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            text = msg.text,
                            color = Color(0xFF281C38),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 19.sp
                        )

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
                modifier = Modifier.widthIn(max = 280.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 用户名牌
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF00B0FF))
                        .border(1.5.dp, strokeColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "指挥官",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 4.dp,
                                bottomStart = 16.dp,
                                bottomEnd = 16.dp
                            )
                        )
                        .background(Color(0xFF7B61FF))
                        .border(1.8.dp, strokeColor, RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = msg.text,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
