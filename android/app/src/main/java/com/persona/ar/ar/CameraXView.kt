package com.persona.ar.ar

import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 现实环境透传与光照融合视口 (CameraX View with Real-time Environmental Blending)
 * 1. 负责全屏渲染无延时的物理摄像头采集流；
 * 2. 挂载环境光照实时分析通道 (ImageAnalysis)，提取现实明暗与冷暖色度；
 * 3. 支持落脚点硬件级物理自动对焦与局部曝光测光 (Tap-to-Focus & AE Metering)；
 * 4. 暴露当前帧无感抓取器 (bitmap provider)，供 AR 拍立得真实合影合成。
 */
@Composable
fun CameraXView(
    modifier: Modifier = Modifier,
    focusAnchorNormX: Float = 0.5f,
    focusAnchorNormY: Float = 0.65f,
    isFocusTriggered: Boolean = false,
    onFocusStateChanged: ((isFocusing: Boolean) -> Unit)? = null,
    onEnvironmentLightChanged: (luminance: Float, isLowLight: Boolean) -> Unit = { _, _ -> },
    onOpticalProfileChanged: ((EnvironmentalOpticalProfile) -> Unit)? = null,
    onFrameCaptureReady: (((() -> Bitmap?)) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    val analyzer = remember {
        CameraEnvironmentalAnalyzer(
            onLightEstimated = { lum, isLow ->
                onEnvironmentLightChanged(lum, isLow)
            },
            onOpticalProfileEstimated = { profile ->
                onOpticalProfileChanged?.invoke(profile)
            }
        )
    }

    LaunchedEffect(focusAnchorNormX, focusAnchorNormY) {
        analyzer.focusNormX = focusAnchorNormX
        analyzer.focusNormY = focusAnchorNormY
    }

    // 联动物理摄像头执行真实硬件自动对焦与局部曝光测光 (Hardware AF/AE Metering)
    LaunchedEffect(isFocusTriggered, focusAnchorNormX, focusAnchorNormY, activeCamera) {
        val cam = activeCamera ?: return@LaunchedEffect
        try {
            onFocusStateChanged?.invoke(true)
            val factory = SurfaceOrientedMeteringPointFactory(1.0f, 1.0f)
            val point = factory.createPoint(focusAnchorNormX, focusAnchorNormY)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()
            val future = cam.cameraControl.startFocusAndMetering(action)
            future.addListener({
                onFocusStateChanged?.invoke(false)
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            onFocusStateChanged?.invoke(false)
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(Unit) {
        onFrameCaptureReady?.invoke {
            previewView.bitmap
        }
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 实时环境光照与明暗色彩分析用例
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor, analyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                activeCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
