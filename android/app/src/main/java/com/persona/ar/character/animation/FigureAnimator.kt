package com.persona.ar.character.animation

import com.persona.ar.character.skeleton.SkeletonStance
import com.persona.ar.data.model.ActionType
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.TouchPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
 * 1. 构建基础姿态层（站立/坐姿 + 非机械复合呼吸 + 自主微动作 + 镜头协同注视 + 情绪体态）；
 * 2. 对激活的动作或触摸反应写入「绝对目标骨骼姿态」并沿四元数最短球面弧 (SLERP) 混合；
 * 3. **全骨骼跨动作四元数过渡（Crossfade SLERP）**：动作切换、触摸打断或自然结束回落时，
 *    自动以 0.28s 过渡窗从上一刻实际姿态无缝滑向新姿态，彻底消除跨动作跳帧与抽搐；
 * 4. 在腿部屈伸解算完成后执行裙摆链反向物理补偿，消除坐姿/跳跃/害羞时的裙摆穿模；
 * 5. 驱动 VRM 面部表情、偶发双连眨眼与真实音频口型。
 */
class FigureAnimator(private val rig: GltfRig) {

    companion object {
        private const val BREATH_PERIOD = 3.9f
        private const val BLEND_RATE = 7.8f
        private const val SIT_HIPS_DOWN = 0.40f
        private const val SIT_HIPS_FORWARD = 0.02f
        private const val SIT_THIGH_FLEX = 80f
        private const val TOUCH_DURATION = 2.6f
        private const val CROSSFADE_DURATION = 0.28f
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

    // ---- 跨动作四元数无缝过渡状态 ----
    private var hasPreviousPose = false
    private val prevFinalQuats = FloatArray(rig.nodeCount * 4)
    private val crossfadeStartQuats = FloatArray(rig.nodeCount * 4)
    private var prevHipsY = 0f
    private var prevHipsZ = 0f
    private var crossfadeStartHipsY = 0f
    private var crossfadeStartHipsZ = 0f
    private var lastActionSignature: String = ""
    private var crossfadeElapsed = CROSSFADE_DURATION
    private val qPrevTmp = FloatArray(4)
    private val qCurrTmp = FloatArray(4)
    private val qOutTmp = FloatArray(4)

    // ---- 平滑与微动作状态 ----
    private var sitBlend = 0f
    val currentSitBlend: Float get() = sitBlend
    private var smoothMouth = 0f
    private var smoothGazeX = 0f
    private var smoothGazeY = 0f
    private var blinkCountdown = 2.2f
    private var blinkClock = -1f
    private var doubleBlinkPending = false
    private var hairPhase = 0f
    private var fingerExtraOpen = 0f // 挥手/牵手时手指微微张开更灵动

    private val nodeByBone: Map<HumanoidBone, Int> = rig.humanoid

    fun update(input: AnimationInput) {
        val dt = input.dtSeconds.coerceIn(0f, 0.12f)
        val t = input.timeSeconds
        java.util.Arrays.fill(angles, 0f)
        java.util.Arrays.fill(actionTargets, 0f)
        java.util.Arrays.fill(actionHasBone, false)
        hipsOffsetX = 0f; hipsOffsetY = 0f; hipsOffsetZ = 0f
        airborne = 0f
        fingerExtraOpen = 0f

        // 1) 体态层：站立 ↔ 桌面边缘垂腿坐姿
        val sitTarget = if (input.stance == SkeletonStance.SITTING) 1f else 0f
        sitBlend += (sitTarget - sitBlend) * min(1f, dt * BLEND_RATE * 0.55f)

        // 视线平滑阻尼，消除传感器微小跳跃
        val gazeLerp = if (!hasPreviousPose) 1f else (1f - exp(-10f * dt)).coerceIn(0f, 1f)
        smoothGazeX += (input.gazeX.coerceIn(-1f, 1f) - smoothGazeX) * gazeLerp
        smoothGazeY += (input.gazeY.coerceIn(-1f, 1f) - smoothGazeY) * gazeLerp

        val headDominance = computeHeadOverrideWeight(input)

        // 2) 生动待机呼吸 / 重心转移 / 自主小微动 / 镜头协同注视
        applyIdle(t, input, gazeWeight = 1f - headDominance * 0.85f)
        // 3) 情绪微姿态
        applyEmotion(input.emotion)
        // 4) 坐姿基底
        val hasTouchReaction = input.touchReaction != null && input.touchElapsed in 0f..TOUCH_DURATION
        if (sitBlend > 0.001f) applySitting(t, sitBlend)

        // 5) 动作或触摸绝对目标骨骼姿态 → 四元数 SLERP 混合
        if (hasTouchReaction) {
            actionActive = false
            actionProgress = 0f
            applyTouchReaction(input)
        } else {
            actionActive = applyAction(input, t)
        }

        // 6) 裙摆链物理补偿（跟随最终大腿/躯干屈伸防穿模）
        applySkirtPhysics(t, input)

        // 7) 面部表情 / 双连眨眼 / 口型
        updateFace(dt, input)

        // 8) 手指弯曲与头发次级惯性
        applyFingerCurl()
        applyHairSecondary(dt, t, input)

        // 9) 跨动作四元数无缝过渡：检测状态切换，使用 0.28s 平滑过渡窗消除一切跳帧
        val activeTag = when {
            hasTouchReaction -> "T:${input.touchReaction?.name}"
            actionActive -> "A:${input.action.name}"
            else -> "I:${input.stance.name}:${input.emotion.name}"
        }
        applyCrossfadeSmoothing(dt, activeTag)
    }

    private fun applyCrossfadeSmoothing(dt: Float, activeTag: String) {
        if (!hasPreviousPose) {
            // 首帧立即保存并就位，保证无历史时的初始测试/渲染准确到位
            for (node in 0 until rig.nodeCount) {
                val i3 = node * 3
                val i4 = node * 4
                QuatMath.fromEulerDegrees(qCurrTmp, angles[i3], angles[i3 + 1], angles[i3 + 2])
                QuatMath.copy(prevFinalQuats, i4, qCurrTmp, 0)
            }
            prevHipsY = hipsOffsetY
            prevHipsZ = hipsOffsetZ
            lastActionSignature = activeTag
            crossfadeElapsed = CROSSFADE_DURATION
            hasPreviousPose = true
            return
        }

        if (activeTag != lastActionSignature) {
            System.arraycopy(prevFinalQuats, 0, crossfadeStartQuats, 0, rig.nodeCount * 4)
            crossfadeStartHipsY = prevHipsY
            crossfadeStartHipsZ = prevHipsZ
            crossfadeElapsed = 0f
            lastActionSignature = activeTag
        }

        if (crossfadeElapsed < CROSSFADE_DURATION) {
            crossfadeElapsed = (crossfadeElapsed + dt).coerceAtMost(CROSSFADE_DURATION)
            val w = smoothIn(crossfadeElapsed / CROSSFADE_DURATION)
            hipsOffsetY = crossfadeStartHipsY + (hipsOffsetY - crossfadeStartHipsY) * w
            hipsOffsetZ = crossfadeStartHipsZ + (hipsOffsetZ - crossfadeStartHipsZ) * w

            for (node in 0 until rig.nodeCount) {
                val i3 = node * 3
                val i4 = node * 4
                QuatMath.copy(qPrevTmp, 0, crossfadeStartQuats, i4)
                QuatMath.fromEulerDegrees(qCurrTmp, angles[i3], angles[i3 + 1], angles[i3 + 2])
                QuatMath.slerp(qOutTmp, qPrevTmp, qCurrTmp, w)
                QuatMath.toEulerDegrees(qOutTmp, angles, i3)
                QuatMath.copy(prevFinalQuats, i4, qOutTmp, 0)
            }
        } else {
            for (node in 0 until rig.nodeCount) {
                val i3 = node * 3
                val i4 = node * 4
                QuatMath.fromEulerDegrees(qCurrTmp, angles[i3], angles[i3 + 1], angles[i3 + 2])
                QuatMath.copy(prevFinalQuats, i4, qCurrTmp, 0)
            }
        }
        prevHipsY = hipsOffsetY
        prevHipsZ = hipsOffsetZ
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
        // 自然垂臂：微屈肘、轻靠身侧，消除僵直木偶感
        add(HumanoidBone.UPPER_ARM_L, rx = 2f, ry = -6f, rz = 72f)
        add(HumanoidBone.UPPER_ARM_R, rx = 2f, ry = 6f, rz = -72f)
        add(HumanoidBone.LOWER_ARM_L, ry = -16f)
        add(HumanoidBone.LOWER_ARM_R, ry = 16f)

        // 双频呼吸叠加：自然深浅律动 + 微肩起伏
        val breath = sin(t * 2f * PI.toFloat() / BREATH_PERIOD) * 0.78f +
                sin(t * 2f * PI.toFloat() / (BREATH_PERIOD * 1.7f) + 0.8f) * 0.22f
        val breathLag = sin(t * 2f * PI.toFloat() / BREATH_PERIOD + 0.45f)
        add(HumanoidBone.CHEST, rx = breath * 1.05f, rz = breath * 0.25f)
        add(HumanoidBone.SPINE, rx = breathLag * 0.45f)
        add(HumanoidBone.UPPER_CHEST, rx = breath * 0.35f)
        add(HumanoidBone.SHOULDER_L, rz = -breath * 0.55f)
        add(HumanoidBone.SHOULDER_R, rz = breath * 0.55f)

        // 缓慢自然重心转移
        val weightPhase = t * 2f * PI.toFloat() / 7.2f
        val swayHips = sin(weightPhase) * 1.35f + sin(weightPhase * 0.5f + 1.1f) * 0.45f
        add(HumanoidBone.HIPS, rz = swayHips, ry = sin(weightPhase * 0.7f) * 1.1f)
        add(HumanoidBone.SPINE, rz = -swayHips * 0.48f)

        // 自主可爱小微动：每 ~6.5s 轻偏头思考一下、偶尔微侧耳聆听
        val curiosityWindow = ((t % 6.8f) / 2.2f).coerceIn(0f, 1f)
        val idleHeadTilt = if ((t % 6.8f) < 2.2f) sin(curiosityWindow * PI.toFloat()) * 3.8f else 0f

        // 镜头协同注视（分配给 头+颈+上胸，避免只扭脖子）
        val microGazeYaw = sin(t * 0.42f) * 1.8f + sin(t * 0.85f) * 0.7f
        val gx = smoothGazeX
        val gy = smoothGazeY
        val targetChestYaw = gx * 4.5f * gazeWeight
        val targetNeckYaw = gx * 9.5f * gazeWeight
        val targetHeadYaw = gx * 20.5f * gazeWeight

        val targetNeckPitch = gy * 6.0f * gazeWeight
        val targetHeadPitch = gy * 14.0f * gazeWeight

        val headYaw = microGazeYaw * (0.3f + 0.7f * gazeWeight) + targetHeadYaw
        val neckYaw = (microGazeYaw * 0.25f) * gazeWeight + targetNeckYaw
        val headPitch = sin(t * 0.55f) * 1.1f * (0.4f + 0.6f * gazeWeight) + targetHeadPitch - smoothMouth * 2.4f
        val neckPitch = targetNeckPitch + sin(t * 0.55f) * 0.4f * gazeWeight
        val headRoll = (sin(t * 0.31f) * 1.2f + idleHeadTilt) * gazeWeight - (gx * 2.5f * gazeWeight)

        add(HumanoidBone.UPPER_CHEST, ry = targetChestYaw)
        add(HumanoidBone.HEAD, rx = headPitch, ry = headYaw, rz = headRoll)
        add(HumanoidBone.NECK, rx = neckPitch, ry = neckYaw)

        // 呼吸带动手臂轻微随动 + 说话时手部微小手势
        val talkGesture = smoothMouth * sin(t * 5.2f) * 3.2f
        add(HumanoidBone.UPPER_ARM_L, rz = sin(t * 0.41f) * 1.5f, ry = -breath * 0.7f - talkGesture * 0.4f)
        add(HumanoidBone.UPPER_ARM_R, rz = -sin(t * 0.37f + 0.8f) * 1.5f, ry = breathLag * 0.7f + talkGesture * 0.6f)
        add(HumanoidBone.LOWER_ARM_L, ry = -sin(t * 0.33f + 0.4f) * 1.8f - talkGesture)
        add(HumanoidBone.LOWER_ARM_R, ry = sin(t * 0.31f + 1.1f) * 1.8f + talkGesture)

        // 拖拽与移动惯性摆动
        val sway = input.inertialSway.coerceIn(-18f, 18f)
        if (abs(sway) > 0.05f) {
            add(HumanoidBone.HIPS, rz = sway * 0.32f)
            add(HumanoidBone.SPINE, rz = sway * 0.24f)
            add(HumanoidBone.CHEST, rz = sway * 0.18f)
            add(HumanoidBone.HEAD, rz = -sway * 0.14f)
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

    private fun applySitting(t: Float, k: Float) {
        val legScale = k * (1f - airborne * 0.85f).coerceIn(0.15f, 1f)
        hipsOffsetY -= SIT_HIPS_DOWN * legScale
        hipsOffsetZ += SIT_HIPS_FORWARD * legScale

        setTarget(HumanoidBone.HIPS, rx = 5f, ry = 10f, rz = -2f)
        setTarget(HumanoidBone.SPINE, rx = -6f, ry = -2f, rz = 0f)
        setTarget(HumanoidBone.CHEST, rx = 4f, ry = -4f, rz = 0f)
        setTarget(HumanoidBone.NECK, rx = 0f, ry = -2f, rz = -2f)
        setTarget(HumanoidBone.HEAD, rx = -2f, ry = -4f, rz = -4f)

        val kickL = sin(t * 2.1f) * 3.8f
        val kickR = sin(t * 2.1f + 1.2f) * 3.8f
        setTarget(HumanoidBone.UPPER_LEG_L, rx = SIT_THIGH_FLEX * (legScale / k.coerceAtLeast(0.01f)), ry = 3f, rz = 2f)
        setTarget(HumanoidBone.UPPER_LEG_R, rx = SIT_THIGH_FLEX * (legScale / k.coerceAtLeast(0.01f)), ry = -2f, rz = -2f)
        setTarget(HumanoidBone.LOWER_LEG_L, rx = -78f + kickL, ry = 0f, rz = -2f)
        setTarget(HumanoidBone.LOWER_LEG_R, rx = -88f + kickR, ry = 0f, rz = 2f)
        setTarget(HumanoidBone.FOOT_L, rx = -14f + kickL * 0.5f, ry = 0f, rz = 0f)
        setTarget(HumanoidBone.FOOT_R, rx = -16f + kickR * 0.5f, ry = 0f, rz = 0f)

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

    /** 挥手：右肩外展至头部右上方（绝不贴耳遮脸），前臂与手腕欢快柔摆 + 手指自然舒展 */
    private fun applyWave(p: Float) {
        val w = envelope(p, 0.16f, 0.84f)
        if (w <= 0f) return
        fingerExtraOpen = 0.65f * w
        val wave = sin(p * 2f * PI.toFloat() * 3.0f)
        setTarget(HumanoidBone.HIPS, rz = 3f)
        setTarget(HumanoidBone.SPINE, rx = -2f, ry = 2f, rz = -4f)
        setTarget(HumanoidBone.CHEST, rx = 1f, ry = 2f, rz = -3f)
        setTarget(HumanoidBone.NECK, rx = 2f, ry = 2f, rz = 4f)
        setTarget(HumanoidBone.HEAD, rx = 4f, ry = 6f, rz = 8f)
        setTarget(HumanoidBone.SHOULDER_R, rz = 8f)
        setTarget(HumanoidBone.UPPER_ARM_R, rx = 8f, ry = 16f, rz = 46f)
        setTarget(HumanoidBone.LOWER_ARM_R, rx = 0f, ry = 20f, rz = 52f + wave * 18f)
        setTarget(HumanoidBone.HAND_R, ry = -wave * 10f, rz = wave * 20f)
        setTarget(HumanoidBone.UPPER_ARM_L, rx = 4f, ry = -14f, rz = 78f)
        setTarget(HumanoidBone.LOWER_ARM_L, ry = -24f)
        blendTargetsSlerp(w)
    }

    private fun applyNod(p: Float) {
        val w = bell(p)
        if (w <= 0f) return
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

    private fun applyShakeHead(p: Float) {
        val w = envelope(p, 0.14f, 0.86f)
        if (w <= 0f) return
        fingerExtraOpen = 0.35f * w
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

    /**
     * 跳跃：连续无断点的 4 阶段（蓄力微后摆 → 起跳高举展臂 → 空中欢呼 → 落地屈膝前伸缓冲）。
     * 修复旧版 p=0.18 瞬间手臂归零跳帧问题：全程使用连续插值的手臂关键姿态！
     */
    private fun applyJump(p: Float) {
        val hipsY: Float
        val legBend: Float
        val crouchArmWeight: Float
        val cheerArmWeight: Float
        when {
            p < 0.18f -> {
                val k = smoothIn(p / 0.18f)
                hipsY = -0.11f * k
                legBend = k
                crouchArmWeight = k
                cheerArmWeight = 0f
            }
            p < 0.52f -> {
                val k = smoothIn((p - 0.18f) / 0.34f)
                hipsY = -0.11f + 0.44f * k
                legBend = 1f - k * 0.65f
                crouchArmWeight = (1f - k * 2.2f).coerceIn(0f, 1f)
                cheerArmWeight = k
            }
            p < 0.72f -> {
                val k = (p - 0.52f) / 0.20f
                hipsY = 0.33f * (1f - k * k)
                legBend = 0.35f + 0.55f * k
                crouchArmWeight = 0f
                cheerArmWeight = 1f - smoothIn(k)
            }
            p < 0.86f -> {
                val k = smoothIn((p - 0.72f) / 0.14f)
                hipsY = -0.08f * k
                legBend = 0.90f * (1f - k) + 0.75f * k
                crouchArmWeight = k * 0.45f
                cheerArmWeight = 0f
            }
            else -> {
                val k = 1f - smoothIn((p - 0.86f) / 0.14f)
                hipsY = -0.08f * k
                legBend = k * 0.75f
                crouchArmWeight = k * 0.45f
                cheerArmWeight = 0f
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
        // 在单一目标中连续插值蓄力姿态 ↔ 欢呼姿态，彻底消除阶段交界跳变
        val armTotalWeight = maxOf(crouchArmWeight, cheerArmWeight)
        if (armTotalWeight > 0.001f) {
            fingerExtraOpen = 0.55f * cheerArmWeight
            val mix = if (crouchArmWeight + cheerArmWeight > 1e-4f) {
                cheerArmWeight / (crouchArmWeight + cheerArmWeight)
            } else 0f
            val rxArm = -12f + 22f * mix
            val ryArmL = 16f - 32f * mix
            val rzArmL = 74f - 116f * mix
            val ryArmR = -16f + 32f * mix
            val rzArmR = -74f + 116f * mix
            setTarget(HumanoidBone.SPINE, rx = -4f + 9f * mix)
            setTarget(HumanoidBone.CHEST, rx = -4f + 10f * mix)
            setTarget(HumanoidBone.HEAD, rx = -3f + 11f * mix)
            setTarget(HumanoidBone.SHOULDER_L, rz = -10f * mix)
            setTarget(HumanoidBone.SHOULDER_R, rz = 10f * mix)
            setTarget(HumanoidBone.UPPER_ARM_L, rx = rxArm, ry = ryArmL, rz = rzArmL)
            setTarget(HumanoidBone.LOWER_ARM_L, rz = -22f * mix, ry = -16f + 6f * mix)
            setTarget(HumanoidBone.UPPER_ARM_R, rx = rxArm, ry = ryArmR, rz = rzArmR)
            setTarget(HumanoidBone.LOWER_ARM_R, rz = 22f * mix, ry = 16f - 6f * mix)
            blendTargetsSlerp(armTotalWeight)
        }
    }

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
                fingerExtraOpen = 0.7f * w
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
                fingerExtraOpen = 0.45f * w
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

    // ============================================================ 面部 / 双连眨眼 / 口型

    private fun updateFace(dt: Float, input: AnimationInput) {
        faceEmotion = input.emotion
        val expressionOverride = expressionForAction(input)
        faceExpression = expressionOverride ?: input.expression

        if (blinkClock < 0f) {
            blinkCountdown -= dt
            if (blinkCountdown <= 0f) {
                blinkClock = 0f
                if (doubleBlinkPending) {
                    doubleBlinkPending = false
                    blinkCountdown = 2.6f + (timeNoise(input.timeSeconds) * 3.6f)
                } else {
                    // 约 25% 概率触发可爱的双连眨眼
                    val rollNoise = timeNoise(input.timeSeconds * 3.7f)
                    if (rollNoise < 0.25f) {
                        doubleBlinkPending = true
                        blinkCountdown = 0.08f
                    } else {
                        blinkCountdown = 2.1f + (timeNoise(input.timeSeconds) * 3.8f)
                    }
                }
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
        val k = min(1f, dt * 22f)
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
        val relax = 1f - fingerExtraOpen * 0.65f
        for (f in rig.fingerJoints) {
            addNode(f.nodeIndex, rz = f.curlDegrees * relax)
        }
    }

    private fun applyHairSecondary(dt: Float, t: Float, input: AnimationInput) {
        hairPhase += dt
        val sway = input.inertialSway.coerceIn(-18f, 18f) * 0.11f
        for ((chainIdx, chain) in rig.hairChains.withIndex()) {
            for ((level, node) in chain.withIndex()) {
                val lag = sin(hairPhase * 1.45f - level * 0.55f + chainIdx * 0.7f) *
                        (0.55f + level * 0.45f)
                addNode(
                    node,
                    rx = lag * 0.85f + sway * (level + 1) * 0.5f,
                    rz = lag * 0.60f + sway * (level + 1) * 0.35f
                )
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
