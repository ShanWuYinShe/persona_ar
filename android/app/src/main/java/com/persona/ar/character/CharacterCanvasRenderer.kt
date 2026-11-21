package com.persona.ar.character

import android.content.Context
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 卡通游戏风格（Anime Chibi / 二次元二游萌系）动态纸片人渲染引擎
 */
@Composable
fun CharacterCanvasRenderer(
    visualState: CharacterVisualState,
    onTouchPart: (TouchPart) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vibrator = remember {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(
                            VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(35)
                    }

                    val normalizedY = tapOffset.y / size.height
                    val part = when {
                        normalizedY < 0.35f -> TouchPart.HEAD
                        normalizedY < 0.50f -> TouchPart.CHEEK
                        tapOffset.x < size.width * 0.25f || tapOffset.x > size.width * 0.75f -> TouchPart.HAND
                        else -> TouchPart.BODY
                    }
                    onTouchPart(part)
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(
                        (offsetX + visualState.tiltOffsetX).roundToInt(),
                        (offsetY + visualState.tiltOffsetY + breathBounce + jumpAnim).roundToInt()
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 40f
            val s = scale

            // 1. 脚底现实接触阴影 (卡通双重柔光椭圆)
            drawOval(
                color = Color(0x33000000),
                topLeft = Offset(cx - 140f * s, cy + 240f * s),
                size = Size(280f * s, 46f * s)
            )
            drawOval(
                color = Color(0x661A0C2E),
                topLeft = Offset(cx - 95f * s, cy + 245f * s),
                size = Size(190f * s, 32f * s)
            )

            // 2. 绘制萌系角色主体
            drawChibiBody(cx, cy, s, visualState)
            drawChibiHairBack(cx, cy, s, visualState)
            drawChibiFace(cx, cy, s, visualState)
            drawChibiEyes(cx, cy, s, isBlinking, visualState)
            drawChibiBlush(cx, cy, s, visualState)
            drawChibiMouth(cx, cy, s, visualState.lipSyncAmplitude, visualState)
            drawChibiHairFront(cx, cy, s, ahogeWiggle, visualState)
            drawGameEmoteParticles(cx, cy, s, visualState)
        }
    }
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

// 绘制 Q 萌身体与水手服
private fun DrawScope.drawChibiBody(
    cx: Float,
    cy: Float,
    s: Float,
    state: CharacterVisualState
) {
    val model = state.model
    val skirtColor = parseColorHex(model?.skirtColorHex, Color(0xFF384B70))
    val ribbonColor = parseColorHex(model?.previewColorHex, Color(0xFFFF5277))
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
    val hairColor = parseColorHex(model?.hairColorHex, Color(0xFF7E57C2))
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
    val skinColor = Color(0xFFFFF1E8)
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
    val eyeColor = parseColorHex(model?.eyeColorHex, Color(0xFF673AB7))
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
            drawBigChibiEye(rightEyeX, eyeY, s, eyeColor)
        }
    } else {
        drawBigChibiEye(leftEyeX, eyeY, s, eyeColor)
        drawBigChibiEye(rightEyeX, eyeY, s, eyeColor)
    }
}

private fun DrawScope.drawBigChibiEye(
    ex: Float,
    ey: Float,
    s: Float,
    irisColor: Color
) {
    val strokeColor = Color(0xFF281C38)

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

    // 瞳孔多层渐变 (动漫大眼)
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(irisColor, irisColor.copy(alpha = 0.6f), Color(0xFFFF80AB)),
            startY = ey - 20f * s,
            endY = ey + 20f * s
        ),
        topLeft = Offset(ex - 15f * s, ey - 20f * s),
        size = Size(30f * s, 40f * s)
    )

    // 顶部超大高光
    drawCircle(
        color = Color.White,
        radius = 7.5f * s,
        center = Offset(ex - 5f * s, ey - 8f * s)
    )
    // 底部星芒/小高光
    drawCircle(
        color = Color.White,
        radius = 3.5f * s,
        center = Offset(ex + 6f * s, ey + 7f * s)
    )

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
    val isBlushing = state.emotion == EmotionType.SHY || state.action == ActionType.BLUSH
    val blushColor = if (isBlushing) Color(0xFFFF4081) else Color(0xFFFF80AB)
    val blushAlpha = if (isBlushing) 0.8f else 0.45f

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
    val hairColor = parseColorHex(model?.hairColorHex, Color(0xFF7E57C2))
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
