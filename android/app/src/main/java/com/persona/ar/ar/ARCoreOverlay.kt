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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
    onTouchPart: (TouchPart, Float, Float) -> Unit,
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
                // B. 已放置角色时：三层真实物理光学接触模型 (Contact AO + Diffuse Penumbra + Ground Radiosity)
                val ax = w * spatialAnchor.screenX + spatialAnchor.parallaxShiftX
                val ay = h * spatialAnchor.screenY + (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY
                val envLight = spatialAnchor.lightIntensity.coerceIn(0.25f, 1.25f)
                val alpha = (0.50f * envLight).coerceIn(0.16f, 0.58f)
                val shadowCenterColor = if (spatialAnchor.isLowLight) Color(0x990A0414) else Color.Black.copy(alpha = alpha)

                // 随设备俯仰与偏转动态自适应透视阴影偏心距与物理贴地扁平比 (Flat Ground Surface Aspect)
                val tiltSkewX = (spatialAnchor.parallaxShiftX * 0.22f)
                val tiltOffsetY = (spatialAnchor.parallaxShiftY * 0.18f)
                val shadowCenterX = ax + tiltSkewX
                val shadowCenterY = ay + tiltOffsetY
                // 基于真实水平桌面光学投影模型：俯视角 pitch 越大 (更俯视)，地面投影越圆；平视时压扁成细线
                val groundPitchRad = (spatialAnchor.cameraPitchDegrees * PI / 180.0).coerceIn(0.18, 1.55)
                val groundAspect = sin(groundPitchRad).toFloat()
                val shadowWidth = 175f * spatialAnchor.scaleFactor
                val shadowHeight = (shadowWidth * 0.44f * groundAspect).coerceAtLeast(14f * spatialAnchor.scaleFactor)

                // 1) 现实桌面色彩反射微辉光 (Subtle Radiosity Color Bleed)
                val bounceAlpha = (0.07f * envLight).coerceIn(0.02f, 0.12f)
                val bounceColor = Color(
                    red = (spatialAnchor.ambientTintRed * 0.25f).coerceIn(0f, 1f),
                    green = (spatialAnchor.ambientTintGreen * 0.25f).coerceIn(0f, 1f),
                    blue = (spatialAnchor.ambientTintBlue * 0.35f).coerceIn(0f, 1f),
                    alpha = bounceAlpha
                )
                drawOval(
                    color = bounceColor,
                    topLeft = Offset(shadowCenterX - shadowWidth * 0.55f, shadowCenterY - shadowHeight * 0.55f),
                    size = Size(shadowWidth * 1.1f, shadowHeight * 1.1f)
                )

                // 2) 漫反射自适应半影 (Diffuse Penumbra) - 柔和椭圆环境光衰减与焦外弥散扩展
                val penumbraSpread = 1.0f + (spatialAnchor.depthOfFieldBlur * 0.30f)
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            shadowCenterColor,
                            shadowCenterColor.copy(alpha = alpha * 0.38f),
                            Color.Transparent
                        ),
                        center = Offset(shadowCenterX, shadowCenterY),
                        radius = shadowWidth * 0.85f * penumbraSpread
                    ),
                    topLeft = Offset(shadowCenterX - shadowWidth * 0.5f * penumbraSpread, shadowCenterY - shadowHeight * 0.5f * penumbraSpread),
                    size = Size(shadowWidth * penumbraSpread, shadowHeight * penumbraSpread)
                )

                // 3) 核心接触环境遮蔽 (Contact AO Footprint) - 紧贴足底的深色物理贴合缝隙
                val aoWidth = shadowWidth * 0.54f
                val aoHeight = shadowHeight * 0.30f
                val aoAlpha = (0.78f * envLight).coerceIn(0.42f, 0.90f)
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = aoAlpha),
                            Color.Black.copy(alpha = aoAlpha * 0.45f),
                            Color.Transparent
                        ),
                        center = Offset(shadowCenterX, shadowCenterY + 1.5f),
                        radius = aoWidth * 0.65f
                    ),
                    topLeft = Offset(shadowCenterX - aoWidth * 0.5f, shadowCenterY + 1.5f - aoHeight * 0.5f),
                    size = Size(aoWidth, aoHeight)
                )

                // 伴侣脚下若隐若现的二游契约微光光环 (现实弱光下呈现明亮辉光，强光下呈现通透水晶光)
                val ringAlpha = if (spatialAnchor.isLowLight) 0.35f else 0.18f
                drawOval(
                    color = Color(0x00E5FF).copy(alpha = ringAlpha),
                    topLeft = Offset(shadowCenterX - shadowWidth * 0.45f, shadowCenterY - shadowHeight * 0.45f),
                    size = Size(shadowWidth * 0.9f, shadowHeight * 0.9f),
                    style = Stroke(width = 1.5f)
                )

                // 现实桌面透视空间微光切面网格 (横向阶梯透视线 + 纵向透视发散辐射线，构建完整物理贴地切面)
                val gridColor = Color(0x00E5FF).copy(alpha = 0.13f)
                val gridW = shadowWidth * 1.65f
                val gridH = shadowHeight * 2.2f

                // 1) 横向深度阶梯线
                for (step in -3..3) {
                    val lineY = shadowCenterY + (step * (gridH / 7f))
                    val shrink = 1.0f - (step * 0.06f)
                    drawLine(
                        color = gridColor,
                        start = Offset(shadowCenterX - (gridW * 0.5f * shrink), lineY),
                        end = Offset(shadowCenterX + (gridW * 0.5f * shrink), lineY),
                        strokeWidth = 1.2f
                    )
                }

                // 2) 纵向透视发散辐射线 (以远端视差消失点发散到近端地面)
                for (k in -2..2) {
                    val farX = shadowCenterX + (k * (gridW * 0.20f))
                    val nearX = shadowCenterX + (k * (gridW * 0.46f))
                    drawLine(
                        color = gridColor.copy(alpha = 0.09f),
                        start = Offset(farX, shadowCenterY - (gridH * 0.48f)),
                        end = Offset(nearX, shadowCenterY + (gridH * 0.48f)),
                        strokeWidth = 1.0f
                    )
                }
            }
        }

        // 2. 真实 3D 空间姿态投影层 (足底物理接触锚定 + 重力铅垂锁定 + 3D 俯仰透视)
        if (spatialAnchor.isAnchored) {
            val offsetX = ((spatialAnchor.screenX - 0.5f) * 300f + spatialAnchor.parallaxShiftX).dp
            val offsetY = ((spatialAnchor.screenY - 0.5f) * 200f + spatialAnchor.parallaxShiftY).dp

            // 1) 3D 俯仰透视收缩 (相机俯仰看桌面时，角色头部前倾近大远小，下肢透视缩短)
            val pitchTilt = (spatialAnchor.cameraPitchDegrees - 45f).coerceIn(-35f, 40f)
            // 2) 3D 重力铅垂锁定 (手机横滚侧倾时，伴侣身体在现实中依然保持垂直于物理地面站立)
            val gravityRoll = -spatialAnchor.cameraRollDegrees.coerceIn(-45f, 45f)
            // 3) 用户在平面上手势旋转的偏航角 (Yaw)
            val yawRotation = spatialAnchor.rotationYaw

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = offsetX, y = offsetY)
                    .graphicsLayer {
                        // 关键：将 3D 透视变换原点严格锚定在伴侣与物理桌面的接触点 (双足地面切点)
                        transformOrigin = TransformOrigin(0.5f, 0.94f)
                        // 3D 俯仰透视收缩 (根据单反广角真实物理焦距)
                        rotationX = -pitchTilt
                        // 3D 偏航旋转 (伴侣在桌面上可以 360 度转身面朝不同方向)
                        rotationY = yawRotation
                        // 3D 重力铅垂锁定 (手机横滚侧倾时，伴侣身体在现实中依然保持垂直站立)
                        rotationZ = gravityRoll
                        // 物理相机光学透视视距
                        cameraDistance = 8f * density
                    }
            ) {
                CharacterCanvasRenderer(
                    visualState = visualState.copy(
                        scale = spatialAnchor.scaleFactor,
                        environmentalLighting = spatialAnchor.lightIntensity,
                        isLowLight = spatialAnchor.isLowLight,
                        ambientColorTemp = spatialAnchor.ambientColorTemp,
                        ambientTintRed = spatialAnchor.ambientTintRed,
                        ambientTintGreen = spatialAnchor.ambientTintGreen,
                        ambientTintBlue = spatialAnchor.ambientTintBlue,
                        sensorGrainIntensity = spatialAnchor.sensorGrainIntensity,
                        gazeShiftX = spatialAnchor.cameraGazeOffsetX,
                        gazeShiftY = spatialAnchor.cameraGazeOffsetY,
                        chromaticAberrationIntensity = spatialAnchor.chromaticAberrationIntensity,
                        chromaticAngleRad = spatialAnchor.chromaticAngleRad,
                        bloomIntensity = spatialAnchor.bloomIntensity,
                        depthOfFieldBlur = spatialAnchor.depthOfFieldBlur,
                        isMacroProximity = spatialAnchor.isMacroProximity,
                        surfaceOcclusionBottomCutoff = spatialAnchor.surfaceOcclusionBottomCutoff
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
                        val pillText = when {
                            spatialAnchor.isMacroProximity ->
                                "微距特写已就绪 (${(spatialAnchor.scaleFactor * 100).toInt()}%) · 瞳孔镜头反光与心跳红晕 ✨"
                            spatialAnchor.bloomIntensity > 0.35f ->
                                "环境强光漫射 · 边缘光晕溢出与光影融合 (Bloom Bleed) ☀️"
                            spatialAnchor.chromaticAberrationIntensity > 0.40f ->
                                "边缘广角透镜色散校准中 · 真实物理光学嵌入 🌈"
                            spatialAnchor.isLowLight ->
                                "已锁定于${spatialAnchor.planeTypeDescription} · 现实暗光与 CMOS 噪点调和 🌙"
                            else ->
                                "已锁定于${spatialAnchor.planeTypeDescription} (${spatialAnchor.distanceMeters}m) · 双指可旋转缩放"
                        }
                        val pillBgColor = when {
                            spatialAnchor.isMacroProximity -> Color(0xE6D81B60)
                            spatialAnchor.bloomIntensity > 0.35f -> Color(0xE6E65100)
                            spatialAnchor.chromaticAberrationIntensity > 0.40f -> Color(0xE600838F)
                            spatialAnchor.isLowLight -> Color(0xE61A237E)
                            else -> Color(0xE61B5E20)
                        }
                        ARGuidePill(
                            icon = Icons.Filled.AutoAwesome,
                            text = pillText,
                            bgColor = pillBgColor
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
    ARCoreOverlay(
        trackingStatus = trackingStatus,
        spatialAnchor = spatialAnchor,
        visualState = visualState,
        partnerName = partnerName,
        onTapPlace = onTapPlace,
        onTransformGesture = onTransformGesture,
        onResetAnchor = onResetAnchor,
        onTouchPart = { part, _, _ -> onTouchPart(part) },
        modifier = modifier
    )
}
