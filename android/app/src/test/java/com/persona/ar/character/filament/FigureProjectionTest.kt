package com.persona.ar.character.filament

import com.persona.ar.character.animation.GltfRig
import com.persona.ar.character.animation.HumanoidBone
import com.persona.ar.character.animation.PoseSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FigureProjectionTest {

    private fun buildSyntheticHumanoidRig(): GltfRig {
        // 0: Hips(0, 0.98, 0) -> 1: Chest(0, 0.25, 0) -> 2: Head(0, 0.24, 0)
        val count = 3
        val names = arrayOf("J_Bip_C_Hips", "J_Bip_C_Chest", "J_Bip_C_Head")
        val parent = intArrayOf(-1, 0, 1)
        val order = intArrayOf(0, 1, 2)
        val restT = floatArrayOf(
            0f, 0.98f, 0f,
            0f, 0.25f, 0f,
            0f, 0.24f, 0f
        )
        val restR = floatArrayOf(
            0f, 0f, 0f, 1f,
            0f, 0f, 0f, 1f,
            0f, 0f, 0f, 1f
        )
        val restS = floatArrayOf(
            1f, 1f, 1f,
            1f, 1f, 1f,
            1f, 1f, 1f
        )
        val humanoid = mapOf(
            HumanoidBone.HIPS to 0,
            HumanoidBone.CHEST to 1,
            HumanoidBone.HEAD to 2
        )
        return GltfRig(
            nodeCount = count,
            names = names,
            parent = parent,
            order = order,
            restT = restT,
            restR = restR,
            restS = restS,
            humanoid = humanoid,
            skins = emptyArray(),
            meshNodes = emptyArray(),
            fingerJoints = emptyArray(),
            hairChains = emptyArray()
        )
    }

    @Test
    fun `head and vfx crown project strictly above hips and feet at centered and dragged positions`() {
        val rig = buildSyntheticHumanoidRig()
        val solver = PoseSolver(rig)
        solver.solve(FloatArray(rig.nodeCount * 3), 0f, 0f, 0f)

        val pitch = Math.toRadians(28.0)
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        val camRight = floatArrayOf(1f, 0f, 0f)
        val camUp = floatArrayOf(0f, cp, sp)
        val camForward = floatArrayOf(0f, -sp, -cp)
        val aspect = 1080f / 2340f
        val vFov = 62f

        val assetScale = 0.22f / 1.71f
        val mModel = FloatArray(16)
        val local = FloatArray(3)
        val world = FloatArray(3)
        val footProj = FloatArray(3)
        val hipsProj = FloatArray(3)
        val headProj = FloatArray(3)
        val vfxProj = FloatArray(3)

        // 测试拖拽到画面偏右侧时的 3D -> 屏幕投影（验证不再滞留中央 0.48）
        val footWorld = floatArrayOf(0.16f, -0.18f, -0.68f)
        FigureProjectionMath.buildModelMatrix(
            out = mModel,
            footX = footWorld[0], footY = footWorld[1], footZ = footWorld[2],
            yawDegrees = 0f, uniformScale = assetScale,
            pivotX = 0f, pivotY = 0f, pivotZ = 0f
        )

        assertTrue(
            FigureProjectionMath.projectWorldToNormalized(
                footWorld[0], footWorld[1], footWorld[2],
                0f, 0f, 0f, camRight, camUp, camForward, vFov, aspect, footProj
            )
        )

        solver.worldPosition(rig.humanoid[HumanoidBone.HIPS]!!, local)
        FigureProjectionMath.transformLocalToWorld(mModel, local[0], local[1], local[2], world)
        assertTrue(
            FigureProjectionMath.projectWorldToNormalized(
                world[0], world[1], world[2],
                0f, 0f, 0f, camRight, camUp, camForward, vFov, aspect, hipsProj
            )
        )

        solver.worldPosition(rig.humanoid[HumanoidBone.HEAD]!!, local)
        FigureProjectionMath.transformLocalToWorld(mModel, local[0], local[1], local[2], world)
        assertTrue(
            FigureProjectionMath.projectWorldToNormalized(
                world[0], world[1], world[2],
                0f, 0f, 0f, camRight, camUp, camForward, vFov, aspect, headProj
            )
        )

        FigureProjectionMath.computeHeadVfxLocal(solver.world, rig.humanoid[HumanoidBone.HEAD]!!, 0.12f, local)
        FigureProjectionMath.transformLocalToWorld(mModel, local[0], local[1], local[2], world)
        assertTrue(
            FigureProjectionMath.projectWorldToNormalized(
                world[0], world[1], world[2],
                0f, 0f, 0f, camRight, camUp, camForward, vFov, aspect, vfxProj
            )
        )

        // 屏幕 Y 向下为正：头顶 VFX < HEAD 关节 < HIPS 骨盆 < 脚底锚点
        assertTrue("VFX Y (${vfxProj[1]}) 必须在 HEAD (${headProj[1]}) 上方", vfxProj[1] < headProj[1])
        assertTrue("HEAD Y (${headProj[1]}) 必须在 HIPS (${hipsProj[1]}) 上方", headProj[1] < hipsProj[1])
        assertTrue("HIPS Y (${hipsProj[1]}) 必须在脚底 (${footProj[1]}) 上方", hipsProj[1] < footProj[1])
        // 横向坐标必须跟随模型停在右侧 (> 0.65)，并与头部实际透视 X 保持一致（不能滞留中央 0.48）
        assertEquals(headProj[0], vfxProj[0], 0.015f)
        assertTrue("向右拖拽后头顶 VFX X 应显著大于 0.65，实际 ${vfxProj[0]}", vfxProj[0] > 0.65f)
        assertTrue("向右拖拽后脚底 X 应显著大于 0.65，实际 ${footProj[0]}", footProj[0] > 0.65f)
    }

    @Test
    fun `head crown vfx tracks sitting crouch and tilted bow`() {
        val rig = buildSyntheticHumanoidRig()
        val solver = PoseSolver(rig)
        val angles = FloatArray(rig.nodeCount * 3)
        // 站立基线
        solver.solve(angles, 0f, 0f, 0f)
        val standVfx = FloatArray(3)
        FigureProjectionMath.computeHeadVfxLocal(solver.world, 2, 0.12f, standVfx)

        // 坐姿降低骨盆 + 头部前倾 35°
        angles[2 * 3] = 35f
        solver.solve(angles, 0f, -0.42f, 0f)
        val sitVfx = FloatArray(3)
        FigureProjectionMath.computeHeadVfxLocal(solver.world, 2, 0.12f, sitVfx)

        assertTrue("坐姿头顶高度应明显低于站姿: sit=${sitVfx[1]}, stand=${standVfx[1]}", sitVfx[1] < standVfx[1] - 0.35f)
    }
}
