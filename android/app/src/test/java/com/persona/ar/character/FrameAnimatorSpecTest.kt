package com.persona.ar.character

import com.persona.ar.data.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5D 精灵表映射的回归测试。
 *
 * 历史事故：多次捏脸触发 POUT 时没有对应的精灵序列帧，渲染层回退到静态立绘/矢量路径，
 * 角色两侧出现白色羽毛状伪影。这里的断言保证「触摸能触发的动作」始终命中预渲染精灵表，
 * 不会再走到非精灵绘制分支。
 */
class FrameAnimatorSpecTest {

    @Test
    fun `cheek pinch POUT always maps to the shy sprite sheet`() {
        val spec = FrameAnimator.specFor(ActionType.POUT)
        assertNotNull("POUT 缺少精灵表映射，会回退到非精灵路径并复现白色羽毛", spec)
        assertEquals("shy", spec!!.fileName)
        assertEquals(6, spec.frameCount)
    }

    @Test
    fun `all touch-triggered actions have a sprite mapping`() {
        // 与 PersonaViewModel.onTouchPart 的映射保持一致：HEAD/BODY/HAND/CHEEK
        val touchActions = listOf(
            ActionType.WAVE,   // HEAD / HAND
            ActionType.POUT,   // CHEEK
            ActionType.BLUSH   // BODY
        )
        touchActions.forEach { action ->
            assertNotNull("$action 缺少精灵表映射，触摸时会回退到非精灵路径", FrameAnimator.specFor(action))
        }
    }

    @Test
    fun `idle is mapped so the renderer always has a sprite fallback`() {
        // idleClip 非空是 activeClip 非空的前提；两者任一为空才可能走到矢量兜底。
        assertNotNull("IDLE 缺少精灵表映射，渲染层会失去精灵兜底", FrameAnimator.specFor(ActionType.IDLE))
    }

    @Test
    fun `every clip spec is playable`() {
        FrameAnimator.ACTION_CLIP_SPECS.forEach { (action, spec) ->
            assertTrue("$action 帧数必须大于 0", spec.frameCount > 0)
            assertTrue("$action 帧率必须大于 0", spec.fps > 0f)
            assertTrue("$action 精灵表文件名不能为空", spec.fileName.isNotBlank())
        }
    }
}
