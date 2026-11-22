package com.persona.ar.character

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.persona.ar.ar.ARCoreOverlay
import com.persona.ar.ar.ARSpatialAnchor
import com.persona.ar.ar.ARTrackingStatus
import com.persona.ar.data.model.TouchPart

/**
 * AR 3D 空间渲染器
 * 融合 ARCore 物理平面追踪、空间锚点、接触阴影与全手势交互
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
    onResetAnchor: () -> Unit = {}
) {
    ARCoreOverlay(
        trackingStatus = trackingStatus,
        spatialAnchor = spatialAnchor,
        visualState = visualState,
        partnerName = partnerName,
        onTapPlace = onTapPlace,
        onTransformGesture = onTransformGesture,
        onResetAnchor = onResetAnchor,
        onTouchPart = onTouchPart,
        modifier = modifier
    )
}

@Composable
fun Character3DRenderer(
    visualState: CharacterVisualState,
    onTouchPart: (TouchPart) -> Unit,
    modifier: Modifier = Modifier,
    trackingStatus: ARTrackingStatus = ARTrackingStatus.ANCHORED,
    spatialAnchor: ARSpatialAnchor = ARSpatialAnchor(isAnchored = true),
    partnerName: String = "星野",
    onTapPlace: (Float, Float) -> Unit = { _, _ -> },
    onTransformGesture: (Float, Float) -> Unit = { _, _ -> },
    onResetAnchor: () -> Unit = {}
) {
    Character3DRenderer(
        visualState = visualState,
        onTouchPart = { part, _, _ -> onTouchPart(part) },
        modifier = modifier,
        trackingStatus = trackingStatus,
        spatialAnchor = spatialAnchor,
        partnerName = partnerName,
        onTapPlace = onTapPlace,
        onTransformGesture = onTransformGesture,
        onResetAnchor = onResetAnchor
    )
}
