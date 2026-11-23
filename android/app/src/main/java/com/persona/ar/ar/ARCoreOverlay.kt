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
    onDragAnchor: (deltaNormX: Float, deltaNormY: Float) -> Unit = { _, _ -> },
    onDragStateChanged: (isDragging: Boolean) -> Unit = {},
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
                        if (zoom != 1.0f || kotlin.math.abs(rotation) > 0.4f) {
                            // 双指手势：双指捏合缩放与偏航旋转
                            onTransformGesture(zoom, pan.x * 0.4f)
                        } else if (pan.getDistance() > 1.0f) {
                            // 单指滑动：在现实桌面物理平面上自由拖拽模型平滑滑行到新位置
                            val deltaX = pan.x / size.width
                            val deltaY = pan.y / size.height
                            onDragAnchor(deltaX, deltaY)
                        }
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
                            if (change != null && change.pressed) {
                                val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                                val normY = (change.position.y / size.height).coerceIn(0f, 1f)
                                // 无论是首次放置还是已放置后重新选点，用户点击桌面任意位置均可自由重定位！
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
                // B. 已放置角色时：三层真实物理光学接触模型 (Contact AO + Diffuse Penumbra + Ground Radiosity + 光流吸附)
                val flowX = spatialAnchor.visualFlowOffsetX
                val flowY = spatialAnchor.visualFlowOffsetY
                val ax = w * spatialAnchor.screenX + spatialAnchor.parallaxShiftX + flowX
                val ay = h * spatialAnchor.screenY + (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY + flowY
                val envLight = (spatialAnchor.lightIntensity * spatialAnchor.lensVignetteFactor).coerceIn(0.20f, 1.25f)
                val alpha = (0.50f * envLight).coerceIn(0.16f, 0.58f)
                val shadowCenterColor = if (spatialAnchor.isLowLight) Color(0x990A0414) else Color.Black.copy(alpha = alpha)

                // 随设备俯仰与偏转动态自适应透视阴影偏心距与物理贴地扁平比 (Flat Ground Surface Aspect)
                val tiltSkewX = (spatialAnchor.parallaxShiftX * 0.22f)
                val tiltOffsetY = (spatialAnchor.parallaxShiftY * 0.18f)

                // 基于真实水平桌面光学投影模型：俯视角 pitch 越大 (更俯视)，地面投影越圆；平视时压扁成细线
                val groundPitchRad = (spatialAnchor.cameraPitchDegrees * PI / 180.0).coerceIn(0.18, 1.55)
                val groundAspect = sin(groundPitchRad).toFloat()

                // 真实物理主光源反向投射偏移 (Directional Projected Shadow Displacement)
                // 当台灯/窗户强光源在一侧时，影子反向延展并依入射角拉长，彻底告别单调正圆阴影
                val lightStrength = spatialAnchor.lightDirectionalStrength.coerceIn(0.1f, 1.0f)
                val dirShadowShiftX = -spatialAnchor.lightDirectionX * 42f * lightStrength * spatialAnchor.scaleFactor
                val dirShadowShiftY = -spatialAnchor.lightDirectionY * 22f * lightStrength * groundAspect * spatialAnchor.scaleFactor

                val stretchFactorX = 1.0f + kotlin.math.abs(spatialAnchor.lightDirectionX) * 0.38f * lightStrength
                val stretchFactorY = 1.0f + kotlin.math.abs(spatialAnchor.lightDirectionY) * 0.28f * lightStrength

                // 足底接触点 (严格贴合双脚，杜绝浮空脱节)
                val footContactX = ax + tiltSkewX
                val footContactY = ay + tiltOffsetY

                // 定向投射阴影中心 (随真实环境光源方向反向拉长)
                val shadowCenterX = footContactX + dirShadowShiftX
                val shadowCenterY = footContactY + dirShadowShiftY

                val baseShadowWidth = 175f * spatialAnchor.scaleFactor
                val baseShadowHeight = (baseShadowWidth * 0.44f * groundAspect).coerceAtLeast(14f * spatialAnchor.scaleFactor)
                val shadowWidth = baseShadowWidth * stretchFactorX
                val shadowHeight = baseShadowHeight * stretchFactorY

                // 1) 现实桌面色彩反射微辉光 (Subtle Radiosity Color Bleed，融入伴侣所处局部的真实材质底色)
                val bounceAlpha = (0.09f * envLight).coerceIn(0.03f, 0.16f)
                val bounceColor = Color(
                    red = (spatialAnchor.localSurfaceTintRed * 0.32f).coerceIn(0f, 1f),
                    green = (spatialAnchor.localSurfaceTintGreen * 0.32f).coerceIn(0f, 1f),
                    blue = (spatialAnchor.localSurfaceTintBlue * 0.32f).coerceIn(0f, 1f),
                    alpha = bounceAlpha
                )
                drawOval(
                    color = bounceColor,
                    topLeft = Offset(shadowCenterX - shadowWidth * 0.55f, shadowCenterY - shadowHeight * 0.55f),
                    size = Size(shadowWidth * 1.1f, shadowHeight * 1.1f)
                )

                // 2) 漫反射自适应半影 (Diffuse Penumbra) - 柔和椭圆环境光衰减、焦外弥散与悬空离地虚化扩展
                val dangleSpread = 1.0f + (spatialAnchor.dangleAirDistance * 0.72f)
                val dangleAlphaFactor = (1.0f - spatialAnchor.dangleAirDistance * 0.62f).coerceIn(0.15f, 1.0f)
                val penumbraSpread = (1.0f + (spatialAnchor.depthOfFieldBlur * 0.30f)) * dangleSpread
                val actualPenumbraAlpha = alpha * dangleAlphaFactor
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            shadowCenterColor.copy(alpha = actualPenumbraAlpha),
                            shadowCenterColor.copy(alpha = actualPenumbraAlpha * 0.38f),
                            Color.Transparent
                        ),
                        center = Offset(shadowCenterX, shadowCenterY),
                        radius = shadowWidth * 0.85f * penumbraSpread
                    ),
                    topLeft = Offset(shadowCenterX - shadowWidth * 0.5f * penumbraSpread, shadowCenterY - shadowHeight * 0.5f * penumbraSpread),
                    size = Size(shadowWidth * penumbraSpread, shadowHeight * penumbraSpread)
                )

                // 3) 核心接触环境遮蔽 (Contact AO Footprint) - 紧贴足底切面的深色物理贴合缝隙 (悬空脱离时平滑消散)
                val aoConfidence = spatialAnchor.surfaceSupportConfidence.coerceIn(0f, 1f)
                val aoWidth = baseShadowWidth * 0.54f * (0.35f + 0.65f * aoConfidence)
                val aoHeight = baseShadowHeight * 0.30f * (0.35f + 0.65f * aoConfidence)
                val aoAlpha = (0.78f * envLight * aoConfidence).coerceIn(0.0f, 0.90f)
                val aoCenterX = footContactX + dirShadowShiftX * 0.2f
                val aoCenterY = footContactY + 1.5f + dirShadowShiftY * 0.2f
                if (aoAlpha > 0.04f) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = aoAlpha),
                                Color.Black.copy(alpha = aoAlpha * 0.45f),
                                Color.Transparent
                            ),
                            center = Offset(aoCenterX, aoCenterY),
                            radius = aoWidth * 0.65f
                        ),
                        topLeft = Offset(aoCenterX - aoWidth * 0.5f, aoCenterY - aoHeight * 0.5f),
                        size = Size(aoWidth, aoHeight)
                    )
                }

                // 4) 真实桌面悬崖边缘微光脉冲警戒线 (Desk Cliff Edge Warning Arc)
                if (spatialAnchor.isNearCliffEdge || spatialAnchor.cliffEdgeIntensity > 0.04f) {
                    val cliffAlpha = (spatialAnchor.cliffEdgeIntensity * 0.85f).coerceIn(0.15f, 0.90f)
                    val edgeWidth = shadowWidth * 1.55f
                    val edgeY = footContactY + 22f * spatialAnchor.scaleFactor
                    // 警示地平线弧度
                    drawArc(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFFFA726).copy(alpha = cliffAlpha),
                                Color(0xFFFF7043).copy(alpha = cliffAlpha * 0.9f),
                                Color.Transparent
                            )
                        ),
                        startAngle = 10f,
                        sweepAngle = 160f,
                        useCenter = false,
                        topLeft = Offset(footContactX - edgeWidth * 0.5f, edgeY - 14f * spatialAnchor.scaleFactor),
                        size = Size(edgeWidth, 28f * spatialAnchor.scaleFactor),
                        style = Stroke(width = 2.5f)
                    )
                }

                // 伴侣脚下若隐若现的二游契约微光光环 (现实弱光下呈现明亮辉光，强光下呈现通透水晶光)
                val ringAlpha = if (spatialAnchor.isLowLight) 0.35f else 0.18f
                drawOval(
                    color = Color(0x00E5FF).copy(alpha = ringAlpha),
                    topLeft = Offset(shadowCenterX - shadowWidth * 0.45f, shadowCenterY - shadowHeight * 0.45f),
                    size = Size(shadowWidth * 0.9f, shadowHeight * 0.9f),
                    style = Stroke(width = 1.5f)
                )

                // 自由滑动中：高亮贴地滑行瞄准光圈
                if (spatialAnchor.isDragging) {
                    drawOval(
                        color = Color(0x8800E5FF),
                        topLeft = Offset(shadowCenterX - shadowWidth * 0.58f, shadowCenterY - shadowHeight * 0.58f),
                        size = Size(shadowWidth * 1.16f, shadowHeight * 1.16f),
                        style = Stroke(width = 2.0f)
                    )
                }

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
                // 4) 专业单反相机硬件对焦测光指示框 (Hardware AF/AE Focus Reticle)
                val focusAge = System.currentTimeMillis() - spatialAnchor.cameraFocusTimestamp
                if (spatialAnchor.isCameraFocusing || focusAge < 1600L) {
                    val focusProgress = (focusAge.toFloat() / 1600f).coerceIn(0f, 1f)
                    val focusAlpha = if (spatialAnchor.isCameraFocusing) 0.90f else (1.0f - focusProgress).coerceIn(0f, 1f)
                    val boxSize = (70f - (if (spatialAnchor.isCameraFocusing) 6f else 18f * focusProgress)) * spatialAnchor.scaleFactor
                    val focusColor = if (spatialAnchor.isCameraFocusing) Color(0xFFFFD54F) else Color(0xFF69F0AE)
                    val cornerLen = 14f * spatialAnchor.scaleFactor
                    val fX = ax
                    val fY = ay - (105f * spatialAnchor.scaleFactor) // 对准角色躯干与心口

                    // 绘制取景器四角直角线瞄准括号 (AF Bracket)
                    // 左上
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY - boxSize), Offset(fX - boxSize + cornerLen, fY - boxSize), strokeWidth = 2.5f)
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY - boxSize), Offset(fX - boxSize, fY - boxSize + cornerLen), strokeWidth = 2.5f)
                    // 右上
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY - boxSize), Offset(fX + boxSize - cornerLen, fY - boxSize), strokeWidth = 2.5f)
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY - boxSize), Offset(fX + boxSize, fY - boxSize + cornerLen), strokeWidth = 2.5f)
                    // 左下
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY + boxSize), Offset(fX - boxSize + cornerLen, fY + boxSize), strokeWidth = 2.5f)
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY + boxSize), Offset(fX - boxSize, fY + boxSize - cornerLen), strokeWidth = 2.5f)
                    // 右下
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY + boxSize), Offset(fX + boxSize - cornerLen, fY + boxSize), strokeWidth = 2.5f)
                    drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY + boxSize), Offset(fX + boxSize, fY + boxSize - cornerLen), strokeWidth = 2.5f)
                    // 中心十字聚焦微点
                    drawCircle(focusColor.copy(alpha = focusAlpha * 0.9f), radius = 2.5f, center = Offset(fX, fY))
                }
            }
        }

        // 2. 真实 3D 空间姿态投影层 (足底物理接触锚定 + 重力铅垂锁定 + 3D 俯仰透视 + 台阶高度抬升)
        if (spatialAnchor.isAnchored) {
            val offsetX = ((spatialAnchor.screenX - 0.5f) * 300f + spatialAnchor.parallaxShiftX + spatialAnchor.visualFlowOffsetX).dp
            val offsetY = ((spatialAnchor.screenY - 0.5f) * 200f + spatialAnchor.parallaxShiftY + spatialAnchor.visualFlowOffsetY - spatialAnchor.surfaceStepHeightOffset).dp

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
                        surfaceOcclusionBottomCutoff = spatialAnchor.surfaceOcclusionBottomCutoff,
                        cameraPitchDegrees = spatialAnchor.cameraPitchDegrees,
                        cameraRollDegrees = spatialAnchor.cameraRollDegrees,
                        visualFlowOffsetX = spatialAnchor.visualFlowOffsetX,
                        visualFlowOffsetY = spatialAnchor.visualFlowOffsetY,
                        lensVignetteFactor = spatialAnchor.lensVignetteFactor,
                        isCameraFocusing = spatialAnchor.isCameraFocusing,
                        gazeTrackingEyeX = spatialAnchor.gazeTrackingEyeX,
                        gazeTrackingEyeY = spatialAnchor.gazeTrackingEyeY,
                        inertialSwayAngle = spatialAnchor.inertialSwayAngle,
                        inertialSquishY = spatialAnchor.inertialSquishY,
                        foregroundOcclusionRatio = spatialAnchor.foregroundOcclusionRatio,
                        isNearCliffEdge = spatialAnchor.isNearCliffEdge,
                        cliffEdgeIntensity = spatialAnchor.cliffEdgeIntensity,
                        isDanglingOverCliff = spatialAnchor.isDanglingOverCliff,
                        dangleAirDistance = spatialAnchor.dangleAirDistance,
                        surfaceSupportConfidence = spatialAnchor.surfaceSupportConfidence,
                        surfaceStepHeightOffset = spatialAnchor.surfaceStepHeightOffset,
                        highResPose = visualState.highResPose,
                        blushIntensity = visualState.blushIntensity,
                        sleepinessFactor = visualState.sleepinessFactor,
                        poutIntensity = visualState.poutIntensity,
                        smugTiltDegrees = visualState.smugTiltDegrees,
                        haloSparkleIntensity = visualState.haloSparkleIntensity
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
                            visualState.sleepinessFactor > 0.4f || visualState.highResPose == "SLEEPY" ->
                                "💤 星野正在打瞌睡打哈欠... (大叔休眠模式，轻触唤醒) ✨"
                            visualState.blushIntensity > 0.4f || visualState.highResPose == "SHY" ->
                                "💕 星野面颊泛起羞涩微红晕 · 眼神羞怯微闪 ✨"
                            visualState.poutIntensity > 0.4f || visualState.highResPose == "POUT" ->
                                "💢 戳脸颊惹怒了星野！鼓起腮帮子傲娇中 ✨"
                            visualState.smugTiltDegrees > 1.5f || visualState.highResPose == "SMUG" ->
                                "⭐ 受到指挥官夸奖！星野前辈叉腰得意中 ✨"
                            spatialAnchor.isDanglingOverCliff ->
                                "🎈 桌面边缘悬空脱离 · 重力悬摆与虚化半影物理已激活 ✨"
                            spatialAnchor.isNearCliffEdge ->
                                "⚠️ 逼近桌面边缘悬崖 · 重心后仰自平衡与警觉立起 ✨"
                            spatialAnchor.surfaceStepHeightOffset > 1.0f ->
                                "⌨️ 踏上机械键盘阶梯 · 物理碰撞台阶抬升 (+14mm) ✨"
                            spatialAnchor.isCameraFocusing ->
                                "🎯 物理镜头硬件对焦中 · 桌面局部曝光测光与微纹理锁死 ✨"
                            spatialAnchor.isDragging ->
                                "📍 正在自由移动 (X: ${(spatialAnchor.screenX * 100).toInt()}%, Y: ${(spatialAnchor.screenY * 100).toInt()}%) · 局部光照自适应 ✨"
                            spatialAnchor.deskTextureConfidence > 0.65f && (kotlin.math.abs(spatialAnchor.visualFlowOffsetX) > 0.4f || kotlin.math.abs(spatialAnchor.visualFlowOffsetY) > 0.4f) ->
                                "桌面物理纹理光流吸附锁死 · 零漂移稳固 ⚓"
                            spatialAnchor.isMacroProximity ->
                                "微距特写已就绪 (${(spatialAnchor.scaleFactor * 100).toInt()}%) · 瞳孔镜头反光与心跳红晕 ✨"
                            spatialAnchor.lightDirectionalStrength > 0.40f ->
                                "主光源定向拉伸阴影已就绪 (${if (spatialAnchor.lightDirectionX < 0) "左侧" else "右侧"}光源) · 3D 立体轮廓光 ☀️"
                            spatialAnchor.bloomIntensity > 0.35f ->
                                "环境强光漫射 · 边缘光晕溢出与光影融合 (Bloom Bleed) ☀️"
                            spatialAnchor.chromaticAberrationIntensity > 0.40f ->
                                "边缘广角透镜色散校准中 · 真实物理光学嵌入 🌈"
                            spatialAnchor.isLowLight ->
                                "已锁定于${spatialAnchor.planeTypeDescription} · 现实暗光与 CMOS 噪点调和 🌙"
                            else ->
                                "已锁定于${spatialAnchor.planeTypeDescription} · 轻触桌面任意处重选位置 / 单指拖拽滑行"
                        }
                        val pillBgColor = when {
                            visualState.sleepinessFactor > 0.4f || visualState.highResPose == "SLEEPY" -> Color(0xE6283593) // 深夜幽蓝
                            visualState.blushIntensity > 0.4f || visualState.highResPose == "SHY" -> Color(0xE6AD1457) // 害羞绯红
                            visualState.poutIntensity > 0.4f || visualState.highResPose == "POUT" -> Color(0xE6C2185B) // 傲娇玫红
                            visualState.smugTiltDegrees > 1.5f || visualState.highResPose == "SMUG" -> Color(0xE6F57F17) // 耀金得意
                            spatialAnchor.isDanglingOverCliff -> Color(0xE67B1FA2)
                            spatialAnchor.isNearCliffEdge -> Color(0xE6E65100)
                            spatialAnchor.surfaceStepHeightOffset > 1.0f -> Color(0xE600897B)
                            spatialAnchor.isDragging -> Color(0xE60288D1)
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
