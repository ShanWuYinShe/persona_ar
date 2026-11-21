package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.persona.ar.ar.ARMode
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.VoiceMode

/**
 * 卡通二游风格的系统与交互设置公告板
 * 严守专业规范：全矢量图标，绝不使用系统 Emoji 字符代替图标。
 */
@Composable
fun SettingsDialog(
    currentMode: ARMode,
    currentVoiceMode: VoiceMode,
    currentBubbleMode: BubbleDisplayMode,
    currentServerUrl: String,
    onDismiss: () -> Unit,
    onToggleMode: () -> Unit,
    onSelectVoiceMode: (VoiceMode) -> Unit,
    onSelectBubbleMode: (BubbleDisplayMode) -> Unit,
    onUpdateServerUrl: (String) -> Unit
) {
    var serverInput by remember { mutableStateOf(currentServerUrl) }
    var selectedVoiceMode by remember { mutableStateOf(currentVoiceMode) }
    var selectedBubbleMode by remember { mutableStateOf(currentBubbleMode) }

    val borderColor = Color(0xFF281C38)
    val surfaceCream = Color(0xFFFFFDF9)
    val sectionBg = Color(0xFFFFF5EB)

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(surfaceCream)
                .border(2.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标题栏 + 矢量关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD54F))
                                .border(1.5.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                tint = borderColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "系统与互动设置",
                            color = borderColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFE0E8))
                            .border(1.5.dp, borderColor, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color(0xFFFF5277),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 1. 语音对讲方式设置
                SectionContainer(
                    icon = Icons.Filled.Mic,
                    title = "语音交流互动机制",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GameOptionChip(
                            title = "随时打断·连麦",
                            subtitle = "如同真人同室交流",
                            isSelected = selectedVoiceMode == VoiceMode.CONTINUOUS_NATURAL,
                            activeColor = Color(0xFF00E676),
                            onClick = { selectedVoiceMode = VoiceMode.CONTINUOUS_NATURAL },
                            modifier = Modifier.weight(1f)
                        )
                        GameOptionChip(
                            title = "按住说话·对讲",
                            subtitle = "街机手柄式呼叫",
                            isSelected = selectedVoiceMode == VoiceMode.HOLD_TO_TALK,
                            activeColor = Color(0xFFFF5277),
                            onClick = { selectedVoiceMode = VoiceMode.HOLD_TO_TALK },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = if (selectedVoiceMode == VoiceMode.CONTINUOUS_NATURAL)
                            "• 连麦模式: 开启监听后，星野说话期间你随时开口即可瞬间掐断播音，静音自动断句发送"
                        else
                            "• 对讲模式: 按住底部的麦克风按钮蓄力说话，松开后即刻发送",
                        color = Color(0xFF6B587B),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                // 2. 对话气泡显示策略
                SectionContainer(
                    icon = Icons.Filled.ChatBubble,
                    title = "对白气泡显示策略",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GameMiniChip(
                            title = "停留常驻",
                            isSelected = selectedBubbleMode == BubbleDisplayMode.PERSISTENT,
                            activeColor = Color(0xFF7B61FF),
                            onClick = { selectedBubbleMode = BubbleDisplayMode.PERSISTENT },
                            modifier = Modifier.weight(1f)
                        )
                        GameMiniChip(
                            title = "5秒自动淡出",
                            isSelected = selectedBubbleMode == BubbleDisplayMode.AUTO_FADE_OUT,
                            activeColor = Color(0xFFFFB300),
                            onClick = { selectedBubbleMode = BubbleDisplayMode.AUTO_FADE_OUT },
                            modifier = Modifier.weight(1f)
                        )
                        GameMiniChip(
                            title = "完全不显示",
                            isSelected = selectedBubbleMode == BubbleDisplayMode.HIDDEN,
                            activeColor = Color(0xFF00B0FF),
                            onClick = { selectedBubbleMode = BubbleDisplayMode.HIDDEN },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = when (selectedBubbleMode) {
                            BubbleDisplayMode.PERSISTENT -> "• 常驻模式: 完整保留对白记录，方便回滚查阅"
                            BubbleDisplayMode.AUTO_FADE_OUT -> "• 5秒淡出: 播放结束5秒后自动隐去，视野干净不挡风景"
                            BubbleDisplayMode.HIDDEN -> "• 纯净模式: 零文字遮挡，完全凭借星野的神态动作与萌音交流"
                        },
                        color = Color(0xFF6B587B),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                // 3. 现实投射视觉模式
                SectionContainer(
                    icon = Icons.Filled.ViewInAr,
                    title = "现实投射视觉维度",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GameOptionChip(
                            title = "2.5D Q萌立绘",
                            subtitle = "动态呼吸·眨眼大头身",
                            isSelected = currentMode == ARMode.BILLBOARD_2_5D,
                            activeColor = Color(0xFFFF7597),
                            onClick = { if (currentMode != ARMode.BILLBOARD_2_5D) onToggleMode() },
                            modifier = Modifier.weight(1f)
                        )
                        GameOptionChip(
                            title = "3D 空间追踪",
                            subtitle = "ARCore 平面落桌",
                            isSelected = currentMode == ARMode.ARCORE_3D,
                            activeColor = Color(0xFF00B0FF),
                            onClick = { if (currentMode != ARMode.ARCORE_3D) onToggleMode() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 4. 后端 API 地址
                SectionContainer(
                    icon = Icons.Filled.Dns,
                    title = "情感中枢服务器地址",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        textStyle = LocalTextStyle.current.copy(
                            color = borderColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        placeholder = { Text("例如 http://192.168.1.5:8000/", fontSize = 11.sp, color = Color(0xFF9E8EA8)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color(0xFFFF5277),
                            unfocusedBorderColor = borderColor.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 底部按钮栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(text = "取消", color = Color(0xFF7A6B88), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            onSelectVoiceMode(selectedVoiceMode)
                            onSelectBubbleMode(selectedBubbleMode)
                            onUpdateServerUrl(serverInput.trim())
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF5277)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .border(1.8.dp, borderColor, RoundedCornerShape(14.dp))
                    ) {
                        Text(
                            text = "保存并应用",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionContainer(
    icon: ImageVector,
    title: String,
    sectionBg: Color,
    borderColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(sectionBg)
            .border(1.5.dp, borderColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = borderColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = borderColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        content()
    }
}

@Composable
private fun GameOptionChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = Color(0xFF281C38)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) activeColor else Color.White)
            .border(
                width = if (isSelected) 2.dp else 1.2.dp,
                color = if (isSelected) borderColor else borderColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (isSelected) Color.White else borderColor
            )
            Text(
                text = subtitle,
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                color = if (isSelected) Color.White.copy(alpha = 0.9f) else Color(0xFF887A99)
            )
        }
    }
}

@Composable
private fun GameMiniChip(
    title: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = Color(0xFF281C38)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) activeColor else Color.White)
            .border(
                width = if (isSelected) 1.8.dp else 1.2.dp,
                color = if (isSelected) borderColor else borderColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
            color = if (isSelected) Color.White else Color(0xFF483A5A),
            maxLines = 1
        )
    }
}
