package com.persona.ar.character.animation

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯 Kotlin 四元数 / 4x4 矩阵数学工具（零 Android 依赖，可在 JVM 单元测试中直接运行）。
 *
 * 约定：
 * - 四元数用 FloatArray(4) 存 [x, y, z, w]；
 * - 矩阵用 FloatArray(16) 存 **列主序**（与 OpenGL / Filament 一致），m[col * 4 + row]；
 * - 所有函数都把结果写入调用方提供的 out 数组，避免每帧分配。
 */
object QuatMath {

    fun identity(out: FloatArray = FloatArray(4)): FloatArray {
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f
        return out
    }

    /** 轴角（角度制）→ 四元数 */
    fun setAxisAngle(out: FloatArray, ax: Float, ay: Float, az: Float, degrees: Float) {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-8f || kotlin.math.abs(degrees) < 1e-6f) {
            identity(out)
            return
        }
        val half = Math.toRadians(degrees.toDouble()).toFloat() * 0.5f
        val s = sin(half)
        out[0] = ax / len * s
        out[1] = ay / len * s
        out[2] = az / len * s
        out[3] = cos(half)
    }

    /** out = a * b（先施加 b 再施加 a，标准四元数乘法） */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        multiply(out, 0, a, 0, b, 0)
    }

    /** out[outOff..] = a[aOff..] * b[bOff..] */
    fun multiply(out: FloatArray, outOff: Int, a: FloatArray, aOff: Int, b: FloatArray, bOff: Int) {
        val ax = a[aOff]; val ay = a[aOff + 1]; val az = a[aOff + 2]; val aw = a[aOff + 3]
        val bx = b[bOff]; val by = b[bOff + 1]; val bz = b[bOff + 2]; val bw = b[bOff + 3]
        val x = aw * bx + ax * bw + ay * bz - az * by
        val y = aw * by - ax * bz + ay * bw + az * bx
        val z = aw * bz + ax * by - ay * bx + az * bw
        val w = aw * bw - ax * bx - ay * by - az * bz
        out[outOff] = x; out[outOff + 1] = y; out[outOff + 2] = z; out[outOff + 3] = w
    }

    fun normalize(q: FloatArray) {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len < 1e-8f) { identity(q); return }
        q[0] /= len; q[1] /= len; q[2] /= len; q[3] /= len
    }

    fun copy(dst: FloatArray, src: FloatArray) {
        System.arraycopy(src, 0, dst, 0, 4)
    }

    fun copy(dst: FloatArray, dstOff: Int, src: FloatArray, srcOff: Int) {
        System.arraycopy(src, srcOff, dst, dstOff, 4)
    }

    /** q 旋转向量 v，结果写入 out3 */
    fun rotate(q: FloatArray, vx: Float, vy: Float, vz: Float, out3: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        // t = 2 * (q.xyz × v)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        out3[0] = vx + w * tx + (y * tz - z * ty)
        out3[1] = vy + w * ty + (z * tx - x * tz)
        out3[2] = vz + w * tz + (x * ty - y * tx)
    }

    /** 角度 (rx, ry, rz) → 四元数，旋转顺序：先 Z，再 Y，最后 X（与动作库数值标定口径一致） */
    fun fromEulerDegrees(out: FloatArray, rx: Float, ry: Float, rz: Float) {
        val hx = Math.toRadians(rx.toDouble()).toFloat() * 0.5f
        val hy = Math.toRadians(ry.toDouble()).toFloat() * 0.5f
        val hz = Math.toRadians(rz.toDouble()).toFloat() * 0.5f
        val cx = cos(hx); val sx = sin(hx)
        val cy = cos(hy); val sy = sin(hy)
        val cz = cos(hz); val sz = sin(hz)
        // q = Qx * Qy * Qz
        out[0] = sx * cy * cz + cx * sy * sz
        out[1] = cx * sy * cz - sx * cy * sz
        out[2] = cx * cy * sz + sx * sy * cz
        out[3] = cx * cy * cz - sx * sy * sz
    }

    /** 最短路径球面线性插值 out = slerp(a, b, t)，零分配 */
    fun slerp(out: FloatArray, a: FloatArray, b: FloatArray, t: Float) {
        val u = t.coerceIn(0f, 1f)
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
        if (dot < 0f) {
            dot = -dot
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        if (dot > 0.9995f) {
            out[0] = a[0] + u * (bx - a[0])
            out[1] = a[1] + u * (by - a[1])
            out[2] = a[2] + u * (bz - a[2])
            out[3] = a[3] + u * (bw - a[3])
            normalize(out)
            return
        }
        val clampedDot = dot.coerceIn(-1f, 1f)
        val theta0 = kotlin.math.acos(clampedDot)
        val sinTheta0 = sin(theta0)
        val theta = theta0 * u
        val s0 = cos(theta) - clampedDot * sin(theta) / sinTheta0
        val s1 = sin(theta) / sinTheta0
        out[0] = s0 * a[0] + s1 * bx
        out[1] = s0 * a[1] + s1 * by
        out[2] = s0 * a[2] + s1 * bz
        out[3] = s0 * a[3] + s1 * bw
    }

    /** 四元数 → 欧拉角 (rx, ry, rz) 度，与 fromEulerDegrees (先 Z 再 Y 最后 X) 互逆 */
    fun toEulerDegrees(q: FloatArray, out3: FloatArray, outOffset: Int = 0) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val r00 = 1f - 2f * (y * y + z * z)
        val r01 = 2f * (x * y - w * z)
        val r02 = 2f * (x * z + w * y)
        val r11 = 1f - 2f * (x * x + z * z)
        val r12 = 2f * (y * z - w * x)
        val r21 = 2f * (y * z + w * x)
        val r22 = 1f - 2f * (x * x + y * y)

        val sy = r02.coerceIn(-1f, 1f)
        val ry = kotlin.math.asin(sy)
        val rx: Float
        val rz: Float
        if (kotlin.math.abs(r02) < 0.9999f) {
            rx = kotlin.math.atan2(-r12, r22)
            rz = kotlin.math.atan2(-r01, r00)
        } else {
            rx = kotlin.math.atan2(r21, r11)
            rz = 0f
        }
        val rad2deg = (180.0 / Math.PI).toFloat()
        out3[outOffset] = rx * rad2deg
        out3[outOffset + 1] = ry * rad2deg
        out3[outOffset + 2] = rz * rad2deg
    }
}

/** 纯 Kotlin 4x4 列主序矩阵工具 */
object Mat4 {
    fun identity(out: FloatArray = FloatArray(16)): FloatArray {
        java.util.Arrays.fill(out, 0f)
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        return out
    }

    /** out = T(t) * R(q) * S(s)，列主序 */
    fun fromTRS(out: FloatArray, tx: Float, ty: Float, tz: Float, q: FloatArray, sx: Float, sy: Float, sz: Float) {
        fromTRS(out, tx, ty, tz, q, 0, sx, sy, sz)
    }

    /** out = T(t) * R(q[qOff..]) * S(s)，列主序 */
    fun fromTRS(out: FloatArray, tx: Float, ty: Float, tz: Float, q: FloatArray, qOff: Int,
                sx: Float, sy: Float, sz: Float) {
        val x = q[qOff]; val y = q[qOff + 1]; val z = q[qOff + 2]; val w = q[qOff + 3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = (1f - 2f * (yy + zz)) * sx
        out[1] = (2f * (xy + wz)) * sx
        out[2] = (2f * (xz - wy)) * sx
        out[3] = 0f
        out[4] = (2f * (xy - wz)) * sy
        out[5] = (1f - 2f * (xx + zz)) * sy
        out[6] = (2f * (yz + wx)) * sy
        out[7] = 0f
        out[8] = (2f * (xz + wy)) * sz
        out[9] = (2f * (yz - wx)) * sz
        out[10] = (1f - 2f * (xx + yy)) * sz
        out[11] = 0f
        out[12] = tx; out[13] = ty; out[14] = tz; out[15] = 1f
    }

    /** out = a * b（列主序） */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        multiply(out, 0, a, 0, b, 0)
    }

    /** out[outOff..] = a[aOff..] * b[bOff..]（列主序，允许 out 与 a 为同一数组但区域不重叠） */
    fun multiply(out: FloatArray, outOff: Int, a: FloatArray, aOff: Int, b: FloatArray, bOff: Int) {
        for (c in 0 until 4) {
            val b0 = b[bOff + c * 4]; val b1 = b[bOff + c * 4 + 1]
            val b2 = b[bOff + c * 4 + 2]; val b3 = b[bOff + c * 4 + 3]
            for (r in 0 until 4) {
                out[outOff + c * 4 + r] = a[aOff + r] * b0 + a[aOff + 4 + r] * b1 +
                        a[aOff + 8 + r] * b2 + a[aOff + 12 + r] * b3
            }
        }
    }

    /** 刚体 TRS 矩阵求逆（假设无剪切、均匀/非均匀缩放均可） */
    fun invertRigid(out: FloatArray, m: FloatArray) {
        // 3x3 逆（列主序读取）
        val a00 = m[0]; val a01 = m[4]; val a02 = m[8]
        val a10 = m[1]; val a11 = m[5]; val a12 = m[9]
        val a20 = m[2]; val a21 = m[6]; val a22 = m[10]
        val det = a00 * (a11 * a22 - a12 * a21) -
                a01 * (a10 * a22 - a12 * a20) +
                a02 * (a10 * a21 - a11 * a20)
        if (kotlin.math.abs(det) < 1e-12f) { identity(out); return }
        val inv = 1f / det
        val i00 = (a11 * a22 - a12 * a21) * inv
        val i01 = (a02 * a21 - a01 * a22) * inv
        val i02 = (a01 * a12 - a02 * a11) * inv
        val i10 = (a12 * a20 - a10 * a22) * inv
        val i11 = (a00 * a22 - a02 * a20) * inv
        val i12 = (a02 * a10 - a00 * a12) * inv
        val i20 = (a10 * a21 - a11 * a20) * inv
        val i21 = (a01 * a20 - a00 * a21) * inv
        val i22 = (a00 * a11 - a01 * a10) * inv
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        out[0] = i00; out[1] = i10; out[2] = i20; out[3] = 0f
        out[4] = i01; out[5] = i11; out[6] = i21; out[7] = 0f
        out[8] = i02; out[9] = i12; out[10] = i22; out[11] = 0f
        out[12] = -(i00 * tx + i01 * ty + i02 * tz)
        out[13] = -(i10 * tx + i11 * ty + i12 * tz)
        out[14] = -(i20 * tx + i21 * ty + i22 * tz)
        out[15] = 1f
    }
}