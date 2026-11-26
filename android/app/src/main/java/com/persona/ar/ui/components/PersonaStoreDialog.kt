package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.persona.ar.data.model.PersonaModelManifest

/**
 * 二游风格的纸片人图鉴与契约工坊弹窗
 * 严守专业规范：全矢量图标，绝不使用系统 Emoji 字符代替图标。
 */
@Composable
fun PersonaStoreDialog(
    activeModel: PersonaModelManifest?,
    installedModels: List<PersonaModelManifest>,
    storeModels: List<PersonaModelManifest>,
    onDismiss: () -> Unit,
    onSwitchModel: (String) -> Unit,
    onInstallModel: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 我的伙伴, 1: 契约工坊
    val borderColor = Color(0xFF281C38)
    val surfaceCream = Color(0xFFFFFDF9)

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(26.dp))
                .background(surfaceCream)
                .border(2.dp, borderColor, RoundedCornerShape(26.dp))
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 顶部标题与矢量关闭按钮
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
                                .background(Color(0xFFFFB300))
                                .border(1.5.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = borderColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "纸片人图鉴 · 契约工坊",
                                color = borderColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "自由唤醒与邂逅你的专属 AR 纸片人伙伴",
                                color = Color(0xFF8B7B99),
                                fontSize = 10.sp
                            )
                        }
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

                // 切换 Tab (矢量风格切换按键)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF0E8F8))
                        .border(1.5.dp, borderColor.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(3.dp)
                ) {
                    GameStoreTab(
                        text = "驻留伙伴 (${installedModels.size})",
                        isSelected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    GameStoreTab(
                        text = "契约工坊商城",
                        isSelected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 模型列表流 (二游卡牌流)
                val displayList = if (selectedTab == 0) installedModels else storeModels
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayList, key = { it.id }) { item ->
                        val isCurrentActive = activeModel?.id == item.id
                        GamePersonaCard(
                            model = item,
                            isActive = isCurrentActive,
                            onUseClick = { onSwitchModel(item.id) },
                            onInstallClick = { onInstallModel(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameStoreTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = Color(0xFF281C38)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (isSelected) Color(0xFFFF5277) else Color.Transparent)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) borderColor else Color.Transparent,
                shape = RoundedCornerShape(11.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else Color(0xFF5A4D6B),
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
        )
    }
}

@Composable
private fun GamePersonaCard(
    model: PersonaModelManifest,
    isActive: Boolean,
    onUseClick: () -> Unit,
    onInstallClick: () -> Unit
) {
    val borderColor = Color(0xFF281C38)
    val modelColor = try {
        Color(android.graphics.Color.parseColor(model.previewColorHex))
    } catch (e: Exception) {
        Color(0xFFFF7597)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isActive) Color(0xFFFFF0F5) else Color.White)
            .border(
                width = if (isActive) 2.dp else 1.2.dp,
                color = if (isActive) Color(0xFFFF5277) else borderColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 角色头像圆框
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(modelColor.copy(alpha = 0.2f))
                    .border(2.dp, modelColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = model.name.take(1),
                    color = modelColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 角色基本信息与属性标签
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        color = borderColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // 3D 模型标签徽章
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE1F5FE))
                            .border(1.dp, Color(0xFF00B0FF), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "3D手办",
                            color = Color(0xFF0288D1),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Text(
                    text = "画师: ${model.author} · v${model.version}",
                    color = Color(0xFF9080A0),
                    fontSize = 10.sp
                )

                Text(
                    text = model.description,
                    color = Color(0xFF554466),
                    fontSize = 11.sp,
                    maxLines = 2,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // 评分与标签栏（矢量五角星）
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "评分",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "${model.rating} (${model.downloadsCount})",
                        color = Color(0xFFFF8F00),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp, end = 6.dp)
                    )
                    Text(
                        text = model.tags.take(2).joinToString(" · "),
                        color = Color(0xFF887799),
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 状态/操作按钮（全矢量组件）
            if (isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE8F5E9))
                        .border(1.5.dp, Color(0xFF00C853), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF00C853),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "陪伴中",
                            color = Color(0xFF00C853),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            } else if (model.isInstalled) {
                Button(
                    onClick = onUseClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B61FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(text = "唤醒", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            } else {
                Button(
                    onClick = onInstallClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5277)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(text = "获取", fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
