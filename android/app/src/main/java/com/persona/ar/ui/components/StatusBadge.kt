package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.ar.ARMode
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus

/**
 * 极简通透的二游顶部 HUD 状态栏
 * 包含萌系羁绊经验条（Bond EXP Bar）与手账快捷入口，纯矢量图标。
 */
@Composable
fun StatusBadge(
    status: PersonaStatus?,
    activeModel: PersonaModelManifest?,
    arMode: ARMode,
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
    val borderColor = Color(0xFF281C38)
    val progress = (bondExp.toFloat() / bondExpNext.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：极简萌系角色身份与经验胶囊（点击可呼出回忆手账）
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6FFFDF9)) // 90% 不透明奶油暖白
                .border(1.5.dp, borderColor, RoundedCornerShape(18.dp))
                .clickable { onOpenMemoryNotebook() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 心情表情矢量小图标
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFE0E8)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SentimentVerySatisfied,
                        contentDescription = "角色心情",
                        tint = Color(0xFFFF5277),
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 角色名与等级称号
                Text(
                    text = "$charName · Lv.$bondLevel",
                    color = borderColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 好感爱心矢量图标
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "好感度",
                    tint = Color(0xFFFF5277),
                    modifier = Modifier.size(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // 萌系羁绊经验条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(100.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFE8DFD0))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFF4E8E))
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$bondExp/$bondExpNext",
                    fontSize = 8.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 右侧：AR 模式秒切、手账快捷入口、工坊快捷按钮与设置入口（纯矢量图标）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // AR 现实模式 / 3D 纯净桌面 快速切换快捷键
            val isARActive = arMode == ARMode.AR_CAMERA
            IconButton(
                onClick = onToggleARMode,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isARActive) Color(0xFFFF5277) else Color(0xE6FFFDF9))
                    .border(1.5.dp, borderColor, CircleShape)
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
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xE6FFFDF9))
                    .border(1.5.dp, borderColor, CircleShape)
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
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xE6FFFDF9))
                    .border(1.5.dp, borderColor, CircleShape)
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
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xE6FFFDF9))
                    .border(1.5.dp, borderColor, CircleShape)
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
