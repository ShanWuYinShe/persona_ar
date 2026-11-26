package com.persona.ar.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D AR 角色二次元漫画情绪特效层（Anime Comic Emotion VFX）。
 *
 * 严格规范：不使用任何白色柔边圆/羽毛状光团（防止在暗背景上与轮廓叠加出现「白色羽毛」伪影），
 * 全部采用高饱和度矢量漫画符号（音符 ♪、爱心 ♥、傲娇十字 💢、金星 ✦、Zzz、泪珠 💧、叹号 !）。
 */
internal fun DrawScope.drawAnimeEmoteVfx(
    headCenterX: Float,
    headCenterY: Float,
    scale: Float,
    emotion: EmotionType,
    action: ActionType,
    animTime: Float,
    blushIntensity: Float = 0f,
    drawFaceBlushGlow: Boolean = false
) {
    val s = scale.coerceIn(0.45f, 2.5f)
    val isJoy = emotion == EmotionType.JOY || action == ActionType.WAVE || action == ActionType.JUMP
    val isShy = emotion == EmotionType.SHY || action == ActionType.BLUSH
    val isAngry = emotion == EmotionType.ANGRY || action == ActionType.POUT
    val isSmug = emotion == EmotionType.CONFIDENT || action == ActionType.SMUG
    val isSleepy = emotion == EmotionType.SLEEPY || action == ActionType.SLEEP || action == ActionType.YAWN
    val isSurprised = emotion == EmotionType.SURPRISED
    val isSad = emotion == EmotionType.SAD
    val isThinking = emotion == EmotionType.THINKING

    // 害羞 / 抱抱 / 傲娇时的柔和暖粉颊晕（仅透明玫瑰粉渐变，绝无纯白）
    if (drawFaceBlushGlow && (isShy || blushIntensity > 0.25f)) {
        val alpha = if (isShy) 0.34f else (blushIntensity * 0.32f).coerceIn(0f, 0.36f)
        val cheekSpread = 20f * s
        val cheekY = headCenterY + 6f * s
        val cheekR = 16f * s
        for (sign in intArrayOf(-1, 1)) {
            val cx = headCenterX + sign * cheekSpread
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFF5277).copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cheekY),
                    radius = cheekR
                ),
                radius = cheekR,
                center = Offset(cx, cheekY)
            )
        }
    }

    when {
        isJoy -> {
            val t1 = (animTime * 3.4f) % (2f * Math.PI.toFloat())
            val note1Y = headCenterY - 26f * s + sin(t1) * 7f * s
            val note2Y = headCenterY - 36f * s + cos(t1 * 1.2f) * 8f * s
            drawAnimeMusicNote(headCenterX + 46f * s, note1Y, 12f * s, Color(0xFFFFD54F))
            drawAnimeMusicNote(headCenterX - 44f * s, note2Y, 10.5f * s, Color(0xFFFF6090))
            drawAnimeFourPointStar(headCenterX + 32f * s, headCenterY - 52f * s, 9f * s, (animTime * 90f) % 360f, Color(0xFFFFEB3B))
        }
        isShy -> {
            val cycle1 = (animTime * 0.78f) % 1.0f
            val heart1Y = headCenterY - 10f * s - cycle1 * 48f * s
            val heart1X = headCenterX + 42f * s + sin(cycle1 * 6.28f) * 6f * s
            val alpha1 = (1f - cycle1).coerceIn(0f, 1f)

            val cycle2 = (animTime * 0.78f + 0.5f) % 1.0f
            val heart2Y = headCenterY - 14f * s - cycle2 * 46f * s
            val heart2X = headCenterX - 40f * s - sin(cycle2 * 6.28f) * 6f * s
            val alpha2 = (1f - cycle2).coerceIn(0f, 1f)

            drawAnimeHeart(heart1X, heart1Y, 11.5f * s, Color(0xFFFF4081).copy(alpha = alpha1))
            drawAnimeHeart(heart2X, heart2Y, 9f * s, Color(0xFFFF80AB).copy(alpha = alpha2))
        }
        isAngry -> {
            val pulse = 1.0f + sin(animTime * 8f) * 0.16f
            drawAnimeAngryCross(headCenterX + 38f * s, headCenterY - 32f * s, 14f * s * pulse, Color(0xFFFF1744))
        }
        isSmug -> {
            val rot = (animTime * 120f) % 360f
            val pulse = 1f + sin(animTime * 6f) * 0.18f
            drawAnimeFourPointStar(headCenterX + 46f * s, headCenterY - 28f * s, 15f * s * pulse, rot, Color(0xFFFFD700))
            drawAnimeFourPointStar(headCenterX - 42f * s, headCenterY - 18f * s, 11f * s, -rot * 0.8f, Color(0xFFFFF176))
        }
        isSleepy -> {
            val z1 = (animTime * 0.55f) % 1.0f
            val z2 = (animTime * 0.55f + 0.48f) % 1.0f
            drawAnimeZzzBubble(
                x = headCenterX + 36f * s + sin(z1 * 4f) * 6f * s,
                y = headCenterY - 16f * s - z1 * 52f * s,
                radius = (9f + z1 * 6f) * s,
                alpha = (1f - z1).coerceIn(0f, 1f)
            )
            drawAnimeZzzBubble(
                x = headCenterX + 48f * s + sin(z2 * 5f) * 5f * s,
                y = headCenterY - 8f * s - z2 * 46f * s,
                radius = (7f + z2 * 5f) * s,
                alpha = (1f - z2).coerceIn(0f, 1f)
            )
        }
        isSurprised -> {
            drawAnimeExclamationBubble(headCenterX + 30f * s, headCenterY - 46f * s, 13f * s, Color(0xFFFFD54F))
        }
        isSad -> {
            val drop = (animTime * 1.6f) % 1.0f
            drawAnimeTearDrop(headCenterX + 26f * s, headCenterY + 2f * s + drop * 24f * s, 6.5f * s, Color(0xFF00B0FF).copy(alpha = 1f - drop * 0.45f))
            drawAnimeTearDrop(headCenterX - 26f * s, headCenterY + 5f * s + ((drop + 0.35f) % 1f) * 22f * s, 5.5f * s, Color(0xFF00B0FF).copy(alpha = 1f - drop * 0.45f))
        }
        isThinking -> {
            val b = (animTime * 2.2f) % 3f
            for (idx in 0..2) {
                val r = (4.5f + idx * 1.8f) * s
                val a = if (idx <= b.toInt()) 0.92f else 0.35f
                val cx = headCenterX + (26f + idx * 10f) * s
                val cy = headCenterY - (18f + idx * 12f) * s
                drawCircle(Color(0xFF40C4FF).copy(alpha = a), r, Offset(cx, cy))
                drawCircle(Color(0xFF281C38).copy(alpha = a), r, Offset(cx, cy), style = Stroke(1.6f))
            }
        }
        else -> Unit
    }
}

private fun DrawScope.drawAnimeMusicNote(x: Float, y: Float, size: Float, color: Color) {
    val headR = size * 0.35f
    drawOval(color = color, topLeft = Offset(x - headR * 1.2f, y - headR), size = Size(headR * 2.2f, headR * 1.6f))
    drawOval(color = Color(0xFF281C38), topLeft = Offset(x - headR * 1.2f, y - headR), size = Size(headR * 2.2f, headR * 1.6f), style = Stroke(2f))
    val stemX = x + headR * 0.8f
    val stemTopY = y - size * 1.4f
    drawLine(color, Offset(stemX, y), Offset(stemX, stemTopY), strokeWidth = 2.6f, cap = StrokeCap.Round)
    drawLine(Color(0xFF281C38), Offset(stemX, y), Offset(stemX, stemTopY), strokeWidth = 1.2f, cap = StrokeCap.Round)
    val flagPath = Path().apply {
        moveTo(stemX, stemTopY)
        cubicTo(stemX + size * 0.7f, stemTopY + size * 0.3f, stemX + size * 0.5f, stemTopY + size * 0.8f, stemX + size * 0.8f, stemTopY + size)
    }
    drawPath(flagPath, color, style = Stroke(2.6f, cap = StrokeCap.Round))
}

private fun DrawScope.drawAnimeFourPointStar(x: Float, y: Float, radius: Float, rotationDegrees: Float, color: Color) {
    val path = Path().apply {
        val rad = Math.toRadians(rotationDegrees.toDouble())
        for (i in 0..7) {
            val r = if (i % 2 == 0) radius else radius * 0.28f
            val angle = rad + i * Math.PI / 4.0
            val px = x + (r * cos(angle)).toFloat()
            val py = y + (r * sin(angle)).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38), style = Stroke(1.8f))
}

private fun DrawScope.drawAnimeAngryCross(x: Float, y: Float, size: Float, color: Color) {
    val half = size * 0.5f
    val thick = size * 0.24f
    val strokeW = 3f
    val borderColor = Color(0xFF281C38)
    val pts = arrayOf(
        Offset(x - half, y - thick) to Offset(x + half, y - thick),
        Offset(x - half, y + thick) to Offset(x + half, y + thick),
        Offset(x - thick, y - half) to Offset(x - thick, y + half),
        Offset(x + thick, y - half) to Offset(x + thick, y + half)
    )
    for ((a, b) in pts) {
        drawLine(borderColor, a, b, strokeWidth = thick + strokeW, cap = StrokeCap.Round)
        drawLine(color, a, b, strokeWidth = thick, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawAnimeZzzBubble(x: Float, y: Float, radius: Float, alpha: Float) {
    if (alpha <= 0.02f) return
    drawCircle(color = Color(0xFF9575CD).copy(alpha = alpha * 0.35f), radius = radius, center = Offset(x, y))
    drawCircle(color = Color(0xFF281C38).copy(alpha = alpha * 0.78f), radius = radius, center = Offset(x, y), style = Stroke(1.6f))
    val s = radius * 0.44f
    val zPath = Path().apply {
        moveTo(x - s, y - s)
        lineTo(x + s, y - s)
        lineTo(x - s, y + s)
        lineTo(x + s, y + s)
    }
    drawPath(zPath, Color(0xFFEDE7F6).copy(alpha = alpha), style = Stroke(2.2f, cap = StrokeCap.Round))
}

private fun DrawScope.drawAnimeExclamationBubble(x: Float, y: Float, size: Float, color: Color) {
    drawCircle(color, size, Offset(x, y))
    drawCircle(Color(0xFF281C38), size, Offset(x, y), style = Stroke(2.2f))
    drawLine(Color(0xFF281C38), Offset(x, y - size * 0.5f), Offset(x, y + size * 0.1f), strokeWidth = 3.2f, cap = StrokeCap.Round)
    drawCircle(Color(0xFF281C38), 2.1f, Offset(x, y + size * 0.48f))
}

internal fun DrawScope.drawAnimeHeart(x: Float, y: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(x, y + size * 0.3f)
        cubicTo(x - size, y - size * 0.5f, x - size * 1.3f, y + size * 0.6f, x, y + size * 1.3f)
        cubicTo(x + size * 1.3f, y + size * 0.6f, x + size, y - size * 0.5f, x, y + size * 0.3f)
    }
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38).copy(alpha = color.alpha), style = Stroke(1.8f))
}

private fun DrawScope.drawAnimeTearDrop(x: Float, y: Float, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(x, y - radius)
        cubicTo(x - radius, y, x - radius, y + radius, x, y + radius)
        cubicTo(x + radius, y + radius, x + radius, y, x, y - radius)
    }
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38).copy(alpha = color.alpha), style = Stroke(1.5f))
}

internal fun emotionAccentColor(emotion: EmotionType): Color = when (emotion) {
    EmotionType.JOY -> Color(0xFFFFB300)
    EmotionType.SHY -> Color(0xFFFF5277)
    EmotionType.ANGRY -> Color(0xFFFF5722)
    EmotionType.CONFIDENT -> Color(0xFFFFD54F)
    EmotionType.SLEEPY -> Color(0xFF9575CD)
    EmotionType.THINKING -> Color(0xFF40C4FF)
    EmotionType.SAD -> Color(0xFF4FC3F7)
    EmotionType.SURPRISED -> Color(0xFFFFCA28)
    EmotionType.NEUTRAL -> Color(0xFF00E5FF)
}

internal fun DrawScope.drawStar(
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
        val px = x + (r * cos(angle)).toFloat()
        val py = y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
    drawPath(path, Color(0xFF281C38), style = Stroke(2.5f))
}
