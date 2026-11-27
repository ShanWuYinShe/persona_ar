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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.BubbleDisplayMode
import com.persona.ar.data.model.VoiceMode

/**
 * 卡通二游风格的系统与交互设置公告板
 * 严守专业规范：全矢量图标，绝不使用系统 Emoji 字符代替图标。
 */
@Composable
fun SettingsDialog(
    partnerName: String = "星野",
    currentStance: SkeletonStance = SkeletonStance.STANDING,
    currentVoiceMode: VoiceMode,
    currentBubbleMode: BubbleDisplayMode,
    currentServerUrl: String,
    isServerReachable: Boolean = false,
    isServerProbing: Boolean = false,
    serverProbeOk: Boolean? = null,
    serverProbeStatus: String? = null,
    currentLlmProvider: String = "mock",
    currentLlmBaseUrl: String = "https://api.deepseek.com",
    currentLlmApiKey: String = "",
    currentLlmModel: String = "deepseek-chat",
    currentAsrProvider: String = "mock",
    currentAsrBaseUrl: String = "https://api.openai.com/v1",
    currentAsrApiKey: String = "",
    currentAsrModel: String = "whisper-1",
    onDismiss: () -> Unit,
    onSelectVoiceMode: (VoiceMode) -> Unit,
    onSelectBubbleMode: (BubbleDisplayMode) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onTestServerConnection: (serverUrl: String, llmProvider: String, llmBaseUrl: String, llmApiKey: String, llmModel: String) -> Unit = { _, _, _, _, _ -> },
    onUpdateLlmConfig: (provider: String, baseUrl: String, apiKey: String, model: String) -> Unit = { _, _, _, _ -> },
    onUpdateAsrConfig: (provider: String, baseUrl: String, apiKey: String, model: String) -> Unit = { _, _, _, _ -> },
    /** 点击后立即触发 3D 手办动作照出（无需等待对话） */
    onPreviewAction: (ActionType) -> Unit = {},
    /** 直接切换到站立姿态 */
    onStand: () -> Unit = {},
    /** 直接切换到桌沿垂腿坐姿 */
    onSit: () -> Unit = {}
) {
    var serverInput by remember { mutableStateOf(currentServerUrl) }
    var selectedStance by remember(currentStance) { mutableStateOf(currentStance) }
    var selectedVoiceMode by remember { mutableStateOf(currentVoiceMode) }
    var selectedBubbleMode by remember { mutableStateOf(currentBubbleMode) }
    var llmProvider by remember { mutableStateOf(currentLlmProvider) }
    var llmBaseUrl by remember { mutableStateOf(currentLlmBaseUrl) }
    var llmApiKey by remember { mutableStateOf(currentLlmApiKey) }
    var llmModel by remember { mutableStateOf(currentLlmModel) }
    var showLlmApiKey by remember { mutableStateOf(false) }
    var asrProvider by remember { mutableStateOf(currentAsrProvider) }
    var asrBaseUrl by remember { mutableStateOf(currentAsrBaseUrl) }
    var asrApiKey by remember { mutableStateOf(currentAsrApiKey) }
    var asrModel by remember { mutableStateOf(currentAsrModel) }
    var showAsrApiKey by remember { mutableStateOf(false) }

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
                            "• 连麦模式: 开启监听后，${partnerName}说话期间你随时开口即可瞬间掐断播音，静音自动断句发送"
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
                            BubbleDisplayMode.HIDDEN -> "• 纯净模式: 零文字遮挡，完全凭借${partnerName}的神态动作与萌音交流"
                        },
                        color = Color(0xFF6B587B),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                // 3. 3D 手办动作演出预览（同时也是真机动作回归验证入口）
                SectionContainer(
                    icon = Icons.Filled.AutoAwesome,
                    title = "3D 动作演出预览",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "触发动作后可点右侧「收起观赏」或主屏幕左下「动作」按钮全屏观赏。",
                            color = Color(0xFF6B587B),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GameMiniChip(
                            title = "收起观赏",
                            isSelected = false,
                            activeColor = Color(0xFFFF4E8E),
                            onClick = onDismiss,
                            horizontalPadding = 10.dp
                        )
                    }
                    val actionRows = listOf(
                        listOf(
                            "挥手" to ActionType.WAVE,
                            "点头" to ActionType.NOD,
                            "摇头" to ActionType.SHAKE_HEAD,
                            "鞠躬" to ActionType.BOW
                        ),
                        listOf(
                            "跳跃" to ActionType.JUMP,
                            "噘嘴" to ActionType.POUT,
                            "害羞" to ActionType.BLUSH,
                            "打哈欠" to ActionType.YAWN
                        ),
                        listOf(
                            "打瞌睡" to ActionType.SLEEP,
                            "得意" to ActionType.SMUG,
                            "待机" to ActionType.IDLE
                        )
                    )
                    for (row in actionRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((label, action) in row) {
                                GameMiniChip(
                                    title = label,
                                    isSelected = false,
                                    activeColor = Color(0xFF00B0FF),
                                    onClick = { onPreviewAction(action) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GameOptionChip(
                            title = "站立姿态",
                            subtitle = "站直在桌面",
                            isSelected = selectedStance == SkeletonStance.STANDING,
                            activeColor = Color(0xFF00B0FF),
                            onClick = {
                                selectedStance = SkeletonStance.STANDING
                                onStand()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GameOptionChip(
                            title = "桌沿垂腿坐",
                            subtitle = "坐在桌面边缘",
                            isSelected = selectedStance == SkeletonStance.SITTING,
                            activeColor = Color(0xFFE65100),
                            onClick = {
                                selectedStance = SkeletonStance.SITTING
                                onSit()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 4. 后端 API 地址与连通性测试
                SectionContainer(
                    icon = Icons.Filled.Dns,
                    title = "情感中枢服务器地址",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val effectiveOnline = serverProbeOk ?: isServerReachable
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (effectiveOnline) Color(0xFF00C853) else Color(0xFF9E8EA8))
                            )
                            Text(
                                text = if (effectiveOnline) "服务在线" else "离线 / 端侧陪伴模式",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (effectiveOnline) Color(0xFF2E7D32) else Color(0xFF7A6888)
                            )
                        }
                        GameMiniChip(
                            title = if (isServerProbing) "探测中..." else "测试连接",
                            isSelected = false,
                            activeColor = Color(0xFF00B0FF),
                            onClick = {
                                if (!isServerProbing) {
                                    onTestServerConnection(serverInput, llmProvider, llmBaseUrl, llmApiKey, llmModel)
                                }
                            },
                            horizontalPadding = 10.dp
                        )
                    }

                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        textStyle = LocalTextStyle.current.copy(
                            color = borderColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
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

                    serverProbeStatus?.let { probeMsg ->
                        Text(
                            text = probeMsg,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 14.sp,
                            color = when (serverProbeOk) {
                                true -> Color(0xFF2E7D32)
                                false -> Color(0xFFC62828)
                                null -> Color(0xFF6B587B)
                            }
                        )
                    }
                }

                // 5. 大语言模型对话引擎配置 (LLM API)
                SectionContainer(
                    icon = Icons.Filled.Psychology,
                    title = "大模型大脑引擎配置 (LLM API)",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Text(
                        text = "支持接入 DeepSeek、OpenAI、Ollama 本地私有模型等处理情感对话。",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        lineHeight = 14.sp
                    )

                    // 厂商预置选择（先判定是否 mock，避免残余 URL 导致双选中）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("DeepSeek", "OpenAI", "Ollama", "系统内置").forEach { pName ->
                            val isSel = when (pName) {
                                "DeepSeek" -> llmProvider != "mock" && llmBaseUrl.contains("deepseek", ignoreCase = true)
                                "OpenAI" -> llmProvider != "mock" && llmBaseUrl.contains("openai", ignoreCase = true)
                                "Ollama" -> llmProvider == "ollama" || (llmProvider != "mock" && llmBaseUrl.contains("11434"))
                                else -> llmProvider == "mock"
                            }
                            GameMiniChip(
                                title = pName,
                                isSelected = isSel,
                                activeColor = Color(0xFF673AB7),
                                onClick = {
                                    when (pName) {
                                        "DeepSeek" -> {
                                            llmProvider = "openai"
                                            llmBaseUrl = "https://api.deepseek.com"
                                            llmModel = "deepseek-chat"
                                        }
                                        "OpenAI" -> {
                                            llmProvider = "openai"
                                            llmBaseUrl = "https://api.openai.com"
                                            llmModel = "gpt-4o-mini"
                                        }
                                        "Ollama" -> {
                                            llmProvider = "ollama"
                                            llmBaseUrl = "http://10.0.2.2:11434"
                                            llmModel = "qwen2.5:7b"
                                        }
                                        else -> {
                                            llmProvider = "mock"
                                            llmBaseUrl = ""
                                            llmModel = "builtin-heuristic"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (llmProvider != "mock") {
                        // API 密钥输入（默认掩码遮罩 + 显隐切换）
                        OutlinedTextField(
                            value = llmApiKey,
                            onValueChange = { llmApiKey = it },
                            label = { Text("API Key 密钥", fontSize = 10.sp) },
                            singleLine = true,
                            visualTransformation = if (showLlmApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showLlmApiKey = !showLlmApiKey }) {
                                    Icon(
                                        imageVector = if (showLlmApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showLlmApiKey) "隐藏密钥" else "显示密钥",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Base URL
                        OutlinedTextField(
                            value = llmBaseUrl,
                            onValueChange = { llmBaseUrl = it },
                            label = { Text("API 端点地址 (Base URL)", fontSize = 10.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 模型名称
                        OutlinedTextField(
                            value = llmModel,
                            onValueChange = { llmModel = it },
                            label = { Text("模型标识 (Model Name)", fontSize = 10.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 5. 语音识别 (ASR) 引擎配置
                SectionContainer(
                    icon = Icons.Filled.Mic,
                    title = "语音识别 (ASR) 引擎设置",
                    sectionBg = sectionBg,
                    borderColor = borderColor
                ) {
                    Text(
                        text = "选择语音识别服务商，支持 OpenAI Whisper 官方、Groq 极速转写或内置离线智能降级。",
                        fontSize = 11.sp,
                        color = Color(0xFF6E5E80),
                        lineHeight = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GameMiniChip(
                            title = "Whisper 官方",
                            isSelected = asrProvider == "whisper",
                            activeColor = Color(0xFF00B0FF),
                            onClick = {
                                asrProvider = "whisper"
                                asrBaseUrl = "https://api.openai.com/v1"
                                asrModel = "whisper-1"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GameMiniChip(
                            title = "Groq 极速",
                            isSelected = asrProvider == "groq",
                            activeColor = Color(0xFFFF9100),
                            onClick = {
                                asrProvider = "groq"
                                asrBaseUrl = "https://api.groq.com/openai/v1"
                                asrModel = "whisper-large-v3"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GameMiniChip(
                            title = "内置降级",
                            isSelected = asrProvider == "mock",
                            activeColor = Color(0xFF7B61FF),
                            onClick = {
                                asrProvider = "mock"
                                asrBaseUrl = ""
                                asrModel = "heuristic-mock"
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (asrProvider != "mock") {
                        OutlinedTextField(
                            value = asrBaseUrl,
                            onValueChange = { asrBaseUrl = it },
                            label = { Text("ASR API Base URL", fontSize = 10.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = asrApiKey,
                            onValueChange = { asrApiKey = it },
                            label = { Text("ASR API Key", fontSize = 10.sp) },
                            singleLine = true,
                            visualTransformation = if (showAsrApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAsrApiKey = !showAsrApiKey }) {
                                    Icon(
                                        imageVector = if (showAsrApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showAsrApiKey) "隐藏密钥" else "显示密钥",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = asrModel,
                            onValueChange = { asrModel = it },
                            label = { Text("ASR 模型标识 (Model Name)", fontSize = 10.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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
                            onUpdateLlmConfig(llmProvider, llmBaseUrl.trim(), llmApiKey.trim(), llmModel.trim())
                            onUpdateAsrConfig(asrProvider, asrBaseUrl.trim(), asrApiKey.trim(), asrModel.trim())
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
            .heightIn(min = 44.dp)
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
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = 2.dp
) {
    val borderColor = Color(0xFF281C38)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) activeColor else Color.White)
            .border(
                width = if (isSelected) 1.8.dp else 1.2.dp,
                color = if (isSelected) borderColor else borderColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
            color = if (isSelected) Color.White else Color(0xFF483A5A),
            maxLines = 1
        )
    }
}