package com.persona.ar.character

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persona.ar.data.model.TouchPart

@Composable
fun Character3DRenderer(
    visualState: CharacterVisualState,
    onTouchPart: (TouchPart) -> Unit,
    modifier: Modifier = Modifier
) {
    // 3D 空间旋转角度与坐标
    var rotationY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1.0f) }

    // 空间网格扫描动画
    val infiniteTransition = rememberInfiniteTransition(label = "gridScan")
    val gridPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    scale = (scale * zoom).coerceIn(0.6f, 2.2f)
                    rotationY += pan.x * 0.5f
                }
            }
    ) {
        // 绘制 AR 空间平面网格与 3D 投影底座
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 160f

            // AR 地面平面特征点网格
            val gridRadius = 240f * scale
            drawCircle(
                color = Color(0x3300E5FF),
                radius = gridRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = Color(0x5500E5FF),
                radius = gridRadius * gridPhase,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )

            // 透视网格线
            for (i in -3..3) {
                val dx = i * 60f * scale
                drawLine(
                    color = Color(0x2200E5FF),
                    start = Offset(cx + dx, cy - 80f * scale),
                    end = Offset(cx + dx * 1.5f, cy + 80f * scale),
                    strokeWidth = 2f
                )
            }
        }

        // 结合 2.5D 精致渲染核心，并在 3D 底座之上呈现立绘
        CharacterCanvasRenderer(
            visualState = visualState.copy(scale = scale),
            onTouchPart = onTouchPart,
            modifier = Modifier.fillMaxSize()
        )

        // 3D 空间 HUD 提示
        Text(
            text = "3D 空间追踪模式 | 旋转: ${rotationY.toInt()}°",
            color = Color(0xCC00E5FF),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        )
    }
}
