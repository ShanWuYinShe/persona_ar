package com.persona.ar.character.animation

import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        // 在 -Z 为正面的右手系中，前倾低头对应 rx < 0
        assertTrue("nod 应前倾低头，实际 rx=${angle(HumanoidBone.HEAD, 0)}", angle(HumanoidBone.HEAD, 0) < -3f)
    }

    @Test
    fun `bow bends spine forward and shakes head turns sideways`() {
        animator.update(input(ActionType.BOW, 1.1f))
        assertTrue("bow 脊柱应前弯，实际 rx=${angle(HumanoidBone.SPINE, 0)}", angle(HumanoidBone.SPINE, 0) < -3f)
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
    fun `sitting stance lowers hips and folds legs forward above ground`() {
        val i = input()
        i.stance = SkeletonStance.SITTING
        repeat(90) { animator.update(i) }
        assertTrue("坐姿骨盆应下沉至桌面比例，实际 ${animator.hipsOffsetY}", animator.hipsOffsetY in -0.45f..-0.35f)
        assertTrue("坐姿大腿应向前抬平 (rx > 0)，实际 ${angle(HumanoidBone.UPPER_LEG_L, 0)}", angle(HumanoidBone.UPPER_LEG_L, 0) >= 70f)
        assertTrue("坐姿小腿应向下垂直 (rx < 0)，实际 rx=${angle(HumanoidBone.LOWER_LEG_L, 0)}", angle(HumanoidBone.LOWER_LEG_L, 0) in -100f..-70f)
        assertTrue("坐姿前臂应向前弯搭膝头 (ry < 0)，实际 ry=${angle(HumanoidBone.LOWER_ARM_L, 1)}", angle(HumanoidBone.LOWER_ARM_L, 1) < -30f)
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
        // 打瞌睡前垂低头 (rx < 0)
        assertTrue("打瞌睡应向前垂头，实际 rx=${angle(HumanoidBone.HEAD, 0)}", angle(HumanoidBone.HEAD, 0) < -10f)
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
    fun `touch reaction does not double stack arm angles when action is simultaneously active`() {
        val i = input(ActionType.BLUSH, 1.2f)
        i.touchReaction = TouchPart.BODY
        i.touchElapsed = 1.0f
        animator.update(i)
        // 肘关节弯曲角不能超过解剖学安全上限 145°（左臂弯向 -Z 为负，右臂为正）
        val elbowBendL = angle(HumanoidBone.LOWER_ARM_L, 1)
        val elbowBendR = angle(HumanoidBone.LOWER_ARM_R, 1)
        assertTrue("左肘关节弯曲不应过屈变形: $elbowBendL", elbowBendL > -145f)
        assertTrue("右肘关节弯曲不应过屈变形: $elbowBendR", elbowBendR < 145f)
    }

    @Test
    fun `blush action overrides expression`() {
        animator.update(input(ActionType.BLUSH, 1.2f))
        assertTrue(animator.faceExpression == "BLUSH_FACE")
    }

    /** 用带左右手臂链的缩样骨架跑一次动作中段，返回双手与头的模型空间位置。 */
    private fun handWorld(action: ActionType): Triple<FloatArray, FloatArray, FloatArray> {
        val rig = TestRigs.armRig()
        val animator = FigureAnimator(rig)
        val solver = PoseSolver(rig)
        val i = AnimationInput()
        i.dtSeconds = 1f / 60f
        i.timeSeconds = 1.0f
        i.action = action
        i.actionElapsed = ActionTiming.durationOf(action) * 0.45f
        i.emotion = EmotionType.NEUTRAL
        i.expression = "SMILE"
        animator.update(i)
        solver.solve(animator.angles, 0f, 0f, 0f)
        val left = FloatArray(3)
        val right = FloatArray(3)
        val head = FloatArray(3)
        solver.worldPosition(rig.humanoid[HumanoidBone.HAND_L]!!, left)
        solver.worldPosition(rig.humanoid[HumanoidBone.HAND_R]!!, right)
        solver.worldPosition(rig.humanoid[HumanoidBone.HEAD]!!, head)
        return Triple(left, right, head)
    }

    @Test
    fun `blush raises both hands symmetrically in front of cheeks`() {
        val (left, right, head) = handWorld(ActionType.BLUSH)
        assertEquals("双手应左右镜像", -left[0], right[0], 0.02f)
        assertEquals("双手高度应一致", left[1], right[1], 0.02f)
        assertEquals("双手前后应一致", left[2], right[2], 0.02f)
        assertTrue(
            "双手都应抬到脸颊前方 (-Z)，实际 L=(${left[0]},${left[1]},${left[2]}) head.y=${head[1]}",
            abs(left[1] - head[1]) < 0.15f && left[2] < -0.08f && right[2] < -0.08f
        )
    }

    @Test
    fun `smug puts both hands on hips symmetrically`() {
        val (left, right, head) = handWorld(ActionType.SMUG)
        assertEquals("双手应左右镜像", -left[0], right[0], 0.02f)
        assertEquals("双手高度应一致", left[1], right[1], 0.02f)
        assertEquals("双手前后应一致", left[2], right[2], 0.02f)
        assertTrue(
            "双手都应落在腰胯高度，实际 L.y=${left[1]} R.y=${right[1]} head.y=${head[1]}",
            head[1] - left[1] > 0.3f && head[1] - right[1] > 0.3f
        )
    }

    @Test
    fun `dynamic gaze tracking rotates head and neck towards camera direction`() {
        val iLeft = input()
        iLeft.gazeX = 0.8f
        iLeft.gazeY = 0.5f
        animator.update(iLeft)
        val headYawLeft = angle(HumanoidBone.HEAD, 1)
        val neckYawLeft = angle(HumanoidBone.NECK, 1)
        val headPitchUp = angle(HumanoidBone.HEAD, 0)
        assertTrue("gazeX > 0 应当向侧边偏航注视，实际 headYaw=$headYawLeft", headYawLeft > 12f)
        assertTrue("gazeX > 0 颈部协同转动，实际 neckYaw=$neckYawLeft", neckYawLeft > 5f)
        assertTrue("gazeY > 0 镜头在上方时头部向上抬仰 (rx > 0)，实际 headPitch=$headPitchUp", headPitchUp > 0f)

        val iRight = input()
        iRight.gazeX = -0.8f
        animator.update(iRight)
        val headYawRight = angle(HumanoidBone.HEAD, 1)
        assertTrue("gazeX < 0 应当向另一侧偏航，实际 headYaw=$headYawRight", headYawRight < -12f)
    }
}
