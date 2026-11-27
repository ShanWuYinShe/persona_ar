package com.persona.ar.ar

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.character.CharacterVisualState
import com.persona.ar.character.drawAnimeEmoteVfx
import com.persona.ar.character.filament.FigureFrameState
import com.persona.ar.character.filament.FilamentFigureView
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 纯 3D 实景 AR 交互与渲染层：
 * - 单一权威手势仲裁：放置 / 射线-平面绝不乱跑拖拽 / 双指缩放与旋转 / GPU 3D 骨骼精确点触；
 * - 平时保持现实桌面纯净无科幻靶圈网格，仅拖拽重定位时淡出贴地辅助准星；
 * - 脚底叠合真实方向光影与自然柔边接触 AO，真 3D 融于现实画面。
 */
@Composable
fun ARCoreOverlay(
    trackingStatus: ARTrackingStatus,
    spatialAnchor: ARSpatialAnchor,
    visualState: CharacterVisualState,
    partnerName: String,
    onTapPlace: (screenX: Float, screenY: Float) -> Unit,
    onTransformGesture: (scaleMultiplier: Float, rotationYawDelta: Float) -> Unit,
    onDragStart: (screenX: Float, screenY: Float) -> Unit = { _, _ -> },
    onDragTo: (screenX: Float, screenY: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onTouchPart: (TouchPart, Float, Float) -> Unit,
    onFigureCaptureReady: (((() -> Bitmap?)) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reticlePulse"
    )
    val runeRotationCW by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart),
        label = "runeRotationCW"
    )
    val runeRotationCCW by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "runeRotationCCW"
    )

    var headNormX by remember { mutableFloatStateOf(0.5f) }
    var headNormY by remember { mutableFloatStateOf(0.38f) }
    var pickPartRequester by remember { mutableStateOf<((Int, Int, (TouchPart?) -> Unit) -> Unit)?>(null) }

    val latestIsAnchored by rememberUpdatedState(spatialAnchor.isAnchored)
    val latestOnTapPlace by rememberUpdatedState(onTapPlace)
    val latestOnTransform by rememberUpdatedState(onTransformGesture)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragTo by rememberUpdatedState(onDragTo)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnTouchPart by rememberUpdatedState(onTouchPart)

    val activeModel = visualState.model
    val modelAssetPath = activeModel?.modelAsset?.ifBlank { "models/hoshino/figure.glb" }
        ?: "models/hoshino/figure.glb"

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downPos = down.position
                    val downTime = System.currentTimeMillis()
                    var lastSinglePos = downPos
                    var isTransforming = false
                    var isDraggingModel = false
                    var hadMultiTouch = false

                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            hadMultiTouch = true
                            if (isDraggingModel) {
                                isDraggingModel = false
                                latestOnDragEnd()
                            }
                            if (latestIsAnchored) {
                                isTransforming = true
                                val zoom = event.calculateZoom()
                                val rot = event.calculateRotation()
                                val hasZoom = kotlin.math.abs(zoom - 1f) > 0.004f
                                val hasRot = kotlin.math.abs(rot) > 0.15f
                                if (hasZoom || hasRot) {
                                    latestOnTransform(zoom, rot)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } else if (pressed.size == 1 && !isTransforming && !hadMultiTouch) {
                            val change = pressed.first()
                            lastSinglePos = change.position
                            if (latestIsAnchored) {
                                val dist = (change.position - downPos).getDistance()
                                if (!isDraggingModel && dist > touchSlop) {
                                    isDraggingModel = true
                                    latestOnDragStart((downPos.x / w).coerceIn(0f, 1f), (downPos.y / h).coerceIn(0f, 1f))
                                    latestOnDragTo((change.position.x / w).coerceIn(0f, 1f), (change.position.y / h).coerceIn(0f, 1f))
                                    change.consume()
                                } else if (isDraggingModel) {
                                    latestOnDragTo((change.position.x / w).coerceIn(0f, 1f), (change.position.y / h).coerceIn(0f, 1f))
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isDraggingModel) {
                        latestOnDragEnd()
                    } else if (!isTransforming && !hadMultiTouch) {
                        val totalMove = (lastSinglePos - downPos).getDistance()
                        val durationMs = System.currentTimeMillis() - downTime
                        if (totalMove <= touchSlop * 1.4f && durationMs < 450L) {
                            val nx = (lastSinglePos.x / w).coerceIn(0f, 1f)
                            val ny = (lastSinglePos.y / h).coerceIn(0f, 1f)
                            if (!latestIsAnchored) {
                                latestOnTapPlace(nx, ny)
                            } else {
                                pickPartRequester?.invoke(lastSinglePos.x.toInt(), lastSinglePos.y.toInt()) { part ->
                                    if (part != null) {
                                        latestOnTouchPart(part, nx, ny)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // 1. 现实桌面引导与柔和接触环境遮蔽底层
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (!spatialAnchor.isAnchored) {
                // 未放置时：显示简洁契约定位准心
                val rx = w * 0.5f
                val ry = h * 0.65f
                val baseRadius = 60f * pulseScale
                val groundPitchRad = (spatialAnchor.cameraPitchDegrees * PI / 180.0).coerceIn(0.25, 1.45)
                val radiusY = baseRadius * 0.52f * sin(groundPitchRad).toFloat()

                drawOval(
                    color = Color(0x5500E5FF),
                    topLeft = Offset(rx - baseRadius * 1.5f, ry - radiusY * 1.5f),
                    size = Size(baseRadius * 3.0f, radiusY * 3.0f),
                    style = Stroke(width = 2.2f)
                )
                rotate(degrees = runeRotationCW, pivot = Offset(rx, ry)) {
                    for (i in 0 until 12) {
                        val angle = (i * 30f) * PI / 180.0
                        val isCardinal = i % 3 == 0
                        val ox = rx + (cos(angle) * baseRadius * 1.32f).toFloat()
                        val oy = ry + (sin(angle) * radiusY * 1.32f).toFloat()
                        drawCircle(
                            color = if (isCardinal) Color(0xCC00E5FF) else Color(0x6600E5FF),
                            radius = if (isCardinal) 3.2f else 1.5f,
                            center = Offset(ox, oy)
                        )
                    }
                }
                rotate(degrees = runeRotationCCW, pivot = Offset(rx, ry)) {
                    drawOval(
                        color = Color(0x77FF5277),
                        topLeft = Offset(rx - baseRadius * 0.92f, ry - radiusY * 0.92f),
                        size = Size(baseRadius * 1.84f, radiusY * 1.84f),
                        style = Stroke(width = 1.6f)
                    )
                }
                drawCircle(color = Color(0x4400E5FF), radius = 11f * pulseScale, center = Offset(rx, ry))
                drawCircle(color = Color(0xFF00E5FF), radius = 4f, center = Offset(rx, ry))
            } else if (spatialAnchor.isInView) {
                // 已放置且在视野内：
                // - 常态下彻底去除任何科幻发光圆环与网格，仅绘制真实柔软的贴地接触环境光遮蔽 (Contact AO)
                //   与上层 Filament 方向光实体投影叠加，实现阴天漫射/强光直射下都自然扎实的贴地感；
                // - 仅在拖拽滑行重定位时，柔和呈现贴地对准辅助细线。
                val ax = w * spatialAnchor.screenX
                val ay = h * spatialAnchor.screenY
                val groundPitchRad = (spatialAnchor.cameraPitchDegrees * PI / 180.0).coerceIn(0.18, 1.52)
                val groundAspect = sin(groundPitchRad).toFloat()
                val distFactor = (0.65f / spatialAnchor.distanceMeters.coerceIn(0.25f, 2.5f)).coerceIn(0.35f, 2.2f)
                val aoW = 148f * spatialAnchor.scaleFactor * distFactor
                val aoH = (aoW * 0.40f * groundAspect).coerceAtLeast(10f * spatialAnchor.scaleFactor * distFactor)

                // 自然羽化柔边 Contact AO 阴影（无硬边、无蓝光，与 DPCF 阴影自然融为一体）
                val envLum = spatialAnchor.lightIntensity.coerceIn(0.2f, 1.35f)
                val aoAlpha = if (visualState.action == ActionType.JUMP) 0.10f else (0.22f + 0.08f * (1f - envLum)).coerceIn(0.16f, 0.30f)
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0D0A14).copy(alpha = aoAlpha),
                            Color(0xFF0D0A14).copy(alpha = aoAlpha * 0.42f),
                            Color.Transparent
                        ),
                        center = Offset(ax, ay),
                        radius = aoW * 0.52f
                    ),
                    topLeft = Offset(ax - aoW * 0.52f, ay - aoH * 0.52f),
                    size = Size(aoW * 1.04f, aoH * 1.04f)
                )

                if (spatialAnchor.isDragging) {
                    val dragRingW = aoW * 1.15f
                    val dragRingH = aoH * 1.15f
                    drawOval(
                        color = Color.White.copy(alpha = 0.65f),
                        topLeft = Offset(ax - dragRingW * 0.5f, ay - dragRingH * 0.5f),
                        size = Size(dragRingW, dragRingH),
                        style = Stroke(width = 1.8f)
                    )
                    drawCircle(color = Color(0xCC00E5FF), radius = 4f, center = Offset(ax, ay))
                }
            }
        }

        // 2. 唯一 3D 渲染管线：Filament 真实 PBR 手办
        if (spatialAnchor.isAnchored) {
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
                    envTopRed = spatialAnchor.envTopRed,
                    envTopGreen = spatialAnchor.envTopGreen,
                    envTopBlue = spatialAnchor.envTopBlue,
                    envBottomRed = spatialAnchor.envBottomRed,
                    envBottomGreen = spatialAnchor.envBottomGreen,
                    envBottomBlue = spatialAnchor.envBottomBlue,
                    envLeftRed = spatialAnchor.envLeftRed,
                    envLeftGreen = spatialAnchor.envLeftGreen,
                    envLeftBlue = spatialAnchor.envLeftBlue,
                    envRightRed = spatialAnchor.envRightRed,
                    envRightGreen = spatialAnchor.envRightGreen,
                    envRightBlue = spatialAnchor.envRightBlue,
                    localSurfaceTintRed = spatialAnchor.localSurfaceTintRed,
                    localSurfaceTintGreen = spatialAnchor.localSurfaceTintGreen,
                    localSurfaceTintBlue = spatialAnchor.localSurfaceTintBlue,
                    lightDirectionX = spatialAnchor.lightDirectionX,
                    lightDirectionY = spatialAnchor.lightDirectionY,
                    lightDirectionalStrength = spatialAnchor.lightDirectionalStrength,
                    hasWorldLightDir = spatialAnchor.hasWorldLightDir,
                    worldLightDirX = spatialAnchor.worldLightDirX,
                    worldLightDirY = spatialAnchor.worldLightDirY,
                    worldLightDirZ = spatialAnchor.worldLightDirZ,
                    action = visualState.action,
                    actionStartedAtNanos = visualState.actionStartedAtNanos,
                    emotion = visualState.emotion,
                    expression = visualState.expression,
                    stance = spatialAnchor.skeletonStance,
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
                    cameraPosX = spatialAnchor.cameraPosX,
                    cameraPosY = spatialAnchor.cameraPosY,
                    cameraPosZ = spatialAnchor.cameraPosZ,
                    cameraRightX = spatialAnchor.cameraRightX,
                    cameraRightY = spatialAnchor.cameraRightY,
                    cameraRightZ = spatialAnchor.cameraRightZ,
                    cameraForwardX = spatialAnchor.cameraForwardX,
                    cameraForwardY = spatialAnchor.cameraForwardY,
                    cameraForwardZ = spatialAnchor.cameraForwardZ,
                    cameraUpX = spatialAnchor.cameraUpX,
                    cameraUpY = spatialAnchor.cameraUpY,
                    cameraUpZ = spatialAnchor.cameraUpZ,
                    effectiveVerticalFovDegrees = spatialAnchor.effectiveVerticalFovDegrees,
                    isInView = spatialAnchor.isInView,
                    azimuthDeltaDegrees = spatialAnchor.azimuthDeltaDegrees
                ),
                modelAssetPath = modelAssetPath,
                activeModel = activeModel,
                onHeadScreenPosition = { hx, hy ->
                    headNormX = hx
                    headNormY = hy
                },
                onPickPartRequestReady = { requester -> pickPartRequester = requester },
                onFigureCaptureReady = onFigureCaptureReady,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2.5 短暂自动对焦指示 & 基于真实 3D 头骨投影的情绪微特效层
        if (spatialAnchor.isAnchored) {
            CameraFocusBracketOverlay(spatialAnchor = spatialAnchor, modifier = Modifier.fillMaxSize())
            if (spatialAnchor.isInView) {
                Figure3DEmotionVfxOverlay(
                    spatialAnchor = spatialAnchor,
                    visualState = visualState,
                    headNormX = headNormX,
                    headNormY = headNormY,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3. 顶部 AR 状态胶囊引导
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
                        text = "正在初始化实景 3D 空间追踪...",
                        bgColor = Color(0xE6281C38)
                    )
                }
                ARTrackingStatus.SEARCHING_PLANE -> {
                    ARGuidePill(
                        icon = Icons.Filled.CenterFocusStrong,
                        text = "正在扫描桌面空间，请缓缓移动镜头...",
                        bgColor = Color(0xE6281C38)
                    )
                }
                ARTrackingStatus.PLANE_DETECTED -> {
                    ARGuidePill(
                        icon = Icons.Filled.TouchApp,
                        text = "轻触桌面任意处召唤 $partnerName ✨",
                        bgColor = Color(0xE60091EA)
                    )
                }
                ARTrackingStatus.ANCHORED -> Unit
                ARTrackingStatus.TRACKING_LOST -> {
                    ARGuidePill(
                        icon = Icons.Filled.CenterFocusStrong,
                        text = "请对准桌面纹理，确保清晰定位",
                        bgColor = Color(0xE6C62828)
                    )
                }
            }
        }

        // 4. 出画寻回方位雷达指示器
        AnimatedVisibility(
            visible = spatialAnchor.isAnchored && !spatialAnchor.isInView,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
                        Text(
                            text = if (absDeg > 110) "$partnerName 在身后"
                            else if (isLeft) "$partnerName 在左侧 ${absDeg}°"
                            else "$partnerName 在右侧 ${absDeg}°",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
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
private fun CameraFocusBracketOverlay(
    spatialAnchor: ARSpatialAnchor,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val focusAge = System.currentTimeMillis() - spatialAnchor.cameraFocusTimestamp
        if (focusAge >= 750L) return@Canvas
        val w = size.width
        val h = size.height
        val ax = w * spatialAnchor.screenX
        val ay = h * spatialAnchor.screenY

        val focusProgress = (focusAge.toFloat() / 750f).coerceIn(0f, 1f)
        val focusAlpha = ((1.0f - focusProgress * focusProgress) * 0.85f).coerceIn(0f, 0.85f)
        val boxSize = (54f - 16f * focusProgress) * spatialAnchor.scaleFactor
        val focusColor = if (focusProgress < 0.45f && spatialAnchor.isCameraFocusing) Color(0xFFFFD54F) else Color(0xFF69F0AE)
        val cornerLen = 12f * spatialAnchor.scaleFactor
        val fX = ax
        val torsoOffset = h * 0.135f * spatialAnchor.scaleFactor
        val fY = ay - torsoOffset

        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY - boxSize), Offset(fX - boxSize + cornerLen, fY - boxSize), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY - boxSize), Offset(fX - boxSize, fY - boxSize + cornerLen), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY - boxSize), Offset(fX + boxSize - cornerLen, fY - boxSize), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY - boxSize), Offset(fX + boxSize, fY - boxSize + cornerLen), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY + boxSize), Offset(fX - boxSize + cornerLen, fY + boxSize), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX - boxSize, fY + boxSize), Offset(fX - boxSize, fY + boxSize - cornerLen), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY + boxSize), Offset(fX + boxSize - cornerLen, fY + boxSize), strokeWidth = 2.0f)
        drawLine(focusColor.copy(alpha = focusAlpha), Offset(fX + boxSize, fY + boxSize), Offset(fX + boxSize, fY + boxSize - cornerLen), strokeWidth = 2.0f)
    }
}

@Composable
private fun Figure3DEmotionVfxOverlay(
    spatialAnchor: ARSpatialAnchor,
    visualState: CharacterVisualState,
    headNormX: Float,
    headNormY: Float,
    modifier: Modifier = Modifier
) {
    val hasActiveEmote = visualState.emotion != EmotionType.NEUTRAL ||
            visualState.action != ActionType.IDLE ||
            visualState.touchReaction != null ||
            visualState.blushIntensity > 0.2f
    if (!hasActiveEmote) return

    var animTimeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visualState.emotion, visualState.action, visualState.touchReaction) {
        val startNanos = System.nanoTime()
        while (true) {
            animTimeSec = (System.nanoTime() - startNanos) / 1_000_000_000f
            kotlinx.coroutines.delay(16L)
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val distScale = (0.65f / spatialAnchor.distanceMeters.coerceIn(0.25f, 2.5f)).coerceIn(0.45f, 2.0f)
        val s = spatialAnchor.scaleFactor * distScale
        val headX = w * headNormX
        val headY = (h * headNormY).coerceIn(50f, h - 90f)

        drawAnimeEmoteVfx(
            headCenterX = headX,
            headCenterY = headY,
            scale = s,
            emotion = visualState.emotion,
            action = visualState.action,
            animTime = animTimeSec,
            blushIntensity = visualState.blushIntensity,
            drawFaceBlushGlow = true
        )
    }
}
