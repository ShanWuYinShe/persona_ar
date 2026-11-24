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

    // 动作开始时间戳：当 action 或 actionStartedAtNanos 变化时同步更新，确保连续点击时从第 0 帧平滑重播
    var actionStartNanos by remember(visualState.action, visualState.actionStartedAtNanos) {
        mutableLongStateOf(
            if (visualState.actionStartedAtNanos > 0L) visualState.actionStartedAtNanos else System.nanoTime()
        )
    }
    var nowNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visualState.action, visualState.actionStartedAtNanos) {
        actionStartNanos = if (visualState.actionStartedAtNanos > 0L) visualState.actionStartedAtNanos else System.nanoTime()
    }
    // 用无限动画驱动重绘，保证帧刷新
    LaunchedEffect(Unit) {
        while (true) {
            nowNanos = System.nanoTime()
            kotlinx.coroutines.delay(16L)
        }
    }

    val elapsedSeconds = if (actionStartNanos > 0L && nowNanos >= actionStartNanos) {
        (nowNanos - actionStartNanos) / 1_000_000_000f
    } else 0f

    // 一次性动作播完后自动平滑切回待机呼吸，避免定格在最后一帧；再次点击时立即从第 0 帧重新响应
    val (activeClip, activeElapsed) = remember(requestedClip, idleClip, elapsedSeconds) {
        if (requestedClip != null) {
            if (requestedClip.loop || elapsedSeconds < animator.durationSeconds(requestedClip)) {
                Pair(requestedClip, elapsedSeconds)
            } else {
                Pair(idleClip ?: requestedClip, elapsedSeconds)
            }
        } else {
            Pair(idleClip, elapsedSeconds)
        }
    }

    // 当前帧序号：整张精灵表不切分，绘制时按网格取第 index 格
    val currentFrameIndex = remember(activeClip, activeElapsed) {
        activeClip?.let { clip ->
            animator.frameIndexAt(clip, activeElapsed)
        }
    }

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

    val jumpAnim by animateFloatAsState(
        targetValue = if (visualState.action == ActionType.JUMP) -50f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "jump"
    )

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
                        (offsetX + visualState.tiltOffsetX).roundToInt(),
                        (offsetY + visualState.tiltOffsetY + effectiveBounce + jumpAnim + bodySquishOffset).roundToInt()
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

            // 2. 现实强光源边缘光渗与环境光散射 (Bloom Bleed & Atmospheric Rim Light)
            if (visualState.bloomIntensity > 0.04f || envLight > 0.85f) {
                val effectiveBloom = visualState.bloomIntensity.coerceAtLeast(
                    if (envLight > 0.85f) (envLight - 0.75f) * 0.8f else 0f
                )
                drawAtmosphericBloomBleed(cx, cy, s, effectiveBloom, visualState.ambientColorTemp)
            }

            // 3. 真实广角镜头边缘径向色散 (Chromatic Aberration Fringe)
            if (visualState.chromaticAberrationIntensity > 0.04f) {
                drawChromaticAberrationFringe(
                    cx = cx,
                    cy = cy,
                    s = s,
                    intensity = visualState.chromaticAberrationIntensity,
                    angleRad = visualState.chromaticAngleRad
                )
            }

            // 4. 绘制萌系角色主体 (基于 3D 空间多层视差分层、悬浮光环、立体菲涅尔边缘光与微距亲密特写)
            val pitchRad = ((visualState.cameraPitchDegrees - 45f) * kotlin.math.PI / 180.0).toFloat()
            val rollRad = (visualState.cameraRollDegrees * kotlin.math.PI / 180.0).toFloat()

            // 3D 空间多层视差深度位移 (Multi-layer Volumetric Parallax Disparity)
            // 1) 后发层 Z < 0 (向视角反方向视差深陷)
            val backX = cx - kotlin.math.sin(rollRad) * 5.5f * s
            val backY = cy - kotlin.math.sin(pitchRad) * 4.5f * s

            // 2) 面部与五官层 Z > 0 (微幅向前)
            val faceX = cx + kotlin.math.sin(rollRad) * 3f * s
            val faceY = cy + kotlin.math.sin(pitchRad) * 2.5f * s

            // 3) 前刘海与呆毛层 Z >> 0 (明显向前，形成自然发丝遮挡深度)
            val frontX = cx + kotlin.math.sin(rollRad) * 7.5f * s
            val frontY = cy + kotlin.math.sin(pitchRad) * 6.5f * s

            // 优先级：动作精灵表 > 矢量兜底（历史截图静态立绘已移除）
            val animTime = (nowNanos / 1_000_000_000.0).toFloat()

            if (activeClip != null && currentFrameIndex != null) {
                drawAnimatedFrame(
                    clip = activeClip,
                    frameIndex = currentFrameIndex,
                    cx = cx,
                    cy = cy,
                    s = s,
                    visualState = visualState,
                    envLight = envLight
                )
                drawGameEmoteParticles(cx, cy - 80f * s, s, visualState, animTime)
            } else {
                drawChibiHairBack(backX, backY, s * headSquishScaleY, visualState)
                drawChibiBody(cx, cy, s, visualState)
                drawChibiFace(faceX, faceY, s * cheekSquishScaleX, visualState)
                drawChibiEyes(faceX, faceY, s, isBlinking, visualState)
                drawChibiBlush(faceX, faceY, s, visualState)
                drawChibiMouth(faceX, faceY, s, visualState.lipSyncAmplitude, visualState)
                drawChibiHairFront(frontX, frontY, s * headSquishScaleY, ahogeWiggle, visualState)
                // 矢量兜底不再绘制悬浮光环与双侧白色菲涅尔轮廓光：
                // 这两层就是旧版「角色两侧长出发光白羽毛」伪影的原始绘制源（光环白核 + 两条白色贝塞尔弧线）。
                // 精灵表路径本身不画它们；这里同步移除，保证精灵解码失败/动作无映射时也不会重新长出白羽毛。
                drawGameEmoteParticles(frontX, frontY, s, visualState, animTime)
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

// 游戏风格动态漫画情绪特效粒子（二次元符号浮动层）
private fun DrawScope.drawGameEmoteParticles(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState,
    animTime: Float = 0f
) {
    val isJoy = state.emotion == EmotionType.JOY || state.action == ActionType.WAVE || state.action == ActionType.JUMP
    val isShy = state.emotion == EmotionType.SHY || state.action == ActionType.BLUSH
    val isAngry = state.emotion == EmotionType.ANGRY || state.action == ActionType.POUT
    val isSmug = state.emotion == EmotionType.CONFIDENT || state.action == ActionType.SMUG
    val isSleepy = state.emotion == EmotionType.SLEEPY || state.action == ActionType.SLEEP || state.action == ActionType.YAWN
    val isSurprised = state.emotion == EmotionType.SURPRISED
    val isSad = state.emotion == EmotionType.SAD
    val isThinking = state.emotion == EmotionType.THINKING

    when {
        isJoy -> {
            // 欢快跳跃小音符与金色闪耀小星芒
            val t1 = (animTime * 3.4f) % (2f * Math.PI.toFloat())
            val note1Y = cy - 65f * s + kotlin.math.sin(t1) * 8f * s
            val note2Y = cy - 75f * s + kotlin.math.cos(t1 * 1.2f) * 10f * s
            drawMusicNote(cx + 82f * s, note1Y, 14f * s, Color(0xFFFFD54F))
            drawMusicNote(cx - 78f * s, note2Y, 12f * s, Color(0xFFFF6090))
            drawFourPointStar(cx + 60f * s, cy - 90f * s, 9f * s, (animTime * 90f) % 360f, Color(0xFFFFEB3B))
        }
        isShy -> {
            // 娇羞飘动上升粉心（带柔和正弦漂浮摆动与优雅淡出）
            val cycle1 = ((animTime * 0.75f) % 1.0f)
            val heart1Y = cy - 35f * s - cycle1 * 55f * s
            val heart1X = cx + 75f * s + kotlin.math.sin(cycle1 * 6.28f) * 6f * s
            val alpha1 = (1f - cycle1).coerceIn(0f, 1f)

            val cycle2 = ((animTime * 0.75f + 0.5f) % 1.0f)
            val heart2Y = cy - 40f * s - cycle2 * 55f * s
            val heart2X = cx - 72f * s - kotlin.math.sin(cycle2 * 6.28f) * 6f * s
            val alpha2 = (1f - cycle2).coerceIn(0f, 1f)

            drawHeart(heart1X, heart1Y, 13f * s, Color(0xFFFF4081).copy(alpha = alpha1))
            drawHeart(heart2X, heart2Y, 10f * s, Color(0xFFFF80AB).copy(alpha = alpha2))
        }
        isAngry -> {
            // 经典二次元 Q 版红怒十字（带脉冲放大）。
            // 注意：这里严禁再叠加任何白色/半透明白色粒子（旧版「侧边怒气白云团」就是用户多次捏脸时
            // 看到的「白色羽毛」来源之一）。红怒符号本身已足够表达傲娇，白色软边团在暗光与浅色背景下
            // 会与角色轮廓叠成羽毛状伪影，2.5D 全状态都不允许出现。
            val pulse = 1.0f + kotlin.math.sin(animTime * 8f) * 0.15f
            val crossX = cx + 72f * s
            val crossY = cy - 75f * s
            drawAngryCross(crossX, crossY, 16f * s * pulse, Color(0xFFFF1744))
        }
        isSmug -> {
            // 闪亮四角金星（Bling Bling 自信星芒，旋转放大）
            val rot = (animTime * 120f) % 360f
            val pulse = 1f + kotlin.math.sin(animTime * 6f) * 0.2f
            drawFourPointStar(cx + 85f * s, cy - 70f * s, 18f * s * pulse, rot, Color(0xFFFFD700))
            drawFourPointStar(cx - 78f * s, cy - 55f * s, 12f * s, -rot * 0.8f, Color(0xFFFFF176))
        }
        isSleepy -> {
            // 缓缓升腾放大的 Zzz 困倦半透明微光气泡
            val zProgress1 = (animTime * 0.55f) % 1.0f
            val z1X = cx + 65f * s + kotlin.math.sin(zProgress1 * 4f) * 8f * s
            val z1Y = cy - 45f * s - zProgress1 * 65f * s
            val z1Alpha = (1f - zProgress1).coerceIn(0f, 1f)
            drawZzzBubble(z1X, z1Y, (10f + zProgress1 * 8f) * s, z1Alpha)

            val zProgress2 = (animTime * 0.55f + 0.48f) % 1.0f
            val z2X = cx + 80f * s + kotlin.math.sin(zProgress2 * 5f) * 6f * s
            val z2Y = cy - 35f * s - zProgress2 * 60f * s
            val z2Alpha = (1f - zProgress2).coerceIn(0f, 1f)
            drawZzzBubble(z2X, z2Y, (8f + zProgress2 * 6f) * s, z2Alpha)
        }
        isSurprised -> {
            // 惊叹号气泡与闪光
            drawExclamationBubble(cx, cy - 110f * s, 18f * s, Color(0xFFFFD54F))
        }
        isSad -> {
            // 缓缓滑落的晶莹泪珠
            val tearDrop1 = (animTime * 1.8f) % 1.0f
            drawTearDrop(cx + 42f * s, cy + 5f * s + tearDrop1 * 30f * s, 8f * s, Color(0xFF00B0FF).copy(alpha = 1f - tearDrop1 * 0.5f))
            drawTearDrop(cx - 42f * s, cy + 8f * s + ((tearDrop1 + 0.3f) % 1f) * 28f * s, 7f * s, Color(0xFF00B0FF).copy(alpha = 1f - tearDrop1 * 0.5f))
        }
        isThinking -> {
            drawCircle(Color(0xFF40C4FF), 10f * s, Offset(cx + 80f * s, cy - 55f * s))
            drawCircle(Color(0xFF281C38), 10f * s, Offset(cx + 80f * s, cy - 55f * s), style = Stroke(2.5f * s))
        }
        else -> Unit
    }
}

// 辅助八分音符绘制 (♪)
private fun DrawScope.drawMusicNote(x: Float, y: Float, size: Float, color: Color) {
    val noteHeadRadius = size * 0.35f
    // 倾斜椭圆音符头
    drawOval(
        color = color,
        topLeft = Offset(x - noteHeadRadius * 1.2f, y - noteHeadRadius),
        size = Size(noteHeadRadius * 2.2f, noteHeadRadius * 1.6f)
    )
    drawOval(
        color = Color(0xFF281C38),
        topLeft = Offset(x - noteHeadRadius * 1.2f, y - noteHeadRadius),
        size = Size(noteHeadRadius * 2.2f, noteHeadRadius * 1.6f),
        style = Stroke(2f)
    )
    // 符杆
    val stemX = x + noteHeadRadius * 0.8f
    val stemTopY = y - size * 1.4f
    drawLine(color, Offset(stemX, y), Offset(stemX, stemTopY), strokeWidth = 2.5f, cap = StrokeCap.Round)
    drawLine(Color(0xFF281C38), Offset(stemX, y), Offset(stemX, stemTopY), strokeWidth = 1.2f, cap = StrokeCap.Round)
    // 符尾
    val flagPath = Path().apply {
        moveTo(stemX, stemTopY)
        cubicTo(stemX + size * 0.7f, stemTopY + size * 0.3f, stemX + size * 0.5f, stemTopY + size * 0.8f, stemX + size * 0.8f, stemTopY + size)
    }
    drawPath(flagPath, color, style = Stroke(2.5f, cap = StrokeCap.Round))
}

// 辅助四角星芒绘制 (✦)
private fun DrawScope.drawFourPointStar(x: Float, y: Float, radius: Float, rotationDegrees: Float, color: Color) {
    val path = Path().apply {
        val rad = Math.toRadians(rotationDegrees.toDouble())
        for (i in 0..7) {
            val r = if (i % 2 == 0) radius else radius * 0.28f
            val angle = rad + i * Math.PI / 4.0
            val px = x + (r * Math.cos(angle)).toFloat()
            val py = y + (r * Math.sin(angle)).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38), style = Stroke(1.8f))
}

// 辅助二次元 Q 版红怒十字绘制 (💢)
private fun DrawScope.drawAngryCross(x: Float, y: Float, size: Float, color: Color) {
    val half = size * 0.5f
    val thick = size * 0.25f
    val strokeW = 3f
    val borderColor = Color(0xFF281C38)
    // 井字形交错四弧线
    drawLine(color, Offset(x - half, y - thick), Offset(x + half, y - thick), strokeWidth = thick, cap = StrokeCap.Round)
    drawLine(borderColor, Offset(x - half, y - thick), Offset(x + half, y - thick), strokeWidth = thick + strokeW, cap = StrokeCap.Round)
    drawLine(color, Offset(x - half, y - thick), Offset(x + half, y - thick), strokeWidth = thick, cap = StrokeCap.Round)

    drawLine(color, Offset(x - half, y + thick), Offset(x + half, y + thick), strokeWidth = thick, cap = StrokeCap.Round)
    drawLine(borderColor, Offset(x - half, y + thick), Offset(x + half, y + thick), strokeWidth = thick + strokeW, cap = StrokeCap.Round)
    drawLine(color, Offset(x - half, y + thick), Offset(x + half, y + thick), strokeWidth = thick, cap = StrokeCap.Round)

    drawLine(color, Offset(x - thick, y - half), Offset(x - thick, y + half), strokeWidth = thick, cap = StrokeCap.Round)
    drawLine(borderColor, Offset(x - thick, y - half), Offset(x - thick, y + half), strokeWidth = thick + strokeW, cap = StrokeCap.Round)
    drawLine(color, Offset(x - thick, y - half), Offset(x - thick, y + half), strokeWidth = thick, cap = StrokeCap.Round)

    drawLine(color, Offset(x + thick, y - half), Offset(x + thick, y + half), strokeWidth = thick, cap = StrokeCap.Round)
    drawLine(borderColor, Offset(x + thick, y - half), Offset(x + thick, y + half), strokeWidth = thick + strokeW, cap = StrokeCap.Round)
    drawLine(color, Offset(x + thick, y - half), Offset(x + thick, y + half), strokeWidth = thick, cap = StrokeCap.Round)
}

// 辅助 Zzz 气泡绘制
private fun DrawScope.drawZzzBubble(x: Float, y: Float, radius: Float, alpha: Float) {
    if (alpha <= 0.02f) return
    drawCircle(
        color = Color(0x66B0BEC5).copy(alpha = alpha * 0.4f),
        radius = radius,
        center = Offset(x, y)
    )
    drawCircle(
        color = Color(0xFF281C38).copy(alpha = alpha * 0.7f),
        radius = radius,
        center = Offset(x, y),
        style = Stroke(1.5f)
    )
    // 气泡内小 Z
    val zPath = Path().apply {
        val s = radius * 0.45f
        moveTo(x - s, y - s)
        lineTo(x + s, y - s)
        lineTo(x - s, y + s)
        lineTo(x + s, y + s)
    }
    drawPath(zPath, Color(0xFF37474F).copy(alpha = alpha), style = Stroke(2f, cap = StrokeCap.Round))
}

// 辅助感叹号气泡绘制
private fun DrawScope.drawExclamationBubble(x: Float, y: Float, size: Float, color: Color) {
    drawCircle(color, size, Offset(x, y))
    drawCircle(Color(0xFF281C38), size, Offset(x, y), style = Stroke(2.5f))
    drawLine(Color(0xFF281C38), Offset(x, y - size * 0.5f), Offset(x, y + size * 0.1f), strokeWidth = 3.5f, cap = StrokeCap.Round)
    drawCircle(Color(0xFF281C38), 2.2f, Offset(x, y + size * 0.5f))
}

// 辅助爱心绘制
private fun DrawScope.drawHeart(
    x: Float,
    y: Float,
    size: Float,
    color: Color
) {
    val path = Path().apply {
        moveTo(x, y + size * 0.3f)
        cubicTo(x - size, y - size * 0.5f, x - size * 1.3f, y + size * 0.6f, x, y + size * 1.3f)
        cubicTo(x + size * 1.3f, y + size * 0.6f, x + size, y - size * 0.5f, x, y + size * 0.3f)
    }
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38), style = Stroke(2f))
}

// 辅助泪滴绘制
private fun DrawScope.drawTearDrop(
    x: Float,
    y: Float,
    radius: Float,
    color: Color
) {
    val path = Path().apply {
        moveTo(x, y - radius)
        cubicTo(x - radius, y, x - radius, y + radius, x, y + radius)
        cubicTo(x + radius, y + radius, x + radius, y, x, y - radius)
    }
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38), style = Stroke(1.5f))
}

// 辅助五角星绘制
private fun DrawScope.drawStar(
    x: Float,
    y: Float,
    radius: Float,
    color: Color
) {
    val path = Path()
    val innerRadius = radius * 0.45f
    for (i in 0..9) {
        val r = if (i % 2 == 0) radius else innerRadius
        val angle = i * Math.PI / 5.0 - Math.PI / 2.0
        val px = x + (r * Math.cos(angle)).toFloat()
        val py = y + (r * Math.sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38), style = Stroke(2.5f))
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
 * 绘制高分辨率真实二次元角色模型 (小鸟游星野 官方全模立绘)
 * 具备环境光与闪光灯自适应混色、独立发光悬浮 Halo、足底精密 Contact AO 与真实定向拉伸阴影
 */
private fun DrawScope.drawHighResolutionHoshino(
    bitmap: ImageBitmap,
    cx: Float,
    cy: Float,
    s: Float,
    headSquishY: Float,
    haloX: Float,
    haloY: Float,
    visualState: CharacterVisualState,
    envLight: Float,
    drowsyCycle: Float,
    emotePulse: Float,
    sparkleAngle: Float,
    animTime: Float = 0f
) {
    val charW = 185f * s
    val charH = charW * (bitmap.height.toFloat() / bitmap.width.toFloat())
    val footY = cy + 245f * s
    val topY = footY - charH
    val leftX = cx - charW / 2f

    // 1. 环境光色温调和与局部材质二次漫反射 (Radiosity Tint)
    // 静态立绘自带完整光影，严禁按环境照度整体乘法，暗光下不能被压成黑色剪影（导致粉发边缘露白像长羽毛）。
    // 采用与动作精灵表一致的柔和色温微调公式，辅以微弱桌面漫反射二次散射，保持二次元伴侣始终通透生动。
    val radiosityBoost = (visualState.environmentalLighting * 0.05f).coerceIn(0f, 0.07f)
    val colorFilter = ColorFilter.lighting(
        multiply = Color(
            red = (0.92f + (visualState.ambientTintRed - 1f) * 0.3f).coerceIn(0.85f, 1.05f),
            green = (0.92f + (visualState.ambientTintGreen - 1f) * 0.3f).coerceIn(0.85f, 1.05f),
            blue = (0.92f + (visualState.ambientTintBlue - 1f) * 0.3f).coerceIn(0.85f, 1.05f)
        ),
        add = Color(
            red = (visualState.ambientTintRed * radiosityBoost * 0.4f).coerceIn(0f, 0.04f),
            green = (visualState.ambientTintGreen * radiosityBoost * 0.4f).coerceIn(0f, 0.04f),
            blue = (visualState.ambientTintBlue * radiosityBoost * 0.4f).coerceIn(0f, 0.04f),
            alpha = 0f
        )
    )

    // 2. 姿态动作姿态角解算 (打瞌睡慢点头、傲娇轻扭头、得意挺胸与惯性摆角融合)
    val isSleepyMode = visualState.sleepinessFactor > 0.05f ||
            visualState.emotion == EmotionType.SLEEPY ||
            visualState.action == ActionType.SLEEP ||
            visualState.highResPose == "SLEEPY"
    val sleepIntensity = if (isSleepyMode) maxOf(visualState.sleepinessFactor, 0.85f) else 0f
    val drowsyNodAngle = if (isSleepyMode) kotlin.math.sin(drowsyCycle) * 3.6f * sleepIntensity else 0f

    val isPoutMode = visualState.poutIntensity > 0.05f ||
            visualState.emotion == EmotionType.ANGRY ||
            visualState.action == ActionType.POUT ||
            visualState.highResPose == "POUT"
    val poutIntensity = if (isPoutMode) maxOf(visualState.poutIntensity, 0.85f) else 0f
    val poutSwayAngle = if (isPoutMode) -poutIntensity * 4.5f else 0f

    val isSmugMode = visualState.smugTiltDegrees > 0.5f ||
            visualState.emotion == EmotionType.CONFIDENT ||
            visualState.action == ActionType.SMUG ||
            visualState.highResPose == "SMUG"
    val smugTiltAngle = if (isSmugMode) -maxOf(visualState.smugTiltDegrees, 4.0f) * 0.8f else 0f

    val isJoyMode = visualState.emotion == EmotionType.JOY || visualState.action == ActionType.WAVE || visualState.action == ActionType.JUMP
    val joyBounce = if (isJoyMode) kotlin.math.abs(kotlin.math.sin(animTime * 6f)) * 4.5f * s else 0f

    val isShyMode = visualState.emotion == EmotionType.SHY || visualState.action == ActionType.BLUSH
    val shyTrembleAngle = if (isShyMode) kotlin.math.sin(animTime * 22f) * 1.5f else 0f

    val totalPoseAngle = visualState.inertialSwayAngle + drowsyNodAngle + poutSwayAngle + smugTiltAngle + shyTrembleAngle

    val sitP = visualState.sitProgress.coerceIn(0f, 1f)
    val sitDropY = charH * 0.165f * sitP
    val effectiveTopY = topY + sitDropY - joyBounce
    val effectiveLeftX = cx - charW / 2f

    // 3. 惯性物理摆动、重力下垂与悬空微摇 (以地面足底接触线或身体重心为旋转原点)
    val pivotPoint = if (visualState.isDanglingOverCliff) {
        Offset(cx, effectiveTopY + charH * 0.45f) // 悬空状态以躯干重心为轴心钟摆下垂
    } else if (sitP > 0.4f) {
        Offset(cx, effectiveTopY + charH * 0.65f) // 坐姿以臀部坐下支撑点为轴心
    } else {
        Offset(cx, footY) // 踏地状态以足底接触点为轴心
    }

    val dangleDroopStretchY = if (visualState.isDanglingOverCliff) {
        (visualState.inertialSquishY * (1.0f + visualState.dangleAirDistance * 0.08f)).coerceIn(1.0f, 1.14f)
    } else {
        visualState.inertialSquishY.coerceIn(0.92f, 1.08f)
    }

    val scaleX = (1.0f / dangleDroopStretchY).coerceIn(0.90f, 1.08f) * visualState.cylinderContractionX

    // 4. 坐姿下的桌面接触 AO 阴影
    if (sitP > 0.05f) {
        val sitDeskY = effectiveTopY + charH * 0.64f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.55f * sitP * (1.0f - visualState.dangleAirDistance)),
                    Color.Black.copy(alpha = 0.22f * sitP * (1.0f - visualState.dangleAirDistance)),
                    Color.Transparent
                ),
                center = Offset(cx, sitDeskY),
                radius = 38f * s
            ),
            topLeft = Offset(cx - 38f * s, sitDeskY - 8f * s),
            size = Size(76f * s, 16f * s)
        )
    }

    withTransform({
        rotate(
            degrees = totalPoseAngle,
            pivot = pivotPoint
        )
        scale(
            scaleX = scaleX,
            scaleY = dangleDroopStretchY,
            pivot = pivotPoint
        )
    }) {
        // 如果是背面回眸视角，绘制后背长发底垫
        if (visualState.backProfileProgress > 0.35f) {
            draw3DBackLookBackPose(
                cx = cx,
                topY = effectiveTopY,
                charW = charW,
                charH = charH,
                s = s,
                backProgress = visualState.backProfileProgress,
                visualState = visualState
            )
        }

        // 绘制高精度角色图像
        if (sitP > 0.15f) {
            // 坐姿时：绘制坐姿上半身 (裁剪下肢并融合坐姿折叠双腿)
            drawHighResSittingCharacter(
                bitmap = bitmap,
                leftX = effectiveLeftX,
                topY = effectiveTopY,
                charW = charW,
                charH = charH,
                cx = cx,
                s = s,
                sitP = sitP,
                colorFilter = colorFilter,
                drowsyCycle = drowsyCycle,
                visualState = visualState
            )
        } else {
            // 站立姿态全模
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(effectiveLeftX.roundToInt(), effectiveTopY.roundToInt()),
                dstSize = IntSize(charW.roundToInt(), charH.roundToInt()),
                colorFilter = colorFilter
            )
        }

        // 5. 3D 多视角面部视差位移与微表情差分渲染
        val faceDisparity = visualState.depthDisparityFace * s
        drawHighResFacialExpressions(
            cx = cx + faceDisparity,
            topY = effectiveTopY,
            charW = charW,
            charH = charH,
            s = s,
            visualState = visualState,
            sleepIntensity = sleepIntensity,
            poutIntensity = poutIntensity,
            isSmugMode = isSmugMode,
            drowsyCycle = drowsyCycle,
            emotePulse = emotePulse,
            sparkleAngle = sparkleAngle
        )

    }

    // 7. 前景物理遮挡保护 (例如被近处机械键盘键帽遮挡脚部，避免逆向穿模)
    if (visualState.foregroundOcclusionRatio > 0.02f) {
        val cutoffH = charH * visualState.foregroundOcclusionRatio
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                startY = footY - cutoffH,
                endY = footY
            ),
            topLeft = Offset(leftX - 10f * s, footY - cutoffH),
            size = Size(charW + 20f * s, cutoffH),
            blendMode = BlendMode.DstOut
        )
    }

    // 8. 悬崖边缘警惕与悬空微表情 (Cliff Edge Alert Emotes: 💦 萌系警惕微汗珠)
    if (visualState.isDanglingOverCliff || visualState.cliffEdgeIntensity > 0.35f) {
        val alertIntensity = if (visualState.isDanglingOverCliff) 1.0f else visualState.cliffEdgeIntensity
        drawCliffAlertEmote(
            cx = cx + 38f * s + totalPoseAngle * 1.2f,
            topY = topY + 68f * s,
            s = s,
            intensity = alertIntensity,
            isDangling = visualState.isDanglingOverCliff
        )
    }

    // 9. 情感微粒子 (动态漫画情绪符号层)
    drawGameEmoteParticles(cx, topY + 60f * s, s, visualState, animTime)
}

/**
 * 绘制高精模型异色双瞳视线追焦 (Eye Contact Gaze Focus)
 */
private fun DrawScope.drawHighResGazeFocus(
    cx: Float,
    topY: Float,
    charW: Float,
    charH: Float,
    s: Float,
    visualState: CharacterVisualState
) {
    // 眼睛在星野高精立绘中的基准坐标 (面部中心 y 约在顶部 22% 处)
    val eyeBaseY = topY + charH * 0.225f
    val eyeDist = 18f * s
    val eyeGazeX = visualState.gazeTrackingEyeX * s * 0.5f
    val eyeGazeY = visualState.gazeTrackingEyeY * s * 0.4f

    // 1) 左眼 (澄澈湖蓝色) 瞳孔微动反光焦点
    val leftEyeCenter = Offset(cx - eyeDist + eyeGazeX, eyeBaseY + eyeGazeY)
    drawCircle(
        color = Color(0x6638BDF8),
        radius = 3.5f * s,
        center = leftEyeCenter
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = 1.6f * s,
        center = Offset(leftEyeCenter.x - 1f * s, leftEyeCenter.y - 1f * s)
    )

    // 2) 右眼 (暖金琥珀色) 瞳孔微动反光焦点
    val rightEyeCenter = Offset(cx + eyeDist + eyeGazeX, eyeBaseY + eyeGazeY)
    drawCircle(
        color = Color(0x66F59E0B),
        radius = 3.5f * s,
        center = rightEyeCenter
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = 1.6f * s,
        center = Offset(rightEyeCenter.x - 1f * s, rightEyeCenter.y - 1f * s)
    )
}

/**
 * 绘制坐姿下半身与桌面垂腿下肢物理 (Folded Seated Legs & Dangling Calves)
 */
private fun DrawScope.drawHighResSittingCharacter(
    bitmap: ImageBitmap,
    leftX: Float,
    topY: Float,
    charW: Float,
    charH: Float,
    cx: Float,
    s: Float,
    sitP: Float,
    colorFilter: ColorFilter,
    drowsyCycle: Float,
    visualState: CharacterVisualState
) {
    // 1. 坐姿躯干与水手服 (绘制上半身并平滑收束在桌面水平线)
    val hipsDeskY = topY + charH * 0.63f
    val cutoffBottom = charH * 0.37f * sitP

    drawImage(
        image = bitmap,
        dstOffset = IntOffset(leftX.roundToInt(), topY.roundToInt()),
        dstSize = IntSize(charW.roundToInt(), charH.roundToInt()),
        colorFilter = colorFilter
    )

    // 遮罩消除原立绘直立腿部，无缝替换为折叠坐姿双腿
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.98f)),
            startY = hipsDeskY - 5f * s,
            endY = hipsDeskY + 18f * s
        ),
        topLeft = Offset(leftX - 10f * s, hipsDeskY),
        size = Size(charW + 20f * s, cutoffBottom + 80f * s),
        blendMode = BlendMode.DstOut
    )

    // 2. 坐姿折叠百褶裙摆 (铺展在桌面，形成自然坐姿压折与层次感)
    val skirtColor = Color(0xFF1E2842)
    val skirtShadowColor = Color(0xFF141A2E)
    val skirtHemY = hipsDeskY
    val skirtWidth = 72f * s

    drawOval(
        color = skirtShadowColor,
        topLeft = Offset(cx - skirtWidth * 0.55f, skirtHemY - 4f * s),
        size = Size(skirtWidth * 1.1f, 22f * s)
    )
    drawOval(
        color = skirtColor,
        topLeft = Offset(cx - skirtWidth * 0.5f, skirtHemY - 8f * s),
        size = Size(skirtWidth, 18f * s)
    )

    // 3. 坐姿向前延伸的水平大腿 (Thighs on desk surface)
    val legSpread = 16f * s
    val thighLength = 26f * s
    val sockColor = Color(0xFF1A1A24) // 经典过膝黑丝袜
    val sockHighlight = Color(0xFF2C2C3D)

    for (dir in listOf(-1f, 1f)) {
        val legCenterX = cx + dir * legSpread
        // 水平大腿
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(sockHighlight, sockColor),
                startY = skirtHemY - 4f * s,
                endY = skirtHemY + thighLength
            ),
            topLeft = Offset(legCenterX - 9f * s, skirtHemY - 2f * s),
            size = Size(18f * s, thighLength),
            cornerRadius = CornerRadius(6f * s, 6f * s)
        )

        // 4. 桌沿垂直悬垂小腿与足尖 (Calves dangling past desk edge)
        val danglingKneeY = skirtHemY + thighLength - 4f * s
        val calfLength = 52f * s
        val swingDeg = kotlin.math.sin(drowsyCycle * 1.5f + dir * 0.5f) * 4.2f * sitP

        withTransform({
            rotate(
                degrees = swingDeg,
                pivot = Offset(legCenterX, danglingKneeY)
            )
        }) {
            // 圆润膝关节
            drawCircle(
                color = sockHighlight,
                radius = 9.5f * s,
                center = Offset(legCenterX, danglingKneeY)
            )

            // 悬垂小腿 (带自然二次元腿型收束)
            val calfPath = Path().apply {
                moveTo(legCenterX - 8.5f * s, danglingKneeY)
                lineTo(legCenterX - 6.5f * s, danglingKneeY + calfLength)
                lineTo(legCenterX + 6.5f * s, danglingKneeY + calfLength)
                lineTo(legCenterX + 8.5f * s, danglingKneeY)
                close()
            }
            drawPath(
                path = calfPath,
                brush = Brush.horizontalGradient(
                    colors = listOf(sockColor, sockHighlight, sockColor),
                    startX = legCenterX - 9f * s,
                    endX = legCenterX + 9f * s
                )
            )

            // 5. 悬空脚踝与黑色乐福校服皮鞋 (School Loafers)
            val ankleY = danglingKneeY + calfLength
            val shoeColor = Color(0xFF0F0F14)
            val shoeHighlight = Color(0xFF424254)

            // 白色袜口边线 (萌点细节)
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(legCenterX - 6.5f * s, ankleY - 2f * s),
                end = Offset(legCenterX + 6.5f * s, ankleY - 2f * s),
                strokeWidth = 1.5f * s
            )

            // 黑色小皮鞋
            val shoePath = Path().apply {
                moveTo(legCenterX - 7f * s, ankleY)
                cubicTo(
                    legCenterX - 7f * s, ankleY + 12f * s,
                    legCenterX + dir * 3f * s, ankleY + 16f * s,
                    legCenterX + dir * 4f * s, ankleY + 18f * s
                )
                lineTo(legCenterX + dir * 1f * s, ankleY + 18f * s)
                cubicTo(
                    legCenterX - 4f * s, ankleY + 14f * s,
                    legCenterX - 7.5f * s, ankleY + 8f * s,
                    legCenterX - 7f * s, ankleY
                )
                close()
            }
            drawPath(path = shoePath, color = shoeColor)
            // 皮鞋反光高光点
            drawCircle(
                color = shoeHighlight,
                radius = 2.2f * s,
                center = Offset(legCenterX + dir * 2f * s, ankleY + 12f * s)
            )
        }
    }

    // 6. 扶桌双手 (Hands resting beside hips on desk)
    val handDeskY = skirtHemY + 2f * s
    for (dir in listOf(-1f, 1f)) {
        val handX = cx + dir * (skirtWidth * 0.48f)
        // 水手服白袖口
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(handX - 6f * s, handDeskY - 14f * s),
            size = Size(12f * s, 12f * s),
            cornerRadius = CornerRadius(3f * s, 3f * s)
        )
        // 蓝色袖口条纹
        drawLine(
            color = Color(0xFF0288D1),
            start = Offset(handX - 5.5f * s, handDeskY - 4f * s),
            end = Offset(handX + 5.5f * s, handDeskY - 4f * s),
            strokeWidth = 1.8f * s
        )
        // 纤巧萌系小手
        drawOval(
            color = Color(0xFFFFE0D0),
            topLeft = Offset(handX - 5f * s, handDeskY - 2f * s),
            size = Size(10f * s, 8f * s)
        )
    }
}

/**
 * 绘制头发天使光环各向异性高光与二次元阶梯阴影 (Toon Cel-Shading & Hair Anisotropy)
 */
private fun DrawScope.drawToonCelShadingAndHairAnisotropy(
    cx: Float,
    topY: Float,
    charW: Float,
    charH: Float,
    s: Float,
    visualState: CharacterVisualState
) {
    // 1. 头发天使光环各向异性高光 (Anisotropic Angel Ring Specular)
    val hairCrownY = topY + charH * 0.145f
    val ringWidth = 75f * s
    val ringHeight = 16f * s
    val lightAngle = visualState.lightDirectionX * 18f * s

    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.75f),
                Color(0xFFFFE4EC).copy(alpha = 0.45f),
                Color.Transparent
            ),
            center = Offset(cx + lightAngle, hairCrownY),
            radius = ringWidth * 0.45f
        ),
        topLeft = Offset(cx + lightAngle - ringWidth * 0.45f, hairCrownY - ringHeight * 0.5f),
        size = Size(ringWidth * 0.9f, ringHeight)
    )

    // 2. 二次元阶梯 Cel-Shading 侧向漫反射阴影 (Toon Shadow Ramp)
    if (visualState.threeQuarterProfileProgress > 0.08f) {
        val shadowProgress = visualState.threeQuarterProfileProgress
        val isLeftFacing = visualState.depthDisparityFace > 0
        val shadowSideX = if (isLeftFacing) cx + charW * 0.12f else cx - charW * 0.38f
        val shadowW = charW * 0.28f * shadowProgress

        drawRect(
            brush = Brush.horizontalGradient(
                colors = if (isLeftFacing) {
                    listOf(Color.Transparent, Color(0x221B1432), Color(0x38120C24))
                } else {
                    listOf(Color(0x38120C24), Color(0x221B1432), Color.Transparent)
                },
                startX = shadowSideX,
                endX = shadowSideX + shadowW
            ),
            topLeft = Offset(shadowSideX, topY + charH * 0.15f),
            size = Size(shadowW, charH * 0.65f),
            blendMode = BlendMode.SrcOver
        )
    }
}

/**
 * 绘制 3D 背面回眸姿态底垫 (Back Look-Back Pose)
 */
private fun DrawScope.draw3DBackLookBackPose(
    cx: Float,
    topY: Float,
    charW: Float,
    charH: Float,
    s: Float,
    backProgress: Float,
    visualState: CharacterVisualState
) {
    val alpha = (backProgress * 0.95f).coerceIn(0f, 0.95f)
    val hairBackY = topY + charH * 0.18f
    val hairW = 105f * s
    val hairH = 145f * s

    // 1. 唯美柔顺粉色后背长发流线
    val backHairPath = Path().apply {
        moveTo(cx - hairW * 0.42f, hairBackY)
        cubicTo(
            cx - hairW * 0.55f, hairBackY + hairH * 0.4f,
            cx - hairW * 0.35f, hairBackY + hairH * 0.85f,
            cx, hairBackY + hairH
        )
        cubicTo(
            cx + hairW * 0.35f, hairBackY + hairH * 0.85f,
            cx + hairW * 0.55f, hairBackY + hairH * 0.4f,
            cx + hairW * 0.42f, hairBackY
        )
        close()
    }
    drawPath(
        path = backHairPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFC0D0).copy(alpha = alpha),
                Color(0xFFFF9EB5).copy(alpha = alpha)
            ),
            startY = hairBackY,
            endY = hairBackY + hairH
        )
    )

    // 2. 水手服后背披肩 (Sailor Collar Back Flap)
    val flapY = topY + charH * 0.34f
    val flapW = 54f * s
    val flapH = 34f * s
    drawRoundRect(
        color = Color(0xFF1E2842).copy(alpha = alpha),
        topLeft = Offset(cx - flapW * 0.5f, flapY),
        size = Size(flapW, flapH),
        cornerRadius = CornerRadius(4f * s, 4f * s)
    )
    // 水手服双道经典青蓝细边
    drawRoundRect(
        color = Color(0xFF00E5FF).copy(alpha = alpha * 0.8f),
        topLeft = Offset(cx - flapW * 0.44f, flapY + 3f * s),
        size = Size(flapW * 0.88f, flapH - 6f * s),
        style = Stroke(width = 1.6f * s),
        cornerRadius = CornerRadius(3f * s, 3f * s)
    )
}

/**
 * 绘制悬崖警觉/悬空微表情 (二次元萌系受惊微汗珠 💦)
 */
private fun DrawScope.drawCliffAlertEmote(
    cx: Float,
    topY: Float,
    s: Float,
    intensity: Float,
    isDangling: Boolean
) {
    val sweatW = 10f * s
    val sweatH = 16f * s
    val dropPath = Path().apply {
        moveTo(cx, topY)
        cubicTo(
            cx + sweatW * 0.8f, topY + sweatH * 0.45f,
            cx + sweatW * 0.7f, topY + sweatH,
            cx, topY + sweatH
        )
        cubicTo(
            cx - sweatW * 0.7f, topY + sweatH,
            cx - sweatW * 0.8f, topY + sweatH * 0.45f,
            cx, topY
        )
        close()
    }

    val sweatAlpha = (0.85f * intensity).coerceIn(0.15f, 0.92f)
    drawPath(
        path = dropPath,
        color = Color(0xFF38BDF8).copy(alpha = sweatAlpha)
    )
    // 汗珠边缘高光与立体光泽
    drawPath(
        path = dropPath,
        color = Color.White.copy(alpha = sweatAlpha * 0.7f),
        style = Stroke(width = 1.2f * s)
    )
    drawCircle(
        color = Color.White.copy(alpha = sweatAlpha * 0.95f),
        radius = 1.8f * s,
        center = Offset(cx - 2f * s, topY + sweatH * 0.65f)
    )
}

/**
 * 绘制官方高精全模微表情覆盖系统 (Facial Expressions & Anime Micro-Emotes)
 * 涵盖：打瞌睡半闭眼与打哈欠/Zzz气泡、害羞双颊红晕与手绘斜线、傲娇鼓颊小噘嘴与青筋、得意眨眼星芒闪耀
 */
private fun DrawScope.drawHighResFacialExpressions(
    cx: Float,
    topY: Float,
    charW: Float,
    charH: Float,
    s: Float,
    visualState: CharacterVisualState,
    sleepIntensity: Float,
    poutIntensity: Float,
    isSmugMode: Boolean,
    drowsyCycle: Float,
    emotePulse: Float,
    sparkleAngle: Float
) {
    val eyeBaseY = topY + charH * 0.225f
    val eyeDist = 18f * s
    val leftEyeX = cx - eyeDist
    val rightEyeX = cx + eyeDist
    val cheekY = eyeBaseY + 12f * s
    val mouthY = eyeBaseY + 28f * s

    val isShyMode = visualState.blushIntensity > 0.05f ||
            visualState.emotion == EmotionType.SHY ||
            visualState.action == ActionType.BLUSH ||
            visualState.highResPose == "SHY"
    val blushIntensity = if (isShyMode) maxOf(visualState.blushIntensity, 0.85f) else 0f

    // 1. 害羞双颊红晕与二次元斜线 (Anime Radiant Blush)
    if (blushIntensity > 0.05f) {
        val blushAlpha = (0.75f * blushIntensity * emotePulse).coerceIn(0.2f, 0.95f)
        val blushRadius = 16f * s
        // 左面颊红晕
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF4081).copy(alpha = blushAlpha * 0.7f),
                    Color(0xFFFF80AB).copy(alpha = blushAlpha * 0.35f),
                    Color.Transparent
                ),
                center = Offset(cx - 25f * s, cheekY),
                radius = blushRadius
            ),
            topLeft = Offset(cx - 25f * s - blushRadius, cheekY - blushRadius * 0.55f),
            size = Size(blushRadius * 2f, blushRadius * 1.1f)
        )
        // 右面颊红晕
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF4081).copy(alpha = blushAlpha * 0.7f),
                    Color(0xFFFF80AB).copy(alpha = blushAlpha * 0.35f),
                    Color.Transparent
                ),
                center = Offset(cx + 25f * s, cheekY),
                radius = blushRadius
            ),
            topLeft = Offset(cx + 25f * s - blushRadius, cheekY - blushRadius * 0.55f),
            size = Size(blushRadius * 2f, blushRadius * 1.1f)
        )

        // 双颊经典 45° 萌系三斜线
        for (i in -1..1) {
            val ox = i * 4.5f * s
            drawLine(
                color = Color(0xFFF50057).copy(alpha = blushAlpha * 0.8f),
                start = Offset(cx - 25f * s + ox - 3f * s, cheekY - 3.5f * s),
                end = Offset(cx - 25f * s + ox + 3f * s, cheekY + 3.5f * s),
                strokeWidth = 1.3f * s,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFFF50057).copy(alpha = blushAlpha * 0.8f),
                start = Offset(cx + 25f * s + ox - 3f * s, cheekY - 3.5f * s),
                end = Offset(cx + 25f * s + ox + 3f * s, cheekY + 3.5f * s),
                strokeWidth = 1.3f * s,
                cap = StrokeCap.Round
            )
        }
    }

    // 2. 傲娇鼓颊与额头生气青筋 (Pouty Cheek Puff & 💢 Anger Mark)
    if (poutIntensity > 0.05f) {
        val poutAlpha = (0.85f * poutIntensity).coerceIn(0.2f, 0.95f)
        // 鼓颊软肉微光
        drawCircle(
            color = Color(0xFFFF80AB).copy(alpha = poutAlpha * 0.45f),
            radius = 10f * s,
            center = Offset(cx - 30f * s, cheekY + 2f * s)
        )
        drawCircle(
            color = Color(0xFFFF80AB).copy(alpha = poutAlpha * 0.45f),
            radius = 10f * s,
            center = Offset(cx + 30f * s, cheekY + 2f * s)
        )
        // 傲娇小噘嘴 (圆嘟嘟小口)
        drawOval(
            color = Color(0xFFE91E63).copy(alpha = poutAlpha * 0.9f),
            topLeft = Offset(cx - 3.5f * s, mouthY - 2.5f * s),
            size = Size(7f * s, 5f * s)
        )
    }

    // 3. 打瞌睡与打哈欠 (Sleepy Half-closed Eyes & Yawn Mouth & Zzz Bubbles)
    if (sleepIntensity > 0.05f) {
        val sleepAlpha = (0.90f * sleepIntensity).coerceIn(0.2f, 0.98f)
        val skinToneColor = Color(0xFFFFF2EB)

        // 1) 困倦上眼睑半闭遮罩 (遮住上半部分瞳孔，呈现朦胧半醒的慵懒大叔态)
        drawArc(
            color = skinToneColor.copy(alpha = sleepAlpha * 0.92f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(leftEyeX - 10f * s, eyeBaseY - 11f * s),
            size = Size(20f * s, 14f * s)
        )
        drawArc(
            color = skinToneColor.copy(alpha = sleepAlpha * 0.92f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(rightEyeX - 10f * s, eyeBaseY - 11f * s),
            size = Size(20f * s, 14f * s)
        )

        // 柔和微垂的眼睫毛曲线描边
        drawArc(
            color = Color(0xFF5D4037).copy(alpha = sleepAlpha),
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(leftEyeX - 10f * s, eyeBaseY - 8f * s),
            size = Size(20f * s, 12f * s),
            style = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color(0xFF5D4037).copy(alpha = sleepAlpha),
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(rightEyeX - 10f * s, eyeBaseY - 8f * s),
            size = Size(20f * s, 12f * s),
            style = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
        )

        // 眼角一抹困倦小晶莹微泪滴
        drawCircle(
            color = Color(0xCC64B5F6),
            radius = 1.6f * s,
            center = Offset(leftEyeX + 9f * s, eyeBaseY + 3f * s)
        )
        drawCircle(
            color = Color(0xCC64B5F6),
            radius = 1.6f * s,
            center = Offset(rightEyeX + 9f * s, eyeBaseY + 3f * s)
        )

        // 2) 打哈欠软萌小张口 (随呼吸周期张合)
        val yawnStretch = (0.7f + 0.3f * kotlin.math.sin(drowsyCycle)).coerceIn(0.5f, 1.0f)
        val mouthW = 8f * s * yawnStretch
        val mouthH = 9f * s * yawnStretch
        drawOval(
            color = Color(0xFFE57373).copy(alpha = sleepAlpha * 0.95f),
            topLeft = Offset(cx - mouthW * 0.5f, mouthY - mouthH * 0.5f),
            size = Size(mouthW, mouthH)
        )
        drawOval(
            color = Color(0xFFEF9A9A).copy(alpha = sleepAlpha * 0.85f),
            topLeft = Offset(cx - mouthW * 0.35f, mouthY - mouthH * 0.3f),
            size = Size(mouthW * 0.7f, mouthH * 0.6f)
        )

        // 3) 梦幻漂浮 Zzz 睡眠气泡 (向上缓动渐隐)
        val bubbleBaseX = cx + 46f * s
        val bubbleBaseY = topY + 48f * s
        for (k in 0..2) {
            val phase = (drowsyCycle + k * 1.8f) % (2 * Math.PI.toFloat())
            val progress = phase / (2 * Math.PI.toFloat())
            val by = bubbleBaseY - progress * 42f * s
            val bx = bubbleBaseX + kotlin.math.sin(progress * 5f) * 6f * s
            val bRadius = (3.5f + k * 2.2f) * s * (0.8f + progress * 0.4f)
            val bAlpha = ((1.0f - progress) * 0.75f * sleepAlpha).coerceIn(0f, 0.85f)

            if (bAlpha > 0.05f) {
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = bAlpha * 0.25f),
                    radius = bRadius,
                    center = Offset(bx, by)
                )
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = bAlpha * 0.7f),
                    radius = bRadius,
                    center = Offset(bx, by),
                    style = Stroke(width = 1.0f * s)
                )
                // 气泡高光
                drawCircle(
                    color = Color.White.copy(alpha = bAlpha * 0.85f),
                    radius = bRadius * 0.3f,
                    center = Offset(bx - bRadius * 0.35f, by - bRadius * 0.35f)
                )
            }
        }
    } else if (isSmugMode) {
        // 4. 得意自信叉腰与眨眼微笑 (Smug Winking & Golden Sparkles ✨)
        // 左眼眨成上扬的俏皮弯月弧线 ^
        drawArc(
            color = Color(0xFF4A148C),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(leftEyeX - 9f * s, eyeBaseY - 6f * s),
            size = Size(18f * s, 10f * s),
            style = Stroke(width = 2.2f * s, cap = StrokeCap.Round)
        )
        // 右眼大放异彩
        drawHighResGazeFocus(
            cx = cx,
            topY = topY,
            charW = charW,
            charH = charH,
            s = s,
            visualState = visualState
        )
        // 微微挑起的前辈得意嘴角
        drawArc(
            color = Color(0xFFD81B60),
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(cx - 5f * s, mouthY - 3f * s),
            size = Size(10f * s, 6f * s),
            style = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
        )

        // 4 颗围绕闪耀的四角星芒粒子 (Sparkle Stars)
        val starPositions = listOf(
            Offset(cx - 55f * s, topY + 80f * s),
            Offset(cx + 52f * s, topY + 75f * s),
            Offset(cx - 48f * s, topY + 160f * s),
            Offset(cx + 50f * s, topY + 155f * s)
        )
        for ((idx, pos) in starPositions.withIndex()) {
            val rot = sparkleAngle + idx * 45f
            val starRadius = (5f + (idx % 2) * 2.5f) * s
            withTransform({
                rotate(degrees = rot, pivot = pos)
            }) {
                // 绘制四角星
                val starPath = Path().apply {
                    moveTo(pos.x, pos.y - starRadius)
                    cubicTo(pos.x, pos.y, pos.x, pos.y, pos.x + starRadius, pos.y)
                    cubicTo(pos.x, pos.y, pos.x, pos.y, pos.x, pos.y + starRadius)
                    cubicTo(pos.x, pos.y, pos.x, pos.y, pos.x - starRadius, pos.y)
                    cubicTo(pos.x, pos.y, pos.x, pos.y, pos.x, pos.y - starRadius)
                    close()
                }
                drawPath(starPath, Color(0xFFFFD54F).copy(alpha = 0.90f))
                drawPath(starPath, Color.White.copy(alpha = 0.95f), style = Stroke(width = 1.0f * s))
            }
        }
    } else {
        // 5. 默认状态：澄澈双瞳视线追踪 (Eye Contact Tracking)
        drawHighResGazeFocus(
            cx = cx,
            topY = topY,
            charW = charW,
            charH = charH,
            s = s,
            visualState = visualState
        )
    }
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
    val charW = 185f * s
    val charH = charW * (clip.frameHeight.toFloat() / clip.frameWidth.toFloat())
    val footY = cy + 245f * s
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
