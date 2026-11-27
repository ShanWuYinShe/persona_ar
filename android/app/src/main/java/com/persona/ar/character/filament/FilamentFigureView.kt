package com.persona.ar.character.filament

import android.graphics.Bitmap
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.android.UiHelper
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.TouchPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FilamentFigureView"

/**
 * 真 3D 手办 Compose 层：TextureView 承载透明 Filament SwapChain，
 * Choreographer 驱动每帧渲染，暴露 GPU 拾取接口、头顶屏幕投影坐标与透明 3D 帧截图句柄（供真 3D 合影）。
 */
@Composable
fun FilamentFigureView(
    frameState: FigureFrameState,
    modelAssetPath: String?,
    activeModel: PersonaModelManifest? = null,
    modifier: Modifier = Modifier,
    onHeadScreenPosition: ((Float, Float) -> Unit)? = null,
    onPickPartRequestReady: (((Int, Int, (TouchPart?) -> Unit) -> Unit) -> Unit)? = null,
    onFigureCaptureReady: (((() -> Bitmap?)) -> Unit)? = null
) {
    val context = LocalContext.current
    val scene = remember {
        runCatching { FigureScene(context.applicationContext) }
            .onFailure { Log.e(TAG, "Filament 场景创建失败", it) }
            .getOrNull()
    }
    if (scene == null) {
        Spacer(modifier)
        return
    }

    val uiHelper = remember {
        UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            isOpaque = false
            setRenderCallback(scene.surfaceCallback)
        }
    }
    val latestFrame by rememberUpdatedState(frameState)
    val latestHeadCallback by rememberUpdatedState(onHeadScreenPosition)
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(scene) {
        onPickPartRequestReady?.invoke { x, y, cb ->
            scene.requestPick(x, y, cb)
        }
        onFigureCaptureReady?.invoke {
            textureViewRef.value?.bitmap
        }

        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(nanos: Long) {
                scene.render(latestFrame.copy(timeNanos = nanos))
                latestHeadCallback?.invoke(scene.latestHeadScreenX, scene.latestHeadScreenY)
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose {
            choreographer.removeFrameCallback(callback)
            uiHelper.detach()
            scene.destroy()
        }
    }

    LaunchedEffect(
        scene,
        modelAssetPath,
        activeModel?.id,
        activeModel?.version,
        activeModel?.hairColorHex,
        activeModel?.eyeColorHex,
        activeModel?.skirtColorHex,
        activeModel?.previewColorHex
    ) {
        val path = modelAssetPath ?: return@LaunchedEffect
        val targetModel = activeModel
        val model = withContext(Dispatchers.IO) {
            runCatching { scene.loadModelAsset(path, targetModel) }
                .onFailure { Log.e(TAG, "读取/解析模型失败: $path (${targetModel?.id})", it) }
                .getOrNull()
        }
        model?.let { scene.installAsset(it) }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also {
                it.isOpaque = false
                textureViewRef.value = it
                uiHelper.attachTo(it)
            }
        },
        modifier = modifier
    )
}
