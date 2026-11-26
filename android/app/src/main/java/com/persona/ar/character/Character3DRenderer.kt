package com.persona.ar.character

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.persona.ar.ar.ARCoreOverlay
import com.persona.ar.ar.ARSpatialAnchor
import com.persona.ar.ar.ARTrackingStatus
import com.persona.ar.data.model.TouchPart

/**
 * AR 3D 空间渲染器（唯一 3D 实景交互入口）
 */
@Composable
fun Character3DRenderer(
    visualState: CharacterVisualState,
    onTouchPart: (TouchPart, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    trackingStatus: ARTrackingStatus = ARTrackingStatus.ANCHORED,
    spatialAnchor: ARSpatialAnchor = ARSpatialAnchor(isAnchored = true),
    partnerName: String = "星野",
    onTapPlace: (Float, Float) -> Unit = { _, _ -> },
    onTransformGesture: (Float, Float) -> Unit = { _, _ -> },
    onDragStart: (Float, Float) -> Unit = { _, _ -> },
    onDragTo: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onFigureCaptureReady: (((() -> Bitmap?)) -> Unit)? = null
) {
    ARCoreOverlay(
        trackingStatus = trackingStatus,
        spatialAnchor = spatialAnchor,
        visualState = visualState,
        partnerName = partnerName,
        onTapPlace = onTapPlace,
        onTransformGesture = onTransformGesture,
        onDragStart = onDragStart,
        onDragTo = onDragTo,
        onDragEnd = onDragEnd,
        onTouchPart = onTouchPart,
        onFigureCaptureReady = onFigureCaptureReady,
        modifier = modifier
    )
}
