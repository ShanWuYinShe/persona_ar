package com.persona.ar.character

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
    val vibrator = remember {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    val scope = rememberCoroutineScope()

    // 加载高精度官方级角色贴图资产 (若存在则优先使用高精度真实模型，彻底告别粗糙简笔人)
    val hoshinoBitmap = remember(context) {
        try {
            BitmapFactory.decodeResource(context.resources, com.persona.ar.R.drawable.hoshino_high_res)?.asImageBitmap()
        } catch (e: Throwable) {
            null
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

                    // 1. 部位专属精细触觉震动反馈
                    triggerHapticFeedback(vibrator, part)

                    // 2. 触发对应部位局部弹性缩放
                    activeTouchPart = part
                    scope.launch {
                        delay(240L)
                        if (activeTouchPart == part) {
                            activeTouchPart = null
                        }
                    }

                    // 3. 传出精准触碰坐标给外部粒子飘字
                    onTouchPart(part, normalizedX, normalizedY)
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(
                        (offsetX + visualState.tiltOffsetX).roundToInt(),
                        (offsetY + visualState.tiltOffsetY + breathBounce + jumpAnim + bodySquishOffset).roundToInt()
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

            // 4) 头顶契约光环 Z >>> 0 (高高悬浮在头顶上方最前层)
            val haloX = cx + kotlin.math.sin(rollRad) * 12f * s
            val haloY = cy + kotlin.math.sin(pitchRad) * 14f * s

            // 逐层立体空间渲染 (优先使用高保真动漫模型，保持多层视差与真实物理光影)
            if (visualState.useHighResolutionModel && hoshinoBitmap != null) {
                drawHighResolutionHoshino(
                    bitmap = hoshinoBitmap,
                    cx = cx,
                    cy = cy,
                    s = s,
                    headSquishY = headSquishScaleY,
                    haloX = haloX,
                    haloY = haloY,
                    visualState = visualState,
                    envLight = envLight
                )
            } else {
                drawChibiHairBack(backX, backY, s * headSquishScaleY, visualState)
                drawChibiBody(cx, cy, s, visualState)
                drawChibiFace(faceX, faceY, s * cheekSquishScaleX, visualState)
                drawChibiEyes(faceX, faceY, s, isBlinking, visualState)
                drawChibiBlush(faceX, faceY, s, visualState)
                drawChibiMouth(faceX, faceY, s, visualState.lipSyncAmplitude, visualState)
                drawChibiHairFront(frontX, frontY, s * headSquishScaleY, ahogeWiggle, visualState)
                drawFloatingHalo(haloX, haloY, s, visualState)
                drawVolumetricDirectionalRimLight(cx, cy, s, visualState)
                drawGameEmoteParticles(frontX, frontY, s, visualState)
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

/**
 * 针对不同触控部位定制触觉震动波形
 */
private fun triggerHapticFeedback(vibrator: Vibrator?, part: TouchPart) {
    if (vibrator == null || !vibrator.hasVibrator()) return
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (part) {
                TouchPart.HEAD -> {
                    // 摸头：轻柔双脉冲抚摸感
                    val timings = longArrayOf(0, 15, 35, 18)
                    val amplitudes = intArrayOf(0, 95, 0, 120)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                }
                TouchPart.CHEEK -> {
                    // 戳脸：弹性微顿中频震动
                    vibrator.vibrate(VibrationEffect.createOneShot(42, 170))
                }
                TouchPart.HAND -> {
                    // 牵手：轻巧微点微震
                    vibrator.vibrate(VibrationEffect.createOneShot(16, 90))
                }
                TouchPart.BODY -> {
                    // 拥抱：温暖充盈长震
                    vibrator.vibrate(VibrationEffect.createOneShot(65, 190))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val durationMs = when (part) {
                TouchPart.HEAD -> 25L
                TouchPart.CHEEK -> 42L
                TouchPart.HAND -> 16L
                TouchPart.BODY -> 65L
            }
            vibrator.vibrate(durationMs)
        }
    } catch (e: Exception) {
        // 忽略震动服务不可用
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

// 游戏风格情绪特效粒子
private fun DrawScope.drawGameEmoteParticles(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    when (state.emotion) {
        EmotionType.JOY -> {
            drawStar(cx + 80f * s, cy - 60f * s, 16f * s, Color(0xFFFFD54F))
            drawStar(cx - 75f * s, cy - 50f * s, 12f * s, Color(0xFFFF4081))
        }
        EmotionType.SHY -> {
            drawHeart(cx + 78f * s, cy - 55f * s, 14f * s, Color(0xFFFF4081))
            drawHeart(cx - 72f * s, cy - 65f * s, 10f * s, Color(0xFFFF80AB))
        }
        EmotionType.SURPRISED -> {
            drawStar(cx, cy - 130f * s, 14f * s, Color(0xFFFFEA00))
        }
        EmotionType.SAD -> {
            drawTearDrop(cx + 42f * s, cy + 15f * s, 8f * s, Color(0xFF00B0FF))
            drawTearDrop(cx - 42f * s, cy + 15f * s, 8f * s, Color(0xFF00B0FF))
        }
        EmotionType.ANGRY -> {
            val sx = cx + 70f * s
            val sy = cy - 70f * s
            val sColor = Color(0xFFFF1744)
            drawLine(sColor, Offset(sx - 14f * s, sy - 7f * s), Offset(sx + 14f * s, sy - 7f * s), strokeWidth = 4f * s, cap = StrokeCap.Round)
            drawLine(sColor, Offset(sx - 14f * s, sy + 7f * s), Offset(sx + 14f * s, sy + 7f * s), strokeWidth = 4f * s, cap = StrokeCap.Round)
            drawLine(sColor, Offset(sx - 7f * s, sy - 14f * s), Offset(sx - 7f * s, sy + 14f * s), strokeWidth = 4f * s, cap = StrokeCap.Round)
            drawLine(sColor, Offset(sx + 7f * s, sy - 14f * s), Offset(sx + 7f * s, sy + 14f * s), strokeWidth = 4f * s, cap = StrokeCap.Round)
        }
        EmotionType.THINKING -> {
            drawCircle(Color(0xFF40C4FF), 10f * s, Offset(cx + 80f * s, cy - 55f * s))
            drawCircle(Color(0xFF281C38), 10f * s, Offset(cx + 80f * s, cy - 55f * s), style = Stroke(2.5f * s))
        }
        else -> Unit
    }
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
    val redOffsetX = (kotlin.math.cos(angleRad) * fringeDistance).toFloat()
    val redOffsetY = (kotlin.math.sin(angleRad) * fringeDistance).toFloat()
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
 * 二游专属头顶立体悬浮契约光环 (Floating Halo)
 * 具有最高 Z 轴视差位移，随视角在头顶前后呈现真实的 3D 浮动错位
 */
private fun DrawScope.drawFloatingHalo(
    hx: Float,
    hy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val haloY = hy - 138f * s
    val haloWidth = 92f * s
    val haloHeight = 24f * s
    val haloColor = Color(0xFF00E5FF)
    val haloGlowAlpha = if (state.isLowLight) 0.65f else 0.35f

    // 1) 悬浮光环发光微光晕
    drawOval(
        color = haloColor.copy(alpha = haloGlowAlpha * 0.45f),
        topLeft = Offset(hx - haloWidth * 0.6f, haloY - haloHeight * 0.6f),
        size = Size(haloWidth * 1.2f, haloHeight * 1.2f)
    )

    // 2) 契约光环椭圆外环
    drawOval(
        color = haloColor.copy(alpha = haloGlowAlpha),
        topLeft = Offset(hx - haloWidth * 0.5f, haloY - haloHeight * 0.5f),
        size = Size(haloWidth, haloHeight),
        style = Stroke(3.5f * s)
    )

    // 3) 内圈高亮星芒核心点
    drawCircle(
        color = Color.White.copy(alpha = 0.85f),
        radius = 2.5f * s,
        center = Offset(hx - haloWidth * 0.4f, haloY)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.85f),
        radius = 2.5f * s,
        center = Offset(hx + haloWidth * 0.4f, haloY)
    )
}

/**
 * 立体菲涅尔边缘轮廓光与主光源定向漫反射 (Volumetric Directional Fresnel Rim Light)
 * 根据 CameraX 帧分析解算出的真实主光源 (台灯/显示器/窗户) 入射矢量与强度，
 * 在伴侣迎光一侧渲染晶莹剔透的高光轮廓，背光一侧叠加微妙景深阴影，产生概念图级别的立体真实感。
 */
private fun DrawScope.drawVolumetricDirectionalRimLight(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val lightStrength = state.lightDirectionalStrength.coerceIn(0.1f, 1.0f)
    val lightDirX = state.lightDirectionX

    // 迎光轮廓光颜色：吸收环境色温 (暖光偏金黄，冷光偏冰蓝，暗光带微弱夜景冷光)
    val rimBaseColor = when {
        state.ambientColorTemp > 0.2f -> Color(0xFFFFD54F) // 暖色台灯金光
        state.ambientColorTemp < -0.2f -> Color(0xFF80D8FF) // 冷色屏幕蓝光
        state.isLowLight -> Color(0xFFB388FF) // 暗光夜景微光
        else -> Color.White
    }

    val rimAlpha = (0.28f * lightStrength * (if (state.isLowLight) 0.65f else 1.0f)).coerceIn(0.06f, 0.45f)
    val rimWidth = (6.0f * s * lightStrength).coerceIn(2.5f * s, 10f * s)

    // 若光源来自左侧 (lightDirX < -0.15f)，则左侧为迎光面
    if (lightDirX < -0.15f) {
        val leftArcPath = Path().apply {
            moveTo(cx - 84f * s, cy - 80f * s)
            cubicTo(
                cx - 96f * s, cy - 10f * s,
                cx - 92f * s, cy + 60f * s,
                cx - 68f * s, cy + 120f * s
            )
        }
        drawPath(
            path = leftArcPath,
            color = rimBaseColor.copy(alpha = rimAlpha),
            style = Stroke(width = rimWidth, cap = StrokeCap.Round)
        )
        // 发丝边缘高光细线
        drawPath(
            path = leftArcPath,
            color = Color.White.copy(alpha = rimAlpha * 0.8f),
            style = Stroke(width = rimWidth * 0.4f, cap = StrokeCap.Round)
        )
    } else if (lightDirX > 0.15f) {
        // 若光源来自右侧 (lightDirX > 0.15f)，则右侧为迎光面
        val rightArcPath = Path().apply {
            moveTo(cx + 84f * s, cy - 80f * s)
            cubicTo(
                cx + 96f * s, cy - 10f * s,
                cx + 92f * s, cy + 60f * s,
                cx + 68f * s, cy + 120f * s
            )
        }
        drawPath(
            path = rightArcPath,
            color = rimBaseColor.copy(alpha = rimAlpha),
            style = Stroke(width = rimWidth, cap = StrokeCap.Round)
        )
        // 发丝边缘高光细线
        drawPath(
            path = rightArcPath,
            color = Color.White.copy(alpha = rimAlpha * 0.8f),
            style = Stroke(width = rimWidth * 0.4f, cap = StrokeCap.Round)
        )
    }
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
    envLight: Float
) {
    val charW = 185f * s
    val charH = charW * (bitmap.height.toFloat() / bitmap.width.toFloat())
    val footY = cy + 245f * s
    val topY = footY - charH
    val leftX = cx - charW / 2f

    // 1. 环境光色温调和与局部材质二次漫反射 (Radiosity Tint)
    val colorFilter = ColorFilter.lighting(
        multiply = Color(
            red = (visualState.ambientTintRed * envLight).coerceIn(0.25f, 1.35f),
            green = (visualState.ambientTintGreen * envLight).coerceIn(0.25f, 1.35f),
            blue = (visualState.ambientTintBlue * envLight).coerceIn(0.25f, 1.35f)
        ),
        add = if (visualState.isLowLight) Color(0x15101A) else Color.Transparent
    )

    // 2. 惯性物理摆动与弹性形变 (以地面足底接触线为旋转原点)
    withTransform({
        rotate(
            degrees = visualState.inertialSwayAngle,
            pivot = Offset(cx, footY)
        )
        scale(
            scaleX = (1.0f / visualState.inertialSquishY).coerceIn(0.92f, 1.08f),
            scaleY = visualState.inertialSquishY.coerceIn(0.92f, 1.08f),
            pivot = Offset(cx, footY)
        )
    }) {
        // 绘制高精度角色图像
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(leftX.roundToInt(), topY.roundToInt()),
            dstSize = IntSize(charW.roundToInt(), charH.roundToInt()),
            colorFilter = colorFilter
        )

        // 3. 动态视线追踪焦点 (Eye Contact Gaze Tracking)
        // 异色双瞳 (左蓝右金) 随手机姿态与镜头相对位置微幅转动，产生灵动的眼神注视交流
        drawHighResGazeFocus(
            cx = cx,
            topY = topY,
            charW = charW,
            charH = charH,
            s = s,
            visualState = visualState
        )
    }

    // 4. 头顶独立发光星穹光环 (带三维视差、呼吸辉光与惯性延迟)
    drawFloatingHalo(
        hx = haloX + visualState.inertialSwayAngle * 1.5f,
        hy = topY + 138f * s,
        s = s * 0.95f,
        state = visualState
    )

    // 5. 迎光面立体菲涅尔边缘高光 (模拟现场闪光灯直射在发丝边缘的闪耀感)
    drawVolumetricDirectionalRimLight(cx, cy, s, visualState)

    // 6. 前景物理遮挡保护 (例如被近处机械键盘键帽遮挡脚部，避免逆向穿模)
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

    // 7. 情感微粒子
    drawGameEmoteParticles(cx, topY + 60f * s, s, visualState)
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
