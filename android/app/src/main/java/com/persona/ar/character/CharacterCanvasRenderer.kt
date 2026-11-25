package com.persona.ar.character

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.persona.ar.character.skeleton.MultiViewProfile
import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 卡通游戏风格（Anime Chibi / 二次元二游萌系）动态纸片人渲染引擎
 */
@Composable
fun CharacterCanvasRenderer(
    visualState: CharacterVisualState,
    onTouchPart: (TouchPart, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 逐帧精灵动画：动作有预渲染序列帧时优先播放（真抬手/真弯腰）。
    // 历史 hoshino_high_res 静态立绘是从截图里抠出来的，白衬衫/白裙区域被误抠成透明空洞，
    // 叠到相机画面上会透出现实背景；该资产已删除，2.5D 只使用透明精灵表，缺失时回退矢量立绘。
    val animator = remember { FrameAnimator(context.applicationContext) }
    val requestedClip = remember(visualState.action) { animator.clipFor(visualState.action) }
    val idleClip = remember { animator.clipFor(ActionType.IDLE) }
    val sitClip = remember { animator.clipFor(ActionType.SIT) }

    // 动作开始时间戳与常驻待机基准时钟解耦，避免动作结束后与 scheduleActionReset 发生二次跳帧
    var actionStartNanos by remember(visualState.action, visualState.actionStartedAtNanos) {
        mutableLongStateOf(
            if (visualState.actionStartedAtNanos > 0L) visualState.actionStartedAtNanos else System.nanoTime()
        )
    }
    val idleStartNanos = remember { System.nanoTime() }
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(visualState.action, visualState.actionStartedAtNanos) {
        actionStartNanos = if (visualState.actionStartedAtNanos > 0L) visualState.actionStartedAtNanos else System.nanoTime()
    }
    LaunchedEffect(Unit) {
        while (true) {
            nowNanos = System.nanoTime()
            kotlinx.coroutines.delay(16L)
        }
    }

    val elapsedSeconds = if (actionStartNanos > 0L && nowNanos >= actionStartNanos) {
        (nowNanos - actionStartNanos) / 1_000_000_000f
    } else 0f
    val idleClockSeconds = ((nowNanos - idleStartNanos) / 1_000_000_000f).coerceAtLeast(0f)

    // 坐姿保持：SIT 播完定格在正坐末帧；IDLE 且处于坐姿时延续正坐态不站回；一次性动作播完切回连续时钟待机
    val (activeClip, activeElapsed) = remember(
        requestedClip, idleClip, sitClip, visualState.action, visualState.skeletonStance, elapsedSeconds, idleClockSeconds
    ) {
        val isSitting = visualState.skeletonStance == SkeletonStance.SITTING
        when {
            visualState.action == ActionType.SIT && sitClip != null -> {
                Pair(sitClip, elapsedSeconds.coerceAtMost(animator.durationSeconds(sitClip)))
            }
            requestedClip != null && visualState.action != ActionType.IDLE -> {
                if (requestedClip.loop || elapsedSeconds < animator.durationSeconds(requestedClip)) {
                    Pair(requestedClip, elapsedSeconds)
                } else if (isSitting && sitClip != null) {
                    Pair(sitClip, animator.durationSeconds(sitClip))
                } else {
                    Pair(idleClip ?: requestedClip, idleClockSeconds)
                }
            }
            isSitting && sitClip != null -> {
                Pair(sitClip, animator.durationSeconds(sitClip))
            }
            else -> {
                Pair(idleClip, idleClockSeconds)
            }
        }
    }

    val currentFrameIndex = remember(activeClip, activeElapsed) {
        activeClip?.let { clip ->
            animator.frameIndexAt(clip, activeElapsed)
        }
    }

    // 触碰与平滑重力偏置：防止手机静置时的传感器微震造成像素级跳变
    val smoothTiltX by animateFloatAsState(
        targetValue = visualState.tiltOffsetX,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "smoothTiltX"
    )
    val smoothTiltY by animateFloatAsState(
        targetValue = visualState.tiltOffsetY,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "smoothTiltY"
    )

    // 动作与表情物理律动（跳跃腾空+挤压拉伸 / 摇头摆头 / 睡觉轻呼吸摇晃 / 得意昂首）
    val actionPhaseSec = elapsedSeconds.coerceAtLeast(0f)
    val jumpClipDur = requestedClip?.let { animator.durationSeconds(it) } ?: 1.55f
    val jumpOffsetY: Float
    val jumpScaleX: Float
    val jumpScaleY: Float
    if (visualState.action == ActionType.JUMP && actionPhaseSec < jumpClipDur) {
        val p = (actionPhaseSec / jumpClipDur).coerceIn(0f, 1f)
        when {
            p < 0.18f -> {
                val k = p / 0.18f
                jumpOffsetY = 12f * k; jumpScaleX = 1f + 0.07f * k; jumpScaleY = 1f - 0.08f * k
            }
            p < 0.78f -> {
                val arc = sin(((p - 0.18f) / 0.60f) * Math.PI).toFloat()
                jumpOffsetY = -78f * arc; jumpScaleX = 1f - 0.04f * arc; jumpScaleY = 1f + 0.06f * arc
            }
            else -> {
                val c = sin(((p - 0.78f) / 0.22f) * Math.PI).toFloat()
                jumpOffsetY = 10f * c; jumpScaleX = 1f + 0.06f * c; jumpScaleY = 1f - 0.06f * c
            }
        }
    } else {
        jumpOffsetY = 0f; jumpScaleX = 1f; jumpScaleY = 1f
    }

    val actionShakeOffsetX = if (visualState.action == ActionType.SHAKE_HEAD && actionPhaseSec < 1.75f) {
        val p = (actionPhaseSec / 1.75f).coerceIn(0f, 1f)
        sin(p * Math.PI * 6.0).toFloat() * 14f * (1f - p * 0.5f)
    } else 0f
    val actionShakeTiltDeg = if (visualState.action == ActionType.SHAKE_HEAD && actionPhaseSec < 1.75f) {
        val p = (actionPhaseSec / 1.75f).coerceIn(0f, 1f)
        sin(p * Math.PI * 6.0).toFloat() * 3.2f * (1f - p * 0.5f)
    } else 0f

    val sleepBobY = if (visualState.action == ActionType.SLEEP) {
        sin(idleClockSeconds * 1.45).toFloat() * 3.5f
    } else 0f
    val sleepTiltDeg = if (visualState.action == ActionType.SLEEP) {
        sin(idleClockSeconds * 0.92).toFloat() * 2.2f
    } else 0f
    val smugScaleBoost = if (visualState.action == ActionType.SMUG || visualState.emotion == EmotionType.CONFIDENT) {
        1.028f
    } else 1f
    val smugLiftY = if (visualState.action == ActionType.SMUG) {
        -6f * sin((actionPhaseSec / 2.2f).coerceIn(0f, 1f) * Math.PI).toFloat()
    } else 0f

    // 触碰即时局部形变响应 (Squeeze & Spring Bounce)
    var activeTouchPart by remember { mutableStateOf<TouchPart?>(null) }

    val headSquishScaleY by animateFloatAsState(
        targetValue = if (activeTouchPart == TouchPart.HEAD) 0.91f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "headSquish"
    )
    val cheekSquishScaleX by animateFloatAsState(
        targetValue = if (activeTouchPart == TouchPart.CHEEK) 1.07f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cheekSquish"
    )
    val bodySquishOffset by animateFloatAsState(
        targetValue = if (activeTouchPart == TouchPart.BODY) 10f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "bodySquish"
    )

    val smugLeanDeg by animateFloatAsState(
        targetValue = when {
            visualState.action == ActionType.SMUG || visualState.emotion == EmotionType.CONFIDENT ->
                2.5f * sin(idleClockSeconds * 1.8).toFloat()
            else -> 0f
        },
        animationSpec = tween(durationMillis = 180),
        label = "smugLean"
    )

    // 果冻般 Q 弹的待机呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "bouncyBreathing")
    val breathBounce by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOutBack),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    // 呆毛俏皮晃动动画
    val ahogeWiggle by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wiggle"
    )

    // 周期性自然眨眼
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((2800..4500).random().toLong())
            isBlinking = true
            delay(130)
            isBlinking = false
        }
    }

    // 手势拖拽与缩放
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1.0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 2.5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val normalizedX = (tapOffset.x / size.width).coerceIn(0f, 1f)
                    val normalizedY = (tapOffset.y / size.height).coerceIn(0f, 1f)
                    val part = when {
                        normalizedY < 0.35f -> TouchPart.HEAD
                        normalizedY < 0.50f -> TouchPart.CHEEK
                        tapOffset.x < size.width * 0.25f || tapOffset.x > size.width * 0.75f -> TouchPart.HAND
                        else -> TouchPart.BODY
                    }

                    // 1. 触发对应部位局部弹性缩放
                    activeTouchPart = part
                    scope.launch {
                        delay(240L)
                        if (activeTouchPart == part) {
                            activeTouchPart = null
                        }
                    }

                    // 2. 传出精准触碰坐标给外部粒子飘字与统一触感震动反馈
                    onTouchPart(part, normalizedX, normalizedY)
                }
            }
    ) {
        // 精灵动画自带完整动作与呼吸起伏，播放期间不叠加外层弹跳；无精灵时由矢量立绘接管呼吸弹跳
        val effectiveBounce = if (activeClip != null) 0f else breathBounce

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(
                        (offsetX + smoothTiltX + actionShakeOffsetX).roundToInt(),
                        (offsetY + smoothTiltY + effectiveBounce + jumpOffsetY + sleepBobY + smugLiftY + bodySquishOffset).roundToInt()
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 40f
            val s = scale
            // 综合环境光照与镜头边缘自然暗角失光系数 (Lens Optical Vignetting Falloff)
            val envLight = (visualState.environmentalLighting * visualState.lensVignetteFactor).coerceIn(0.18f, 1.35f)
            val isLow = visualState.isLowLight

            // 1. 脚底现实接触阴影 (根据现实摄像头环境光自适应深浅)
            val shadowBaseAlpha = (0.28f * envLight).coerceIn(0.12f, 0.42f)
            drawOval(
                color = Color.Black.copy(alpha = shadowBaseAlpha),
                topLeft = Offset(cx - 140f * s, cy + 240f * s),
                size = Size(280f * s, 46f * s)
            )
            drawOval(
                color = if (isLow) Color(0x550D061A) else Color(0x551A0C2E),
                topLeft = Offset(cx - 95f * s, cy + 245f * s),
                size = Size(190f * s, 32f * s)
            )

            // 2. 强光源光晕与镜头色散环仅用于圆形大头矢量兜底；精灵立绘自带完整阴影与高光，
            //    严禁在精灵上绘制半径 89dp 的圆形描边（否则会在人物肩膀周围出现细白圆环杂边）。
            if (activeClip == null) {
                if (visualState.bloomIntensity > 0.04f || envLight > 0.85f) {
                    val effectiveBloom = visualState.bloomIntensity.coerceAtLeast(
                        if (envLight > 0.85f) (envLight - 0.75f) * 0.8f else 0f
                    )
                    drawAtmosphericBloomBleed(cx, cy, s, effectiveBloom, visualState.ambientColorTemp)
                }
                if (visualState.chromaticAberrationIntensity > 0.04f) {
                    drawChromaticAberrationFringe(
                        cx = cx,
                        cy = cy,
                        s = s,
                        intensity = visualState.chromaticAberrationIntensity,
                        angleRad = visualState.chromaticAngleRad
                    )
                }
            }

            // 4. 绘制萌系角色主体 (基于 3D 空间多层视差分层与情绪符号系统)
            val pitchRad = ((visualState.cameraPitchDegrees - 45f) * kotlin.math.PI / 180.0).toFloat()
            val rollRad = (visualState.cameraRollDegrees * kotlin.math.PI / 180.0).toFloat()

            val backX = cx - kotlin.math.sin(rollRad) * 5.5f * s
            val backY = cy - kotlin.math.sin(pitchRad) * 4.5f * s
            val faceX = cx + kotlin.math.sin(rollRad) * 3f * s
            val faceY = cy + kotlin.math.sin(pitchRad) * 2.5f * s
            val frontX = cx + kotlin.math.sin(rollRad) * 7.5f * s
            val frontY = cy + kotlin.math.sin(pitchRad) * 6.5f * s

            val animTime = (nowNanos / 1_000_000_000.0).toFloat()

            if (activeClip != null && currentFrameIndex != null) {
                val charW = 240f * s
                val charH = charW * (activeClip.frameHeight.toFloat() / activeClip.frameWidth.toFloat())
                val footY = cy + 255f * s
                val waistY = footY - charH * 0.45f
                val headCenterY = footY - charH * 0.84f
                val spriteTilt = smugLeanDeg + actionShakeTiltDeg + sleepTiltDeg
                val sx = cheekSquishScaleX * jumpScaleX * smugScaleBoost
                val sy = headSquishScaleY * jumpScaleY * smugScaleBoost
                withTransform({
                    rotate(degrees = spriteTilt, pivot = Offset(cx, waistY))
                    scale(scaleX = sx, scaleY = sy, pivot = Offset(cx, footY))
                }) {
                    drawAnimatedFrame(
                        clip = activeClip,
                        frameIndex = currentFrameIndex,
                        cx = cx,
                        cy = cy,
                        s = s,
                        visualState = visualState,
                        envLight = envLight
                    )
                }
                drawGameEmoteParticles(cx, headCenterY, s * 1.1f, visualState, animTime)
            } else {
                drawChibiHairBack(backX, backY, s * headSquishScaleY, visualState)
                drawChibiBody(cx, cy, s, visualState)
                drawChibiFace(faceX, faceY, s * cheekSquishScaleX, visualState)
                drawChibiEyes(faceX, faceY, s, isBlinking, visualState)
                drawChibiBlush(faceX, faceY, s, visualState)
                drawChibiMouth(faceX, faceY, s, visualState.lipSyncAmplitude, visualState)
                drawChibiHairFront(frontX, frontY, s * headSquishScaleY, ahogeWiggle, visualState)
                drawGameEmoteParticles(frontX, frontY - 75f * s, s, visualState, animTime)
            }

            // 5. 桌面边缘深度遮挡与水平面切片 (Surface Horizon Edge Occlusion，防止脚底悬空穿模)
            if (visualState.surfaceOcclusionBottomCutoff > 0.04f) {
                drawSurfaceEdgeOcclusion(cx, cy, s, visualState.surfaceOcclusionBottomCutoff)
            }

            // 6. 现实物理相机高 ISO 传感器微噪点粒子模拟 (消除纯数码矢量的过度纯净感，完美融合摄像头 CMOS 真实质感)
            if (visualState.isLowLight || visualState.sensorGrainIntensity > 0.04f) {
                drawCameraSensorGrain(cx, cy, s, visualState.sensorGrainIntensity.coerceIn(0.04f, 0.16f))
            }
        }
    }
}

@Composable
fun CharacterCanvasRenderer(
    visualState: CharacterVisualState,
    onTouchPart: (TouchPart) -> Unit,
    modifier: Modifier = Modifier
) {
    CharacterCanvasRenderer(
        visualState = visualState,
        onTouchPart = { part, _, _ -> onTouchPart(part) },
        modifier = modifier
    )
}

private fun parseColorHex(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        val clean = hex.removePrefix("#")
        val colorInt = clean.toLong(16)
        if (clean.length == 6) {
            Color(0xFF000000 or colorInt)
        } else {
            Color(colorInt)
        }
    } catch (e: Exception) {
        fallback
    }
}

/**
 * 现实物理相机高 ISO 传感器微噪点模拟 (消除纸片人过度纯净的数码感，融于摄像头真实 CMOS 画面)
 */
private fun DrawScope.drawCameraSensorGrain(cx: Float, cy: Float, s: Float, intensity: Float) {
    val grainColorLight = Color.White.copy(alpha = intensity * 0.40f)
    val grainColorDark = Color.Black.copy(alpha = intensity * 0.50f)
    val boundW = 160f * s
    val boundH = 240f * s
    val step = 16f * s
    var y = cy - 80f * s
    var rowIndex = 0
    while (y < cy + boundH - 80f * s) {
        val xOffset = if (rowIndex % 2 == 0) 0f else step * 0.5f
        var x = cx - boundW * 0.5f + xOffset
        var colIndex = 0
        while (x < cx + boundW * 0.5f) {
            val isDarkGrain = (rowIndex + colIndex) % 3 == 0
            drawCircle(
                color = if (isDarkGrain) grainColorDark else grainColorLight,
                radius = 1.1f,
                center = Offset(x, y)
            )
            x += step
            colIndex++
        }
        y += step
        rowIndex++
    }
}

/**
 * 将二次元角色基色与摄像头现实物理环境全光谱 (明暗度、色温、红绿蓝反射增益) 实时调和
 * 在暖灯下染上温暖通透金黄，在冷灯下呈现科技冷冽，在暗光下展现真实微噪点与电影级冷调遮蔽
 */
private fun modulateColorWithEnvLight(
    color: Color,
    state: CharacterVisualState
): Color {
    val factor = state.environmentalLighting.coerceIn(0.28f, 1.30f)
    var r = (color.red * factor * state.ambientTintRed).coerceIn(0f, 1f)
    var g = (color.green * factor * state.ambientTintGreen).coerceIn(0f, 1f)
    var b = (color.blue * factor * state.ambientTintBlue).coerceIn(0f, 1f)

    if (state.isLowLight) {
        // 暗光下人眼视杆细胞主导 (Purkinje 效应)：饱和度降低、带轻微蓝紫夜视色阶
        val gray = 0.299f * r + 0.587f * g + 0.114f * b
        r = r * 0.65f + gray * 0.35f
        g = g * 0.65f + gray * 0.35f
        b = (b * 0.70f + gray * 0.30f + 0.03f).coerceIn(0f, 1f)
    }
    return Color(red = r, green = g, blue = b, alpha = color.alpha)
}

private fun modulateColorWithEnvLight(
    color: Color,
    lightIntensity: Float,
    isLowLight: Boolean
): Color {
    val factor = lightIntensity.coerceIn(0.28f, 1.30f)
    var r = (color.red * factor).coerceIn(0f, 1f)
    var g = (color.green * factor).coerceIn(0f, 1f)
    var b = if (isLowLight) {
        (color.blue * factor * 1.08f).coerceIn(0f, 1f)
    } else {
        (color.blue * factor).coerceIn(0f, 1f)
    }
    return Color(red = r, green = g, blue = b, alpha = color.alpha)
}

// 绘制 Q 萌身体与水手服
private fun DrawScope.drawChibiBody(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val model = state.model
    val rawSkirt = parseColorHex(model?.skirtColorHex, Color(0xFF384B70))
    val skirtColor = modulateColorWithEnvLight(rawSkirt, state)
    val rawRibbon = parseColorHex(model?.previewColorHex, Color(0xFFFF5277))
    val ribbonColor = modulateColorWithEnvLight(rawRibbon, state)
    val strokeColor = Color(0xFF281C38) // 饱满卡通深紫黑描边
    val strokeW = 3.5f * s

    // 白短袜小脚丫
    drawRoundRect(
        color = Color(0xFFF0F4F8),
        topLeft = Offset(cx - 36f * s, cy + 210f * s),
        size = Size(24f * s, 42f * s),
        cornerRadius = CornerRadius(12f * s)
    )
    drawRoundRect(
        color = strokeColor,
        topLeft = Offset(cx - 36f * s, cy + 210f * s),
        size = Size(24f * s, 42f * s),
        cornerRadius = CornerRadius(12f * s),
        style = Stroke(strokeW)
    )

    drawRoundRect(
        color = Color(0xFFF0F4F8),
        topLeft = Offset(cx + 12f * s, cy + 210f * s),
        size = Size(24f * s, 42f * s),
        cornerRadius = CornerRadius(12f * s)
    )
    drawRoundRect(
        color = strokeColor,
        topLeft = Offset(cx + 12f * s, cy + 210f * s),
        size = Size(24f * s, 42f * s),
        cornerRadius = CornerRadius(12f * s),
        style = Stroke(strokeW)
    )

    // 百褶裙
    val skirtPath = Path().apply {
        moveTo(cx - 48f * s, cy + 155f * s)
        lineTo(cx + 48f * s, cy + 155f * s)
        lineTo(cx + 72f * s, cy + 215f * s)
        lineTo(cx - 72f * s, cy + 215f * s)
        close()
    }
    drawPath(skirtPath, skirtColor)
    drawPath(skirtPath, strokeColor, style = Stroke(strokeW))

    // 裙摆金黄条纹
    drawLine(
        color = Color(0xFFFFD54F),
        start = Offset(cx - 62f * s, cy + 203f * s),
        end = Offset(cx + 62f * s, cy + 203f * s),
        strokeWidth = 3f * s
    )

    // 上衣身躯
    val bodyPath = Path().apply {
        moveTo(cx - 50f * s, cy + 90f * s)
        lineTo(cx + 50f * s, cy + 90f * s)
        lineTo(cx + 44f * s, cy + 160f * s)
        lineTo(cx - 44f * s, cy + 160f * s)
        close()
    }
    drawPath(bodyPath, Color(0xFFFFFDF9))
    drawPath(bodyPath, strokeColor, style = Stroke(strokeW))

    // 水手服大翻领
    val collarPath = Path().apply {
        moveTo(cx - 48f * s, cy + 90f * s)
        lineTo(cx - 16f * s, cy + 130f * s)
        lineTo(cx, cy + 115f * s)
        lineTo(cx + 16f * s, cy + 130f * s)
        lineTo(cx + 48f * s, cy + 90f * s)
        close()
    }
    drawPath(collarPath, skirtColor)
    drawPath(collarPath, strokeColor, style = Stroke(strokeW))

    // 大红蝴蝶结领结
    drawCircle(ribbonColor, 12f * s, Offset(cx, cy + 120f * s))
    drawCircle(strokeColor, 12f * s, Offset(cx, cy + 120f * s), style = Stroke(strokeW))

    // 双手
    val handColor = Color(0xFFFFE8DC)
    if (state.action == ActionType.WAVE) {
        val waveHand = Path().apply {
            moveTo(cx + 46f * s, cy + 95f * s)
            lineTo(cx + 90f * s, cy + 45f * s)
            lineTo(cx + 104f * s, cy + 58f * s)
            lineTo(cx + 60f * s, cy + 110f * s)
            close()
        }
        drawPath(waveHand, Color(0xFFFFFDF9))
        drawPath(waveHand, strokeColor, style = Stroke(strokeW))
        drawCircle(handColor, 14f * s, Offset(cx + 98f * s, cy + 46f * s))
        drawCircle(strokeColor, 14f * s, Offset(cx + 98f * s, cy + 46f * s), style = Stroke(strokeW))
    } else {
        drawRoundRect(
            color = Color(0xFFFFFDF9),
            topLeft = Offset(cx - 68f * s, cy + 95f * s),
            size = Size(20f * s, 68f * s),
            cornerRadius = CornerRadius(10f * s)
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(cx - 68f * s, cy + 95f * s),
            size = Size(20f * s, 68f * s),
            cornerRadius = CornerRadius(10f * s),
            style = Stroke(strokeW)
        )
        drawCircle(handColor, 12f * s, Offset(cx - 58f * s, cy + 162f * s))
        drawCircle(strokeColor, 12f * s, Offset(cx - 58f * s, cy + 162f * s), style = Stroke(strokeW))

        drawRoundRect(
            color = Color(0xFFFFFDF9),
            topLeft = Offset(cx + 48f * s, cy + 95f * s),
            size = Size(20f * s, 68f * s),
            cornerRadius = CornerRadius(10f * s)
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(cx + 48f * s, cy + 95f * s),
            size = Size(20f * s, 68f * s),
            cornerRadius = CornerRadius(10f * s),
            style = Stroke(strokeW)
        )
        drawCircle(handColor, 12f * s, Offset(cx + 58f * s, cy + 162f * s))
        drawCircle(strokeColor, 12f * s, Offset(cx + 58f * s, cy + 162f * s), style = Stroke(strokeW))
    }
}

// 后脑勺与 Q 弹双马尾
private fun DrawScope.drawChibiHairBack(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val model = state.model
    val rawHair = parseColorHex(model?.hairColorHex, Color(0xFF7E57C2))
    val hairColor = modulateColorWithEnvLight(rawHair, state)
    val strokeColor = Color(0xFF281C38)
    val strokeW = 3.5f * s

    // 后头发大圆
    drawCircle(hairColor, 88f * s, Offset(cx, cy - 8f * s))
    drawCircle(strokeColor, 88f * s, Offset(cx, cy - 8f * s), style = Stroke(strokeW))

    // 左右大双马尾
    val leftTail = Path().apply {
        moveTo(cx - 65f * s, cy - 25f * s)
        cubicTo(cx - 150f * s, cy - 10f * s, cx - 145f * s, cy + 130f * s, cx - 85f * s, cy + 120f * s)
        cubicTo(cx - 80f * s, cy + 60f * s, cx - 60f * s, cy + 10f * s, cx - 65f * s, cy - 25f * s)
        close()
    }
    drawPath(leftTail, hairColor)
    drawPath(leftTail, strokeColor, style = Stroke(strokeW))

    val rightTail = Path().apply {
        moveTo(cx + 65f * s, cy - 25f * s)
        cubicTo(cx + 150f * s, cy - 10f * s, cx + 145f * s, cy + 130f * s, cx + 85f * s, cy + 120f * s)
        cubicTo(cx + 80f * s, cy + 60f * s, cx + 60f * s, cy + 10f * s, cx + 65f * s, cy - 25f * s)
        close()
    }
    drawPath(rightTail, hairColor)
    drawPath(rightTail, strokeColor, style = Stroke(strokeW))

    // 马尾上的黄色星星发夹
    drawStar(cx - 72f * s, cy - 20f * s, 14f * s, Color(0xFFFFD54F))
    drawStar(cx + 72f * s, cy - 20f * s, 14f * s, Color(0xFFFFD54F))
}

// 萌系大包子脸
private fun DrawScope.drawChibiFace(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val rawSkin = Color(0xFFFFF1E8)
    val skinColor = modulateColorWithEnvLight(rawSkin, state)
    val strokeColor = Color(0xFF281C38)
    val strokeW = 3.5f * s

    val facePath = Path().apply {
        moveTo(cx - 76f * s, cy - 10f * s)
        cubicTo(cx - 82f * s, cy + 50f * s, cx - 55f * s, cy + 98f * s, cx, cy + 102f * s)
        cubicTo(cx + 55f * s, cy + 98f * s, cx + 82f * s, cy + 50f * s, cx + 76f * s, cy - 10f * s)
        close()
    }
    drawPath(facePath, skinColor)
    drawPath(facePath, strokeColor, style = Stroke(strokeW))
}

// 灵动水汪汪渐变大眼睛
private fun DrawScope.drawChibiEyes(
    cx: Float,
    cy: Float,
    s: Float,
    isBlinking: Boolean,
    state: CharacterVisualState
) {
    val model = state.model
    val rawEyeColor = parseColorHex(model?.eyeColorHex, Color(0xFF673AB7))
    val eyeColor = modulateColorWithEnvLight(rawEyeColor, state)
    val strokeColor = Color(0xFF281C38)
    val eyeY = cy + 24f * s
    val leftEyeX = cx - 35f * s
    val rightEyeX = cx + 35f * s

    if (isBlinking || state.action == ActionType.BLUSH || state.expression == "WINK") {
        // 可爱的弧形眯眯眼 ^ ^
        val blinkPath = Path().apply {
            moveTo(leftEyeX - 18f * s, eyeY + 4f * s)
            quadraticBezierTo(leftEyeX, eyeY - 14f * s, leftEyeX + 18f * s, eyeY + 4f * s)
        }
        drawPath(blinkPath, strokeColor, style = Stroke(4.5f * s, cap = StrokeCap.Round))

        if (state.expression != "WINK") {
            val blinkPathRight = Path().apply {
                moveTo(rightEyeX - 18f * s, eyeY + 4f * s)
                quadraticBezierTo(rightEyeX, eyeY - 14f * s, rightEyeX + 18f * s, eyeY + 4f * s)
            }
            drawPath(blinkPathRight, strokeColor, style = Stroke(4.5f * s, cap = StrokeCap.Round))
        } else {
            drawBigChibiEye(rightEyeX, eyeY, s, eyeColor, state.gazeShiftX, state.gazeShiftY, state.isMacroProximity)
        }
    } else {
        drawBigChibiEye(leftEyeX, eyeY, s, eyeColor, state.gazeShiftX, state.gazeShiftY, state.isMacroProximity)
        drawBigChibiEye(rightEyeX, eyeY, s, eyeColor, state.gazeShiftX, state.gazeShiftY, state.isMacroProximity)
    }
}

private fun DrawScope.drawBigChibiEye(
    ex: Float,
    ey: Float,
    s: Float,
    irisColor: Color,
    gazeShiftX: Float = 0f,
    gazeShiftY: Float = 0f,
    isMacroProximity: Boolean = false
) {
    val strokeColor = Color(0xFF281C38)
    val gazeX = gazeShiftX.coerceIn(-1f, 1f) * 4.5f * s
    val gazeY = gazeShiftY.coerceIn(-1f, 1f) * 3.0f * s
    val pupilScale = if (isMacroProximity) 1.15f else 1.0f

    // 眼白
    drawOval(
        color = Color.White,
        topLeft = Offset(ex - 20f * s, ey - 24f * s),
        size = Size(40f * s, 48f * s)
    )
    drawOval(
        color = strokeColor,
        topLeft = Offset(ex - 20f * s, ey - 24f * s),
        size = Size(40f * s, 48f * s),
        style = Stroke(2.5f * s)
    )

    // 瞳孔多层渐变 (微距特写瞳孔放大与视线注视)
    val pupilW = 30f * s * pupilScale
    val pupilH = 40f * s * pupilScale
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(irisColor, irisColor.copy(alpha = 0.6f), Color(0xFFFF80AB)),
            startY = ey - 20f * s + gazeY,
            endY = ey + 20f * s + gazeY
        ),
        topLeft = Offset(ex - pupilW * 0.5f + gazeX, ey - pupilH * 0.5f + gazeY),
        size = Size(pupilW, pupilH)
    )

    // 顶部超大高光
    drawCircle(
        color = Color.White,
        radius = 7.5f * s * pupilScale,
        center = Offset(ex - 5f * s + gazeX * 0.4f, ey - 8f * s + gazeY * 0.4f)
    )
    // 底部星芒/小高光
    drawCircle(
        color = Color.White,
        radius = 3.5f * s,
        center = Offset(ex + 6f * s + gazeX * 0.4f, ey + 7f * s + gazeY * 0.4f)
    )

    // 镜头微距特写专属：相机近景双光斑反光 (Macro Specular Glint)
    if (isMacroProximity) {
        drawCircle(
            color = Color(0xCC00E5FF),
            radius = 2.4f * s,
            center = Offset(ex - 1.5f * s + gazeX * 0.4f, ey + 1.5f * s + gazeY * 0.4f)
        )
    }

    // 翘睫毛
    drawArc(
        color = strokeColor,
        startAngle = 185f,
        sweepAngle = 170f,
        useCenter = false,
        topLeft = Offset(ex - 22f * s, ey - 30f * s),
        size = Size(44f * s, 26f * s),
        style = Stroke(width = 5f * s, cap = StrokeCap.Round)
    )
}

// 萌系粉红脸颊腮红与高光
private fun DrawScope.drawChibiBlush(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val isBlushing = state.emotion == EmotionType.SHY || state.action == ActionType.BLUSH || state.isMacroProximity
    val blushColor = if (isBlushing) Color(0xFFFF4081) else Color(0xFFFF80AB)
    val blushAlpha = if (state.isMacroProximity) 0.82f else if (isBlushing) 0.72f else 0.45f

    // 左腮红圆圈
    drawCircle(
        color = blushColor.copy(alpha = blushAlpha),
        radius = 14f * s,
        center = Offset(cx - 48f * s, cy + 45f * s)
    )
    // 右腮红圆圈
    drawCircle(
        color = blushColor.copy(alpha = blushAlpha),
        radius = 14f * s,
        center = Offset(cx + 48f * s, cy + 45f * s)
    )

    // 腮红上的白色斜光条
    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(cx - 54f * s, cy + 45f * s),
        end = Offset(cx - 42f * s, cy + 41f * s),
        strokeWidth = 2.5f * s,
        cap = StrokeCap.Round
    )
    drawLine(
        color = Color.White.copy(alpha = 0.7f),
        start = Offset(cx + 42f * s, cy + 45f * s),
        end = Offset(cx + 54f * s, cy + 41f * s),
        strokeWidth = 2.5f * s,
        cap = StrokeCap.Round
    )
}

// 可爱猫嘴 / Q 弹波浪嘴
private fun DrawScope.drawChibiMouth(
    cx: Float,
    cy: Float,
    s: Float,
    amplitude: Float,
    state: CharacterVisualState
) {
    val mouthY = cy + 62f * s
    val strokeColor = Color(0xFF281C38)

    if (amplitude > 0.08f) {
        // 说话时 Q 弹张合小圆嘴
        val openH = (16f * amplitude).coerceIn(6f, 22f) * s
        val openW = (16f + amplitude * 12f) * s
        drawOval(
            color = Color(0xFFFF5277),
            topLeft = Offset(cx - openW / 2f, mouthY - openH / 2f),
            size = Size(openW, openH)
        )
        drawOval(
            color = strokeColor,
            topLeft = Offset(cx - openW / 2f, mouthY - openH / 2f),
            size = Size(openW, openH),
            style = Stroke(3f * s)
        )
        // 可爱小牙齿
        drawRect(
            color = Color.White,
            topLeft = Offset(cx - 4f * s, mouthY - openH / 2f + 1f),
            size = Size(8f * s, 4f * s)
        )
    } else {
        // 经典动漫猫猫嘴 'w' 弧线
        val catMouth = Path().apply {
            moveTo(cx - 14f * s, mouthY)
            quadraticBezierTo(cx - 7f * s, mouthY + 7f * s, cx, mouthY + 2f * s)
            quadraticBezierTo(cx + 7f * s, mouthY + 7f * s, cx + 14f * s, mouthY)
        }
        drawPath(catMouth, strokeColor, style = Stroke(4f * s, cap = StrokeCap.Round))
    }
}

// 前刘海与晃动的萌系呆毛
private fun DrawScope.drawChibiHairFront(
    cx: Float,
    cy: Float,
    s: Float,
    ahogeWiggle: Float,
    state: CharacterVisualState
) {
    val model = state.model
    val rawHair = parseColorHex(model?.hairColorHex, Color(0xFF7E57C2))
    val hairColor = modulateColorWithEnvLight(rawHair, state)
    val strokeColor = Color(0xFF281C38)
    val strokeW = 3.5f * s

    // 萌系齐刘海弧线
    val bangPath = Path().apply {
        moveTo(cx - 86f * s, cy - 20f * s)
        lineTo(cx - 50f * s, cy + 25f * s)
        lineTo(cx - 28f * s, cy + 5f * s)
        lineTo(cx, cy + 30f * s)
        lineTo(cx + 28f * s, cy + 5f * s)
        lineTo(cx + 50f * s, cy + 25f * s)
        lineTo(cx + 86f * s, cy - 20f * s)
        cubicTo(cx + 50f * s, cy - 100f * s, cx - 50f * s, cy - 100f * s, cx - 86f * s, cy - 20f * s)
        close()
    }
    drawPath(bangPath, hairColor)
    drawPath(bangPath, strokeColor, style = Stroke(strokeW))

    // 晃动的萌系大呆毛
    val ahogePath = Path().apply {
        moveTo(cx, cy - 88f * s)
        cubicTo(
            cx - 25f * s + ahogeWiggle * s, cy - 135f * s,
            cx + 35f * s + ahogeWiggle * s, cy - 145f * s,
            cx + 18f * s, cy - 98f * s
        )
    }
    drawPath(ahogePath, hairColor, style = Stroke(6f * s, cap = StrokeCap.Round))
    drawPath(ahogePath, strokeColor, style = Stroke(2f * s, cap = StrokeCap.Round))
}

// 游戏风格动态漫画情绪特效粒子（复用共享 AnimeEmoteVfx，绝不出现白色羽状杂边）
private fun DrawScope.drawGameEmoteParticles(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState,
    animTime: Float = 0f
) {
    drawAnimeEmoteVfx(
        headCenterX = cx,
        headCenterY = cy,
        scale = s,
        emotion = state.emotion,
        action = state.action,
        animTime = animTime,
        blushIntensity = state.blushIntensity,
        drawFaceBlushGlow = false
    )
}

/**
 * 真实广角光学镜头边缘径向色散 (Chromatic Aberration Radial Fringe)
 * 在偏离画面中心 (光轴) 时，物理玻璃透镜对不同波长光线的折射率差异产生红/青边缘色散分离
 */
private fun DrawScope.drawChromaticAberrationFringe(
    cx: Float,
    cy: Float,
    s: Float,
    intensity: Float,
    angleRad: Float
) {
    val fringeDistance = (2.6f * intensity * s).coerceIn(1.0f, 4.2f)
    val redOffsetX = kotlin.math.cos(angleRad) * fringeDistance
    val redOffsetY = kotlin.math.sin(angleRad) * fringeDistance
    val cyanOffsetX = -redOffsetX
    val cyanOffsetY = -redOffsetY

    val redFringeColor = Color(0x32FF2D55)
    val cyanFringeColor = Color(0x3200E5FF)
    val strokeWidth = (2.2f * s).coerceAtLeast(1.5f)

    // 红移外边缘弧光
    drawCircle(
        color = redFringeColor,
        radius = 89f * s,
        center = Offset(cx + redOffsetX, cy - 8f * s + redOffsetY),
        style = Stroke(width = strokeWidth)
    )

    // 青移外边缘弧光 (反向互补色散)
    drawCircle(
        color = cyanFringeColor,
        radius = 89f * s,
        center = Offset(cx + cyanOffsetX, cy - 8f * s + cyanOffsetY),
        style = Stroke(width = strokeWidth)
    )
}

/**
 * 现实环境强光源/逆光边缘光晕溢出与光渗漫射 (Atmospheric Bloom & Rim Light Bleed)
 * 当摄像头画面存在台灯、窗外日光等强光源时，光子在镜头介质内散射并漫射浸润角色边缘，打破死板贴图感
 */
private fun DrawScope.drawAtmosphericBloomBleed(
    cx: Float,
    cy: Float,
    s: Float,
    bloomIntensity: Float,
    colorTemp: Float
) {
    val bloomClamped = bloomIntensity.coerceIn(0.06f, 0.85f)
    // 根据色温调整光晕冷暖：暖光偏金白 (0xFFFFF4D0)，冷光偏冰蓝白 (0xFFD8F3FF)
    val bloomTint = if (colorTemp > 0.1f) {
        Color(0xFFFFF6DF)
    } else if (colorTemp < -0.1f) {
        Color(0xFFE2F7FF)
    } else {
        Color(0xFFFFFFFF)
    }

    // 1) 最外层大范围柔和光晕
    val outerAlpha = (0.16f * bloomClamped).coerceIn(0.04f, 0.22f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                bloomTint.copy(alpha = outerAlpha),
                bloomTint.copy(alpha = outerAlpha * 0.4f),
                Color.Transparent
            ),
            center = Offset(cx, cy - 15f * s),
            radius = 135f * s
        ),
        radius = 135f * s,
        center = Offset(cx, cy - 15f * s)
    )

    // 2) 头发与双肩菲涅尔高光边缘轮廓 (Fresnel Rim Bleed)
    val rimAlpha = (0.35f * bloomClamped).coerceIn(0.08f, 0.45f)
    drawCircle(
        color = bloomTint.copy(alpha = rimAlpha),
        radius = 89.5f * s,
        center = Offset(cx, cy - 8f * s),
        style = Stroke(width = 3.8f * s)
    )
}

/**
 * 桌面边缘物理深度遮挡与水平面切片 (Surface Horizon Edge Occlusion)
 * 当角色靠近桌面边缘或视线倾角较大时，下半身与脚底呈现物理桌面边缘遮挡与平滑渐隐，
 * 彻底消除角色脚底穿模漂浮在桌面半空的视觉违和感。
 */
private fun DrawScope.drawSurfaceEdgeOcclusion(
    cx: Float,
    cy: Float,
    s: Float,
    cutoff: Float
) {
    val clampedCutoff = cutoff.coerceIn(0f, 1f)
    val footBaseY = cy + 245f * s
    val occlusionHeight = (75f * s * clampedCutoff).coerceAtLeast(10f)
    val occlusionWidth = 260f * s

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0x3310081C).copy(alpha = 0.35f * clampedCutoff),
                Color(0x9910081C).copy(alpha = 0.75f * clampedCutoff)
            ),
            startY = footBaseY - occlusionHeight,
            endY = footBaseY + 12f * s
        ),
        topLeft = Offset(cx - occlusionWidth * 0.5f, footBaseY - occlusionHeight),
        size = Size(occlusionWidth, occlusionHeight + 12f * s)
    )
}

/**
 * 绘制精灵表中的某一帧。
 *
 * 不做离线切片：整张精灵表按 [FrameAnimator.Clip.frameCount] 横向等分，
 * 这里用 srcOffset/srcSize 直接取第 [frameIndex] 格（每格尺寸恒为 frameWidth × frameHeight），
 * 因此所有帧的源矩形完全相同 —— 身高基准、脚底位置、画面占比在帧间天然一致，无需任何对齐补丁。
 */
private fun DrawScope.drawAnimatedFrame(
    clip: FrameAnimator.Clip,
    frameIndex: Int,
    cx: Float,
    cy: Float,
    s: Float,
    visualState: CharacterVisualState,
    envLight: Float
) {
    // 尺寸基准与矢量立绘对齐：立绘宽 185dp、脚底在 cy + 245dp。
    // 精灵表单帧宽高比决定实际高度，所有帧共用同一比例，所以帧间不会跳动。
    val charW = 240f * s
    val charH = charW * (clip.frameHeight.toFloat() / clip.frameWidth.toFloat())
    val footY = cy + 255f * s
    val topY = footY - charH
    val leftX = cx - charW / 2f

    // 环境光调和：精灵表自带完整的画好光影，**不能再按环境照度整体乘法**。
    // 曾经这里沿用静态立绘的 (tint × envLight) 公式，暗光下 envLight 被压到 ~0.2，
    // 人物 RGB 直接被乘成深色剪影，只剩抠图残边（粉色头发边）在暗底上显眼，看起来像「两侧长了羽毛」。
    // 正确做法：只做极轻的色温融合（0.85~1.0），不做亮度衰减，人物始终清晰可见。
    val tint = ColorFilter.lighting(
        multiply = Color(
            red = (0.92f + (visualState.ambientTintRed - 1f) * 0.3f).coerceIn(0.85f, 1.05f),
            green = (0.92f + (visualState.ambientTintGreen - 1f) * 0.3f).coerceIn(0.85f, 1.05f),
            blue = (0.92f + (visualState.ambientTintBlue - 1f) * 0.3f).coerceIn(0.85f, 1.05f)
        ),
        add = Color(0f, 0f, 0f, 0f)
    )
    drawImage(
        image = clip.sheet,
        srcOffset = IntOffset(frameIndex * clip.frameWidth, 0),
        srcSize = IntSize(clip.frameWidth, clip.frameHeight),
        dstOffset = IntOffset(leftX.toInt(), topY.toInt()),
        dstSize = IntSize(charW.toInt(), charH.toInt()),
        colorFilter = tint
    )
}
