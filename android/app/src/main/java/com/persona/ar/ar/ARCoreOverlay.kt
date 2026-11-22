package com.persona.ar.ar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.character.CharacterCanvasRenderer
import com.persona.ar.character.CharacterVisualState
import com.persona.ar.data.model.TouchPart

/**
 * 完整 ARCore 空间增强层与物理平面锚定组件
 * 包含：平面扫描点阵、贴地准心 (Reticle)、自适应柔和接触阴影、手势位姿操控与状态指示胶囊
 */
@Composable
fun ARCoreOverlay(
    trackingStatus: ARTrackingStatus,
    spatialAnchor: ARSpatialAnchor,
    visualState: CharacterVisualState,
    partnerName: String,
    onTapPlace: (screenX: Float, screenY: Float) -> Unit,
    onTransformGesture: (scaleMultiplier: Float, rotationYawDelta: Float) -> Unit,
    onResetAnchor: () -> Unit,
    onTouchPart: (TouchPart) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reticlePulse"
    )

    val reticleRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reticleRotation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(spatialAnchor.isAnchored) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    if (spatialAnchor.isAnchored) {
                        onTransformGesture(zoom, pan.x * 0.4f)
                    }
                }
            }
    ) {
        // 1. 物理平面网格与接触阴影绘制层
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change != null && change.pressed && !spatialAnchor.isAnchored) {
                                val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                                val normY = (change.position.y / size.height).coerceIn(0f, 1f)
                                onTapPlace(normX, normY)
                                change.consume()
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // A. 未放置角色时：绘制地面贴地引导准心 (Reticle)
            if (!spatialAnchor.isAnchored) {
                val rx = w * 0.5f
                val ry = h * 0.65f
                val baseRadius = 55f * pulseScale

                // 贴地椭圆准心（带透视压扁效果）
                drawOval(
                    color = Color(0x3300E5FF),
                    topLeft = Offset(rx - baseRadius * 1.4f, ry - baseRadius * 0.5f),
                    size = androidx.compose.ui.geometry.Size(baseRadius * 2.8f, baseRadius),
                    style = Stroke(width = 2.5f)
                )

                rotate(degrees = reticleRotation, pivot = Offset(rx, ry)) {
                    drawCircle(
                        color = Color(0x6600E5FF),
                        radius = baseRadius * 0.7f,
                        center = Offset(rx, ry),
                        style = Stroke(width = 2f)
                    )
                }

                // 中心定位点
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 4f,
                    center = Offset(rx, ry)
                )
            } else {
                // B. 已放置角色时：在真实平面绘制根据环境光强衰减的柔和接触阴影 (Contact Shadow)
                val ax = w * spatialAnchor.screenX
                val ay = h * spatialAnchor.screenY + (140f * spatialAnchor.scaleFactor)
                val shadowWidth = 160f * spatialAnchor.scaleFactor
                val shadowHeight = 45f * spatialAnchor.scaleFactor
                val alpha = (0.45f * spatialAnchor.lightIntensity).coerceIn(0.15f, 0.55f)

                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = alpha),
                            Color.Black.copy(alpha = alpha * 0.5f),
                            Color.Transparent
                        ),
                        center = Offset(ax, ay),
                        radius = shadowWidth * 0.8f
                    ),
                    topLeft = Offset(ax - shadowWidth * 0.5f, ay - shadowHeight * 0.5f),
                    size = androidx.compose.ui.geometry.Size(shadowWidth, shadowHeight)
                )
            }
        }

        // 2. 纸片人实体立绘投射 (带空间位姿与距离缩放)
        if (spatialAnchor.isAnchored) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(
                        x = ((spatialAnchor.screenX - 0.5f) * 300).dp,
                        y = ((spatialAnchor.screenY - 0.5f) * 200).dp
                    )
            ) {
                CharacterCanvasRenderer(
                    visualState = visualState.copy(scale = spatialAnchor.scaleFactor),
                    onTouchPart = onTouchPart,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. 顶部 AR 状态胶囊与引导提示 (Spatial Guidance Pill)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (trackingStatus) {
                ARTrackingStatus.INITIALIZING -> {
                    ARGuidePill(
                        icon = Icons.Filled.NearMe,
                        text = "正在启动空间相机与 ARCore 追踪...",
                        bgColor = Color(0xE6281C38)
                    )
                }
                ARTrackingStatus.SEARCHING_PLANE -> {
                    ARGuidePill(
                        icon = Icons.Filled.CenterFocusStrong,
                        text = "正在扫描平坦桌面，请缓慢平移镜头...",
                        bgColor = Color(0xE6281C38)
                    )
                }
                ARTrackingStatus.PLANE_DETECTED -> {
                    ARGuidePill(
                        icon = Icons.Filled.TouchApp,
                        text = "已发现平坦桌面！轻触屏幕放置 $partnerName ✨",
                        bgColor = Color(0xE60091EA)
                    )
                }
                ARTrackingStatus.ANCHORED -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ARGuidePill(
                            icon = Icons.Filled.ViewInAr,
                            text = "已锚定于${spatialAnchor.planeTypeDescription} (${spatialAnchor.distanceMeters}m) · 双指可旋转缩放",
                            bgColor = Color(0xE61B5E20)
                        )
                        // 一键复位按钮
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(0xE6281C38))
                                .border(1.2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                .clickable { onResetAnchor() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "重新放置",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                ARTrackingStatus.TRACKING_LOST -> {
                    ARGuidePill(
                        icon = Icons.Filled.CenterFocusStrong,
                        text = "空间特征丢失，请对准光线充足的桌面重新追踪",
                        bgColor = Color(0xE6C62828)
                    )
                }
            }
        }
    }
}

@Composable
private fun ARGuidePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    bgColor: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
