package com.persona.ar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.ar.ARMode
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus

/**
 * 卡通游戏风格（RPG Game HUD）顶部状态栏
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
    val progress = (affinity % 20) / 20f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：RPG 萌系角色羁绊经验卡（立体厚底卡通贴纸风）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFFFFDF9)) // 奶油暖白底
                .border(2.5.dp, Color(0xFF281C38), RoundedCornerShape(22.dp)) // 饱满卡通深紫黑描边
                .clickable { onOpenStore() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 角色头像圆徽章（带金黄色描边）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFD54F))
                    .border(2.dp, Color(0xFF281C38), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = charName.take(1),
                    color = Color(0xFF281C38),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Lv 等级标签
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFF5277))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Lv.$level",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = charName,
                        color = Color(0xFF281C38),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                // 好感度经验进度条 (EXP Bar)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(84.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE8DEF8))
                            .border(1.dp, Color(0xFF281C38), RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0.1f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFFFF80AB), Color(0xFFFF4081))
                                    )
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = status?.mood ?: "元气",
                        color = Color(0xFF7E57C2),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 右侧卡通游戏操作胶囊群
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 角色工坊/换装按钮（粉色卡通圆形贴纸）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF80AB))
                    .border(2.dp, Color(0xFF281C38), CircleShape)
                    .clickable { onOpenStore() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = "模型工坊",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 模式切换胶囊（果汁蓝/紫立体胶囊）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (arMode == ARMode.BILLBOARD_2_5D)
                            Color(0xFF7E57C2)
                        else
                            Color(0xFF00B0FF)
                    )
                    .border(2.dp, Color(0xFF281C38), RoundedCornerShape(18.dp))
                    .clickable { onToggleARMode() }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = "切换AR模式",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (arMode == ARMode.BILLBOARD_2_5D) "2.5D立绘" else "3D空间",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // 卡通齿轮设置按钮
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFD54F))
                    .border(2.dp, Color(0xFF281C38), CircleShape)
                    .clickable { onOpenSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "打开设置",
                    tint = Color(0xFF281C38),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
