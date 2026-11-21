package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.persona.ar.ar.ARMode
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.VoiceMode

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

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF262233))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "系统与交互设置",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                // 1. 语音对讲方式设置（可调选项）
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "语音对讲交互方式",
                        color = Color(0xFFAAAAFF),
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { selectedVoiceMode = VoiceMode.CONTINUOUS_NATURAL },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedVoiceMode == VoiceMode.CONTINUOUS_NATURAL) Color(0xFF00C853) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "随时打断·自然说话", fontSize = 11.sp, maxLines = 1)
                        }
                        Button(
                            onClick = { selectedVoiceMode = VoiceMode.HOLD_TO_TALK },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedVoiceMode == VoiceMode.HOLD_TO_TALK) Color(0xFFFF5277) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "按住说话·松开发送", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Text(
                        text = if (selectedVoiceMode == VoiceMode.CONTINUOUS_NATURAL)
                            "• 自然对话: 点击开启监听，你一开口就会自动打断星野当前播音，静音自动断句发送"
                        else
                            "• 对讲机: 类似语音对讲，按住底部的麦克风按钮说话，松开后即刻发送",
                        color = Color(0xFFB0A8C0),
                        fontSize = 10.sp
                    )
                }

                // 2. 对话气泡显示策略（可调选项）
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "对话气泡显示策略",
                        color = Color(0xFFAAAAFF),
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { selectedBubbleMode = BubbleDisplayMode.PERSISTENT },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedBubbleMode == BubbleDisplayMode.PERSISTENT) Color(0xFF7B61FF) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "停留常驻", fontSize = 11.sp, maxLines = 1)
                        }
                        Button(
                            onClick = { selectedBubbleMode = BubbleDisplayMode.AUTO_FADE_OUT },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedBubbleMode == BubbleDisplayMode.AUTO_FADE_OUT) Color(0xFF7B61FF) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "5秒自动淡出", fontSize = 11.sp, maxLines = 1)
                        }
                        Button(
                            onClick = { selectedBubbleMode = BubbleDisplayMode.HIDDEN },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedBubbleMode == BubbleDisplayMode.HIDDEN) Color(0xFF7B61FF) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "完全不显示", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Text(
                        text = when (selectedBubbleMode) {
                            BubbleDisplayMode.PERSISTENT -> "• 常驻模式: 保留所有对话历史，方便回滚查阅"
                            BubbleDisplayMode.AUTO_FADE_OUT -> "• 5秒淡出: 最新对话显示并在播放结束 5 秒后平滑隐去，画面更干净"
                            BubbleDisplayMode.HIDDEN -> "• 纯净模式: 零文字遮挡，全凭星野的声音与神态动作交流"
                        },
                        color = Color(0xFFB0A8C0),
                        fontSize = 10.sp
                    )
                }

                // 3. 现实投射视觉模式
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "现实投射视觉模式",
                        color = Color(0xFFAAAAFF),
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { if (currentMode != ARMode.BILLBOARD_2_5D) onToggleMode() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentMode == ARMode.BILLBOARD_2_5D) Color(0xFF7B61FF) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "2.5D 精致立绘", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { if (currentMode != ARMode.ARCORE_3D) onToggleMode() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentMode == ARMode.ARCORE_3D) Color(0xFF00B0FF) else Color(0xFF3B354D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "3D 空间追踪", fontSize = 12.sp)
                        }
                    }
                }

                // 4. 后端 API 地址
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "专属情感后端 API 基础地址",
                        color = Color(0xFFAAAAFF),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
                        placeholder = { Text("例如 http://192.168.1.5:8000/", fontSize = 11.sp, color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF7597),
                            unfocusedBorderColor = Color(0xFF4C4560)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "取消", color = Color.Gray)
                    }
                    Button(
                        onClick = {
                            onSelectVoiceMode(selectedVoiceMode)
                            onSelectBubbleMode(selectedBubbleMode)
                            onUpdateServerUrl(serverInput.trim())
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5277)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "保存并应用")
                    }
                }
            }
        }
    }
}
