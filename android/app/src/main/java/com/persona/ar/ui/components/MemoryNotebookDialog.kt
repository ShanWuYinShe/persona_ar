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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
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
import com.persona.ar.data.model.MemoryItem
import com.persona.ar.util.MemoryCategoryMapper

/**
 * 纸片人专属情感手账本组件 (MemoryNotebookDialog)
 * 采用 Galgame/二游精致质感手账设计，包含记忆分类胶囊、重要度星星评价、回忆实时录入与删除。
 * 遵循专业移动端规约：全矢量图标，绝不使用 Emoji 字符代替图标。
 */
@Composable
fun MemoryNotebookDialog(
    partnerName: String,
    bondLevel: Int,
    bondTitle: String,
    memories: List<MemoryItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onCreateMemory: (content: String, category: String, importance: Int) -> Unit,
    onDeleteMemory: (String) -> Unit
) {
    val borderColor = Color(0xFF281C38)
    val surfaceCream = Color(0xFFFFFDF9)
    val brandPink = Color(0xFFFF4E8E)
    val brandGold = Color(0xFFFFB300)
    val brandPurple = Color(0xFF8E24AA)

    var selectedCategoryFilter by remember { mutableStateOf("all") }
    var isWritingNewMemory by remember { mutableStateOf(false) }
    var inputContent by remember { mutableStateOf("") }
    var inputCategory by remember { mutableStateOf("daily") }
    var inputImportance by remember { mutableIntStateOf(3) }

    val categories = listOf(
        "all" to "全部记忆",
        "daily" to "日常",
        "pledge" to "誓约",
        "emotion" to "情感",
        "event" to "约定"
    )

    val filteredMemories = remember(memories, selectedCategoryFilter) {
        memories.filter { MemoryCategoryMapper.matchesFilter(it.category, selectedCategoryFilter) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(26.dp))
                .background(surfaceCream)
                .border(2.dp, borderColor, RoundedCornerShape(26.dp))
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部栏：标题与关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(brandPink)
                                .border(1.5.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.HistoryEdu,
                                contentDescription = "手账",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "心语回忆手账",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                color = borderColor
                            )
                            Text(
                                text = "与 $partnerName 的第 ${memories.size} 篇记忆回响",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFFEFE6D8), CircleShape)
                            .border(1.5.dp, borderColor, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = borderColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 羁绊与重要度统计横幅
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFECB3))
                        .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = brandPink,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Lv.$bondLevel $bondTitle",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = borderColor
                        )
                    }
                    Text(
                        text = "共收录 ${memories.size} 条长期记忆",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6D4C41)
                    )
                }

                // 分类筛选 Chip 栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { (key, label) ->
                        val isSelected = selectedCategoryFilter == key
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) brandPurple else Color(0xFFF3ECE0))
                                .border(
                                    1.2.dp,
                                    if (isSelected) borderColor else Color.LightGray,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedCategoryFilter = key }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else borderColor
                            )
                        }
                    }
                }

                // 新建手账折叠/展开区域
                if (isWritingNewMemory) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDF7EE))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "镌刻新回忆",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = borderColor
                                )
                                Text(
                                    text = "取消",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.clickable { isWritingNewMemory = false }
                                )
                            }

                            OutlinedTextField(
                                value = inputContent,
                                onValueChange = { inputContent = it },
                                placeholder = { Text("记下和 ${partnerName} 难忘的瞬间...", fontSize = 12.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 72.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                            )

                            // 分类选择与星级评定
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 分类选择
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("daily" to "日常", "pledge" to "誓约", "event" to "约定").forEach { (catKey, catName) ->
                                        val active = inputCategory == catKey
                                        Text(
                                            text = catName,
                                            fontSize = 10.sp,
                                            color = if (active) Color.White else borderColor,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (active) brandPink else Color(0xFFE8DFD0))
                                                .clickable { inputCategory = catKey }
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                // 重要度星星
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (1..5).forEach { star ->
                                        Icon(
                                            imageVector = Icons.Filled.Star,
                                            contentDescription = null,
                                            tint = if (star <= inputImportance) brandGold else Color.LightGray,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { inputImportance = star }
                                        )
                                    }
                                }
                            }

                            // 保存按钮
                            Button(
                                onClick = {
                                    if (inputContent.isNotBlank()) {
                                        onCreateMemory(inputContent.trim(), inputCategory, inputImportance)
                                        inputContent = ""
                                        isWritingNewMemory = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .border(1.5.dp, borderColor, RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = brandPink),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("镌刻入心 (+15 羁绊经验)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { isWritingNewMemory = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFFFF0F5))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = brandPink,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("手动记录一段新心事 / 约定", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = brandPink)
                    }
                }

                // 记忆列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = brandPink, modifier = Modifier.size(32.dp))
                    }
                } else if (filteredMemories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = Color.LightGray,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "手账空空如也，和她多多畅聊或写下第一篇吧~",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredMemories, key = { it.id }) { memory ->
                            MemoryCardItem(
                                memory = memory,
                                borderColor = borderColor,
                                brandPink = brandPink,
                                brandGold = brandGold,
                                onDelete = { onDeleteMemory(memory.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCardItem(
    memory: MemoryItem,
    borderColor: Color,
    brandPink: Color,
    brandGold: Color,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 分类标签（兼容后端 USER_FACT / SHARED_MOMENT / EMOTIONAL_PROMISE）
                    val badgeStyle = MemoryCategoryMapper.resolveBadgeStyle(memory.category)
                    val catColor = when (badgeStyle) {
                        MemoryCategoryMapper.BadgeStyle.PLEDGE -> brandPink
                        MemoryCategoryMapper.BadgeStyle.EMOTION -> Color(0xFFAB47BC)
                        MemoryCategoryMapper.BadgeStyle.EVENT -> Color(0xFF26A69A)
                        MemoryCategoryMapper.BadgeStyle.DAILY -> Color(0xFF5C6BC0)
                    }
                    Text(
                        text = badgeStyle.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(catColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    // 重要度星级
                    Row {
                        repeat(memory.importance.coerceIn(1, 5)) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = brandGold,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                // 删除按钮（保持 ≥44dp 触控热区）
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "删除记忆",
                        tint = Color(0xFF9E8EA8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 记忆正文
            Text(
                text = memory.content,
                fontSize = 13.sp,
                color = borderColor,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp
            )

            // 记录时间戳
            if (memory.timestamp > 0.0) {
                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date((memory.timestamp * 1000).toLong()))
                Text(
                    text = timeStr,
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
