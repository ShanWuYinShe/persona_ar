package com.persona.ar.ar

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import com.persona.ar.character.filament.FigureFrameState
import com.persona.ar.character.filament.FilamentFigureView
import com.persona.ar.character.skeleton.MultiViewProfile
import com.persona.ar.character.skeleton.Skeleton3DEngine
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.PersonaModelType
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
    onToggleStance: () -> Unit = {},
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

    // 3D 俯仰透视、重力铅垂角与偏航角解算
    val pitchTilt = (spatialAnchor.cameraPitchDegrees - 45f).coerceIn(-35f, 40f)
    val gravityRoll = -spatialAnchor.cameraRollDegrees.coerceIn(-45f, 45f)
    val yawRotation = spatialAnchor.rotationYaw

    // 人格是否拥有 3D 资产：决定角色层走 Filament 真 3D 还是回退 2.5D Canvas，也决定贴地层/对焦框的足底坐标口径
    val activeModel = visualState.model
    val isTrue3D = activeModel != null && activeModel.modelType == PersonaModelType.GLTF_3D && activeModel.modelAsset != null

    // 3D 骨骼逆运动学与 360 度多视角圆柱投影求解器
    val skeletonEngine = remember { Skeleton3DEngine() }
    val multiViewProj = remember(yawRotation, pitchTilt) {
        skeletonEngine.calculateMultiViewPerspective(yawRotation, pitchTilt)
    }

    // 坐姿平滑过渡弹簧动画 (当处于坐姿模式或拖拽至桌沿悬崖边缘自动自平衡坐下)
    val animatedSitProgress by animateFloatAsState(
        targetValue = if (spatialAnchor.skeletonStance == SkeletonStance.SITTING || (spatialAnchor.isNearCliffEdge && spatialAnchor.screenY > 0.76f)) 1.0f else 0.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sitProgress"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(spatialAnchor.isAnchored) {
                if (!spatialAnchor.isAnchored) return@pointerInput
                awaitEachGesture {
                    var isTransforming = false
                    var isDraggingModel = false
                    awaitFirstDown(requireUnconsumed = false)

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (canceled) break

                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.size >= 2) {
                            if (isDraggingModel) {
                                isDraggingModel = false
                                onDragStateChanged(false)
                            }
                            isTransforming = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val rot = event.calculateRotation()
                            if (zoom != 1f || kotlin.math.abs(pan.x) > 0.8f || kotlin.math.abs(rot) > 0.2f) {
                                onTransformGesture(zoom, pan.x * 0.35f)
                                event.changes.forEach { it.consume() }
                            }
                        } else if (activePointers.size == 1 && !isTransforming) {
                            val change = activePointers.first()
                            val pan = change.position - change.previousPosition
                            if (pan.getDistance() > 1.2f) {
                                if (!isDraggingModel) {
                                    isDraggingModel = true
                                    onDragStateChanged(true)
                                }
                                val deltaX = (pan.x / size.width) * 0.95f
                                val deltaY = (pan.y / size.height) * 0.95f
                                onDragAnchor(deltaX, deltaY)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isDraggingModel) {
                        isDraggingModel = false
                        onDragStateChanged(false)
                    }
                }
            }
    ) {
        // 1. 物理平面网格、契约法阵与接触阴影绘制层
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(spatialAnchor.isAnchored) {
                    // 仅在未锚定状态下监听点击选点放置；已锚定时由重放按钮 [ ⟳ ] 显式重置，避免误触漂移
                    if (!spatialAnchor.isAnchored) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change != null && change.pressed) {
                                    val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val normY = (change.position.y / size.height).coerceIn(0f, 1f)
                                    onTapPlace(normX, normY)
                                    change.consume()
                                }
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
                val baseRadius = 65f * pulseScale
                val groundPitchRad = (spatialAnchor.cameraPitchDegrees * PI / 180.0).coerceIn(0.25, 1.45)
                val pitchAspect = sin(groundPitchRad).toFloat()
                val radiusY = baseRadius * 0.52f * pitchAspect

                // 1) 贴地透视压扁外环 (随俯仰角自适应压扁)
                drawOval(
                    color = Color(0x5500E5FF),
                    topLeft = Offset(rx - baseRadius * 1.5f, ry - radiusY * 1.5f),
                    size = Size(baseRadius * 3.0f, radiusY * 3.0f),
                    style = Stroke(width = 2.5f)
                )

                // 2) 顺时针旋转刻度圈
                rotate(degrees = runeRotationCW, pivot = Offset(rx, ry)) {
                    for (i in 0 until 12) {
                        val angle = (i * 30f) * PI / 180.0
                        val isCardinal = i % 3 == 0
                        val tickLen = if (isCardinal) 9f else 4f
                        val ox = rx + (cos(angle) * baseRadius * 1.35f).toFloat()
                        val oy = ry + (sin(angle) * radiusY * 1.35f).toFloat()
                        drawCircle(
                            color = if (isCardinal) Color(0xCC00E5FF) else Color(0x7700E5FF),
                            radius = tickLen * 0.35f,
                            center = Offset(ox, oy)
                        )
                    }
                }

                // 3) 逆时针旋转内圈星芒阵法
                rotate(degrees = runeRotationCCW, pivot = Offset(rx, ry)) {
                    drawOval(
                        color = Color(0x88FF5277),
                        topLeft = Offset(rx - baseRadius * 0.95f, ry - radiusY * 0.95f),
                        size = Size(baseRadius * 1.9f, radiusY * 1.9f),
                        style = Stroke(width = 1.8f)
                    )
                }

                // 4) 核心定位聚焦星点与光晕脉冲
                drawCircle(
                    color = Color(0x4400E5FF),
                    radius = 12f * pulseScale,
                    center = Offset(rx, ry)
                )
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 4.5f,
                    center = Offset(rx, ry)
                )
            } else {
                // B. 已放置角色时：贴地 AR 引导层 (契约光环 / 透视网格 / 悬崖警戒 / 拖拽光圈)
                //    脚下的接触阴影与 AO 不再由 Canvas 伪造 —— 3D 手办由 Filament 方向光投射真实阴影到 shadow catcher 平面；
                //    2.5D Canvas 回退路径 (无 3D 资产的人格) 继续沿用原有视差偏移
                val ax = w * spatialAnchor.screenX + if (isTrue3D) 0f else spatialAnchor.parallaxShiftX
                // 2.5D Canvas 角色盒的足底在盒中心下方 135dp；3D 手办落脚点精确等于锚点
                val ay = h * spatialAnchor.screenY + if (isTrue3D) 0f else (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY

                // 贴地引导环随俯视角压扁 (平视时压成细线)
                val groundPitchRad = (spatialAnchor.cameraPitchDegrees * PI / 180.0).coerceIn(0.18, 1.55)
                val groundAspect = sin(groundPitchRad).toFloat()

                val footContactX = ax
                val footContactY = ay
                val shadowCenterX = footContactX
                val shadowCenterY = footContactY
                val baseShadowWidth = 175f * spatialAnchor.scaleFactor
                val shadowWidth = baseShadowWidth
                val shadowHeight = (baseShadowWidth * 0.44f * groundAspect).coerceAtLeast(14f * spatialAnchor.scaleFactor)


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

                // 自由滑动中：高亮贴地滑行瞄准光盘与十字准星
                if (spatialAnchor.isDragging) {
                    drawOval(
                        color = Color(0x9900E5FF),
                        topLeft = Offset(shadowCenterX - shadowWidth * 0.62f, shadowCenterY - shadowHeight * 0.62f),
                        size = Size(shadowWidth * 1.24f, shadowHeight * 1.24f),
                        style = Stroke(width = 2.2f)
                    )
                    // 十字准星瞄准辅助线
                    drawLine(
                        color = Color(0xAA00E5FF),
                        start = Offset(shadowCenterX - shadowWidth * 0.72f, shadowCenterY),
                        end = Offset(shadowCenterX - shadowWidth * 0.35f, shadowCenterY),
                        strokeWidth = 1.8f
                    )
                    drawLine(
                        color = Color(0xAA00E5FF),
                        start = Offset(shadowCenterX + shadowWidth * 0.35f, shadowCenterY),
                        end = Offset(shadowCenterX + shadowWidth * 0.72f, shadowCenterY),
                        strokeWidth = 1.8f
                    )
                    drawLine(
                        color = Color(0xAA00E5FF),
                        start = Offset(shadowCenterX, shadowCenterY - shadowHeight * 0.72f),
                        end = Offset(shadowCenterX, shadowCenterY - shadowHeight * 0.35f),
                        strokeWidth = 1.8f
                    )
                    drawLine(
                        color = Color(0xAA00E5FF),
                        start = Offset(shadowCenterX, shadowCenterY + shadowHeight * 0.35f),
                        end = Offset(shadowCenterX, shadowCenterY + shadowHeight * 0.72f),
                        strokeWidth = 1.8f
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
            }
        }

        // 2. 角色层：GLTF_3D 人格走 Filament 真 3D 手办 (PBR 受光 + 方向光接触阴影 + 相机同轴闪光灯)；否则回退 2.5D Canvas
        if (spatialAnchor.isAnchored) {
            if (isTrue3D) {
                FilamentFigureView(
                    frameState = FigureFrameState(
                        anchored = true,
                        screenX = spatialAnchor.screenX,
                        screenY = spatialAnchor.screenY,
                        distanceMeters = spatialAnchor.distanceMeters,
                        scale = spatialAnchor.scaleFactor,
                        yawDegrees = spatialAnchor.rotationYaw,
                        cameraPitchDegrees = spatialAnchor.cameraPitchDegrees,
                        cameraRollDegrees = spatialAnchor.cameraRollDegrees,
                        envLuminance = spatialAnchor.lightIntensity,
                        isLowLight = spatialAnchor.isLowLight,
                        ambientTintRed = spatialAnchor.ambientTintRed,
                        ambientTintGreen = spatialAnchor.ambientTintGreen,
                        ambientTintBlue = spatialAnchor.ambientTintBlue,
                        ambientColorTemp = spatialAnchor.ambientColorTemp,
                        lightDirectionX = spatialAnchor.lightDirectionX,
                        lightDirectionY = spatialAnchor.lightDirectionY,
                        lightDirectionalStrength = spatialAnchor.lightDirectionalStrength,
                        surfaceSupportConfidence = spatialAnchor.surfaceSupportConfidence,
                        dangleAirDistance = spatialAnchor.dangleAirDistance,
                        action = visualState.action,
                        actionStartedAtNanos = visualState.actionStartedAtNanos,
                        emotion = visualState.emotion,
                        expression = visualState.expression,
                        stance = if (animatedSitProgress > 0.5f) SkeletonStance.SITTING else spatialAnchor.skeletonStance,
                        gazeX = spatialAnchor.cameraGazeOffsetX,
                        gazeY = spatialAnchor.cameraGazeOffsetY,
                        inertialSwayAngle = spatialAnchor.inertialSwayAngle,
                        touchReaction = visualState.touchReaction,
                        touchReactionStartedAtNanos = visualState.touchReactionStartedAtNanos,
                        lipSyncAmplitude = visualState.lipSyncAmplitude,
                        isWorldAnchored = spatialAnchor.isWorldAnchored,
                        worldAnchorX = spatialAnchor.worldAnchorX,
                        worldAnchorY = spatialAnchor.worldAnchorY,
                        worldAnchorZ = spatialAnchor.worldAnchorZ,
                        cameraForwardX = spatialAnchor.cameraForwardX,
                        cameraForwardY = spatialAnchor.cameraForwardY,
                        cameraForwardZ = spatialAnchor.cameraForwardZ,
                        cameraUpX = spatialAnchor.cameraUpX,
                        cameraUpY = spatialAnchor.cameraUpY,
                        cameraUpZ = spatialAnchor.cameraUpZ,
                        isInView = spatialAnchor.isInView,
                        azimuthDeltaDegrees = spatialAnchor.azimuthDeltaDegrees
                    ),
                    modelAssetPath = activeModel.modelAsset,
                    onTapPart = onTouchPart,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (spatialAnchor.isInView) {
                val offsetX = ((spatialAnchor.screenX - 0.5f) * 300f + spatialAnchor.parallaxShiftX).dp
                val offsetY = ((spatialAnchor.screenY - 0.5f) * 200f + spatialAnchor.parallaxShiftY - spatialAnchor.surfaceStepHeightOffset).dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = offsetX, y = offsetY)
                        .graphicsLayer {
                            // 关键：将 3D 透视变换原点严格锚定在伴侣与物理桌面的接触点 (双足地面切点)
                            transformOrigin = TransformOrigin(0.5f, 0.94f)
                            // 3D 俯仰透视收缩 (根据单反广角真实物理焦距)
                            rotationX = -pitchTilt
                            // 3D 偏航朝向 (结合 360 度多视角投影，硬件层保持柔和自然倾角，避免纯 2D 平面旋转时露馅切成薄片)
                            rotationY = (multiViewProj.normalizedYaw * 0.38f).coerceIn(-28f, 28f)
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
                            haloSparkleIntensity = visualState.haloSparkleIntensity,
                            skeletonStance = if (animatedSitProgress > 0.5f) SkeletonStance.SITTING else spatialAnchor.skeletonStance,
                            sitProgress = animatedSitProgress,
                            multiViewProfile = multiViewProj.profile,
                            threeQuarterProfileProgress = multiViewProj.threeQuarterProgress,
                            backProfileProgress = multiViewProj.backProgress,
                            cylinderContractionX = multiViewProj.cylinderContractionX,
                            depthDisparityHead = multiViewProj.depthDisparityHead,
                            depthDisparityHairBack = multiViewProj.depthDisparityHairBack,
                            depthDisparityFace = multiViewProj.depthDisparityFace,
                            pigtailSwayAngleLeft = -spatialAnchor.inertialSwayAngle * 0.5f,
                            pigtailSwayAngleRight = spatialAnchor.inertialSwayAngle * 0.5f
                        ),
                        onTouchPart = onTouchPart,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 2.5 硬件对焦指示框：独立成层，绘制在 3D 手办之上（原在贴地层 Canvas 内会被手办遮挡）
        if (spatialAnchor.isAnchored) {
            CameraFocusBracketOverlay(spatialAnchor = spatialAnchor, isTrue3D = isTrue3D, modifier = Modifier.fillMaxSize())
        }


        // 3. 顶部 AR 状态胶囊与引导提示 (Spatial Guidance Pill)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 110.dp),
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
                    // 极简 AR 悬浮控制岛：站姿 / 坐姿清晰切换 + 重新放置
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xCC1A1A2E),
                        border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.25f)),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            // 站姿切换
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (animatedSitProgress <= 0.5f) Color(0xFF6750A4) else Color.Transparent
                                    )
                                    .clickable {
                                        if (animatedSitProgress > 0.5f) onToggleStance()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AccessibilityNew,
                                        contentDescription = "站立姿态",
                                        tint = if (animatedSitProgress <= 0.5f) Color.White else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "站姿",
                                        color = if (animatedSitProgress <= 0.5f) Color.White else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontWeight = if (animatedSitProgress <= 0.5f) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }

                            // 坐姿切换
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (animatedSitProgress > 0.5f) Color(0xFFE65100) else Color.Transparent
                                    )
                                    .clickable {
                                        if (animatedSitProgress <= 0.5f) onToggleStance()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AirlineSeatReclineNormal,
                                        contentDescription = "坐姿姿态",
                                        tint = if (animatedSitProgress > 0.5f) Color.White else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "坐姿",
                                        color = if (animatedSitProgress > 0.5f) Color.White else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontWeight = if (animatedSitProgress > 0.5f) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(2.dp))

                            // 一键重新放置按钮
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
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

        // 4. 出画寻回方位雷达指示器（当手办离开当前相机视野时，在屏幕边缘柔和指示其方位与距离角度）
        if (spatialAnchor.isAnchored && !spatialAnchor.isInView) {
            val delta = spatialAnchor.azimuthDeltaDegrees
            val isLeft = delta < 0
            val absDeg = kotlin.math.abs(delta).toInt()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xD92A1A40),
                    border = BorderStroke(1.2.dp, Color(0xFFB388FF)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isLeft) {
                            Text(text = "◂", color = Color(0xFFFFD54F), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = if (absDeg > 110) "星野在身后 ↺" else if (isLeft) "星野在左侧 ${absDeg}°" else "星野在右侧 ${absDeg}°",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!isLeft) {
                            Text(text = "▸", color = Color(0xFFFFD54F), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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

/**
 * 物理相机硬件对焦测光指示框 (Hardware AF/AE Focus Reticle)：专业单反四角直角瞄准括号 + 中心十字。
 * 独立于贴地层 Canvas，以保证绘制在 Filament 3D 手办之上。
 */
@Composable
private fun CameraFocusBracketOverlay(
    spatialAnchor: ARSpatialAnchor,
    isTrue3D: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val focusAge = System.currentTimeMillis() - spatialAnchor.cameraFocusTimestamp
        if (!spatialAnchor.isCameraFocusing && focusAge >= 1600L) return@Canvas
        val w = size.width
        val h = size.height
        val ax = w * spatialAnchor.screenX + if (isTrue3D) 0f else spatialAnchor.parallaxShiftX
        val ay = h * spatialAnchor.screenY + if (isTrue3D) 0f else (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY

        val focusProgress = (focusAge.toFloat() / 1600f).coerceIn(0f, 1f)
        val focusAlpha = if (spatialAnchor.isCameraFocusing) 0.90f else (1.0f - focusProgress).coerceIn(0f, 1f)
        val boxSize = (70f - (if (spatialAnchor.isCameraFocusing) 6f else 18f * focusProgress)) * spatialAnchor.scaleFactor
        val focusColor = if (spatialAnchor.isCameraFocusing) Color(0xFFFFD54F) else Color(0xFF69F0AE)
        val cornerLen = 14f * spatialAnchor.scaleFactor
        val fX = ax
        val fY = ay - (105f * spatialAnchor.scaleFactor) // 对准角色躯干与心口

        // 取景器四角直角线瞄准括号 (AF Bracket)
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