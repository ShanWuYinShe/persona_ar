package com.persona.ar.ar

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.ui.geometry.Size
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 零 GMS 依赖的通用 AR 空间实景增强层
 * 包含：二游契约魔法阵 (Summoning Circle)、视差贴地准心 (Reticle)、自适应柔和接触阴影与全手势位姿操控
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
        initialValue = 0.9f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reticlePulse"
    )

    val runeRotationCW by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "runeRotationCW"
    )

    val runeRotationCCW by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "runeRotationCCW"
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
        // 1. 物理平面网格、契约法阵与接触阴影绘制层
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

            // A. 未放置角色时：绘制契约召唤魔法阵与贴地视差准心 (Summoning Circle & Reticle)
            if (!spatialAnchor.isAnchored) {
                val rx = w * 0.5f
                val ry = h * 0.65f
                val baseRadius = 60f * pulseScale

                // 1) 贴地透视压扁外环 (45° 俯视视差透视)
                drawOval(
                    color = Color(0x4400E5FF),
                    topLeft = Offset(rx - baseRadius * 1.5f, ry - baseRadius * 0.55f),
                    size = Size(baseRadius * 3.0f, baseRadius * 1.1f),
                    style = Stroke(width = 2.5f)
                )

                // 2) 顺时针旋转刻度圈
                rotate(degrees = runeRotationCW, pivot = Offset(rx, ry)) {
                    for (i in 0 until 12) {
                        val angle = (i * 30f) * PI / 180.0
                        val tickLen = if (i % 3 == 0) 8f else 4f
                        val ox = rx + (cos(angle) * baseRadius * 1.35f).toFloat()
                        val oy = ry + (sin(angle) * baseRadius * 0.5f).toFloat()
                        drawCircle(
                            color = Color(0x8800E5FF),
                            radius = tickLen * 0.4f,
                            center = Offset(ox, oy)
                        )
                    }
                }

                // 3) 逆时针旋转内圈星芒阵法
                rotate(degrees = runeRotationCCW, pivot = Offset(rx, ry)) {
                    drawOval(
                        color = Color(0x66FF5277),
                        topLeft = Offset(rx - baseRadius * 0.9f, ry - baseRadius * 0.35f),
                        size = Size(baseRadius * 1.8f, baseRadius * 0.7f),
                        style = Stroke(width = 1.8f)
                    )
                }

                // 4) 核心定位聚焦星点
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 4.5f,
                    center = Offset(rx, ry)
                )
            } else {
                // B. 已放置角色时：在真实桌面绘制柔和接触阴影 (Soft Contact Shadow) 与微光契约印记
                val ax = w * spatialAnchor.screenX + spatialAnchor.parallaxShiftX
                val ay = h * spatialAnchor.screenY + (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY
                val shadowWidth = 165f * spatialAnchor.scaleFactor
                val shadowHeight = 48f * spatialAnchor.scaleFactor
                val envLight = spatialAnchor.lightIntensity.coerceIn(0.25f, 1.25f)
                val alpha = (0.50f * envLight).coerceIn(0.16f, 0.58f)
                val shadowCenterColor = if (spatialAnchor.isLowLight) Color(0x990A0414) else Color.Black.copy(alpha = alpha)

                // 柔和椭圆环境光衰减阴影 (自然融合摄像头采集的现实桌面背景纹理)
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            shadowCenterColor,
                            shadowCenterColor.copy(alpha = alpha * 0.45f),
                            Color.Transparent
                        ),
                        center = Offset(ax, ay),
                        radius = shadowWidth * 0.85f
                    ),
                    topLeft = Offset(ax - shadowWidth * 0.5f, ay - shadowHeight * 0.5f),
                    size = Size(shadowWidth, shadowHeight)
                )

                // 伴侣脚下若隐若现的二游契约微光光环 (现实弱光下呈现明亮辉光，强光下呈现通透水晶光)
                val ringAlpha = if (spatialAnchor.isLowLight) 0.35f else 0.18f
                drawOval(
                    color = Color(0x00E5FF).copy(alpha = ringAlpha),
                    topLeft = Offset(ax - shadowWidth * 0.45f, ay - shadowHeight * 0.45f),
                    size = Size(shadowWidth * 0.9f, shadowHeight * 0.9f),
                    style = Stroke(width = 1.5f)
                )

                // 现实桌面透视空间微光切面网格 (强化物理桌面深度贴合)
                val gridColor = Color(0x00E5FF).copy(alpha = 0.12f)
                val gridW = shadowWidth * 1.6f
                val gridH = shadowHeight * 2.2f
                for (step in -3..3) {
                    val lineY = ay + (step * (gridH / 7f))
                    val shrink = 1.0f - (step * 0.06f)
                    drawLine(
                        color = gridColor,
                        start = Offset(ax - (gridW * 0.5f * shrink), lineY),
                        end = Offset(ax + (gridW * 0.5f * shrink), lineY),
                        strokeWidth = 1.2f
                    )
                }
            }
        }

        // 2. 纸片人实体立绘投射 (融合视差微位移与手势位姿)
        if (spatialAnchor.isAnchored) {
            val offsetX = ((spatialAnchor.screenX - 0.5f) * 300f + spatialAnchor.parallaxShiftX).dp
            val offsetY = ((spatialAnchor.screenY - 0.5f) * 200f + spatialAnchor.parallaxShiftY).dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = offsetX, y = offsetY)
            ) {
                CharacterCanvasRenderer(
                    visualState = visualState.copy(
                        scale = spatialAnchor.scaleFactor,
                        environmentalLighting = spatialAnchor.lightIntensity,
                        isLowLight = spatialAnchor.isLowLight,
                        gazeShiftX = spatialAnchor.cameraGazeOffsetX,
                        gazeShiftY = spatialAnchor.cameraGazeOffsetY
                    ),
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
                        text = "正在启动原生实景透视相机...",
                        bgColor = Color(0xE6281C38)
                    )
                }
                ARTrackingStatus.SEARCHING_PLANE -> {
                    ARGuidePill(
                        icon = Icons.Filled.CenterFocusStrong,
                        text = "正在扫描平坦桌面，请缓缓平移镜头...",
                        bgColor = Color(0xE6281C38)
                    )
                }
                ARTrackingStatus.PLANE_DETECTED -> {
                    ARGuidePill(
                        icon = Icons.Filled.TouchApp,
                        text = "已发现平坦桌面！轻触契约阵召唤 $partnerName ✨",
                        bgColor = Color(0xE60091EA)
                    )
                }
                ARTrackingStatus.ANCHORED -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val pillText = if (spatialAnchor.isLowLight) {
                            "已锁定于${spatialAnchor.planeTypeDescription} · 现实暗光自适应融合"
                        } else {
                            "已锁定于${spatialAnchor.planeTypeDescription} (${spatialAnchor.distanceMeters}m) · 双指可旋转缩放"
                        }
                        ARGuidePill(
                            icon = Icons.Filled.AutoAwesome,
                            text = pillText,
                            bgColor = if (spatialAnchor.isLowLight) Color(0xE61A237E) else Color(0xE61B5E20)
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
                        text = "空间视差受限，请对准书桌平坦表面",
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
