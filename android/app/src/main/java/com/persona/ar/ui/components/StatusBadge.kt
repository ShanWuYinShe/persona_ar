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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus

/**
 * 极简通透且充满二次元精致感的顶部 HUD 状态栏
 * 包含萌系羁绊经验条（Bond EXP Bar）、心跳微律动与重放/手账/工坊/设置快捷入口，全纯矢量图标。
 */
@Composable
fun StatusBadge(
    status: PersonaStatus?,
    activeModel: PersonaModelManifest?,
    isAnchored: Boolean,
    currentEmotion: EmotionType = EmotionType.NEUTRAL,
    bondLevel: Int = 1,
    bondExp: Int = 45,
    bondExpNext: Int = 100,
    bondTitle: String = "初遇契约",
    isServerReachable: Boolean = false,
    onResetAnchor: () -> Unit,
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
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

                    // 纯矢量图标 + 文字动态情绪胶囊（不使用 Emoji 字符）
                    val (emotionIcon, emotionText, emotionColor, emotionBg) = emotionChipVisuals(currentEmotion)

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(emotionBg)
                            .border(0.8.dp, emotionColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Icon(
                            imageVector = emotionIcon,
                            contentDescription = null,
                            tint = emotionColor,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = emotionText,
                            color = emotionColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // 萌系羁绊经验条 + 在线状态指示点
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.widthIn(min = 104.dp, max = 128.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isServerReachable) Color(0xFF00C853) else Color(0xFF9E8EA8))
                            .semantics {
                                contentDescription = if (isServerReachable) "已连接情感大脑服务" else "端侧离线陪伴"
                            }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
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

        // 右侧：3D 重放定位、手账快捷入口、工坊快捷按钮与设置入口（纯矢量图标 + 直观微标签）
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Top
        ) {
            HudActionButton(
                label = "重放",
                icon = Icons.Filled.Refresh,
                contentDescription = "重新定位放置 3D 模型",
                tint = if (isAnchored) Color.White else borderColor,
                borderColor = if (isAnchored) Color(0xFF00B0FF) else borderColor.copy(alpha = 0.85f),
                bgBrush = if (isAnchored) {
                    Brush.linearGradient(listOf(Color(0xFF29B6F6), Color(0xFF0288D1)))
                } else {
                    Brush.linearGradient(listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC)))
                },
                scale = 1f,
                onClick = onResetAnchor
            )

            HudActionButton(
                label = "手账",
                icon = Icons.Filled.HistoryEdu,
                contentDescription = "心语回忆手账",
                tint = Color(0xFFFF4E8E),
                borderColor = borderColor.copy(alpha = 0.85f),
                bgBrush = Brush.linearGradient(listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))),
                onClick = onOpenMemoryNotebook
            )

            HudActionButton(
                label = "伙伴",
                icon = Icons.Filled.AutoAwesome,
                contentDescription = "契约工坊与伙伴图鉴",
                tint = Color(0xFFE65100),
                borderColor = borderColor.copy(alpha = 0.85f),
                bgBrush = Brush.linearGradient(listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))),
                onClick = onOpenStore
            )

            HudActionButton(
                label = "设置",
                icon = Icons.Default.Settings,
                contentDescription = "系统与互动设置",
                tint = borderColor,
                borderColor = borderColor.copy(alpha = 0.85f),
                bgBrush = Brush.linearGradient(listOf(Color(0xF5FFFDF9), Color(0xEEF8F3FC))),
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun HudActionButton(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    borderColor: Color,
    bgBrush: Brush,
    scale: Float = 1f,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .scale(scale)
                    .shadow(elevation = 3.dp, shape = CircleShape)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(bgBrush)
                    .border(1.5.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xAA281C38))
                .padding(horizontal = 4.dp, vertical = 0.5.dp)
        )
    }
}

private data class EmotionChipVisuals(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val bg: Color
)

private fun emotionChipVisuals(emotion: EmotionType): EmotionChipVisuals {
    return when (emotion) {
        EmotionType.JOY -> EmotionChipVisuals(Icons.Filled.AutoAwesome, "欢快", Color(0xFFFF2A6D), Color(0xFFFFEBF2))
        EmotionType.SHY -> EmotionChipVisuals(Icons.Filled.Favorite, "害羞", Color(0xFFE91E63), Color(0xFFFCE4EC))
        EmotionType.ANGRY -> EmotionChipVisuals(Icons.Filled.Whatshot, "傲娇", Color(0xFFFF5722), Color(0xFFFBE9E7))
        EmotionType.CONFIDENT -> EmotionChipVisuals(Icons.Filled.AutoAwesome, "得意", Color(0xFFFFA000), Color(0xFFFFF8E1))
        EmotionType.SLEEPY -> EmotionChipVisuals(Icons.Filled.Bedtime, "困倦", Color(0xFF7E57C2), Color(0xFFEDE7F6))
        EmotionType.THINKING -> EmotionChipVisuals(Icons.Filled.Psychology, "思考", Color(0xFF0288D1), Color(0xFFE1F5FE))
        EmotionType.SAD -> EmotionChipVisuals(Icons.Filled.WaterDrop, "委屈", Color(0xFF1976D2), Color(0xFFE3F2FD))
        EmotionType.SURPRISED -> EmotionChipVisuals(Icons.Filled.Bolt, "惊讶", Color(0xFFFFB300), Color(0xFFFFFDE7))
        EmotionType.NEUTRAL -> EmotionChipVisuals(Icons.Filled.LocalCafe, "平静", Color(0xFF5A4470), Color(0xFFF3E5F5))
    }
}

