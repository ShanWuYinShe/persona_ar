package com.persona.ar.character.animation

import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureAnimatorTest {

    private val rig = TestRigs.flatHumanoid()
    private val animator = FigureAnimator(rig)

    private fun nodeOf(bone: HumanoidBone): Int = rig.humanoid[bone]!!

    private fun angle(bone: HumanoidBone, axis: Int): Float {
        val i = nodeOf(bone) * 3 + axis
        return animator.angles[i]
    }

    private fun input(action: ActionType = ActionType.IDLE, elapsed: Float = Float.MAX_VALUE): AnimationInput {
        val i = AnimationInput()
        i.dtSeconds = 1f / 60f
        i.timeSeconds = 1.0f
        i.action = action
        i.actionElapsed = elapsed
        i.emotion = EmotionType.NEUTRAL
        i.expression = "SMILE"
        i.stance = SkeletonStance.STANDING
        return i
    }

    @Test
    fun `idle pose lowers both arms into A pose`() {
        animator.update(input())
        assertTrue(angle(HumanoidBone.UPPER_ARM_L, 2) > 60f)
        assertTrue(angle(HumanoidBone.UPPER_ARM_R, 2) < -60f)
        assertFalse(animator.actionActive)
    }

    @Test
    fun `wave raises right arm above neutral`() {
        // 动作进行到一半（抬手峰值附近）
        animator.update(input(ActionType.WAVE, 1.1f))
        assertTrue("wave 应抬右臂，实际 rz=${angle(HumanoidBone.UPPER_ARM_R, 2)}",
            angle(HumanoidBone.UPPER_ARM_R, 2) > 40f)
        assertTrue(angle(HumanoidBone.UPPER_ARM_L, 2) > 60f)
        assertTrue(animator.actionActive)
    }

    @Test
    fun `nod rotates head forward`() {
        animator.update(input(ActionType.NOD, 0.5f))
        assertTrue("nod 应低头，实际 rx=${angle(HumanoidBone.HEAD, 0)}", angle(HumanoidBone.HEAD, 0) > 3f)
    }

    @Test
    fun `bow bends spine forward and shakes head turns sideways`() {
        animator.update(input(ActionType.BOW, 1.1f))
        assertTrue(angle(HumanoidBone.SPINE, 0) > 3f)
        animator.update(input(ActionType.SHAKE_HEAD, 0.4f))
        assertTrue(kotlin.math.abs(angle(HumanoidBone.HEAD, 1)) > 1f)
    }

    @Test
    fun `jump lifts hips airborne`() {
        var peakAirborne = 0f
        var peakHips = 0f
        val duration = ActionTiming.durationOf(ActionType.JUMP)
        var step = 0
        var elapsed = 0f
        while (elapsed <= duration) {
            animator.update(input(ActionType.JUMP, elapsed))
            if (animator.airborne > peakAirborne) peakAirborne = animator.airborne
            if (animator.hipsOffsetY > peakHips) peakHips = animator.hipsOffsetY
            step++
            elapsed = step * 0.02f
        }
        assertTrue("跳跃应产生腾空，实际 peak=$peakAirborne hips=$peakHips", peakAirborne > 0.3f)
        assertTrue("跳跃最高点骨盆应抬升，实际 $peakHips", peakHips > 0.1f)
    }

    @Test
    fun `sitting stance lowers hips and folds legs`() {
        val i = input()
        i.stance = SkeletonStance.SITTING
        repeat(90) { animator.update(i) }
        assertTrue("坐姿骨盆应下沉，实际 ${animator.hipsOffsetY}", animator.hipsOffsetY < -0.85f)
        assertTrue(angle(HumanoidBone.UPPER_LEG_L, 0) < -70f)
        assertTrue(angle(HumanoidBone.LOWER_LEG_L, 0) > 70f)
    }

    @Test
    fun `action stops contributing after duration`() {
        animator.update(input(ActionType.WAVE, ActionTiming.durationOf(ActionType.WAVE) + 0.2f))
        assertFalse("动作超时后不应继续激活", animator.actionActive)
        assertTrue(angle(HumanoidBone.UPPER_ARM_R, 2) < -60f)
    }

    @Test
    fun `blink happens within several seconds`() {
        val i = input()
        var maxBlink = 0f
        repeat(420) { step ->
            i.timeSeconds = step / 60f
            animator.update(i)
            if (animator.blinkAmount > maxBlink) maxBlink = animator.blinkAmount
        }
        assertTrue("5 秒内至少应眨眼一次，实际最大 $maxBlink", maxBlink > 0.5f)
    }

    @Test
    fun `sleep action closes eyes`() {
        val i = input(ActionType.SLEEP, 0.5f)
        i.emotion = EmotionType.SLEEPY
        animator.update(i)
        assertTrue(animator.eyesClosed > 0.8f)
        assertTrue(angle(HumanoidBone.HEAD, 0) > 10f)
    }

    @Test
    fun `touch reaction produces head tilt and expression`() {
        val i = input()
        i.touchReaction = TouchPart.HEAD
        i.touchElapsed = 1.0f
        animator.update(i)
        assertTrue(angle(HumanoidBone.HEAD, 2) > 2f)
        assertTrue(kotlin.math.abs(angle(HumanoidBone.HEAD, 0)) > 2f)
    }

    @Test
    fun `blush action overrides expression`() {
        animator.update(input(ActionType.BLUSH, 1.2f))
        assertTrue(animator.faceExpression == "BLUSH_FACE")
    }
}