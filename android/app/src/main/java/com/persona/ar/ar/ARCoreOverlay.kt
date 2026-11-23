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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
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
                // B. 已放置角色时：贴地 AR 引导层 (契约光环 / 透视网格 / 悬崖警戒 / 拖拽光圈)
                //    脚下的接触阴影与 AO 不再由 Canvas 伪造 —— 3D 手办由 Filament 方向光投射真实阴影到 shadow catcher 平面；
                //    2.5D Canvas 回退路径 (无 3D 资产的人格) 继续沿用原有视差偏移
                val ax = w * spatialAnchor.screenX + if (isTrue3D) 0f else spatialAnchor.parallaxShiftX + spatialAnchor.visualFlowOffsetX
                // 2.5D Canvas 角色盒的足底在盒中心下方 135dp；3D 手办落脚点精确等于锚点
                val ay = h * spatialAnchor.screenY + if (isTrue3D) 0f else (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY + spatialAnchor.visualFlowOffsetY

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
                        lipSyncAmplitude = visualState.lipSyncAmplitude
                    ),
                    modelAssetPath = activeModel.modelAsset,
                    onTapPart = onTouchPart,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val offsetX = ((spatialAnchor.screenX - 0.5f) * 300f + spatialAnchor.parallaxShiftX + spatialAnchor.visualFlowOffsetX).dp
                val offsetY = ((spatialAnchor.screenY - 0.5f) * 200f + spatialAnchor.parallaxShiftY + spatialAnchor.visualFlowOffsetY - spatialAnchor.surfaceStepHeightOffset).dp

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
                            spatialAnchor.skeletonStance == SkeletonStance.SITTING || animatedSitProgress > 0.6f ->
                                "🪑 桌面边缘垂腿坐姿 · 3D 骨骼下肢折叠与双足悬垂物理 ✨"
                            multiViewProj.profile == MultiViewProfile.BACK_LOOK_BACK ->
                                "🌸 3D 背面回眸视角 (${multiViewProj.normalizedYaw.toInt()}°) · 柔顺长发与回首注视 ✨"
                            multiViewProj.profile == MultiViewProfile.THREE_QUARTER_LEFT || multiViewProj.profile == MultiViewProfile.THREE_QUARTER_RIGHT ->
                                "✨ 3D 侧身视角 (${multiViewProj.normalizedYaw.toInt()}°) · 多层空间视差与立体着色 ✨"
                            multiViewProj.profile == MultiViewProfile.PROFILE_LEFT || multiViewProj.profile == MultiViewProfile.PROFILE_RIGHT ->
                                "💫 3D 正侧面剖面 (${multiViewProj.normalizedYaw.toInt()}°) · 侧颜与柔体马尾弹簧链 ✨"
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
                                "已锁定于${spatialAnchor.planeTypeDescription} · 3D 骨骼透视与姿态引擎激活"
                        }
                        val pillBgColor = when {
                            spatialAnchor.skeletonStance == SkeletonStance.SITTING || animatedSitProgress > 0.6f -> Color(0xE6E65100)
                            multiViewProj.profile == MultiViewProfile.BACK_LOOK_BACK -> Color(0xE66A1B9A)
                            multiViewProj.profile == MultiViewProfile.THREE_QUARTER_LEFT || multiViewProj.profile == MultiViewProfile.THREE_QUARTER_RIGHT -> Color(0xE600838F)
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
                        // 3D 体态快速切换胶囊 (站立 / 桌沿坐姿)
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    if (animatedSitProgress > 0.5f) Color(0xE6E65100) else Color(0xE6281C38)
                                )
                                .border(1.2.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(15.dp))
                                .clickable { onToggleStance() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (animatedSitProgress > 0.5f) Icons.Filled.AirlineSeatReclineNormal else Icons.Filled.AccessibilityNew,
                                    contentDescription = "切换站立/坐姿",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (animatedSitProgress > 0.5f) "坐姿" else "站姿",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
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
        val ax = w * spatialAnchor.screenX + if (isTrue3D) 0f else spatialAnchor.parallaxShiftX + spatialAnchor.visualFlowOffsetX
        val ay = h * spatialAnchor.screenY + if (isTrue3D) 0f else (135f * spatialAnchor.scaleFactor) + spatialAnchor.parallaxShiftY + spatialAnchor.visualFlowOffsetY

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
