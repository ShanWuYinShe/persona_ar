package com.persona.ar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.ChatMessage
import com.persona.ar.data.model.MessageSender
import kotlinx.coroutines.delay

@Composable
fun ChatOverlay(
    messages: List<ChatMessage>,
    displayMode: BubbleDisplayMode,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    // 模式3：完全不显示
    if (displayMode == BubbleDisplayMode.HIDDEN) {
        return
    }

    val listState = rememberLazyListState()

    // 模式2：延迟 5 秒自动淡出控制
    var isAutoFadeVisible by remember { mutableStateOf(false) }
    val latestMessage = messages.lastOrNull()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }

        if (displayMode == BubbleDisplayMode.AUTO_FADE_OUT && latestMessage != null) {
            isAutoFadeVisible = true
            // 停留 5 秒后淡出隐藏
            delay(5000L)
            isAutoFadeVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(horizontal = 16.dp)
    ) {
        when (displayMode) {
            BubbleDisplayMode.PERSISTENT -> {
                // 模式1：停留常驻历史瀑布流
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(messages, key = { it.id }) { msg ->
                        SingleBubbleItem(msg = msg)
                    }
                }
            }

            BubbleDisplayMode.AUTO_FADE_OUT -> {
                // 模式2：最新单条气泡 5 秒渐隐
                AnimatedVisibility(
                    visible = isAutoFadeVisible && latestMessage != null,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(600)),
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    latestMessage?.let {
                        SingleBubbleItem(msg = it)
                    }
                }
            }

            BubbleDisplayMode.HIDDEN -> Unit
        }
    }
}

@Composable
private fun SingleBubbleItem(msg: ChatMessage) {
    val isUser = msg.sender == MessageSender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 16.dp
                    )
                )
                .background(
                    if (isUser)
                        Color(0xCC7B61FF)
                    else
                        Color(0xCC201C2B)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = msg.text,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
