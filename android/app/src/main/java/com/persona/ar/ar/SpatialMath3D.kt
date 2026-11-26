package com.persona.ar.ar

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 3D 空间追踪、相机内参、射线-平面求交与传感器坐标系变换纯数学模块（零 Android UI 依赖，可 JVM 单元测试）。
 *
 * 坐标系约定：
 * - Filament 世界坐标系（右手 Y-up）：+X 右，+Y 地球重力正上方（垂直桌面向上），+Z 指向锚定时用户方向（相机初始位于 +Z 朝 -Z 看）；
 * - Android 传感器旋转矩阵 R（行主序 3×3，由 SensorManager.getRotationMatrixFromVector 生成）：
 *   把设备局部向量 v_dev 变到 ENU 世界向量 v_enu = R * v_dev，其中 ENU +Z 为天顶方向；
 * - 屏幕归一化坐标：左上 (0, 0)，右下 (1, 1)。
 */
object SpatialMath3D {

    /**
     * 将屏幕归一化坐标 `(screenX, screenY)` 映射到 CameraX `ImageAnalysis` 原始传感器缓冲区归一化坐标 `(u, v)`。
     * 严格处理：
     * 1. `PreviewView.ScaleType.FILL_CENTER`（中心裁剪填充屏幕视口）；
     * 2. `imageInfo.rotationDegrees`（从传感器缓冲顺时针旋转到竖屏显示的度数：0 / 90 / 180 / 270）。
     */
    fun screenNormToBufferNorm(
        screenX: Float,
        screenY: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        viewportAspect: Float
    ): Pair<Float, Float> {
        val bw = bufferWidth.coerceAtLeast(1).toFloat()
        val bh = bufferHeight.coerceAtLeast(1).toFloat()
        val rot = ((rotationDegrees % 360) + 360) % 360
        val rotatedW = if (rot == 90 || rot == 270) bh else bw
        val rotatedH = if (rot == 90 || rot == 270) bw else bh
        val rotAspect = (rotatedW / rotatedH).coerceAtLeast(1e-4f)
        val viewAsp = viewportAspect.coerceAtLeast(1e-4f)

        // FILL_CENTER：按较大缩放比铺满视口，另一轴做中心裁剪
        val rotX: Float
        val rotY: Float
        if (rotAspect > viewAsp) {
            // 旋转后画面比屏幕更宽：上下完整、左右裁剪
            val visibleWidthFraction = (viewAsp / rotAspect).coerceIn(0.05f, 1f)
            rotX = 0.5f + (screenX - 0.5f) * visibleWidthFraction
            rotY = screenY
        } else {
            // 旋转后画面比屏幕更窄：左右完整、上下裁剪
            val visibleHeightFraction = (rotAspect / viewAsp).coerceIn(0.05f, 1f)
            rotX = screenX
            rotY = 0.5f + (screenY - 0.5f) * visibleHeightFraction
        }

        val cx = rotX.coerceIn(0f, 1f)
        val cy = rotY.coerceIn(0f, 1f)
        val u: Float
        val v: Float
        when (rot) {
            90 -> {
                u = cy
                v = 1f - cx
            }
            180 -> {
                u = 1f - cx
                v = 1f - cy
            }
            270 -> {
                u = 1f - cy
                v = cx
            }
            else -> {
                u = cx
                v = cy
            }
        }
        return u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }

    /**
     * 逆映射：把传感器缓冲中的归一化坐标 `(u, v)` 映射回竖屏视口归一化坐标 `(screenX, screenY)`。
     */
    fun bufferNormToScreenNorm(
        u: Float,
        v: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        viewportAspect: Float
    ): Pair<Float, Float> {
        val bw = bufferWidth.coerceAtLeast(1).toFloat()
        val bh = bufferHeight.coerceAtLeast(1).toFloat()
        val rot = ((rotationDegrees % 360) + 360) % 360
        val rotatedW = if (rot == 90 || rot == 270) bh else bw
        val rotatedH = if (rot == 90 || rot == 270) bw else bh
        val rotAspect = (rotatedW / rotatedH).coerceAtLeast(1e-4f)
        val viewAsp = viewportAspect.coerceAtLeast(1e-4f)

        val rotX: Float
        val rotY: Float
        when (rot) {
            90 -> {
                rotX = 1f - v
                rotY = u
            }
            180 -> {
                rotX = 1f - u
                rotY = 1f - v
            }
            270 -> {
                rotX = v
                rotY = 1f - u
            }
            else -> {
                rotX = u
                rotY = v
            }
        }

        val sx: Float
        val sy: Float
        if (rotAspect > viewAsp) {
            val visibleWidthFraction = (viewAsp / rotAspect).coerceIn(0.05f, 1f)
            sx = 0.5f + (rotX - 0.5f) / visibleWidthFraction
            sy = rotY
        } else {
            val visibleHeightFraction = (rotAspect / viewAsp).coerceIn(0.05f, 1f)
            sx = rotX
            sy = 0.5f + (rotY - 0.5f) / visibleHeightFraction
        }
        return sx to sy
    }

    /**
     * 从 SensorManager 行主序 3×3 旋转矩阵（Device -> ENU）提取水平校准偏航角（弧度）。
     * 使锚定瞬间相机的水平前方对齐世界 -Z。
     */
    fun extractAnchorYawRad(rotEnu: FloatArray): Float {
        // 相机前方 = -设备 Z 轴在 ENU 系中的投影
        var fx = -rotEnu[2]
        var fy = -rotEnu[5]
        var len = sqrt(fx * fx + fy * fy)
        if (len < 0.08f) {
            // 手机几乎垂直俯视桌面时，用设备顶部 (+Y_dev) 水平方向作为前向
            fx = rotEnu[1]
            fy = rotEnu[4]
            len = sqrt(fx * fx + fy * fy)
        }
        if (len < 1e-4f) return 0f
        fx /= len
        fy /= len
        // ENU: +X=东, +Y=北; 希望将 (fx, fy) 旋到世界 -Z (对应 ENU +Y 北方 (0, 1))
        return atan2(fx.toDouble(), fy.toDouble()).toFloat()
    }

    /**
     * 将 SensorManager 的 3×3 行主序旋转矩阵 `rotEnu` 转换为经过锚定偏航角 `anchorYawRad` 校准后的
     * Filament Y-up 世界坐标系相机正交基 `(outRight, outUp, outForward)`。
     *
     * 世界系严格保持 +Y 为地球重力正上方（垂直桌面向上），满足右手定则：right × up = -forward。
     */
    fun sensorMatrixToWorldBasis(
        rotEnu: FloatArray,
        anchorYawRad: Float,
        outRight: FloatArray,
        outUp: FloatArray,
        outForward: FloatArray
    ) {
        // 设备三轴在 ENU 系中的向量: Right = col0, Up = col1, Forward = -col2
        val rxE = rotEnu[0]; val ryE = rotEnu[3]; val rzE = rotEnu[6]
        val uxE = rotEnu[1]; val uyE = rotEnu[4]; val uzE = rotEnu[7]
        val fxE = -rotEnu[2]; val fyE = -rotEnu[5]; val fzE = -rotEnu[8]

        // 先绕 ENU +Z 轴（重力轴）逆向旋转使锚定水平前方朝 ENU +Y (0, 1, 0)，再转 Filament (x, z, -y)
        // 等价于在 ENU 水平面按 cos(yaw), sin(yaw) 旋转：
        // x' = x*cos - y*sin,  y' = x*sin + y*cos
        // 核验：当水平朝向 (fx, fy) = (sin(yaw), cos(yaw)) 时：
        // x' = sin*cos - cos*sin = 0; y' = sin^2 + cos^2 = 1 → ENU +Y → Filament -Z！
        val c = cos(anchorYawRad)
        val s = sin(anchorYawRad)

        fun mapVec(vx: Float, vy: Float, vz: Float, out: FloatArray) {
            val ax = vx * c - vy * s
            val ay = vx * s + vy * c
            out[0] = ax
            out[1] = vz
            out[2] = -ay
        }

        mapVec(rxE, ryE, rzE, outRight)
        mapVec(uxE, uyE, uzE, outUp)
        mapVec(fxE, fyE, fzE, outForward)

        orthonormalizeBasis(outRight, outUp, outForward)
    }

    /** 侧置竖屏（手机横向侧放在桌面上）时保留屏幕竖直可读的基向量转换（不强制对齐重力垂直） */
    fun sensorMatrixToAnchoredBasis(
        rotEnu: FloatArray,
        anchorMatrixEnu: FloatArray,
        outRight: FloatArray,
        outUp: FloatArray,
        outForward: FloatArray
    ) {
        // M_rel = R_anchor^T * R_curr（列0=Right, 列1=Up, 列2=-Forward）
        val a00 = anchorMatrixEnu[0]; val a01 = anchorMatrixEnu[1]; val a02 = anchorMatrixEnu[2]
        val a10 = anchorMatrixEnu[3]; val a11 = anchorMatrixEnu[4]; val a12 = anchorMatrixEnu[5]
        val a20 = anchorMatrixEnu[6]; val a21 = anchorMatrixEnu[7]; val a22 = anchorMatrixEnu[8]

        val c00 = rotEnu[0]; val c01 = rotEnu[1]; val c02 = rotEnu[2]
        val c10 = rotEnu[3]; val c11 = rotEnu[4]; val c12 = rotEnu[5]
        val c20 = rotEnu[6]; val c21 = rotEnu[7]; val c22 = rotEnu[8]

        outRight[0] = a00 * c00 + a10 * c10 + a20 * c20
        outRight[1] = a01 * c00 + a11 * c10 + a21 * c20
        outRight[2] = a02 * c00 + a12 * c10 + a22 * c20

        outUp[0] = a00 * c01 + a10 * c11 + a20 * c21
        outUp[1] = a01 * c01 + a11 * c11 + a21 * c21
        outUp[2] = a02 * c01 + a12 * c11 + a22 * c21

        outForward[0] = -(a00 * c02 + a10 * c12 + a20 * c22)
        outForward[1] = -(a01 * c02 + a11 * c12 + a21 * c22)
        outForward[2] = -(a02 * c02 + a12 * c12 + a22 * c22)

        orthonormalizeBasis(outRight, outUp, outForward)
    }

    private fun orthonormalizeBasis(right: FloatArray, up: FloatArray, forward: FloatArray) {
        normalize3(forward)
        // right = normalize(forward × up)
        val rx = forward[1] * up[2] - forward[2] * up[1]
        val ry = forward[2] * up[0] - forward[0] * up[2]
        val rz = forward[0] * up[1] - forward[1] * up[0]
        val rLen = sqrt(rx * rx + ry * ry + rz * rz)
        if (rLen > 1e-5f) {
            right[0] = rx / rLen; right[1] = ry / rLen; right[2] = rz / rLen
        }
        // up = normalize(right × forward) -> 注意右手法则下 camera back = -forward，
        // right × (-forward) = up => forward × right = -up => up = right × forward ?
        // 验证: right=(1,0,0), forward=(0,0,-1), right × forward = (0, 1, 0) = +Y = up！
        val ux = right[1] * forward[2] - right[2] * forward[1]
        val uy = right[2] * forward[0] - right[0] * forward[2]
        val uz = right[0] * forward[1] - right[1] * forward[0]
        up[0] = ux; up[1] = uy; up[2] = uz
        normalize3(up)
    }

    fun normalize3(v: FloatArray) {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len > 1e-6f) {
            v[0] /= len; v[1] /= len; v[2] /= len
        }
    }

    /**
     * 计算从屏幕归一化坐标 `(screenX, screenY)` 出发的世界空间单位射线方向 `outDir`。
     */
    fun screenToWorldRay(
        screenX: Float,
        screenY: Float,
        verticalFovDeg: Float,
        aspect: Float,
        camRight: FloatArray,
        camUp: FloatArray,
        camForward: FloatArray,
        outDir: FloatArray
    ) {
        val tanHalfFov = tan(Math.toRadians(verticalFovDeg.toDouble()) * 0.5).toFloat()
        val ndcX = 2f * screenX - 1f
        val ndcY = 1f - 2f * screenY
        val cx = ndcX * tanHalfFov * aspect.coerceAtLeast(1e-4f)
        val cy = ndcY * tanHalfFov

        outDir[0] = cx * camRight[0] + cy * camUp[0] + camForward[0]
        outDir[1] = cx * camRight[1] + cy * camUp[1] + camForward[1]
        outDir[2] = cx * camRight[2] + cy * camUp[2] + camForward[2]
        normalize3(outDir)
    }

    /**
     * 射线与水平支撑平面 `Y = planeY` 求交，安全处理平视或仰视奇异点：
     * 当射线不再明显向下照射水平面（`rayDir.y > -0.10f`）时，利用有效俯角下限与距离上限保证交互平滑连续，
     * 不会飞出无穷远或反向跃迁。
     */
    fun intersectRayWithHorizontalPlane(
        camPosX: Float,
        camPosY: Float,
        camPosZ: Float,
        rayDir: FloatArray,
        planeY: Float,
        fallbackDistance: Float,
        minDistance: Float = 0.24f,
        maxDistance: Float = 2.60f,
        outPoint: FloatArray
    ) {
        val heightAbovePlane = (camPosY - planeY).coerceAtLeast(0.12f)
        val dirY = rayDir[1]
        if (dirY < -0.10f) {
            val rawT = heightAbovePlane / (-dirY)
            val clampedT = rawT.coerceIn(minDistance, maxDistance)
            outPoint[0] = camPosX + rayDir[0] * clampedT
            outPoint[1] = planeY
            outPoint[2] = camPosZ + rayDir[2] * clampedT
        } else {
            // 平视或向上仰视拖动时：沿射线水平投影方向投影到当前有效工作距离，并保持平面高度不变
            var hx = rayDir[0]
            var hz = rayDir[2]
            val hLen = sqrt(hx * hx + hz * hz)
            if (hLen > 1e-4f) {
                hx /= hLen; hz /= hLen
            } else {
                hx = 0f; hz = -1f
            }
            // 向上滑相当于推远，向下滑相当于拉近；用 dirY 在 [-0.10, +0.60] 平滑插值距离
            val pushFactor = ((dirY + 0.10f) / 0.50f).coerceIn(0f, 1.5f)
            val dist = (fallbackDistance * (1f + pushFactor * 0.8f)).coerceIn(minDistance, maxDistance)
            outPoint[0] = camPosX + hx * dist
            outPoint[1] = planeY
            outPoint[2] = camPosZ + hz * dist
        }
    }

    /**
     * 将世界坐标 `(wx, wy, wz)` 投影到屏幕归一化坐标，返回 `Triple(screenX, screenY, viewDepthZ)`。
     * 与 Filament `Camera.setProjection` / `lookAt` 口径 100% 一致。
     */
    fun projectWorldToScreen(
        wx: Float,
        wy: Float,
        wz: Float,
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
        val dx = wx - camPosX
        val dy = wy - camPosY
        val dz = wz - camPosZ

        val viewX = dx * camRight[0] + dy * camRight[1] + dz * camRight[2]
        val viewY = dx * camUp[0] + dy * camUp[1] + dz * camUp[2]
        val viewZ = dx * camForward[0] + dy * camForward[1] + dz * camForward[2]

        val tanHalfFov = tan(Math.toRadians(verticalFovDeg.toDouble()) * 0.5).toFloat()
        val safeZ = if (viewZ > 0.04f) viewZ else 0.04f
        val ndcX = viewX / (safeZ * tanHalfFov * aspect.coerceAtLeast(1e-4f))
        val ndcY = viewY / (safeZ * tanHalfFov)

        val sx = (ndcX + 1f) * 0.5f
        val sy = (1f - ndcY) * 0.5f
        outScreen[0] = sx
        outScreen[1] = sy
        outScreen[2] = viewZ

        return (viewZ > 0.08f) && (sx in -0.20f..1.20f) && (sy in -0.22f..1.22f)
    }

    /**
     * 根据世界锚点相对相机前向的水平投影，计算方位角差（度，负=在画面左侧，正=在画面右侧）。
     */
    fun computeAzimuthDeltaDegrees(
        wx: Float,
        wy: Float,
        wz: Float,
        camPosX: Float,
        camPosY: Float,
        camPosZ: Float,
        camRight: FloatArray,
        camForward: FloatArray
    ): Float {
        val dx = wx - camPosX
        val dy = wy - camPosY
        val dz = wz - camPosZ
        val vx = dx * camRight[0] + dy * camRight[1] + dz * camRight[2]
        val vz = dx * camForward[0] + dy * camForward[1] + dz * camForward[2]
        return Math.toDegrees(atan2(vx.toDouble(), vz.toDouble())).toFloat()
    }

    /**
     * 从 Camera2 `SENSOR_INFO_PHYSICAL_SIZE` 与焦距计算屏幕实际垂直视场角（度）。
     * 竖屏显示时，传感器长边对应竖直方向（或结合像素宽高与 FILL_CENTER 裁剪）。
     */
    fun computeEffectiveVerticalFov(
        sensorWidthMm: Float,
        sensorHeightMm: Float,
        focalLengthMm: Float,
        rotationDegrees: Int,
        viewportAspect: Float
    ): Float {
        if (sensorWidthMm <= 0f || sensorHeightMm <= 0f || focalLengthMm <= 0f) return 62f
        val rot = ((rotationDegrees % 360) + 360) % 360
        // 旋转后竖屏方向对应的传感器物理尺寸
        val rotPhysicalW = if (rot == 90 || rot == 270) sensorHeightMm else sensorWidthMm
        val rotPhysicalH = if (rot == 90 || rot == 270) sensorWidthMm else sensorHeightMm
        val rotAspect = (rotPhysicalW / rotPhysicalH).coerceAtLeast(1e-4f)
        val viewAsp = viewportAspect.coerceAtLeast(1e-4f)

        // PreviewView FILL_CENTER：若 rotAspect > viewAsp，垂直方向满帧无裁剪，直接取 rotPhysicalH；
        // 若 rotAspect < viewAsp，垂直方向被裁剪为 rotPhysicalW / viewAsp。
        val visiblePhysicalH = if (rotAspect >= viewAsp) rotPhysicalH else (rotPhysicalW / viewAsp)
        val fovRad = 2.0 * atan2((visiblePhysicalH * 0.5).toDouble(), focalLengthMm.toDouble())
        return Math.toDegrees(fovRad).toFloat().coerceIn(35f, 88f)
    }

    /** 角度与弧度快速转换常量 */
    const val DEG_TO_RAD = (PI / 180.0).toFloat()
}
