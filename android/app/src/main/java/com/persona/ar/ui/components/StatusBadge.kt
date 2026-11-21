package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SentimentVerySatisfied
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
 * 严守专业规范：全矢量图标，绝不使用系统 Emoji 字符代替图标，保持跨端渲染精致统一。
 */
@Composable
fun StatusBadge(
    status: PersonaStatus?,
    activeModel: PersonaModelManifest?,
    arMode: ARMode,
    onToggleARMode: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val charName = activeModel?.name ?: status?.name ?: "星野"
    val affinity = status?.affinity ?: 15
    val level = (affinity / 20) + 1
    val borderColor = Color(0xFF281C38)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：极简萌系角色身份胶囊（点击可呼出角色工坊/属性详情）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE6FFFDF9)) // 90% 不透明奶油暖白
                .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
                .clickable { onOpenStore() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 心情表情矢量小图标
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFE0E8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SentimentVerySatisfied,
                    contentDescription = "角色心情",
                    tint = Color(0xFFFF5277),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 角色名与等级
            Text(
                text = "$charName · Lv.$level",
                color = borderColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.width(6.dp))

            // 好感爱心矢量图标
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "好感度",
                tint = Color(0xFFFF5277),
                modifier = Modifier.size(14.dp)
            )
        }

        // 右侧：仅保留设置入口与工坊快捷按钮（纯矢量图标）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
