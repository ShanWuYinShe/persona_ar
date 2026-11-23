package com.persona.ar.character.skeleton

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轻量纯 Kotlin 3D 向量 (100% 离线，零外部数学库依赖)
 */
data class Vector3D(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    operator fun plus(other: Vector3D) = Vector3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3D) = Vector3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vector3D(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = if (scalar != 0f) Vector3D(x / scalar, y / scalar, z / scalar) else ZERO

    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun lengthSquared(): Float = x * x + y * y + z * z

    fun normalized(): Vector3D {
        val len = length()
        return if (len > 1e-6f) this / len else ZERO
    }

    fun dot(other: Vector3D): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3D): Vector3D = Vector3D(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x
    )

    fun lerp(target: Vector3D, t: Float): Vector3D {
        val clampedT = t.coerceIn(0f, 1f)
        return Vector3D(
            x = x + (target.x - x) * clampedT,
            y = y + (target.y - y) * clampedT,
            z = z + (target.z - z) * clampedT
        )
    }

    companion object {
        val ZERO = Vector3D(0f, 0f, 0f)
        val UP = Vector3D(0f, 1f, 0f)
        val FORWARD = Vector3D(0f, 0f, 1f)
        val RIGHT = Vector3D(1f, 0f, 0f)
    }
}

/**
 * 3D 空间旋转四元数
 */
data class Quaternion3D(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val w: Float = 1f
) {
    fun rotate(v: Vector3D): Vector3D {
        val u = Vector3D(x, y, z)
        val s = w
        val uCrossV = u.cross(v)
        return u * (2f * u.dot(v)) + v * (s * s - u.dot(u)) + uCrossV * (2f * s)
    }

    operator fun times(q: Quaternion3D): Quaternion3D = Quaternion3D(
        w = w * q.w - x * q.x - y * q.y - z * q.z,
        x = w * q.x + x * q.w + y * q.z - z * q.y,
        y = w * q.y - x * q.z + y * q.w + z * q.x,
        z = w * q.z + x * q.y - y * q.x + z * q.w
    )

    fun slerp(target: Quaternion3D, t: Float): Quaternion3D {
        var cosOmega = x * target.x + y * target.y + z * target.z + w * target.w
        var end = target
        if (cosOmega < 0f) {
            end = Quaternion3D(-target.x, -target.y, -target.z, -target.w)
            cosOmega = -cosOmega
        }
        val clampedT = t.coerceIn(0f, 1f)
        if (cosOmega > 0.9995f) {
            val q = Quaternion3D(
                x = x + (end.x - x) * clampedT,
                y = y + (end.y - y) * clampedT,
                z = z + (end.z - z) * clampedT,
                w = w + (end.w - w) * clampedT
            )
            val len = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
            return if (len > 1e-6f) Quaternion3D(q.x / len, q.y / len, q.z / len, q.w / len) else IDENTITY
        }
        val omega = kotlin.math.acos(cosOmega.coerceIn(-1f, 1f))
        val sinOmega = sin(omega)
        val scale0 = sin((1f - clampedT) * omega) / sinOmega
        val scale1 = sin(clampedT * omega) / sinOmega
        return Quaternion3D(
            x = scale0 * x + scale1 * end.x,
            y = scale0 * y + scale1 * end.y,
            z = scale0 * z + scale1 * end.z,
            w = scale0 * w + scale1 * end.w
        )
    }

    companion object {
        val IDENTITY = Quaternion3D(0f, 0f, 0f, 1f)

        fun fromEulerDegrees(pitch: Float, yaw: Float, roll: Float): Quaternion3D {
            val p = (pitch * PI / 360f).toFloat()
            val y = (yaw * PI / 360f).toFloat()
            val r = (roll * PI / 360f).toFloat()
            val cp = cos(p); val sp = sin(p)
            val cy = cos(y); val sy = sin(y)
            val cr = cos(r); val sr = sin(r)
            return Quaternion3D(
                w = cr * cp * cy + sr * sp * sy,
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy
            )
        }
    }
}
