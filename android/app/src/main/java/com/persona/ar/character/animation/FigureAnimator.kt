package com.persona.ar.character.animation

import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 动作时长定义（秒）。0 表示「保持型」动作（SLEEP / SIT / IDLE），不自动结束。 */
object ActionTiming {
    fun durationOf(action: ActionType): Float = when (action) {
        ActionType.IDLE -> 0f
        ActionType.WAVE -> 2.6f
        ActionType.NOD -> 1.65f
        ActionType.SHAKE_HEAD -> 1.75f
        ActionType.BOW -> 2.25f
        ActionType.JUMP -> 1.75f
        ActionType.POUT -> 2.4f
        ActionType.BLUSH -> 2.8f
        ActionType.YAWN -> 2.8f
        ActionType.SLEEP -> 0f
        ActionType.SMUG -> 2.8f
        ActionType.SIT -> 0f
    }
}

/** 每帧动画输入（由 FigureScene 从 FigureFrameState 填充，避免动画层依赖渲染层类型） */
class AnimationInput {
    var timeSeconds: Float = 0f
    var dtSeconds: Float = 0f
    var action: ActionType = ActionType.IDLE
    /** 当前动作已播放秒数；动作未激活时为无穷大 */
    var actionElapsed: Float = Float.MAX_VALUE
    var emotion: EmotionType = EmotionType.NEUTRAL
    var expression: String = "SMILE"
    /** 说话音量包络 0..1（真实音频振幅，驱动口型与头部微动） */
    var lipSync: Float = 0f
    var speaking: Boolean = false
    var stance: SkeletonStance = SkeletonStance.STANDING
    var gazeX: Float = 0f
    var gazeY: Float = 0f
    var inertialSway: Float = 0f
    var touchReaction: TouchPart? = null
    var touchElapsed: Float = Float.MAX_VALUE
}

/**
 * 3D 手办全身动画器：
 * 1. 先构建基础姿态层（站立/坐姿 + 待机呼吸/注视 + 情绪体态）；
 * 2. 对激活的动作或触摸反应写入「绝对目标骨骼姿态」并沿四元数最短球面弧 (SLERP) 平滑混合，
 *    避免线性欧拉角累加在中途穿越躯干/背部；
 * 3. 在腿部屈伸解算完成后执行裙摆链反向物理补偿，消除坐姿/跳跃/害羞时的裙摆穿模；
 * 4. 驱动 VRM 面部表情预设、自然眨眼与真实音频口型。
 *
 * 角度约定（每根骨骼的静止局部坐标系，单位度，组合顺序 先 Z 再 Y 最后 X）：
 * - 躯干/头部：rx > 0 后仰 / rx < 0 前倾；ry 左右转体；rz 左右侧倾；
 * - 手臂（T-pose 左腕沿 -X、右腕沿 +X）：左臂 rz > 0 垂落 / rz < 0 上举，ry < 0 前摆；右臂镜像反号；
 * - 腿（沿 -Y）：rx > 0 大腿前抬 / rx < 0 小腿后屈。
 */
class FigureAnimator(private val rig: GltfRig) {

    companion object {
        private const val BREATH_PERIOD = 3.8f
        private const val BLEND_RATE = 7.5f
        private const val SIT_HIPS_DOWN = 0.40f
        private const val SIT_HIPS_FORWARD = 0.02f
        private const val SIT_THIGH_FLEX = 80f
        private const val TOUCH_DURATION = 2.6f
    }

    /** 逐节点最终欧拉角（度），3 个 float / 节点；直接供 PoseSolver 解算 */
    val angles = FloatArray(rig.nodeCount * 3)
    var hipsOffsetX = 0f; private set
    var hipsOffsetY = 0f; private set
    var hipsOffsetZ = 0f; private set

    // ---- 面部输出 ----
    var faceExpression: String = "SMILE"; private set
    var faceEmotion: EmotionType = EmotionType.NEUTRAL; private set
    var blinkAmount: Float = 0f; private set
    var mouthOpen: Float = 0f; private set
    var eyesClosed: Float = 0f; private set

    // ---- 渲染辅助输出 ----
    /** 跳跃腾空系数 0..1：越大接触阴影越淡 */
    var airborne: Float = 0f; private set
    /** 当前动作进度 0..1 */
    var actionProgress: Float = 0f; private set
    var actionActive: Boolean = false; private set

    // ---- 内部工作缓冲（零每帧堆分配）----
    private val actionTargets = FloatArray(rig.nodeCount * 3)
    private val actionHasBone = BooleanArray(rig.nodeCount)
    private val qBase = FloatArray(4)
    private val qTarget = FloatArray(4)
    private val qBlend = FloatArray(4)
    private val eulerTmp = FloatArray(3)

    private var sitBlend = 0f
    val currentSitBlend: Float get() = sitBlend
    private var smoothMouth = 0f
    private var blinkCountdown = 2.2f
    private var blinkClock = -1f
    private var hairPhase = 0f

    private val nodeByBone: Map<HumanoidBone, Int> = rig.humanoid

    fun update(input: AnimationInput) {
        val dt = input.dtSeconds.coerceIn(0f, 0.12f)
        val t = input.timeSeconds
        java.util.Arrays.fill(angles, 0f)
        java.util.Arrays.fill(actionTargets, 0f)
        java.util.Arrays.fill(actionHasBone, false)
        hipsOffsetX = 0f; hipsOffsetY = 0f; hipsOffsetZ = 0f
        airborne = 0f

        // 1) 体态层：站立 ↔ 桌面边缘垂腿坐姿
        val sitTarget = if (input.stance == SkeletonStance.SITTING) 1f else 0f
        sitBlend += (sitTarget - sitBlend) * min(1f, dt * BLEND_RATE * 0.55f)

        // 计算头部主导权：强表情动作或触摸期间减弱待机镜头追焦，防止眼神偏移打架
        val headDominance = computeHeadOverrideWeight(input)

        // 2) 待机呼吸 / 重心 / 镜头注视
        applyIdle(t, input, gazeWeight = 1f - headDominance * 0.85f)
        // 3) 情绪微姿态
        applyEmotion(input.emotion)
        // 4) 坐姿基底
        val hasTouchReaction = input.touchReaction != null && input.touchElapsed in 0f..TOUCH_DURATION
        if (sitBlend > 0.001f) applySitting(t, sitBlend)

        // 5) 动作或触摸绝对目标骨骼姿态 → 四元数 SLERP 平滑混合（杜绝欧拉穿模）
        if (hasTouchReaction) {
            actionActive = false
            actionProgress = 0f
            applyTouchReaction(input)
        } else {
            actionActive = applyAction(input, t)
        }

        // 6) 裙摆链物理补偿（跟随最终大腿/躯干屈伸防穿模）
        applySkirtPhysics(t, input)

        // 7) 面部表情 / 眨眼 / 口型
        updateFace(dt, input)

        // 8) 手指弯曲与头发次级惯性
        applyFingerCurl()
        applyHairSecondary(dt, t, input)
    }

    // ============================================================ 基础工具

    private fun add(bone: HumanoidBone, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) {
        val node = nodeByBone[bone] ?: return
        val i = node * 3
        angles[i] += rx; angles[i + 1] += ry; angles[i + 2] += rz
    }

    private fun addNode(node: Int, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) {
        val i = node * 3
        angles[i] += rx; angles[i + 1] += ry; angles[i + 2] += rz
    }

    private fun angleOf(bone: HumanoidBone, axis: Int): Float {
        val node = nodeByBone[bone] ?: return 0f
        return angles[node * 3 + axis]
    }

    private fun setTarget(bone: HumanoidBone, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) {
        val node = nodeByBone[bone] ?: return
        val i = node * 3
        actionTargets[i] = rx
        actionTargets[i + 1] = ry
        actionTargets[i + 2] = rz
        actionHasBone[node] = true
    }

    /** 将写入到 actionTargets 的姿态沿着四元数最短球面路径插值合并到基础姿态 angles。 */
    private fun blendTargetsSlerp(weight: Float) {
        val w = weight.coerceIn(0f, 1f)
        if (w <= 0f) return
        for (node in 0 until rig.nodeCount) {
            if (!actionHasBone[node]) continue
            val i3 = node * 3
            if (w >= 0.9999f) {
                angles[i3] = actionTargets[i3]
                angles[i3 + 1] = actionTargets[i3 + 1]
                angles[i3 + 2] = actionTargets[i3 + 2]
            } else {
                QuatMath.fromEulerDegrees(qBase, angles[i3], angles[i3 + 1], angles[i3 + 2])
                QuatMath.fromEulerDegrees(qTarget, actionTargets[i3], actionTargets[i3 + 1], actionTargets[i3 + 2])
                QuatMath.slerp(qBlend, qBase, qTarget, w)
                QuatMath.toEulerDegrees(qBlend, eulerTmp, 0)
                angles[i3] = eulerTmp[0]
                angles[i3 + 1] = eulerTmp[1]
                angles[i3 + 2] = eulerTmp[2]
            }
        }
    }

    private fun computeHeadOverrideWeight(input: AnimationInput): Float {
        if (input.touchReaction != null && input.touchElapsed in 0f..TOUCH_DURATION) {
            val p = (input.touchElapsed / TOUCH_DURATION).coerceIn(0f, 1f)
            return envelope(p, 0.12f, 0.85f)
        }
        val action = input.action
        if (action == ActionType.IDLE || action == ActionType.SIT) return 0f
        if (action == ActionType.SLEEP) return 1f
        val duration = ActionTiming.durationOf(action)
        if (duration <= 0f || input.actionElapsed < 0f || input.actionElapsed > duration + 0.05f) return 0f
        val p = (input.actionElapsed / duration).coerceIn(0f, 1f)
        return envelope(p, 0.14f, 0.86f)
    }

    // ============================================================ 待机与情绪

    private fun applyIdle(t: Float, input: AnimationInput, gazeWeight: Float) {
        // 自然垂臂：左臂 rz > 0 / 右臂 rz < 0 垂向地面
        add(HumanoidBone.UPPER_ARM_L, rz = 72f)
        add(HumanoidBone.UPPER_ARM_R, rz = -72f)
        add(HumanoidBone.LOWER_ARM_L, ry = -14f)
        add(HumanoidBone.LOWER_ARM_R, ry = 14f)

        val breath = sin(t * 2f * PI.toFloat() / BREATH_PERIOD)
        val breath2 = sin(t * 2f * PI.toFloat() / BREATH_PERIOD + 0.45f)
        add(HumanoidBone.CHEST, rx = breath * 0.95f, rz = breath * 0.22f)
        add(HumanoidBone.SPINE, rx = breath2 * 0.40f)
        add(HumanoidBone.UPPER_CHEST, rx = breath * 0.30f)

        val weightPhase = t * 2f * PI.toFloat() / 6.8f
        val swayHips = sin(weightPhase) * 1.2f
        add(HumanoidBone.HIPS, rz = swayHips, ry = sin(weightPhase * 0.7f) * 0.8f)
        add(HumanoidBone.SPINE, rz = -swayHips * 0.45f)

        val microGazeYaw = sin(t * 0.42f) * 1.8f + sin(t * 0.85f) * 0.7f
        val targetGazeYaw = input.gazeX.coerceIn(-1f, 1f) * 20.0f * gazeWeight
        val targetNeckYaw = input.gazeX.coerceIn(-1f, 1f) * 9.0f * gazeWeight
        val targetGazePitch = input.gazeY.coerceIn(-1f, 1f) * 14.0f * gazeWeight
        val targetNeckPitch = input.gazeY.coerceIn(-1f, 1f) * 6.0f * gazeWeight

        val headYaw = microGazeYaw * (0.3f + 0.7f * gazeWeight) + targetGazeYaw
        val neckYaw = (microGazeYaw * 0.25f) * gazeWeight + targetNeckYaw
        val headPitch = sin(t * 0.55f) * 1.1f * (0.4f + 0.6f * gazeWeight) + targetGazePitch - smoothMouth * 2.4f
        val neckPitch = targetNeckPitch + sin(t * 0.55f) * 0.4f * gazeWeight
        val headRoll = sin(t * 0.31f) * 1.1f * gazeWeight - (input.gazeX.coerceIn(-1f, 1f) * 2.5f * gazeWeight)

        add(HumanoidBone.HEAD, rx = headPitch, ry = headYaw, rz = headRoll)
        add(HumanoidBone.NECK, rx = neckPitch, ry = neckYaw)

        add(HumanoidBone.UPPER_ARM_L, rz = sin(t * 0.41f) * 1.4f, ry = -breath * 0.6f)
        add(HumanoidBone.UPPER_ARM_R, rz = -sin(t * 0.37f + 0.8f) * 1.4f, ry = breath2 * 0.6f)
        add(HumanoidBone.LOWER_ARM_L, ry = -sin(t * 0.33f + 0.4f) * 1.6f)
        add(HumanoidBone.LOWER_ARM_R, ry = sin(t * 0.31f + 1.1f) * 1.6f)

        val sway = input.inertialSway.coerceIn(-18f, 18f)
        if (abs(sway) > 0.05f) {
            add(HumanoidBone.HIPS, rz = sway * 0.35f)
            add(HumanoidBone.SPINE, rz = sway * 0.25f)
            add(HumanoidBone.CHEST, rz = sway * 0.18f)
            add(HumanoidBone.HEAD, rz = -sway * 0.12f)
        }
    }

    private fun applyEmotion(emotion: EmotionType) {
        when (emotion) {
            EmotionType.JOY -> {
                add(HumanoidBone.CHEST, rx = 1.6f)
                add(HumanoidBone.HEAD, rx = 1.6f, rz = 1.4f)
            }
            EmotionType.SHY -> {
                add(HumanoidBone.HEAD, rx = -4.5f, ry = -3.5f, rz = 2.5f)
                add(HumanoidBone.CHEST, rx = -1.5f)
                add(HumanoidBone.UPPER_ARM_L, ry = -4f)
                add(HumanoidBone.UPPER_ARM_R, ry = 4f)
                add(HumanoidBone.LOWER_ARM_L, ry = -10f)
                add(HumanoidBone.LOWER_ARM_R, ry = 10f)
            }
            EmotionType.SAD -> {
                add(HumanoidBone.HEAD, rx = -6.5f, rz = -2.0f)
                add(HumanoidBone.CHEST, rx = -4.0f)
                add(HumanoidBone.UPPER_ARM_L, rz = 3f)
                add(HumanoidBone.UPPER_ARM_R, rz = -3f)
            }
            EmotionType.SURPRISED -> {
                add(HumanoidBone.HEAD, rx = 4.0f)
                add(HumanoidBone.CHEST, rx = 1.6f)
                add(HumanoidBone.UPPER_ARM_L, rz = -3f)
                add(HumanoidBone.UPPER_ARM_R, rz = 3f)
            }
            EmotionType.THINKING -> {
                add(HumanoidBone.HEAD, rx = 2.2f, ry = 5.5f, rz = 4.0f)
                add(HumanoidBone.NECK, ry = 2.0f)
            }
            EmotionType.ANGRY -> {
                add(HumanoidBone.HEAD, rx = -3.2f)
                add(HumanoidBone.CHEST, rx = 2.0f)
            }
            EmotionType.SLEEPY -> {
                add(HumanoidBone.HEAD, rx = -7.0f, rz = 3.0f)
                add(HumanoidBone.NECK, rx = -4.0f)
                add(HumanoidBone.CHEST, rx = -2.0f)
            }
            EmotionType.CONFIDENT -> {
                add(HumanoidBone.CHEST, rx = 2.8f)
                add(HumanoidBone.HEAD, rx = 2.5f, rz = -1.8f)
            }
            EmotionType.NEUTRAL -> Unit
        }
    }

    // ============================================================ 坐姿层

    /**
     * 优雅自然的桌沿斜坐姿：
     * - 骨盆微转 10° 并小幅度错开左右小腿/脚尖，在正前方镜头下也能清晰呈「曲腿坐在桌沿」轮廓而非正视角短腿感；
     * - 双手自然扶于大腿外侧/膝侧，避免穿透腿部网格；
     * - 垂落的双腿附带轻柔闲适的脚尖晃动。
     */
    private fun applySitting(t: Float, k: Float) {
        val legScale = k * (1f - airborne * 0.85f).coerceIn(0.15f, 1f)
        hipsOffsetY -= SIT_HIPS_DOWN * legScale
        hipsOffsetZ += SIT_HIPS_FORWARD * legScale

        setTarget(HumanoidBone.HIPS, rx = 5f, ry = 10f, rz = -2f)
        setTarget(HumanoidBone.SPINE, rx = -6f, ry = -2f, rz = 0f)
        setTarget(HumanoidBone.CHEST, rx = 4f, ry = -4f, rz = 0f)
        setTarget(HumanoidBone.NECK, rx = 0f, ry = -2f, rz = -2f)
        setTarget(HumanoidBone.HEAD, rx = -2f, ry = -4f, rz = -4f)

        val kickL = sin(t * 2.1f) * 3.2f
        val kickR = sin(t * 2.1f + 1.1f) * 3.2f
        setTarget(HumanoidBone.UPPER_LEG_L, rx = SIT_THIGH_FLEX * (legScale / k.coerceAtLeast(0.01f)), ry = 3f, rz = 2f)
        setTarget(HumanoidBone.UPPER_LEG_R, rx = SIT_THIGH_FLEX * (legScale / k.coerceAtLeast(0.01f)), ry = -2f, rz = -2f)
        setTarget(HumanoidBone.LOWER_LEG_L, rx = -78f + kickL, ry = 0f, rz = -2f)
        setTarget(HumanoidBone.LOWER_LEG_R, rx = -88f + kickR, ry = 0f, rz = 2f)
        setTarget(HumanoidBone.FOOT_L, rx = -14f + kickL * 0.5f, ry = 0f, rz = 0f)
        setTarget(HumanoidBone.FOOT_R, rx = -16f + kickR * 0.5f, ry = 0f, rz = 0f)

        // 双手轻扶大腿两侧（绝对姿态 rz = ±70, ry = ∓36, 肘 -32/32，清晰落在膝上且不穿大腿）
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 4f, ry = -36f, rz = 70f)
        setTarget(HumanoidBone.LOWER_ARM_L, rx = 0f, ry = -32f, rz = 0f)
        setTarget(HumanoidBone.HAND_L, rx = 0f, ry = -8f, rz = 6f)

        setTarget(HumanoidBone.UPPER_ARM_R, rx = 4f, ry = 36f, rz = -70f)
        setTarget(HumanoidBone.LOWER_ARM_R, rx = 0f, ry = 32f, rz = 0f)
        setTarget(HumanoidBone.HAND_R, rx = 0f, ry = 8f, rz = -6f)

        blendTargetsSlerp(k)
        java.util.Arrays.fill(actionHasBone, false)
    }

    // ============================================================ 动作层

    private fun applyAction(input: AnimationInput, t: Float): Boolean {
        val action = input.action
        if (action == ActionType.IDLE || action == ActionType.SIT) {
            actionProgress = if (action == ActionType.SIT) 1f else 0f
            return action == ActionType.SIT
        }
        val duration = ActionTiming.durationOf(action)
        val elapsed = input.actionElapsed
        if (elapsed < 0f) { actionProgress = 0f; return false }
        val hold = duration <= 0f
        val p = if (hold) 0f else (elapsed / duration).coerceIn(0f, 1f)
        actionProgress = if (hold) 1f else p
        if (!hold && elapsed > duration + 0.05f) return false

        when (action) {
            ActionType.WAVE -> applyWave(p)
            ActionType.NOD -> applyNod(p)
            ActionType.SHAKE_HEAD -> applyShakeHead(p)
            ActionType.BOW -> applyBow(p)
            ActionType.JUMP -> applyJump(p)
            ActionType.POUT -> applyPout(p)
            ActionType.BLUSH -> applyBlush(p)
            ActionType.YAWN -> applyYawn(p)
            ActionType.SLEEP -> applySleep(t)
            ActionType.SMUG -> applySmug(p)
            ActionType.SIT, ActionType.IDLE -> Unit
        }
        return true
    }

    /** 挥手：右肩外展至头部右上方（绝不贴耳遮脸），前臂向上欢快挥摆 */
    private fun applyWave(p: Float) {
        val w = envelope(p, 0.16f, 0.84f)
        if (w <= 0f) return
        val wave = sin(p * 2f * PI.toFloat() * 3.0f)
        setTarget(HumanoidBone.HIPS, rz = 3f)
        setTarget(HumanoidBone.SPINE, rx = -2f, ry = 2f, rz = -4f)
        setTarget(HumanoidBone.CHEST, rx = 1f, ry = 2f, rz = -3f)
        setTarget(HumanoidBone.NECK, rx = 2f, ry = 2f, rz = 4f)
        setTarget(HumanoidBone.HEAD, rx = 4f, ry = 6f, rz = 8f)
        setTarget(HumanoidBone.SHOULDER_R, rz = 8f)
        // 右上臂抬起至右上方 (rz = +46°)，手肘在头右侧 (+0.27, 1.51) 完全脱离发丝
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 8f, ry = 16f, rz = 46f)
        setTarget(HumanoidBone.LOWER_ARM_R, rx = 0f, ry = 20f, rz = 52f + wave * 18f)
        setTarget(HumanoidBone.HAND_R, ry = -wave * 10f, rz = wave * 20f)
        // 左臂轻扶身侧
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 4f, ry = -14f, rz = 78f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -24f)
        blendTargetsSlerp(w)
    }

    /** 点头：爽朗二连点头 + 轻微肩胸伴随 */
    private fun applyNod(p: Float) {
        val w = bell(p)
        if (w <= 0f) return
        // 前半程与后半程各点一次，在 p≈0.30（即 0.5s）处于清晰低头峰值
        val nodWave = sin(p * 2f * PI.toFloat() * 2.0f - PI.toFloat() * 0.15f)
        val dip = (-15f - 10f * nodWave).coerceAtMost(-4f)
        setTarget(HumanoidBone.HEAD, rx = dip, rz = sin(p * PI.toFloat()) * 3f)
        setTarget(HumanoidBone.NECK, rx = dip * 0.45f)
        setTarget(HumanoidBone.CHEST, rx = -3.5f)
        setTarget(HumanoidBone.SHOULDER_L, rz = -4f)
        setTarget(HumanoidBone.SHOULDER_R, rz = 4f)
        if (sitBlend < 0.35f) {
            setTarget(HumanoidBone.UPPER_ARM_L, ry = -12f, rz = 68f)
            setTarget(HumanoidBone.UPPER_ARM_R, ry = 12f, rz = -68f)
            setTarget(HumanoidBone.LOWER_ARM_L, ry = -26f)
            setTarget(HumanoidBone.LOWER_ARM_R, ry = 26f)
        }
        blendTargetsSlerp(w)
    }

    /** 摇头：清晰左右摆头 + 胸前双手分离轻摆否认（手掌留安全间距不互插） */
    private fun applyShakeHead(p: Float) {
        val w = envelope(p, 0.14f, 0.86f)
        if (w <= 0f) return
        val shake = sin(p * 2f * PI.toFloat() * 2.5f)
        setTarget(HumanoidBone.HEAD, rx = -3f, ry = shake * 26f, rz = -shake * 6f)
        setTarget(HumanoidBone.NECK, ry = shake * 11f)
        setTarget(HumanoidBone.CHEST, ry = shake * 4f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 20f, ry = -34f, rz = 54f)
        setTarget(HumanoidBone.LOWER_ARM_L, rx = -10f, ry = -64f + shake * 14f, rz = 14f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 20f, ry = 34f, rz = -54f)
        setTarget(HumanoidBone.LOWER_ARM_R, rx = -10f, ry = 64f + shake * 14f, rz = -14f)
        blendTargetsSlerp(w)
    }

    /** 鞠躬：脊柱前躬、骨盆微后移保重心；坐姿自动收敛倾角与手部高度防穿大腿 */
    private fun applyBow(p: Float) {
        val w = envelope(p, 0.22f, 0.78f)
        if (w <= 0f) return
        val sit = sitBlend.coerceIn(0f, 1f)
        hipsOffsetZ += 0.035f * w * (1f - sit)
        setTarget(HumanoidBone.SPINE, rx = -22f + 6f * sit)
        setTarget(HumanoidBone.CHEST, rx = -18f + 5f * sit)
        setTarget(HumanoidBone.NECK, rx = -10f)
        setTarget(HumanoidBone.HEAD, rx = -8f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 14f - 6f * sit, ry = -36f - 10f * sit, rz = 74f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -24f - 12f * sit)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 14f - 6f * sit, ry = 36f + 10f * sit, rz = -74f)
        setTarget(HumanoidBone.LOWER_ARM_R, ry = 24f + 12f * sit)
        if (sit < 0.35f) {
            setTarget(HumanoidBone.UPPER_LEG_L, rx = 4f)
            setTarget(HumanoidBone.UPPER_LEG_R, rx = 4f)
        }
        blendTargetsSlerp(w)
    }

    /** 跳跃：清晰蓄力下蹲 → 高跃展臂欢呼 → 缓冲落地（四元数 SLERP 同步双臂） */
    private fun applyJump(p: Float) {
        val hipsY: Float
        val legBend: Float
        val armCheer: Float
        when {
            p < 0.18f -> {
                val k = smoothIn(p / 0.18f)
                hipsY = -0.11f * k; legBend = k; armCheer = 0f
                setTarget(HumanoidBone.UPPER_ARM_L, rx = -12f, ry = 16f, rz = 74f)
                setTarget(HumanoidBone.UPPER_ARM_R, rx = -12f, ry = -16f, rz = -74f)
                blendTargetsSlerp(k)
            }
            p < 0.52f -> {
                val k = smoothIn((p - 0.18f) / 0.34f)
                hipsY = -0.11f + 0.44f * k
                legBend = 1f - k * 0.65f
                armCheer = k
            }
            p < 0.72f -> {
                val k = (p - 0.52f) / 0.20f
                hipsY = 0.33f * (1f - k * k)
                legBend = 0.35f + 0.55f * k
                armCheer = 1f - smoothIn(k)
            }
            p < 0.86f -> {
                val k = smoothIn((p - 0.72f) / 0.14f)
                hipsY = -0.08f * k; legBend = 0.90f * (1f - k) + 0.75f * k; armCheer = 0f
            }
            else -> {
                val k = 1f - smoothIn((p - 0.86f) / 0.14f)
                hipsY = -0.08f * k; legBend = k * 0.75f; armCheer = 0f
            }
        }
        val standOnly = (1f - sitBlend).coerceIn(0f, 1f)
        hipsOffsetY += hipsY
        airborne = (hipsY / 0.33f).coerceIn(0f, 1f)
        if (standOnly > 0.2f && legBend > 0.001f) {
            add(HumanoidBone.UPPER_LEG_L, rx = 34f * legBend * standOnly, rz = -3f * legBend * standOnly)
            add(HumanoidBone.UPPER_LEG_R, rx = 34f * legBend * standOnly, rz = 3f * legBend * standOnly)
            add(HumanoidBone.LOWER_LEG_L, rx = -54f * legBend * standOnly)
            add(HumanoidBone.LOWER_LEG_R, rx = -54f * legBend * standOnly)
            add(HumanoidBone.FOOT_L, rx = 18f * legBend * standOnly)
            add(HumanoidBone.FOOT_R, rx = 18f * legBend * standOnly)
        }
        if (armCheer > 0.001f) {
            setTarget(HumanoidBone.SPINE, rx = 5f)
            setTarget(HumanoidBone.CHEST, rx = 6f)
            setTarget(HumanoidBone.HEAD, rx = 8f)
            setTarget(HumanoidBone.SHOULDER_L, rz = -10f)
            setTarget(HumanoidBone.SHOULDER_R, rz = 10f)
            setTarget(HumanoidBone.UPPER_ARM_L, rx = 10f, ry = -16f, rz = -42f)
            setTarget(HumanoidBone.LOWER_ARM_L, rz = -22f, ry = -10f)
            setTarget(HumanoidBone.UPPER_ARM_R, rx = 10f, ry = 16f, rz = 42f)
            setTarget(HumanoidBone.LOWER_ARM_R, rz = 22f, ry = 10f)
            blendTargetsSlerp(armCheer)
        }
    }

    /** 傲娇抱臂撇嘴：转身偏头歪睨 + 双臂于胸前干净交叉（SLERP 过胸不刮背） */
    private fun applyPout(p: Float) {
        val w = envelope(p, 0.16f, 0.84f)
        if (w <= 0f) return
        setTarget(HumanoidBone.HIPS, ry = 14f, rz = -3f)
        setTarget(HumanoidBone.SPINE, rx = 3f, ry = 8f, rz = 2f)
        setTarget(HumanoidBone.CHEST, rx = 4f, ry = 6f)
        setTarget(HumanoidBone.NECK, rx = 3f, ry = -10f, rz = -3f)
        setTarget(HumanoidBone.HEAD, rx = 7f, ry = -24f, rz = -6f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = -26f, ry = -84f, rz = 8f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -84f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = -22f, ry = 84f, rz = -10f)
        setTarget(HumanoidBone.LOWER_ARM_R, ry = 88f)
        if (sitBlend < 0.35f) {
            setTarget(HumanoidBone.UPPER_LEG_L, rx = 4f, rz = -3f)
            setTarget(HumanoidBone.UPPER_LEG_R, rx = -6f, rz = 3f)
        }
        blendTargetsSlerp(w)
    }

    /** 害羞娇颤：耸肩低头 + 双手对称捧在颊前（SLERP 经前方抬起不穿透胸部）+ 轻微娇抖 */
    private fun applyBlush(p: Float) {
        val w = envelope(p, 0.18f, 0.82f)
        if (w <= 0f) return
        val tremble = sin(p * 2f * PI.toFloat() * 4.0f) * 2.4f
        setTarget(HumanoidBone.HIPS, rx = 2f)
        setTarget(HumanoidBone.SPINE, rx = -3f)
        setTarget(HumanoidBone.CHEST, rx = -3f)
        setTarget(HumanoidBone.NECK, rx = -4f, ry = -2f, rz = 2f)
        setTarget(HumanoidBone.HEAD, rx = -10f, ry = -4f + tremble, rz = 5f)
        setTarget(HumanoidBone.SHOULDER_L, rz = -6f)
        setTarget(HumanoidBone.SHOULDER_R, rz = 6f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 40f, ry = -14f, rz = 70f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -118f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 40f, ry = 14f, rz = -70f)
        setTarget(HumanoidBone.LOWER_ARM_R, ry = 118f)
        if (sitBlend < 0.35f) {
            setTarget(HumanoidBone.UPPER_LEG_L, rx = 6f, rz = 4.5f)
            setTarget(HumanoidBone.UPPER_LEG_R, rx = 6f, rz = -4.5f)
            setTarget(HumanoidBone.LOWER_LEG_L, rx = -9f)
            setTarget(HumanoidBone.LOWER_LEG_R, rx = -9f)
        }
        blendTargetsSlerp(w)
    }

    /** 打哈欠：慵懒仰头伸腰 + 右手真正抬至唇前轻掩（SLERP 上举不再错落胸口） */
    private fun applyYawn(p: Float) {
        val w = envelope(p, 0.22f, 0.72f)
        if (w <= 0f) return
        setTarget(HumanoidBone.SPINE, rx = 5f)
        setTarget(HumanoidBone.CHEST, rx = 7f, ry = -3f, rz = 2f)
        setTarget(HumanoidBone.NECK, rx = 8f, ry = -2f)
        setTarget(HumanoidBone.HEAD, rx = 12f, ry = -5f, rz = 4f)
        setTarget(HumanoidBone.SHOULDER_R, rz = 10f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 26f, ry = 62f, rz = -10f)
        setTarget(HumanoidBone.LOWER_ARM_R, rx = 0f, ry = 102f, rz = 18f)
        setTarget(HumanoidBone.SHOULDER_L, rz = -4f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 10f, ry = -14f, rz = 58f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -24f)
        blendTargetsSlerp(w)
    }

    /** 困倦瞌睡：告别僵直低头机器人——放松微躬、双肩垂软、双臂交叠身前、膝微弯懒洋洋轻晃（坐姿则倚膝浅眠） */
    private fun applySleep(t: Float) {
        val sway = sin(t * 1.15f) * 2.8f
        val headBob = sin(t * 1.15f - 0.5f) * 3.0f
        val sit = sitBlend.coerceIn(0f, 1f)
        if (sit < 0.5f) {
            hipsOffsetY -= 0.015f
            setTarget(HumanoidBone.UPPER_LEG_L, rx = 7f, rz = 2f)
            setTarget(HumanoidBone.LOWER_LEG_L, rx = -13f)
            setTarget(HumanoidBone.FOOT_L, rx = 6f)
            setTarget(HumanoidBone.UPPER_LEG_R, rx = 7f, rz = -2f)
            setTarget(HumanoidBone.LOWER_LEG_R, rx = -13f)
            setTarget(HumanoidBone.FOOT_R, rx = 6f)
        }
        setTarget(HumanoidBone.SPINE, rx = -5f - 5f * sit, rz = sway * 0.5f)
        setTarget(HumanoidBone.CHEST, rx = -6f - 4f * sit, rz = sway * 0.5f)
        setTarget(HumanoidBone.NECK, rx = -10f, ry = -2f, rz = 8f)
        setTarget(HumanoidBone.HEAD, rx = -16f + headBob, ry = -4f, rz = 15f + sway)
        setTarget(HumanoidBone.SHOULDER_L, rz = 6f)
        setTarget(HumanoidBone.SHOULDER_R, rz = -6f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 12f, ry = -26f, rz = 75f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -52f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 12f, ry = 26f, rz = -75f)
        setTarget(HumanoidBone.LOWER_ARM_R, ry = 52f)
        blendTargetsSlerp(1f)
    }

    /** 嚣张得意：顶胯挺胸仰首 + 双手大弧度叉腰（SLERP 流畅外展叉腰，不撞胯骨） */
    private fun applySmug(p: Float) {
        val w = envelope(p, 0.16f, 0.84f)
        if (w <= 0f) return
        setTarget(HumanoidBone.HIPS, ry = -6f, rz = 6f)
        setTarget(HumanoidBone.SPINE, rx = 7f, rz = -3f)
        setTarget(HumanoidBone.CHEST, rx = 8f)
        setTarget(HumanoidBone.HEAD, rx = 8f, ry = 4f, rz = -5f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = -86f, ry = -48f, rz = 5f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -58f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = -86f, ry = 48f, rz = -5f)
        setTarget(HumanoidBone.LOWER_ARM_R, ry = 58f)
        blendTargetsSlerp(w)
    }

    // ============================================================ 触摸反应层

    private fun applyTouchReaction(input: AnimationInput) {
        val part = input.touchReaction ?: return
        val elapsed = input.touchElapsed
        if (elapsed < 0f || elapsed > TOUCH_DURATION) return
        val p = (elapsed / TOUCH_DURATION).coerceIn(0f, 1f)
        val w = envelope(p, 0.14f, 0.82f)
        if (w <= 0f) return

        when (part) {
            TouchPart.HEAD -> {
                // 摸摸头：欢喜蹭手偏头 + 双肩甜美耸起 + 胸前小拳微举 + 站姿微屈膝萌动
                val purr = sin(p * 2f * PI.toFloat() * 2.5f) * 3.0f
                setTarget(HumanoidBone.CHEST, rx = 2f + sin(p * 2f * PI.toFloat() * 2.0f) * 1.5f, rz = 4f)
                setTarget(HumanoidBone.NECK, rx = -2f, rz = 5f)
                setTarget(HumanoidBone.HEAD, rx = -6f, ry = 4f + purr, rz = 14f)
                setTarget(HumanoidBone.SHOULDER_L, rz = -9f)
                setTarget(HumanoidBone.SHOULDER_R, rz = 9f)
                setTarget(HumanoidBone.UPPER_ARM_L, rx = 22f, ry = -22f, rz = 70f)
                setTarget(HumanoidBone.LOWER_ARM_L, ry = -86f)
                setTarget(HumanoidBone.UPPER_ARM_R, rx = 22f, ry = 22f, rz = -70f)
                setTarget(HumanoidBone.LOWER_ARM_R, ry = 86f)
                if (sitBlend < 0.35f) {
                    setTarget(HumanoidBone.UPPER_LEG_L, rx = 5f, rz = 3f)
                    setTarget(HumanoidBone.LOWER_LEG_L, rx = -9f)
                    setTarget(HumanoidBone.UPPER_LEG_R, rx = 5f, rz = -3f)
                    setTarget(HumanoidBone.LOWER_LEG_R, rx = -9f)
                }
            }
            TouchPart.CHEEK -> {
                // 戳戳脸：歪头躲开 + 右手真正上举至右颊旁护脸（告别旧版手臂向下穿肚兜 bug）
                setTarget(HumanoidBone.CHEST, rx = -3f, ry = -4f, rz = -3f)
                setTarget(HumanoidBone.NECK, rx = -2f, ry = 5f, rz = -5f)
                setTarget(HumanoidBone.HEAD, rx = -4f, ry = 12f, rz = -13f)
                setTarget(HumanoidBone.SHOULDER_R, rz = 9f)
                setTarget(HumanoidBone.UPPER_ARM_R, rx = 24f, ry = 52f, rz = 8f)
                setTarget(HumanoidBone.LOWER_ARM_R, rx = 0f, ry = 96f, rz = 14f)
                setTarget(HumanoidBone.UPPER_ARM_L, rx = 6f, ry = -14f, rz = 78f)
                setTarget(HumanoidBone.LOWER_ARM_L, ry = -28f)
            }
            TouchPart.HAND -> {
                // 牵小手：温柔微俯身 + 右手大大方方朝镜头前方 (-Z) 伸出迎握
                setTarget(HumanoidBone.SPINE, rx = -3f)
                setTarget(HumanoidBone.CHEST, rx = -5f, ry = 4f)
                setTarget(HumanoidBone.NECK, rx = -2f, ry = -2f, rz = 3f)
                setTarget(HumanoidBone.HEAD, rx = -4f, ry = -6f, rz = 6f)
                setTarget(HumanoidBone.UPPER_ARM_R, rx = 10f, ry = 52f, rz = -46f)
                setTarget(HumanoidBone.LOWER_ARM_R, ry = 34f)
                setTarget(HumanoidBone.HAND_R, rz = -10f)
                setTarget(HumanoidBone.UPPER_ARM_L, rx = 4f, ry = -12f, rz = 76f)
                setTarget(HumanoidBone.LOWER_ARM_L, ry = -18f)
            }
            TouchPart.BODY -> {
                // 抱抱：张开双臂在前方形成温暖环抱弧线，羞怯偏头靠向指挥官
                val beat = sin(p * 2f * PI.toFloat() * 3.0f) * 1.4f
                setTarget(HumanoidBone.SPINE, rx = -2f)
                setTarget(HumanoidBone.CHEST, rx = -5f + beat)
                setTarget(HumanoidBone.NECK, rx = -4f, rz = 2f)
                setTarget(HumanoidBone.HEAD, rx = -8f, rz = 6f)
                setTarget(HumanoidBone.SHOULDER_L, ry = -10f)
                setTarget(HumanoidBone.SHOULDER_R, ry = 10f)
                setTarget(HumanoidBone.UPPER_ARM_L, rx = 6f, ry = -42f, rz = 46f)
                setTarget(HumanoidBone.LOWER_ARM_L, ry = -56f)
                setTarget(HumanoidBone.UPPER_ARM_R, rx = 6f, ry = 42f, rz = -46f)
                setTarget(HumanoidBone.LOWER_ARM_R, ry = 56f)
            }
        }
        blendTargetsSlerp(w)
    }

    // ============================================================ 裙摆补偿

    private fun applySkirtPhysics(t: Float, input: AnimationInput) {
        val legFlexDeg = maxOf(
            angleOf(HumanoidBone.UPPER_LEG_L, 0),
            angleOf(HumanoidBone.UPPER_LEG_R, 0),
            0f
        )
        val flex = (legFlexDeg / SIT_THIGH_FLEX).coerceIn(0f, 1.15f)
        val sway = (input.inertialSway.coerceIn(-18f, 18f) * 0.25f) + sin(t * 1.6f) * 1.2f

        for ((idx, chain) in rig.skirtFrontChains.withIndex()) {
            val sideSign = if (idx == 0) -1f else 1f
            if (chain.isNotEmpty()) {
                addNode(chain[0], rx = -10f * flex + sway * 0.5f, rz = sideSign * 3f * flex)
            }
            if (chain.size > 1) {
                addNode(chain[1], rx = -14f * flex + sway * 0.7f)
            }
        }
        for (chain in rig.skirtBackChains) {
            if (chain.isNotEmpty()) {
                addNode(chain[0], rx = -124f * flex - sway * 0.4f)
            }
            if (chain.size > 1) {
                addNode(chain[1], rx = sway * 0.5f)
            }
        }
        for ((idx, chain) in rig.skirtSideChains.withIndex()) {
            val sideSign = if (idx == 0) 1f else -1f
            if (chain.isNotEmpty()) {
                addNode(chain[0], rx = 72f * flex, rz = sideSign * (14f * flex + 1.5f))
            }
            if (chain.size > 1) {
                addNode(chain[1], rx = -10f * flex, rz = sideSign * 2f)
            }
        }
    }

    // ============================================================ 面部 / 眨眼 / 口型

    private fun updateFace(dt: Float, input: AnimationInput) {
        faceEmotion = input.emotion
        val expressionOverride = expressionForAction(input)
        faceExpression = expressionOverride ?: input.expression

        if (blinkClock < 0f) {
            blinkCountdown -= dt
            if (blinkCountdown <= 0f) {
                blinkClock = 0f
                blinkCountdown = 2.2f + (timeNoise(input.timeSeconds) * 3.8f)
            }
        } else {
            blinkClock += dt
            if (blinkClock > 0.16f) blinkClock = -1f
        }
        val blink = when {
            blinkClock < 0f -> 0f
            blinkClock < 0.07f -> blinkClock / 0.07f
            else -> (1f - (blinkClock - 0.07f) / 0.09f).coerceIn(0f, 1f)
        }
        val sleepy = input.emotion == EmotionType.SLEEPY || input.action == ActionType.SLEEP ||
                input.action == ActionType.YAWN || faceExpression == "SLEEPY"
        eyesClosed = when {
            input.action == ActionType.SLEEP -> 0.94f
            sleepy -> 0.52f
            else -> blink
        }
        blinkAmount = eyesClosed

        val mouthTarget = if (input.speaking) input.lipSync.coerceIn(0f, 1f) else 0f
        val k = min(1f, dt * 20f)
        smoothMouth += (mouthTarget - smoothMouth) * k
        mouthOpen = when {
            input.action == ActionType.YAWN -> maxOf(smoothMouth, yawnMouth(input.actionElapsed))
            else -> smoothMouth
        }
    }

    private fun yawnMouth(elapsed: Float): Float {
        val p = (elapsed / ActionTiming.durationOf(ActionType.YAWN)).coerceIn(0f, 1f)
        return envelope(p, 0.25f, 0.65f) * 0.92f
    }

    private fun expressionForAction(input: AnimationInput): String? {
        val touch = input.touchReaction
        if (touch != null && input.touchElapsed in 0f..TOUCH_DURATION) {
            return when (touch) {
                TouchPart.HEAD -> "JOY"
                TouchPart.CHEEK -> "POUT"
                TouchPart.HAND -> "WINK"
                TouchPart.BODY -> "BLUSH_FACE"
            }
        }
        val action = input.action
        val elapsed = input.actionElapsed
        val duration = ActionTiming.durationOf(action)
        val inAction = action != ActionType.IDLE && (duration <= 0f || elapsed <= duration + 0.05f)
        if (!inAction) return null
        return when (action) {
            ActionType.WAVE -> "JOY"
            ActionType.NOD -> "SMILE"
            ActionType.SHAKE_HEAD -> "POUT"
            ActionType.BOW -> "SMILE"
            ActionType.JUMP -> "JOY"
            ActionType.POUT -> "POUT"
            ActionType.BLUSH -> "BLUSH_FACE"
            ActionType.YAWN -> "SLEEPY"
            ActionType.SLEEP -> "SLEEPY"
            ActionType.SMUG -> "SMUG"
            ActionType.SIT, ActionType.IDLE -> null
        }
    }

    // ============================================================ 手指 / 头发

    private fun applyFingerCurl() {
        for (f in rig.fingerJoints) {
            addNode(f.nodeIndex, rz = f.curlDegrees)
        }
    }

    private fun applyHairSecondary(dt: Float, t: Float, input: AnimationInput) {
        hairPhase += dt
        val sway = input.inertialSway.coerceIn(-18f, 18f) * 0.09f
        for ((chainIdx, chain) in rig.hairChains.withIndex()) {
            for ((level, node) in chain.withIndex()) {
                val lag = sin(hairPhase * 1.35f - level * 0.55f + chainIdx * 0.7f) *
                        (0.5f + level * 0.45f)
                addNode(node, rx = lag * 0.8f + sway * (level + 1) * 0.5f,
                    rz = lag * 0.55f + sway * (level + 1) * 0.35f)
            }
        }
    }

    // ============================================================ 缓动工具

    private fun smoothIn(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun bell(p: Float): Float {
        val v = p.coerceIn(0f, 1f)
        return sin(v * PI.toFloat())
    }

    private fun envelope(p: Float, inEnd: Float, outStart: Float): Float {
        val v = p.coerceIn(0f, 1f)
        val up = if (v < inEnd) smoothIn(v / inEnd) else 1f
        val down = if (v > outStart) 1f - smoothIn((v - outStart) / (1f - outStart).coerceAtLeast(0.0001f)) else 1f
        return (up * down).coerceIn(0f, 1f)
    }

    private fun timeNoise(t: Float): Float {
        val s = sin(t * 12.9898f) * 43758.5453f
        return s - kotlin.math.floor(s)
    }
}
