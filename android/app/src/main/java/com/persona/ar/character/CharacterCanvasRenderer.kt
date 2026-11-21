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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingOffset by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathY"
    )

    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((3000..5000).random().toLong())
            isBlinking = true
            delay(120)
            isBlinking = false
        }
    }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1.0f) }

    val jumpAnim by animateFloatAsState(
        targetValue = if (visualState.action == ActionType.JUMP) -40f else 0f,
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
                            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(30)
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
                        (offsetY + visualState.tiltOffsetY + breathingOffset + jumpAnim).roundToInt()
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 50f
            val baseScale = scale

            // 1. 脚底现实空间投射阴影
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x77000000), Color(0x22000000), Color.Transparent),
                    center = Offset(cx, cy + 280f * baseScale),
                    radius = 160f * baseScale
                ),
                topLeft = Offset(cx - 150f * baseScale, cy + 250f * baseScale),
                size = Size(300f * baseScale, 60f * baseScale)
            )

            // 2. 根据加载的动态模型资产配置进行着色绘制
            drawDynamicCharacterBody(cx, cy, baseScale, visualState)
            drawDynamicCharacterHairAndHead(cx, cy, baseScale, visualState)
            drawDynamicCharacterEyes(cx, cy, baseScale, isBlinking, visualState)
            drawCharacterBlush(cx, cy, baseScale, visualState)
            drawCharacterMouth(cx, cy, baseScale, visualState.lipSyncAmplitude, visualState)
            drawEmotionEmotes(cx, cy, baseScale, visualState)
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

private fun DrawScope.drawDynamicCharacterBody(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    val model = state.model
    val bodyColor = Color(0xFFF7F8FA)
    val skirtColor = parseColorHex(model?.skirtColorHex, Color(0xFF3B4A6B))
    val ribbonColor = parseColorHex(model?.previewColorHex, Color(0xFFFF5277))

    // 脖子
    drawRect(
        color = Color(0xFFFFE0D2),
        topLeft = Offset(cx - 16f * scale, cy + 40f * scale),
        size = Size(32f * scale, 35f * scale)
    )

    // 上衣
    val bodyPath = Path().apply {
        moveTo(cx - 55f * scale, cy + 70f * scale)
        lineTo(cx + 55f * scale, cy + 70f * scale)
        lineTo(cx + 45f * scale, cy + 180f * scale)
        lineTo(cx - 45f * scale, cy + 180f * scale)
        close()
    }
    drawPath(bodyPath, bodyColor)

    // 裙子/下装（颜色随模型配置动态变化）
    val skirtPath = Path().apply {
        moveTo(cx - 45f * scale, cy + 180f * scale)
        lineTo(cx + 45f * scale, cy + 180f * scale)
        lineTo(cx + 90f * scale, cy + 260f * scale)
        lineTo(cx - 90f * scale, cy + 260f * scale)
        close()
    }
    drawPath(skirtPath, skirtColor)

    // 领结
    drawCircle(
        color = ribbonColor,
        radius = 12f * scale,
        center = Offset(cx, cy + 85f * scale)
    )

    val handColor = Color(0xFFFFE0D2)
    if (state.action == ActionType.WAVE) {
        val waveArm = Path().apply {
            moveTo(cx + 55f * scale, cy + 70f * scale)
            lineTo(cx + 105f * scale, cy + 10f * scale)
            lineTo(cx + 120f * scale, cy + 25f * scale)
            lineTo(cx + 70f * scale, cy + 85f * scale)
            close()
        }
        drawPath(waveArm, skirtColor)
        drawCircle(handColor, 15f * scale, Offset(cx + 115f * scale, cy + 12f * scale))
    } else {
        drawRoundRect(
            color = skirtColor,
            topLeft = Offset(cx - 75f * scale, cy + 75f * scale),
            size = Size(22f * scale, 100f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale)
        )
        drawRoundRect(
            color = skirtColor,
            topLeft = Offset(cx + 53f * scale, cy + 75f * scale),
            size = Size(22f * scale, 100f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale)
        )
        drawCircle(handColor, 13f * scale, Offset(cx - 64f * scale, cy + 175f * scale))
        drawCircle(handColor, 13f * scale, Offset(cx + 64f * scale, cy + 175f * scale))
    }
}

private fun DrawScope.drawDynamicCharacterHairAndHead(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    val model = state.model
    val hairColor = parseColorHex(model?.hairColorHex, Color(0xFF6B4E71))
    val skinColor = Color(0xFFFFEFE6)

    // 后发
    drawCircle(
        color = hairColor,
        radius = 82f * scale,
        center = Offset(cx, cy - 20f * scale)
    )

    // 双侧发束
    drawOval(
        color = hairColor,
        topLeft = Offset(cx - 130f * scale, cy - 30f * scale),
        size = Size(50f * scale, 140f * scale)
    )
    drawOval(
        color = hairColor,
        topLeft = Offset(cx + 80f * scale, cy - 30f * scale),
        size = Size(50f * scale, 140f * scale)
    )

    // 脸部轮廓
    val facePath = Path().apply {
        moveTo(cx - 62f * scale, cy - 20f * scale)
        cubicTo(
            cx - 60f * scale, cy + 40f * scale,
            cx - 30f * scale, cy + 75f * scale,
            cx, cy + 82f * scale
        )
        cubicTo(
            cx + 30f * scale, cy + 75f * scale,
            cx + 60f * scale, cy + 40f * scale,
            cx + 62f * scale, cy - 20f * scale
        )
        close()
    }
    drawPath(facePath, skinColor)

    // 前刘海
    val bangPath = Path().apply {
        moveTo(cx - 72f * scale, cy - 40f * scale)
        lineTo(cx - 40f * scale, cy + 10f * scale)
        lineTo(cx - 20f * scale, cy - 10f * scale)
        lineTo(cx, cy + 15f * scale)
        lineTo(cx + 20f * scale, cy - 10f * scale)
        lineTo(cx + 45f * scale, cy + 12f * scale)
        lineTo(cx + 72f * scale, cy - 40f * scale)
        cubicTo(
            cx + 40f * scale, cy - 90f * scale,
            cx - 40f * scale, cy - 90f * scale,
            cx - 72f * scale, cy - 40f * scale
        )
        close()
    }
    drawPath(bangPath, hairColor)

    // 呆毛
    val ahogePath = Path().apply {
        moveTo(cx, cy - 85f * scale)
        cubicTo(
            cx - 20f * scale, cy - 120f * scale,
            cx + 30f * scale, cy - 130f * scale,
            cx + 15f * scale, cy - 95f * scale
        )
    }
    drawPath(
        path = ahogePath,
        color = hairColor,
        style = Stroke(width = 5f * scale, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawDynamicCharacterEyes(
    cx: Float,
    cy: Float,
    scale: Float,
    isBlinking: Boolean,
    state: CharacterVisualState
) {
    val model = state.model
    val eyeColor = parseColorHex(model?.eyeColorHex, Color(0xFF5D54A8))
    val eyeHighlight = Color.White
    val eyeY = cy + 2f * scale
    val leftEyeX = cx - 28f * scale
    val rightEyeX = cx + 28f * scale

    if (isBlinking || state.action == ActionType.BLUSH || state.expression == "WINK") {
        val blinkPath = Path().apply {
            moveTo(leftEyeX - 14f * scale, eyeY)
            quadraticBezierTo(leftEyeX, eyeY + 8f * scale, leftEyeX + 14f * scale, eyeY)
        }
        drawPath(blinkPath, Color(0xFF333333), style = Stroke(width = 4f * scale, cap = StrokeCap.Round))

        if (state.expression != "WINK") {
            val blinkPathRight = Path().apply {
                moveTo(rightEyeX - 14f * scale, eyeY)
                quadraticBezierTo(rightEyeX, eyeY + 8f * scale, rightEyeX + 14f * scale, eyeY)
            }
            drawPath(blinkPathRight, Color(0xFF333333), style = Stroke(width = 4f * scale, cap = StrokeCap.Round))
        } else {
            drawEyeOpen(rightEyeX, eyeY, scale, eyeColor, eyeHighlight)
        }
    } else {
        drawEyeOpen(leftEyeX, eyeY, scale, eyeColor, eyeHighlight)
        drawEyeOpen(rightEyeX, eyeY, scale, eyeColor, eyeHighlight)
    }
}

private fun DrawScope.drawEyeOpen(
    ex: Float,
    ey: Float,
    scale: Float,
    irisColor: Color,
    highlight: Color
) {
    drawOval(
        color = Color.White,
        topLeft = Offset(ex - 15f * scale, ey - 18f * scale),
        size = Size(30f * scale, 36f * scale)
    )
    drawOval(
        color = irisColor,
        topLeft = Offset(ex - 11f * scale, ey - 15f * scale),
        size = Size(22f * scale, 30f * scale)
    )
    drawCircle(
        color = highlight,
        radius = 5f * scale,
        center = Offset(ex - 4f * scale, ey - 6f * scale)
    )
    drawCircle(
        color = highlight,
        radius = 2.5f * scale,
        center = Offset(ex + 4f * scale, ey + 4f * scale)
    )
    drawArc(
        color = Color(0xFF2B2538),
        startAngle = 190f,
        sweepAngle = 160f,
        useCenter = false,
        topLeft = Offset(ex - 16f * scale, ey - 22f * scale),
        size = Size(32f * scale, 22f * scale),
        style = Stroke(width = 4f * scale, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawCharacterBlush(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    val shouldBlush = state.emotion == EmotionType.SHY || state.action == ActionType.BLUSH
    val blushAlpha = if (shouldBlush) 0.65f else 0.25f
    val blushColor = Color(1f, 0.45f, 0.55f, blushAlpha)

    drawOval(
        color = blushColor,
        topLeft = Offset(cx - 52f * scale, cy + 22f * scale),
        size = Size(26f * scale, 12f * scale)
    )
    drawOval(
        color = blushColor,
        topLeft = Offset(cx + 26f * scale, cy + 22f * scale),
        size = Size(26f * scale, 12f * scale)
    )
}

private fun DrawScope.drawCharacterMouth(
    cx: Float,
    cy: Float,
    scale: Float,
    amplitude: Float,
    state: CharacterVisualState
) {
    val mouthY = cy + 42f * scale
    val mouthColor = Color(0xFFD64D65)

    if (amplitude > 0.08f) {
        val openH = (22f * amplitude).coerceIn(4f, 25f) * scale
        val openW = (18f + amplitude * 12f) * scale
        drawOval(
            color = mouthColor,
            topLeft = Offset(cx - openW / 2f, mouthY - openH / 2f),
            size = Size(openW, openH)
        )
        drawCircle(
            color = Color(0xFFFF8599),
            radius = openH * 0.35f,
            center = Offset(cx, mouthY + openH * 0.15f)
        )
    } else {
        val mouthPath = Path().apply {
            if (state.action == ActionType.POUT || state.emotion == EmotionType.ANGRY) {
                moveTo(cx - 5f * scale, mouthY)
                quadraticBezierTo(cx, mouthY - 6f * scale, cx + 5f * scale, mouthY)
            } else {
                moveTo(cx - 10f * scale, mouthY)
                quadraticBezierTo(cx, mouthY + 6f * scale, cx + 10f * scale, mouthY)
            }
        }
        drawPath(
            mouthPath,
            Color(0xFF6B3A44),
            style = Stroke(width = 3.5f * scale, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawEmotionEmotes(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    when (state.emotion) {
        EmotionType.JOY -> {
            val heartPath = Path().apply {
                val hx = cx + 80f * scale
                val hy = cy - 70f * scale
                moveTo(hx, hy)
                cubicTo(hx - 15f * scale, hy - 20f * scale, hx - 30f * scale, hy + 5f * scale, hx, hy + 25f * scale)
                cubicTo(hx + 30f * scale, hy + 5f * scale, hx + 15f * scale, hy - 20f * scale, hx, hy)
            }
            drawPath(heartPath, Color(0xFFFF5277))
        }
        EmotionType.ANGRY -> {
            val sx = cx + 65f * scale
            val sy = cy - 60f * scale
            val sColor = Color(0xFFFF3344)
            drawLine(sColor, Offset(sx - 12f * scale, sy - 6f * scale), Offset(sx + 12f * scale, sy - 6f * scale), strokeWidth = 3f * scale)
            drawLine(sColor, Offset(sx - 12f * scale, sy + 6f * scale), Offset(sx + 12f * scale, sy + 6f * scale), strokeWidth = 3f * scale)
            drawLine(sColor, Offset(sx - 6f * scale, sy - 12f * scale), Offset(sx - 6f * scale, sy + 12f * scale), strokeWidth = 3f * scale)
            drawLine(sColor, Offset(sx + 6f * scale, sy - 12f * scale), Offset(sx + 6f * scale, sy + 12f * scale), strokeWidth = 3f * scale)
        }
        EmotionType.THINKING -> {
            drawCircle(Color(0xFF64B5F6), 8f * scale, Offset(cx + 70f * scale, cy - 50f * scale))
        }
        else -> Unit
    }
}
