package com.persona.ar.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.ar.ARMode
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus

/**
 * 极简通透且充满二次元精致感的顶部 HUD 状态栏
 * 包含萌系羁绊经验条（Bond EXP Bar）、心跳微律动与手账/工坊/设置快捷入口，全纯矢量图标。
 */
@Composable
fun StatusBadge(
    status: PersonaStatus?,
    activeModel: PersonaModelManifest?,
    arMode: ARMode,
    currentEmotion: EmotionType = EmotionType.NEUTRAL,
    bondLevel: Int = 1,
    bondExp: Int = 45,
    bondExpNext: Int = 100,
    bondTitle: String = "初遇契约",
    onToggleARMode: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenMemoryNotebook: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val charName = activeModel?.name ?: status?.name ?: "星野"
    val displayName = charName.substringBefore(" ").substringBefore("（").substringBefore("(").trim()
    val borderColor = Color(0xFF281C38)
    val progress = (bondExp.toFloat() / bondExpNext.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    // 心跳微律动动画（羁绊爱心平缓呼吸）
    val infiniteTransition = rememberInfiniteTransition(label = "hudBreathing")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartScale"
    )

    val arGlowPulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arGlowPulse"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：萌系角色身份与微光羁绊胶囊（点击呼出心语回忆手账）
        Box(
            modifier = Modifier
                .shadow(elevation = 3.dp, shape = RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xF5FFFDF9), // 96% 暖奶油
                            Color(0xEEFFF3F6)  // 柔和微粉渐变
                        )
                    )
                )
                .border(
                    1.5.dp,
                    Brush.linearGradient(
                        listOf(
                            borderColor.copy(alpha = 0.90f),
                            Color(0xFF5A4470).copy(alpha = 0.70f)
                        )
                    ),
                    RoundedCornerShape(20.dp)
                )
                .clickable { onOpenMemoryNotebook() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 心情表情矢量小图标
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFE0E8)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SentimentVerySatisfied,
                            contentDescription = "角色心情",
                            tint = Color(0xFFFF4E8E),
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(5.dp))

                    // 角色名与等级称号
                    Text(
                        text = "$displayName · Lv.$bondLevel",
                        color = borderColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // 好感爱心律动图标
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "好感度",
                        tint = Color(0xFFFF4E8E),
                        modifier = Modifier
                            .size(11.dp)
                            .scale(heartScale)
                    )

                    // 萌系二次元动态情绪反馈胶囊
                    val (emotionText, emotionColor, emotionBg) = when (currentEmotion) {
                        EmotionType.JOY -> Triple("🌸 欢快", Color(0xFFFF2A6D), Color(0xFFFFEBF2))
                        EmotionType.SHY -> Triple("💕 害羞", Color(0xFFE91E63), Color(0xFFFCE4EC))
                        EmotionType.ANGRY -> Triple("😤 傲娇", Color(0xFFFF5722), Color(0xFFFBE9E7))
                        EmotionType.CONFIDENT -> Triple("✨ 得意", Color(0xFFFFA000), Color(0xFFFFF8E1))
                        EmotionType.SLEEPY -> Triple("💤 困倦", Color(0xFF7E57C2), Color(0xFFEDE7F6))
                        EmotionType.THINKING -> Triple("💭 思考", Color(0xFF0288D1), Color(0xFFE1F5FE))
                        EmotionType.SAD -> Triple("💧 委屈", Color(0xFF1976D2), Color(0xFFE3F2FD))
                        EmotionType.SURPRISED -> Triple("⚡ 惊讶", Color(0xFFFFB300), Color(0xFFFFFDE7))
                        EmotionType.NEUTRAL -> Triple("🍵 平静", Color(0xFF5A4470), Color(0xFFF3E5F5))
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(emotionBg)
                            .border(0.8.dp, emotionColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = emotionText,
                            color = emotionColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // 萌系羁绊经验条（双层渐变 + 进度端点微高光）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.width(106.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(Color(0xFFEAE2D5))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFFFF7597),
                                            Color(0xFFFF4E8E),
                                            Color(0xFFFF2A6D)
                                        )
                                    )
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "$bondExp/$bondExpNext",
                        fontSize = 8.5.sp,
                        color = Color(0xFF7A6888),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 右侧：AR 模式秒切、手账快捷入口、工坊快捷按钮与设置入口（纯矢量图标，二次元通透微质感）
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isARActive = arMode == ARMode.ARCORE_3D

            // AR 现实相机 / 3D 纯净桌面 快速切换快捷键
            IconButton(
                onClick = onToggleARMode,
                modifier = Modifier
                    .scale(if (isARActive) arGlowPulse else 1f)
                    .shadow(elevation = if (isARActive) 4.dp else 2.dp, shape = CircleShape)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isARActive) {
                            Brush.linearGradient(
                                listOf(Color(0xFFFF5277), Color(0xFFFF2A6D))
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))
                            )
                        }
                    )
                    .border(
                        1.5.dp,
                        if (isARActive) Color(0xFFFF8DA1) else borderColor.copy(alpha = 0.85f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Filled.ViewInAr,
                    contentDescription = if (isARActive) "切换至 3D 虚拟空间" else "切换至 AR 现实相机",
                    tint = if (isARActive) Color.White else borderColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 回忆手账快捷键
            IconButton(
                onClick = onOpenMemoryNotebook,
                modifier = Modifier
                    .shadow(elevation = 2.dp, shape = CircleShape)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))
                        )
                    )
                    .border(1.5.dp, borderColor.copy(alpha = 0.85f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.HistoryEdu,
                    contentDescription = "心语回忆手账",
                    tint = Color(0xFFFF4E8E),
                    modifier = Modifier.size(18.dp)
                )
            }

            // 角色图鉴/契约工坊快捷键
            IconButton(
                onClick = onOpenStore,
                modifier = Modifier
                    .shadow(elevation = 2.dp, shape = CircleShape)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))
                        )
                    )
                    .border(1.5.dp, borderColor.copy(alpha = 0.85f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "契约工坊与伙伴图鉴",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(18.dp)
                )
            }

            // 系统设置快捷键
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .shadow(elevation = 2.dp, shape = CircleShape)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))
                        )
                    )
                    .border(1.5.dp, borderColor.copy(alpha = 0.85f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "系统与互动设置",
                    tint = borderColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

