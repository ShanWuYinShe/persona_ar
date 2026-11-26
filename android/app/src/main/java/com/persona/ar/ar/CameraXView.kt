package com.persona.ar.ar

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * 现实环境透传与物理光学/视觉追踪视口 (CameraX View)
 * 1. 读取物理相机真实传感器尺寸与焦距，同步真实垂直 FOV 给 Filament 3D 相机；
 * 2. 桥接 6DoF 视觉-惯性光流追踪与全光谱环境光探针；
 * 3. 支持实时无感抓取相机画面用于真 3D 拍立得合影合成。
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraXView(
    modifier: Modifier = Modifier,
    focusAnchorNormX: Float = 0.5f,
    focusAnchorNormY: Float = 0.65f,
    isAnchored: Boolean = false,
    isDragging: Boolean = false,
    resetFeaturesSignal: Long = 0L,
    isFocusTriggered: Boolean = false,
    imuBasisProvider: ((FloatArray, FloatArray, FloatArray) -> Boolean)? = null,
    onVisualAnchorObserved: ((VisualAnchorObservation, FloatArray, FloatArray, FloatArray) -> Unit)? = null,
    onCameraIntrinsicsChanged: ((effectiveVerticalFovDeg: Float, viewportAspect: Float) -> Unit)? = null,
    onFocusStateChanged: ((isFocusing: Boolean) -> Unit)? = null,
    onEnvironmentLightChanged: (luminance: Float, isLowLight: Boolean) -> Unit = { _, _ -> },
    onOpticalProfileChanged: ((EnvironmentalOpticalProfile) -> Unit)? = null,
    onFrameCaptureReady: (((() -> Bitmap?)) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var cameraSensorWidthMm by remember { mutableFloatStateOf(0f) }
    var cameraSensorHeightMm by remember { mutableFloatStateOf(0f) }
    var cameraFocalLengthMm by remember { mutableFloatStateOf(0f) }
    var cameraSensorOrientation by remember { mutableIntStateOf(90) }

    val latestImuProvider by rememberUpdatedState(imuBasisProvider)
    val latestVisualCallback by rememberUpdatedState(onVisualAnchorObserved)

    val analyzer = remember {
        CameraEnvironmentalAnalyzer(
            onLightEstimated = { lum, isLow ->
                onEnvironmentLightChanged(lum, isLow)
            },
            onOpticalProfileEstimated = { profile ->
                onOpticalProfileChanged?.invoke(profile)
            },
            imuBasisProvider = { r, u, f ->
                latestImuProvider?.invoke(r, u, f) == true
            },
            onVisualAnchorObserved = { obs, r, u, f ->
                latestVisualCallback?.invoke(obs, r, u, f)
            }
        )
    }

    LaunchedEffect(focusAnchorNormX, focusAnchorNormY) {
        analyzer.focusNormX = focusAnchorNormX
        analyzer.focusNormY = focusAnchorNormY
    }

    LaunchedEffect(isAnchored, isDragging) {
        analyzer.isTrackingActive = isAnchored
        analyzer.isDraggingPaused = isDragging
        if (!isAnchored) {
            analyzer.clearFeatures()
        }
    }

    LaunchedEffect(resetFeaturesSignal) {
        if (resetFeaturesSignal > 0L && isAnchored) {
            analyzer.requestResetFeatures(focusAnchorNormX, focusAnchorNormY)
        }
    }

    // 联动物理摄像头执行真实硬件自动对焦与局部曝光测光
    LaunchedEffect(isFocusTriggered, focusAnchorNormX, focusAnchorNormY, activeCamera) {
        val cam = activeCamera ?: return@LaunchedEffect
        if (!isFocusTriggered) return@LaunchedEffect
        try {
            onFocusStateChanged?.invoke(true)
            val factory = SurfaceOrientedMeteringPointFactory(1.0f, 1.0f)
            val point = factory.createPoint(focusAnchorNormX.coerceIn(0.05f, 0.95f), focusAnchorNormY.coerceIn(0.05f, 0.95f))
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build()
            val future = cam.cameraControl.startFocusAndMetering(action)
            future.addListener({
                onFocusStateChanged?.invoke(false)
            }, ContextCompat.getMainExecutor(context))
        } catch (_: Exception) {
            onFocusStateChanged?.invoke(false)
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(Unit) {
        onFrameCaptureReady?.invoke { previewView.bitmap }
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor, analyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                activeCamera = cam
                runCatching {
                    val info = Camera2CameraInfo.from(cam.cameraInfo)
                    val phys = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focals = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val orientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
                    if (phys != null && focals != null && focals.isNotEmpty()) {
                        cameraSensorWidthMm = phys.width
                        cameraSensorHeightMm = phys.height
                        cameraFocalLengthMm = focals[0]
                    }
                    if (orientation != null) {
                        cameraSensorOrientation = orientation
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val aspect = remember(constraints.maxWidth, constraints.maxHeight) {
            val w = constraints.maxWidth.coerceAtLeast(1).toFloat()
            val h = constraints.maxHeight.coerceAtLeast(1).toFloat()
            w / h
        }
        LaunchedEffect(aspect, cameraSensorWidthMm, cameraSensorHeightMm, cameraFocalLengthMm, cameraSensorOrientation) {
            val effFov = SpatialMath3D.computeEffectiveVerticalFov(
                sensorWidthMm = cameraSensorWidthMm,
                sensorHeightMm = cameraSensorHeightMm,
                focalLengthMm = cameraFocalLengthMm,
                rotationDegrees = cameraSensorOrientation,
                viewportAspect = aspect
            )
            analyzer.viewportAspect = aspect
            analyzer.effectiveVerticalFovDeg = effFov
            onCameraIntrinsicsChanged?.invoke(effFov, aspect)
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
