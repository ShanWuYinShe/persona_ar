package com.persona.ar.character.filament

import com.persona.ar.ar.SpatialMath3D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 手办模型变换与骨骼屏幕投影纯 Kotlin 数学工具（不依赖 Android / Filament JNI）。
 *
 * 与 `FigureScene.updateFigure`、`Camera.lookAt`、`SpatialMath3D.projectWorldToScreen`
 * 保持严格一致的列主序变换口径，用于：
 * 1. 计算实时骨骼关节点与头顶表情 VFX 的精准屏幕坐标；
 * 2. 在 JVM 单测中直接验证站立、拖拽偏心、转身与桌沿坐姿下的投影正确性。
 */
internal object FigureProjectionMath {

    /**
     * 生成列主序模型矩阵：`T(footWorld) · Ry(yawDegrees + 180) · S(uniformScale) · T(-pivot)`。
     */
    fun buildModelMatrix(
        out: FloatArray,
        footX: Float,
        footY: Float,
        footZ: Float,
        yawDegrees: Float,
        uniformScale: Float,
        pivotX: Float,
        pivotY: Float,
        pivotZ: Float
    ) {
        val rad = (yawDegrees + 180f) * (PI / 180.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val k = uniformScale

        out[0] = k * c
        out[1] = 0f
        out[2] = -k * s
        out[3] = 0f

        out[4] = 0f
        out[5] = k
        out[6] = 0f
        out[7] = 0f

        out[8] = k * s
        out[9] = 0f
        out[10] = k * c
        out[11] = 0f

        out[12] = footX - (k * c * pivotX + k * s * pivotZ)
        out[13] = footY - (k * pivotY)
        out[14] = footZ - (-k * s * pivotX + k * c * pivotZ)
        out[15] = 1f
    }

    fun transformLocalToWorld(
        modelMatrix: FloatArray,
        lx: Float,
        ly: Float,
        lz: Float,
        outWorld: FloatArray
    ) {
        outWorld[0] = modelMatrix[0] * lx + modelMatrix[4] * ly + modelMatrix[8] * lz + modelMatrix[12]
        outWorld[1] = modelMatrix[1] * lx + modelMatrix[5] * ly + modelMatrix[9] * lz + modelMatrix[13]
        outWorld[2] = modelMatrix[2] * lx + modelMatrix[6] * ly + modelMatrix[10] * lz + modelMatrix[14]
    }

    /**
     * 基于 `PoseSolver.world` 计算头顶情绪 VFX 锚点（沿 HEAD 骨骼局部 +Y 向上偏移 `crownOffsetModelMeters`，
     * 使音符/爱心/星星在歪头、点头、鞠躬或坐姿时依然贴合脸部/发顶正上方）。
     */
    fun computeHeadVfxLocal(
        solverWorld: FloatArray,
        headNodeIndex: Int,
        crownOffsetModelMeters: Float = 0.12f,
        outLocal: FloatArray
    ) {
        val base = headNodeIndex * 16
        val px = solverWorld[base + 12]
        val py = solverWorld[base + 13]
        val pz = solverWorld[base + 14]
        val ux = solverWorld[base + 4]
        val uy = solverWorld[base + 5]
        val uz = solverWorld[base + 6]
        val len = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-4f)
        outLocal[0] = px + (ux / len) * crownOffsetModelMeters
        outLocal[1] = py + (uy / len) * crownOffsetModelMeters
        outLocal[2] = pz + (uz / len) * crownOffsetModelMeters
    }

    fun projectWorldToNormalized(
        worldX: Float,
        worldY: Float,
        worldZ: Float,
        camPosX: Float,
        camPosY: Float,
        camPosZ: Float,
        camRight: FloatArray,
        camUp: FloatArray,
        camForward: FloatArray,
        verticalFovDeg: Float,
        aspect: Float,
        outScreen: FloatArray
    ): Boolean {
        return SpatialMath3D.projectWorldToScreen(
            wx = worldX,
            wy = worldY,
            wz = worldZ,
            camPosX = camPosX,
            camPosY = camPosY,
            camPosZ = camPosZ,
            camRight = camRight,
            camUp = camUp,
            camForward = camForward,
            verticalFovDeg = verticalFovDeg,
            aspect = aspect,
            outScreen = outScreen
        )
    }
}
