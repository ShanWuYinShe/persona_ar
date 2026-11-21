package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun SettingsDialog(
    currentMode: ARMode,
    currentServerUrl: String,
    onDismiss: () -> Unit,
    onToggleMode: () -> Unit,
    onUpdateServerUrl: (String) -> Unit
) {
    var serverInput by remember { mutableStateOf(currentServerUrl) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF262233))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "系统与投射设置",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                // 1. AR 呈现模式
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

                // 2. 情感后端服务配置
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
                    Text(
                        text = "提示: 模拟器请用 10.0.2.2，真机请填电脑在同一个局域网下的 IP 地址",
                        color = Color(0xFF888899),
                        fontSize = 10.sp
                    )
                }

                // 3. 操作帮助
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1A29))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "交互手势说明：", color = Color(0xFFFFD1DC), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = "• 单指拖动: 随意摆放纸片人在桌上或地面的位置", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                    Text(text = "• 双指捏合: 调整纸片人大小比例", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                    Text(text = "• 点击头部/面颊/双手: 触发触碰情感反应", color = Color(0xFFCCCCCC), fontSize = 10.sp)
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
