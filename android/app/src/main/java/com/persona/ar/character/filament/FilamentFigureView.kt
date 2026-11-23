package com.persona.ar.character.filament

import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.android.UiHelper
import com.persona.ar.data.model.TouchPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FilamentFigureView"

/**
 * 真 3D 手办 Compose 层：TextureView 承载透明 Filament SwapChain，按 Compose 绘制顺序叠在 CameraX 预览与
 * 贴地引导 Canvas 之上、HUD 之下；Choreographer 驱动每帧渲染；点击经 GPU 拾取映射为 TouchPart。
 *
 * 选 TextureView 而非 SurfaceView：SurfaceView 由系统在窗口下方独立合成、无视 Compose 层序，
 * 无法夹在两层 Canvas 之间；TextureView 作为普通视图参与 Compose 绘制顺序，代价仅一次额外拷贝。
 */
@Composable
fun FilamentFigureView(
    frameState: FigureFrameState,
    modelAssetPath: String?,
    modifier: Modifier = Modifier,
    onTapPart: ((TouchPart, Float, Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val scene = remember {
        runCatching { FigureScene(context.applicationContext) }
            .onFailure { Log.e(TAG, "Filament 场景创建失败，该层留空", it) }
            .getOrNull()
    }
    if (scene == null) {
        Spacer(modifier)
        return
    }

    val uiHelper = remember {
        UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            // 非不透明 → SwapChain 带 CONFIG_TRANSPARENT，清屏 alpha=0 透出相机画面
            isOpaque = false
            setRenderCallback(scene.surfaceCallback)
        }
    }
    val latestFrame by rememberUpdatedState(frameState)
    val latestTap by rememberUpdatedState(onTapPart)

    // 渲染循环：每帧读取最新状态快照渲染；Dispose 顺序照抄官方 ModelViewer（先 detach Surface 再销毁场景）
    DisposableEffect(scene) {
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(nanos: Long) {
                scene.render(latestFrame.copy(timeNanos = nanos))
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

    // 模型加载：IO 线程读取 assets 字节，主线程安装到 Filament
    LaunchedEffect(scene, modelAssetPath) {
        val path = modelAssetPath ?: return@LaunchedEffect
        val model = withContext(Dispatchers.IO) {
            runCatching { scene.loadModelAsset(path) }
                .onFailure { Log.e(TAG, "读取/解析模型失败: $path", it) }
                .getOrNull()
        }
        model?.let { scene.installAsset(it) }
    }

    AndroidView(
        factory = { ctx -> TextureView(ctx).also { uiHelper.attachTo(it) } },
        modifier = modifier.pointerInput(scene) {
            detectTapGestures { offset ->
                val tap = latestTap ?: return@detectTapGestures
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                scene.requestPick(offset.x.toInt(), offset.y.toInt()) { part ->
                    if (part != null) tap(part, offset.x / w, offset.y / h)
                }
            }
        }
    )
}