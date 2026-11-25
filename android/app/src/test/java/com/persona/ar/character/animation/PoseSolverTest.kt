package com.persona.ar.character.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PoseSolverTest {

    @Test
    fun `rest pose keeps chain world positions`() {
        val rig = TestRigs.chainRig()
        val solver = PoseSolver(rig)
        solver.solve(FloatArray(rig.nodeCount * 3), 0f, 0f, 0f)
        val pos = FloatArray(3)
        solver.worldPosition(2, pos)
        assertTrue("head y 应为 1.5，实际 ${pos[1]}", abs(pos[1] - 1.5f) < 1e-4f)
        assertEquals(0f, pos[0], 1e-5f)
    }

    @Test
    fun `rotating hips 90deg about Z moves head to left`() {
        val rig = TestRigs.chainRig()
        val solver = PoseSolver(rig)
        val angles = FloatArray(rig.nodeCount * 3)
        angles[1 * 3 + 2] = 90f // hips rz = 90
        solver.solve(angles, 0f, 0f, 0f)
        val pos = FloatArray(3)
        solver.worldPosition(2, pos)
        // 局部 (0,0.5,0) 绕 Z 转 90° = (-0.5, 0, 0)，再加 hips 平移 (0,1,0)
        assertEquals(-0.5f, pos[0], 1e-4f)
        assertEquals(1.0f, pos[1], 1e-4f)
        assertEquals(0f, pos[2], 1e-4f)
    }

    @Test
    fun `hips offset moves whole body`() {
        val rig = TestRigs.chainRig()
        val solver = PoseSolver(rig)
        solver.solve(FloatArray(rig.nodeCount * 3), 0f, -0.8f, 0.1f)
        val pos = FloatArray(3)
        solver.worldPosition(2, pos)
        assertTrue(abs(pos[1] - 0.7f) < 1e-4f)
        assertTrue(abs(pos[2] - 0.1f) < 1e-4f)
    }

    @Test
    fun `euler rotation order applies z then y then x`() {
        // 90° Z 再 90° X：先绕 Z 把 +Y 转到 -X，再绕 X 旋转不改变 -X 方向
        val q = FloatArray(4)
        QuatMath.fromEulerDegrees(q, 90f, 0f, 90f)
        val out = FloatArray(3)
        QuatMath.rotate(q, 0f, 1f, 0f, out)
        assertTrue(abs(out[0] + 1f) < 1e-3f)
        assertTrue(abs(out[1]) < 1e-3f)
    }

    @Test
    fun `slerp and toEulerDegrees roundtrip preserves rotation`() {
        val a = FloatArray(4)
        val b = FloatArray(4)
        val mid = FloatArray(4)
        val euler = FloatArray(3)
        val qBack = FloatArray(4)
        QuatMath.fromEulerDegrees(a, 0f, 0f, -72f)
        QuatMath.fromEulerDegrees(b, 8f, 16f, 46f)
        QuatMath.slerp(mid, a, b, 0.5f)
        QuatMath.toEulerDegrees(mid, euler)
        QuatMath.fromEulerDegrees(qBack, euler[0], euler[1], euler[2])
        val dot = abs(mid[0] * qBack[0] + mid[1] * qBack[1] + mid[2] * qBack[2] + mid[3] * qBack[3])
        assertTrue("SLERP + 欧拉逆解四元数应一致，实际 dot=$dot", dot > 0.9999f)
    }
}