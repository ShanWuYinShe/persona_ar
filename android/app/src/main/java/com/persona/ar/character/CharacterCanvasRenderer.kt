package com.persona.ar.character

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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

    // 呼吸待机动画
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

    // 眨眼动画
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay((3000..5000).random().toLong())
            isBlinking = true
            delay(120)
            isBlinking = false
        }
    }

    // 手势状态：位置偏移与缩放
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1.0f) }

    // 肢体轻跳动作动画（例如 JUMP 时）
    val jumpAnim by animateFloatAsState(
        targetValue = if (visualState.action == ActionType.JUMP) -40f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "jump"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 双指缩放与双指拖动
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 2.5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .pointerInput(Unit) {
                // 点击判定身体部位触碰
                detectTapGestures { tapOffset ->
                    // 简单的物理震动反馈
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(
                            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(30)
                    }

                    // 换算到角色内部坐标进行部位判定
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

            // 1. 脚底现实空间投射阴影（带羽化和透明度，营造站在现实地面/桌面感）
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x77000000), Color(0x22000000), Color.Transparent),
                    center = Offset(cx, cy + 280f * baseScale),
                    radius = 160f * baseScale
                ),
                topLeft = Offset(cx - 150f * baseScale, cy + 250f * baseScale),
                size = Size(300f * baseScale, 60f * baseScale)
            )

            // 2. 角色身体及衣物
            drawCharacterBody(cx, cy, baseScale, visualState)

            // 3. 头部轮廓与发型
            drawCharacterHairAndHead(cx, cy, baseScale, visualState)

            // 4. 眼睛系统（包含眨眼与情绪差分）
            drawCharacterEyes(cx, cy, baseScale, isBlinking, visualState)

            // 5. 动态腮红红晕
            drawCharacterBlush(cx, cy, baseScale, visualState)

            // 6. 口型开合 Lip-sync（根据音频振幅实时开合）
            drawCharacterMouth(cx, cy, baseScale, visualState.lipSyncAmplitude, visualState)

            // 7. 情绪表情粒子/符号
            drawEmotionEmotes(cx, cy, baseScale, visualState)
        }
    }
}

// 绘制身体与水手服/衬衫
private fun DrawScope.drawCharacterBody(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    val bodyColor = Color(0xFFF7F8FA) // 衬衫白
    val skirtColor = Color(0xFF3B4A6B) // 水手深蓝
    val ribbonColor = Color(0xFFFF5277) // 领结粉红

    // 脖子
    drawRect(
        color = Color(0xFFFFE0D2),
        topLeft = Offset(cx - 16f * scale, cy + 40f * scale),
        size = Size(32f * scale, 35f * scale)
    )

    // 上衣身躯
    val bodyPath = Path().apply {
        moveTo(cx - 55f * scale, cy + 70f * scale)
        lineTo(cx + 55f * scale, cy + 70f * scale)
        lineTo(cx + 45f * scale, cy + 180f * scale)
        lineTo(cx - 45f * scale, cy + 180f * scale)
        close()
    }
    drawPath(bodyPath, bodyColor)

    // 百褶裙
    val skirtPath = Path().apply {
        moveTo(cx - 45f * scale, cy + 180f * scale)
        lineTo(cx + 45f * scale, cy + 180f * scale)
        lineTo(cx + 90f * scale, cy + 260f * scale)
        lineTo(cx - 90f * scale, cy + 260f * scale)
        close()
    }
    drawPath(skirtPath, skirtColor)

    // 蝴蝶结领结
    drawCircle(
        color = ribbonColor,
        radius = 12f * scale,
        center = Offset(cx, cy + 85f * scale)
    )
    val ribbonLeft = Path().apply {
        moveTo(cx, cy + 85f * scale)
        lineTo(cx - 28f * scale, cy + 75f * scale)
        lineTo(cx - 28f * scale, cy + 95f * scale)
        close()
    }
    val ribbonRight = Path().apply {
        moveTo(cx, cy + 85f * scale)
        lineTo(cx + 28f * scale, cy + 75f * scale)
        lineTo(cx + 28f * scale, cy + 95f * scale)
        close()
    }
    drawPath(ribbonLeft, ribbonColor)
    drawPath(ribbonRight, ribbonColor)

    // 手臂与手
    val handColor = Color(0xFFFFE0D2)
    if (state.action == ActionType.WAVE) {
        // 挥手动作
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
        // 自然垂下双手
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

// 绘制头部与发型
private fun DrawScope.drawCharacterHairAndHead(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    val hairBack = Color(0xFF6B4E71) // 柔和深紫/樱花粉渐变色
    val hairFront = Color(0xFF8B6B96)
    val skinColor = Color(0xFFFFEFE6)

    // 后脑勺头发底色
    drawCircle(
        color = hairBack,
        radius = 82f * scale,
        center = Offset(cx, cy - 20f * scale)
    )

    // 双马尾侧发
    drawOval(
        color = hairBack,
        topLeft = Offset(cx - 130f * scale, cy - 30f * scale),
        size = Size(50f * scale, 140f * scale)
    )
    drawOval(
        color = hairBack,
        topLeft = Offset(cx + 80f * scale, cy - 30f * scale),
        size = Size(50f * scale, 140f * scale)
    )

    // 脸部轮廓（柔和的动漫下巴弧线）
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
    drawPath(bangPath, hairFront)

    // 呆毛 (Ahoge)
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
        color = hairFront,
        style = Stroke(width = 5f * scale, cap = StrokeCap.Round)
    )
}

// 绘制灵动眼睛
private fun DrawScope.drawCharacterEyes(
    cx: Float,
    cy: Float,
    scale: Float,
    isBlinking: Boolean,
    state: CharacterVisualState
) {
    val eyeColor = Color(0xFF5D54A8)
    val eyeHighlight = Color.White
    val eyeY = cy + 2f * scale
    val leftEyeX = cx - 28f * scale
    val rightEyeX = cx + 28f * scale

    if (isBlinking || state.action == ActionType.BLUSH || state.expression == "WINK") {
        // 眯眼/闭眼弧线
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
            // 单眼睁开眨眼
            drawEyeOpen(rightEyeX, eyeY, scale, eyeColor, eyeHighlight)
        }
    } else {
        // 双眼睁开
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
    // 眼眶与眼白
    drawOval(
        color = Color.White,
        topLeft = Offset(ex - 15f * scale, ey - 18f * scale),
        size = Size(30f * scale, 36f * scale)
    )
    // 瞳孔
    drawOval(
        color = irisColor,
        topLeft = Offset(ex - 11f * scale, ey - 15f * scale),
        size = Size(22f * scale, 30f * scale)
    )
    // 眼神高光
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
    // 上睫毛线
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

// 动态腮红
private fun DrawScope.drawCharacterBlush(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    val shouldBlush = state.emotion == EmotionType.SHY || state.action == ActionType.BLUSH
    val blushAlpha = if (shouldBlush) 0.65f else 0.25f
    val blushColor = Color(1f, 0.45f, 0.55f, blushAlpha)

    // 左腮红
    drawOval(
        color = blushColor,
        topLeft = Offset(cx - 52f * scale, cy + 22f * scale),
        size = Size(26f * scale, 12f * scale)
    )
    // 右腮红
    drawOval(
        color = blushColor,
        topLeft = Offset(cx + 26f * scale, cy + 22f * scale),
        size = Size(26f * scale, 12f * scale)
    )
}

// 口型开合（实时音频振幅 LipSync）
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
        // 说话时的开合动态嘴型
        val openH = (22f * amplitude).coerceIn(4f, 25f) * scale
        val openW = (18f + amplitude * 12f) * scale
        drawOval(
            color = mouthColor,
            topLeft = Offset(cx - openW / 2f, mouthY - openH / 2f),
            size = Size(openW, openH)
        )
        // 口腔高光小舌头
        drawCircle(
            color = Color(0xFFFF8599),
            radius = openH * 0.35f,
            center = Offset(cx, mouthY + openH * 0.15f)
        )
    } else {
        // 静止微翘微笑或噘嘴
        val mouthPath = Path().apply {
            if (state.action == ActionType.POUT || state.emotion == EmotionType.ANGRY) {
                // 噘嘴小圆点
                moveTo(cx - 5f * scale, mouthY)
                quadraticBezierTo(cx, mouthY - 6f * scale, cx + 5f * scale, mouthY)
            } else {
                // 微笑浅浅弯月牙
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

// 情绪特效符号粒子
private fun DrawScope.drawEmotionEmotes(
    cx: Float,
    cy: Float,
    scale: Float,
    state: CharacterVisualState
) {
    when (state.emotion) {
        EmotionType.JOY -> {
            // 漂浮的音符/小爱心
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
            // 傲娇生气十字路标井字符号
            val sx = cx + 65f * scale
            val sy = cy - 60f * scale
            val sColor = Color(0xFFFF3344)
            drawLine(sColor, Offset(sx - 12f * scale, sy - 6f * scale), Offset(sx + 12f * scale, sy - 6f * scale), strokeWidth = 3f * scale)
            drawLine(sColor, Offset(sx - 12f * scale, sy + 6f * scale), Offset(sx + 12f * scale, sy + 6f * scale), strokeWidth = 3f * scale)
            drawLine(sColor, Offset(sx - 6f * scale, sy - 12f * scale), Offset(sx - 6f * scale, sy + 12f * scale), strokeWidth = 3f * scale)
            drawLine(sColor, Offset(sx + 6f * scale, sy - 12f * scale), Offset(sx + 6f * scale, sy + 12f * scale), strokeWidth = 3f * scale)
        }
        EmotionType.THINKING -> {
            // 问号/汗滴
            drawCircle(Color(0xFF64B5F6), 8f * scale, Offset(cx + 70f * scale, cy - 50f * scale))
        }
        else -> Unit
    }
}
