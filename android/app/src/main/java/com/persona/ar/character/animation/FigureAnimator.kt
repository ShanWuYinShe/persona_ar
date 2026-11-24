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
        ActionType.NOD -> 1.15f
        ActionType.SHAKE_HEAD -> 1.5f
        ActionType.BOW -> 2.2f
        ActionType.JUMP -> 1.7f
        ActionType.POUT -> 2.2f
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
 * 3D 手办全身动画器：把「体态 / 待机 / 情绪 / 动作 / 触摸反应 / 面部表情」叠加成逐骨骼欧拉角增量，
 * 交给 [PoseSolver] 做 FK 与蒙皮矩阵计算。
 *
 * 角度约定（每根骨骼的 **静止局部坐标系**，单位度，组合顺序 先 Z 再 Y 最后 X）：
 * - 躯干/头部：rx = 前后俯仰（点头/鞠躬），ry = 左右转体，rz = 左右侧倾；
 * - 手臂（T-pose 沿 ±X）：rz = 抬落（±72° 为自然垂臂），ry = 前后摆动/肘部铰链，rx = 轴向扭转；
 * - 腿（沿 -Y）：rx = 前后屈伸（髋/膝铰链），rz = 外展。
 * 以上角度均通过离线数值仿真在 figure.glb 真实骨架上限定了安全范围（避免穿模/断肢感）。
 */
class FigureAnimator(private val rig: GltfRig) {

    companion object {
        private const val BREATH_PERIOD = 3.8f
        private const val BLEND_RATE = 7.5f
        /** 桌面边缘垂腿坐姿：骨盆下沉到桌面高度，双腿前伸后垂下 */
        private const val SIT_HIPS_DOWN = 0.98f
        private const val SIT_HIPS_FORWARD = 0.03f
        private const val SIT_THIGH_FLEX = 92f
        private const val SIT_KNEE_BEND = 95f
        private const val TOUCH_DURATION = 2.6f
    }

    /** 逐节点欧拉角增量（度），3 个 float / 节点；每帧重算 */
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
    /** 当前动作进度 0..1（调试/UI 用） */
    var actionProgress: Float = 0f; private set
    var actionActive: Boolean = false; private set

    // ---- 内部状态 ----
    private var sitBlend = 0f
    private var smoothMouth = 0f
    private var blinkCountdown = 2.2f
    private var blinkClock = -1f
    private var hairPhase = 0f

    private val nodeByBone: Map<HumanoidBone, Int> = rig.humanoid

    fun update(input: AnimationInput) {
        val dt = input.dtSeconds.coerceIn(0f, 0.12f)
        val t = input.timeSeconds
        java.util.Arrays.fill(angles, 0f)
        hipsOffsetX = 0f; hipsOffsetY = 0f; hipsOffsetZ = 0f
        airborne = 0f

        // 1) 体态层：站立 ↔ 桌面边缘垂腿坐姿（弹簧平滑）
        val sitTarget = if (input.stance == SkeletonStance.SITTING) 1f else 0f
        sitBlend += (sitTarget - sitBlend) * min(1f, dt * BLEND_RATE * 0.55f)

        // 2) 呼吸与待机微动
        applyIdle(t, input)
        // 3) 情绪体态
        applyEmotion(input.emotion)
        // 4) 坐姿
        if (sitBlend > 0.001f) applySitting(sitBlend)
        // 5) 动作层
        actionActive = applyAction(input)
        // 6) 触摸反应层
        applyTouchReaction(input)
        // 7) 面部表情 / 眨眼 / 口型
        updateFace(dt, input)
        // 8) 头发惯性与手指自然弯曲
        applyFingerCurl()
        applyHairSecondary(dt, t, input)
    }

    // ============================================================ 骨骼角度工具

    private fun add(bone: HumanoidBone, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) {
        val node = nodeByBone[bone] ?: return
        val i = node * 3
        angles[i] += rx; angles[i + 1] += ry; angles[i + 2] += rz
    }

    private fun addNode(node: Int, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) {
        val i = node * 3
        angles[i] += rx; angles[i + 1] += ry; angles[i + 2] += rz
    }

    // ============================================================ 待机层

    private fun applyIdle(t: Float, input: AnimationInput) {
        // 自然垂臂（T-pose → A-pose）
        add(HumanoidBone.UPPER_ARM_L, rz = 72f)
        add(HumanoidBone.UPPER_ARM_R, rz = -72f)
        add(HumanoidBone.LOWER_ARM_L, ry = 15f)
        add(HumanoidBone.LOWER_ARM_R, ry = -15f)

        val breath = sin(t * 2f * PI.toFloat() / BREATH_PERIOD)
        val breath2 = sin(t * 2f * PI.toFloat() / BREATH_PERIOD + 0.45f)
        // 胸腔与腹式复合多频呼吸起伏（轻柔微动）
        add(HumanoidBone.CHEST, rx = breath * 0.95f, rz = breath * 0.22f)
        add(HumanoidBone.SPINE, rx = breath2 * 0.40f)
        add(HumanoidBone.UPPER_CHEST, rx = breath * 0.30f)

        // 真实重心左右缓慢转移（周期约 6.8 秒，骨盆微倾 + 脊柱反向自平衡补偿）
        val weightPhase = t * 2f * PI.toFloat() / 6.8f
        val swayHips = sin(weightPhase) * 1.2f
        add(HumanoidBone.HIPS, rz = swayHips, ry = sin(weightPhase * 0.7f) * 0.8f)
        add(HumanoidBone.SPINE, rz = -swayHips * 0.45f)

        // 头部注视：动态 3D 镜头追焦（Eye & Head Contact），伴随多频呼吸与思绪微幅自然环顾探视
        val microGazeYaw = sin(t * 0.42f) * 2.0f + sin(t * 0.85f) * 0.8f
        val targetGazeYaw = input.gazeX.coerceIn(-1f, 1f) * 20.0f
        val targetNeckYaw = input.gazeX.coerceIn(-1f, 1f) * 9.0f

        val targetGazePitch = -input.gazeY.coerceIn(-1f, 1f) * 14.0f
        val targetNeckPitch = -input.gazeY.coerceIn(-1f, 1f) * 6.0f

        val headYaw = microGazeYaw + targetGazeYaw
        val neckYaw = (microGazeYaw * 0.3f) + targetNeckYaw
        val headPitch = sin(t * 0.55f) * 1.2f + targetGazePitch + smoothMouth * 2.6f
        val neckPitch = targetNeckPitch + (sin(t * 0.55f) * 0.4f)
        val headRoll = sin(t * 0.31f) * 1.2f - (input.gazeX.coerceIn(-1f, 1f) * 2.5f)

        add(HumanoidBone.HEAD, rx = headPitch, ry = headYaw, rz = headRoll)
        add(HumanoidBone.NECK, rx = neckPitch, ry = neckYaw)

        // 手臂随呼吸与重心转移自然轻摆（左右相位差异）
        add(HumanoidBone.UPPER_ARM_L, rz = sin(t * 0.41f) * 1.5f, ry = breath * 0.6f)
        add(HumanoidBone.UPPER_ARM_R, rz = -sin(t * 0.37f + 0.8f) * 1.5f, ry = breath2 * 0.6f)
        add(HumanoidBone.LOWER_ARM_L, ry = sin(t * 0.33f + 0.4f) * 1.8f)
        add(HumanoidBone.LOWER_ARM_R, ry = -sin(t * 0.31f + 1.1f) * 1.8f)

        // 拖拽惯性 / 悬崖重心自平衡：整条躯干链轻微倾斜
        val sway = input.inertialSway.coerceIn(-18f, 18f)
        if (abs(sway) > 0.05f) {
            add(HumanoidBone.HIPS, rz = sway * 0.35f)
            add(HumanoidBone.SPINE, rz = sway * 0.25f)
            add(HumanoidBone.CHEST, rz = sway * 0.18f)
            add(HumanoidBone.HEAD, rz = -sway * 0.12f)
        }
    }

    // ============================================================ 情绪体态层

    private fun applyEmotion(emotion: EmotionType) {
        when (emotion) {
            EmotionType.JOY -> {
                add(HumanoidBone.CHEST, rx = -1.4f)
                add(HumanoidBone.HEAD, rx = -1.6f, rz = 1.0f)
            }
            EmotionType.SHY -> {
                add(HumanoidBone.HEAD, rx = 5.5f, ry = -4f, rz = 2.5f)
                add(HumanoidBone.CHEST, rx = 1.8f)
                add(HumanoidBone.UPPER_ARM_L, ry = 4f)
                add(HumanoidBone.UPPER_ARM_R, ry = -4f)
                add(HumanoidBone.LOWER_ARM_L, ry = 12f)
                add(HumanoidBone.LOWER_ARM_R, ry = -12f)
            }
            EmotionType.SAD -> {
                add(HumanoidBone.HEAD, rx = 7f, rz = -1.5f)
                add(HumanoidBone.CHEST, rx = 4.5f)
                add(HumanoidBone.UPPER_ARM_L, rz = 3f)
                add(HumanoidBone.UPPER_ARM_R, rz = -3f)
            }
            EmotionType.SURPRISED -> {
                add(HumanoidBone.HEAD, rx = -5f)
                add(HumanoidBone.CHEST, rx = -1.5f)
            }
            EmotionType.THINKING -> {
                add(HumanoidBone.HEAD, rx = -2.5f, ry = 6f, rz = 4f)
                add(HumanoidBone.NECK, ry = 2.5f)
            }
            EmotionType.ANGRY -> {
                add(HumanoidBone.HEAD, rx = -3.5f)
                add(HumanoidBone.CHEST, rx = -2f)
                add(HumanoidBone.UPPER_ARM_L, ry = -4f)
                add(HumanoidBone.UPPER_ARM_R, ry = 4f)
            }
            EmotionType.SLEEPY -> {
                add(HumanoidBone.HEAD, rx = 7.5f, rz = 3f)
                add(HumanoidBone.NECK, rx = 4f)
                add(HumanoidBone.CHEST, rx = 2.5f)
            }
            EmotionType.CONFIDENT -> {
                add(HumanoidBone.CHEST, rx = -2.8f)
                add(HumanoidBone.HEAD, rx = -2.6f)
            }
            EmotionType.NEUTRAL -> Unit
        }
    }

    // ============================================================ 坐姿层

    private fun applySitting(k: Float) {
        hipsOffsetY -= SIT_HIPS_DOWN * k
        hipsOffsetZ += SIT_HIPS_FORWARD * k
        add(HumanoidBone.HIPS, rx = -3f * k)
        add(HumanoidBone.SPINE, rx = 2f * k)
        add(HumanoidBone.UPPER_LEG_L, rx = -SIT_THIGH_FLEX * k, rz = -4f * k)
        add(HumanoidBone.UPPER_LEG_R, rx = -SIT_THIGH_FLEX * k, rz = 4f * k)
        add(HumanoidBone.LOWER_LEG_L, rx = SIT_KNEE_BEND * k)
        add(HumanoidBone.LOWER_LEG_R, rx = SIT_KNEE_BEND * k)
        add(HumanoidBone.FOOT_L, rx = -8f * k)
        add(HumanoidBone.FOOT_R, rx = -8f * k)
        // 双臂自然放到身前桌面上
        add(HumanoidBone.UPPER_ARM_L, rz = -12f * k, ry = 10f * k)
        add(HumanoidBone.UPPER_ARM_R, rz = 12f * k, ry = -10f * k)
        add(HumanoidBone.LOWER_ARM_L, ry = 22f * k)
        add(HumanoidBone.LOWER_ARM_R, ry = -22f * k)
        // 双腿自然悬垂交替轻摆（休闲律动）
        val legSwingL = sin(hairPhase * 1.5f) * 3.6f * k
        val legSwingR = sin(hairPhase * 1.5f + PI.toFloat() * 0.85f) * 3.0f * k
        add(HumanoidBone.LOWER_LEG_L, rx = legSwingL)
        add(HumanoidBone.LOWER_LEG_R, rx = legSwingR)
        add(HumanoidBone.FOOT_L, rx = -8f * k + cos(hairPhase * 1.5f) * 2.0f * k)
        add(HumanoidBone.FOOT_R, rx = -8f * k + cos(hairPhase * 1.5f + PI.toFloat() * 0.85f) * 1.6f * k)
    }

    // ============================================================ 动作层

    private fun applyAction(input: AnimationInput): Boolean {
        val action = input.action
        if (action == ActionType.IDLE) { actionProgress = 0f; return false }
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
            ActionType.SLEEP -> applySleep()
            ActionType.SMUG -> applySmug(p)
            ActionType.SIT -> Unit
            ActionType.IDLE -> Unit
        }
        return true
    }

    /** 挥手：右臂抬起 → 前臂柔和自然摆动 → 平滑复位 */
    private fun applyWave(p: Float) {
        val raise = envelope(p, 0.22f, 0.78f)
        if (raise <= 0f) return
        val wave = sin(p * 2f * PI.toFloat() * 2.8f) * raise
        // 右臂从自然垂落 (rz=-72) 抬到上方 (rz≈+83)：增量 +155，手腕协同微动
        add(HumanoidBone.UPPER_ARM_R, rz = 155f * raise, ry = -10f * raise)
        add(HumanoidBone.LOWER_ARM_R, ry = (-38f + wave * 18f) * raise)
        add(HumanoidBone.HAND_R, rz = wave * 12f * raise, ry = wave * 6f * raise)
        add(HumanoidBone.HEAD, rz = -4.0f * raise, ry = -5f * raise, rx = -2f * raise)
        add(HumanoidBone.UPPER_ARM_L, rz = -4f * raise)
    }

    /** 点头：两次自然点头（头部+颈部协同，带略微低头再回正） */
    private fun applyNod(p: Float) {
        val env = bell(p)
        val nod = sin(p * 2f * PI.toFloat() * 2.1f)
        add(HumanoidBone.HEAD, rx = (9f + nod * 8f) * env)
        add(HumanoidBone.NECK, rx = (4f + nod * 3f) * env)
    }

    /** 摇头：左右各摆两次 */
    private fun applyShakeHead(p: Float) {
        val env = bell(p)
        val shake = sin(p * 2f * PI.toFloat() * 2.2f)
        add(HumanoidBone.HEAD, ry = shake * 17f * env)
        add(HumanoidBone.NECK, ry = shake * 6f * env)
        add(HumanoidBone.CHEST, ry = shake * 2f * env)
    }

    /** 鞠躬：脊柱链前倾 + 手臂后摆 + 回正 */
    private fun applyBow(p: Float) {
        val env = envelope(p, 0.28f, 0.72f)
        if (env <= 0f) return
        add(HumanoidBone.SPINE, rx = 14f * env)
        add(HumanoidBone.CHEST, rx = 16f * env)
        add(HumanoidBone.NECK, rx = 9f * env)
        add(HumanoidBone.HEAD, rx = 7f * env)
        add(HumanoidBone.UPPER_ARM_L, rx = 10f * env, rz = -3f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = 10f * env, rz = 3f * env)
        add(HumanoidBone.UPPER_LEG_L, rx = -2f * env)
        add(HumanoidBone.UPPER_LEG_R, rx = -2f * env)
    }

    /** 跳跃：蓄力下蹲 → 腾空伸展 → 落地缓冲 */
    private fun applyJump(p: Float) {
        // 关键帧：(进度, 骨盆高度, 屈腿量, 举臂量)
        val hipsY: Float
        val legBend: Float
        val armsUp: Float
        when {
            p < 0.22f -> {
                val k = smoothIn(p / 0.22f)
                hipsY = -0.10f * k; legBend = k; armsUp = -0.15f * k
            }
            p < 0.50f -> {
                val k = smoothIn((p - 0.22f) / 0.28f)
                hipsY = -0.10f + 0.38f * k; legBend = 1f - k; armsUp = -0.15f + 1.15f * k
            }
            p < 0.66f -> {
                val k = (p - 0.50f) / 0.16f
                hipsY = 0.28f * (1f - k) * (1f - k); legBend = k * 0.35f; armsUp = 1.0f * (1f - k)
            }
            p < 0.82f -> {
                val k = smoothIn((p - 0.66f) / 0.16f)
                hipsY = -0.075f * k; legBend = 0.35f + 0.65f * k; armsUp = 0.25f * (1f - k)
            }
            else -> {
                val k = 1f - smoothIn((p - 0.82f) / 0.18f)
                hipsY = -0.075f * k; legBend = k; armsUp = 0f
            }
        }
        hipsOffsetY += hipsY
        airborne = (hipsY / 0.28f).coerceIn(0f, 1f)
        add(HumanoidBone.UPPER_LEG_L, rx = -30f * legBend, rz = -2f * legBend)
        add(HumanoidBone.UPPER_LEG_R, rx = -30f * legBend, rz = 2f * legBend)
        add(HumanoidBone.LOWER_LEG_L, rx = 46f * legBend)
        add(HumanoidBone.LOWER_LEG_R, rx = 46f * legBend)
        add(HumanoidBone.FOOT_L, rx = -12f * legBend)
        add(HumanoidBone.FOOT_R, rx = -12f * legBend)
        // 手臂：蓄力后摆 → 腾空上举
        add(HumanoidBone.UPPER_ARM_L, rz = -78f * armsUp, ry = 10f * armsUp)
        add(HumanoidBone.UPPER_ARM_R, rz = 78f * armsUp, ry = -10f * armsUp)
        add(HumanoidBone.UPPER_ARM_L, rx = 14f * (1f - legBend) * (1f - armsUp))
        add(HumanoidBone.UPPER_ARM_R, rx = 14f * (1f - legBend) * (1f - armsUp))
        add(HumanoidBone.HEAD, rx = -4f * armsUp)
    }

    /** 傲娇噘嘴：扭头 + 下巴微抬 + 双臂抱胸 */
    private fun applyPout(p: Float) {
        val env = envelope(p, 0.18f, 0.78f)
        if (env <= 0f) return
        add(HumanoidBone.HEAD, ry = 16f * env, rx = -6f * env, rz = 5f * env)
        add(HumanoidBone.NECK, ry = 7f * env, rx = -2f * env)
        add(HumanoidBone.CHEST, ry = 3f * env)
        // 双臂抱胸：肩前屈 + 肘部大幅弯曲
        add(HumanoidBone.UPPER_ARM_L, ry = 38f * env, rz = -6f * env)
        add(HumanoidBone.UPPER_ARM_R, ry = -38f * env, rz = 6f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = 96f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = -96f * env)
    }

    /** 害羞：双手捂脸 + 低头 + 轻微晃动 */
    private fun applyBlush(p: Float) {
        val env = envelope(p, 0.22f, 0.74f)
        if (env <= 0f) return
        val tremble = sin(p * 2f * PI.toFloat() * 3.2f) * 1.6f * env
        add(HumanoidBone.HEAD, rx = 10f * env, rz = 4f * env, ry = tremble)
        add(HumanoidBone.NECK, rx = 3f * env)
        // 双臂的 rx 是沿各自上臂轴（左右分别为 ±X）的轴向扭转：左右臂要转到同一世界方向，
        // 旋转角的符号必须相同；若写成 ±60 的镜像符号，只有一只手能抬到脸前，另一只停在胸前。
        add(HumanoidBone.UPPER_ARM_L, rx = -60f * env, rz = 2f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = -60f * env, rz = -2f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = 140f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = -140f * env)
        add(HumanoidBone.UPPER_LEG_L, rz = -1.5f * env)
        add(HumanoidBone.UPPER_LEG_R, rz = 1.5f * env)
    }

    /** 打哈欠：仰头张嘴 + 右手掩口 + 伸懒腰 */
    private fun applyYawn(p: Float) {
        val env = envelope(p, 0.28f, 0.62f)
        if (env <= 0f) return
        add(HumanoidBone.HEAD, rx = -16f * env, ry = -3f * env)
        add(HumanoidBone.NECK, rx = -8f * env)
        add(HumanoidBone.CHEST, rx = -4f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = -60f * env, ry = -40f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = -120f * env)
        add(HumanoidBone.UPPER_ARM_L, rz = -14f * env, ry = 8f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = 10f * env)
    }

    /** 打瞌睡：头低垂 + 身体前倾 + 呼吸变慢（保持型） */
    private fun applySleep() {
        add(HumanoidBone.HEAD, rx = 18f, rz = 6f, ry = -3f)
        add(HumanoidBone.NECK, rx = 10f)
        add(HumanoidBone.CHEST, rx = 4.5f)
        add(HumanoidBone.SPINE, rx = 2f)
        add(HumanoidBone.UPPER_ARM_L, rz = 3f, ry = 2f)
        add(HumanoidBone.UPPER_ARM_R, rz = -3f, ry = -2f)
    }

    /** 得意：挺胸抬头 + 双手叉腰 */
    private fun applySmug(p: Float) {
        val env = envelope(p, 0.22f, 0.8f)
        if (env <= 0f) return
        add(HumanoidBone.CHEST, rx = -6f * env)
        add(HumanoidBone.HEAD, rx = -6f * env, rz = -2f * env)
        // 同 applyBlush：上臂轴向扭转 rx 左右同号（ry/rz 的镜像才是反号），双手才会对称落在腰侧。
        add(HumanoidBone.UPPER_ARM_L, rx = 30f * env, ry = 20f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = 30f * env, ry = -20f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = 40f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = -40f * env)
    }

    // ============================================================ 触摸反应层

    private fun applyTouchReaction(input: AnimationInput) {
        val part = input.touchReaction ?: return
        val elapsed = input.touchElapsed
        if (elapsed < 0f || elapsed > TOUCH_DURATION) return
        val p = (elapsed / TOUCH_DURATION).coerceIn(0f, 1f)
        val env = envelope(p, 0.16f, 0.8f)
        if (env <= 0f) return
        when (part) {
            TouchPart.HEAD -> {
                // 摸头：闭眼享受、头微微低垂并偏向手的方向
                val purr = sin(p * 2f * PI.toFloat() * 2.4f) * 1.8f * env
                add(HumanoidBone.HEAD, rx = 7f * env, rz = 8f * env, ry = purr)
                add(HumanoidBone.NECK, rx = 3f * env, rz = 3f * env)
                add(HumanoidBone.CHEST, rz = 2f * env)
            }
            TouchPart.CHEEK -> {
                // 戳脸：扭头噘嘴抗议 + 身体轻躲
                add(HumanoidBone.HEAD, ry = 14f * env, rx = -4f * env, rz = 3f * env)
                add(HumanoidBone.NECK, ry = 6f * env)
                add(HumanoidBone.CHEST, ry = -4f * env)
            }
            TouchPart.HAND -> {
                // 牵手：低头看向手 + 手臂微微抬起靠近镜头
                add(HumanoidBone.HEAD, rx = 6f * env, ry = -5f * env)
                add(HumanoidBone.UPPER_ARM_R, ry = -26f * env, rz = 14f * env)
                add(HumanoidBone.LOWER_ARM_R, ry = -42f * env)
                add(HumanoidBone.CHEST, rx = 1.5f * env)
            }
            TouchPart.BODY -> {
                // 拥抱：双臂环抱到胸前 + 低头害羞
                add(HumanoidBone.HEAD, rx = 8f * env, rz = 4f * env)
                add(HumanoidBone.UPPER_ARM_L, ry = 52f * env)
                add(HumanoidBone.UPPER_ARM_R, ry = -52f * env)
                add(HumanoidBone.LOWER_ARM_L, ry = 74f * env)
                add(HumanoidBone.LOWER_ARM_R, ry = -74f * env)
                add(HumanoidBone.CHEST, rx = 2f * env)
            }
        }
    }

    // ============================================================ 面部 / 眨眼 / 口型

    private fun updateFace(dt: Float, input: AnimationInput) {
        faceEmotion = input.emotion
        // 动作 / 触摸反应优先覆盖表情（结束后回到服务端下发的持久表情）
        val expressionOverride = expressionForAction(input)
        faceExpression = expressionOverride ?: input.expression

        // 眨眼：随机 2.2~6s 一次，单次 ~150ms；睡觉时半闭/全闭
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
            input.action == ActionType.SLEEP -> 0.92f
            sleepy -> 0.52f
            else -> blink
        }
        blinkAmount = eyesClosed

        // 口型：真实音频振幅平滑，避免嘴部高频抖动
        val mouthTarget = if (input.speaking) input.lipSync.coerceIn(0f, 1f) else 0f
        val k = min(1f, dt * 20f)
        smoothMouth += (mouthTarget - smoothMouth) * k
        // 打哈欠时强制张嘴
        mouthOpen = when {
            input.action == ActionType.YAWN -> maxOf(smoothMouth, yawnMouth(input.actionElapsed))
            else -> smoothMouth
        }
    }

    private fun yawnMouth(elapsed: Float): Float {
        val p = (elapsed / ActionTiming.durationOf(ActionType.YAWN)).coerceIn(0f, 1f)
        return envelope(p, 0.28f, 0.62f) * 0.95f
    }

    private fun expressionForAction(input: AnimationInput): String? {
        val touch = input.touchReaction
        if (touch != null && input.touchElapsed in 0f..TOUCH_DURATION) {
            return when (touch) {
                TouchPart.HEAD -> "SMILE"
                TouchPart.CHEEK -> "POUT"
                TouchPart.HAND -> "SMILE"
                TouchPart.BODY -> "BLUSH_FACE"
            }
        }
        val action = input.action
        val elapsed = input.actionElapsed
        val duration = ActionTiming.durationOf(action)
        val inAction = action != ActionType.IDLE && (duration <= 0f || elapsed <= duration + 0.05f)
        if (!inAction) return null
        return when (action) {
            ActionType.WAVE -> "SMILE"
            ActionType.NOD -> "SMILE"
            ActionType.SHAKE_HEAD -> "POUT"
            ActionType.BOW -> "SMILE"
            ActionType.JUMP -> "SMILE"
            ActionType.POUT -> "POUT"
            ActionType.BLUSH -> "BLUSH_FACE"
            ActionType.YAWN -> "SLEEPY"
            ActionType.SLEEP -> "SLEEPY"
            ActionType.SMUG -> "SMUG"
            ActionType.SIT -> null
            else -> null
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

    /** 进场 → 保持 → 退场的动作包络 */
    private fun envelope(p: Float, inEnd: Float, outStart: Float): Float {
        val v = p.coerceIn(0f, 1f)
        val up = if (v < inEnd) smoothIn(v / inEnd) else 1f
        val down = if (v > outStart) 1f - smoothIn((v - outStart) / (1f - outStart).coerceAtLeast(bigEpsilon())) else 1f
        return (up * down).coerceIn(0f, 1f)
    }

    private fun bigEpsilon(): Float = 0.0001f

    /** 0..1 的确定性噪声（眨眼间隔抖动，不引入 Random 便于单测复现） */
    private fun timeNoise(t: Float): Float {
        val s = sin(t * 12.9898f) * 43758.5453f
        return s - kotlin.math.floor(s)
    }
}
