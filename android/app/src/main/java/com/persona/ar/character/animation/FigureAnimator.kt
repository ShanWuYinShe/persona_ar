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
        /** 桌面优雅曲腿坐姿：骨盆下沉至 0.40m 使脚尖刚好贴合桌面 (y ≈ 0)，双手自然搭膝 */
        private const val SIT_HIPS_DOWN = 0.40f
        private const val SIT_HIPS_FORWARD = 0.02f
        private const val SIT_THIGH_FLEX = 84f
        private const val SIT_KNEE_BEND = -86f
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
    /** 当前坐姿混合权重 0..1（供外部场景与拾取平滑插值） */
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
        hipsOffsetX = 0f; hipsOffsetY = 0f; hipsOffsetZ = 0f
        airborne = 0f

        // 1) 体态层：站立 ↔ 桌面边缘垂腿坐姿（弹簧平滑）
        val sitTarget = if (input.stance == SkeletonStance.SITTING) 1f else 0f
        sitBlend += (sitTarget - sitBlend) * min(1f, dt * BLEND_RATE * 0.55f)

        // 2) 呼吸与待机微动
        applyIdle(t, input)
        // 3) 情绪体态
        applyEmotion(input.emotion)
        // 4) 坐姿：当手臂动作（挥手/抱胸/害羞/得意/打哈欠/鞠躬/触摸）激活时，平滑让出搭膝手臂避免角度叠加过屈
        val hasTouchReaction = input.touchReaction != null && input.touchElapsed in 0f..TOUCH_DURATION
        val sitArmKeep = computeSitArmKeepFactor(input, hasTouchReaction)
        if (sitBlend > 0.001f) applySitting(sitBlend, sitArmKeep)
        // 5) 动作层 (若当前正在播放专属触摸反应，动作层暂避，防止骨骼角度叠加产生关节过屈)
        actionActive = if (hasTouchReaction) false else applyAction(input)
        // 6) 触摸反应层
        applyTouchReaction(input)
        // 7) 面部表情 / 眨眼 / 口型
        updateFace(dt, input)
        // 8) 头发惯性与手指自然弯曲
        applyFingerCurl()
        applyHairSecondary(dt, t, input)
    }

    private fun computeSitArmKeepFactor(input: AnimationInput, hasTouchReaction: Boolean): Float {
        if (hasTouchReaction) {
            return when (input.touchReaction) {
                TouchPart.CHEEK, TouchPart.HAND, TouchPart.BODY -> {
                    val p = (input.touchElapsed / TOUCH_DURATION).coerceIn(0f, 1f)
                    1f - envelope(p, 0.14f, 0.82f)
                }
                else -> 1f
            }
        }
        val action = input.action
        val duration = ActionTiming.durationOf(action)
        if (action == ActionType.IDLE || action == ActionType.SIT || action == ActionType.NOD ||
            action == ActionType.SHAKE_HEAD || action == ActionType.SLEEP) {
            return 1f
        }
        if (duration <= 0f || input.actionElapsed < 0f || input.actionElapsed > duration + 0.05f) return 1f
        val p = (input.actionElapsed / duration).coerceIn(0f, 1f)
        return (1f - envelope(p, 0.16f, 0.82f)).coerceIn(0f, 1f)
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
        // 自然垂臂：VRM 模型正面朝 -Z；左手肘屈曲向前在右手坐标系为 ry < 0，右手为 ry > 0
        add(HumanoidBone.UPPER_ARM_L, rz = 70f)
        add(HumanoidBone.UPPER_ARM_R, rz = -70f)
        add(HumanoidBone.LOWER_ARM_L, ry = -14f)
        add(HumanoidBone.LOWER_ARM_R, ry = 14f)

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

        // 头部注视：动态 3D 镜头追焦；在 -Z 为正面的右手系中，抬头为 rx > 0、低头为 rx < 0
        val microGazeYaw = sin(t * 0.42f) * 2.0f + sin(t * 0.85f) * 0.8f
        val targetGazeYaw = input.gazeX.coerceIn(-1f, 1f) * 20.0f
        val targetNeckYaw = input.gazeX.coerceIn(-1f, 1f) * 9.0f

        val targetGazePitch = input.gazeY.coerceIn(-1f, 1f) * 14.0f
        val targetNeckPitch = input.gazeY.coerceIn(-1f, 1f) * 6.0f

        val headYaw = microGazeYaw + targetGazeYaw
        val neckYaw = (microGazeYaw * 0.3f) + targetNeckYaw
        val headPitch = sin(t * 0.55f) * 1.2f + targetGazePitch - smoothMouth * 2.6f
        val neckPitch = targetNeckPitch + (sin(t * 0.55f) * 0.4f)
        val headRoll = sin(t * 0.31f) * 1.2f - (input.gazeX.coerceIn(-1f, 1f) * 2.5f)

        add(HumanoidBone.HEAD, rx = headPitch, ry = headYaw, rz = headRoll)
        add(HumanoidBone.NECK, rx = neckPitch, ry = neckYaw)

        // 手臂随呼吸与重心转移自然轻摆（左右相位差异）
        add(HumanoidBone.UPPER_ARM_L, rz = sin(t * 0.41f) * 1.5f, ry = -breath * 0.6f)
        add(HumanoidBone.UPPER_ARM_R, rz = -sin(t * 0.37f + 0.8f) * 1.5f, ry = breath2 * 0.6f)
        add(HumanoidBone.LOWER_ARM_L, ry = -sin(t * 0.33f + 0.4f) * 1.8f)
        add(HumanoidBone.LOWER_ARM_R, ry = sin(t * 0.31f + 1.1f) * 1.8f)

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
                add(HumanoidBone.CHEST, rx = 1.6f)
                add(HumanoidBone.HEAD, rx = 1.6f, rz = 1.4f)
            }
            EmotionType.SHY -> {
                add(HumanoidBone.HEAD, rx = -5.5f, ry = -4f, rz = 2.8f)
                add(HumanoidBone.CHEST, rx = -1.8f)
                add(HumanoidBone.UPPER_ARM_L, ry = -4f)
                add(HumanoidBone.UPPER_ARM_R, ry = 4f)
                add(HumanoidBone.LOWER_ARM_L, ry = -12f)
                add(HumanoidBone.LOWER_ARM_R, ry = 12f)
            }
            EmotionType.SAD -> {
                add(HumanoidBone.HEAD, rx = -7.5f, rz = -2.0f)
                add(HumanoidBone.CHEST, rx = -4.5f)
                add(HumanoidBone.UPPER_ARM_L, rz = 3f)
                add(HumanoidBone.UPPER_ARM_R, rz = -3f)
            }
            EmotionType.SURPRISED -> {
                add(HumanoidBone.HEAD, rx = 4.5f)
                add(HumanoidBone.CHEST, rx = 1.8f)
                add(HumanoidBone.UPPER_ARM_L, rz = -4f)
                add(HumanoidBone.UPPER_ARM_R, rz = 4f)
            }
            EmotionType.THINKING -> {
                add(HumanoidBone.HEAD, rx = 2.5f, ry = 6f, rz = 4.5f)
                add(HumanoidBone.NECK, ry = 2.5f)
            }
            EmotionType.ANGRY -> {
                add(HumanoidBone.HEAD, rx = -3.8f)
                add(HumanoidBone.CHEST, rx = 2.4f)
                add(HumanoidBone.UPPER_ARM_L, ry = -5f)
                add(HumanoidBone.UPPER_ARM_R, ry = 5f)
            }
            EmotionType.SLEEPY -> {
                add(HumanoidBone.HEAD, rx = -8.0f, rz = 3.5f)
                add(HumanoidBone.NECK, rx = -4.5f)
                add(HumanoidBone.CHEST, rx = -2.5f)
            }
            EmotionType.CONFIDENT -> {
                add(HumanoidBone.CHEST, rx = 3.2f)
                add(HumanoidBone.HEAD, rx = 2.8f, rz = -2.0f)
            }
            EmotionType.NEUTRAL -> Unit
        }
    }

    // ============================================================ 坐姿层

    private fun applySitting(k: Float, armKeepFactor: Float) {
        // 跳跃动作脱离地面时动态放松坐姿骨盆锁
        val legScale = k * (1f - airborne * 0.85f).coerceIn(0.15f, 1f)
        hipsOffsetY -= SIT_HIPS_DOWN * legScale
        hipsOffsetZ += SIT_HIPS_FORWARD * legScale
        add(HumanoidBone.HIPS, rx = 4f * k)
        add(HumanoidBone.SPINE, rx = -6f * k)
        add(HumanoidBone.CHEST, rx = 4f * k)

        // 少女萌系屈腿坐姿（以 figure.glb -Z 为前方验证：大腿 rx > 0 向前平抬，小腿 rx < 0 向下垂直）
        add(HumanoidBone.UPPER_LEG_L, rx = SIT_THIGH_FLEX * legScale, ry = 3.5f * legScale, rz = 5.0f * legScale)
        add(HumanoidBone.UPPER_LEG_R, rx = SIT_THIGH_FLEX * legScale, ry = -3.5f * legScale, rz = -5.0f * legScale)
        add(HumanoidBone.LOWER_LEG_L, rx = SIT_KNEE_BEND * legScale, rz = -4.0f * legScale)
        add(HumanoidBone.LOWER_LEG_R, rx = SIT_KNEE_BEND * legScale, rz = 4.0f * legScale)
        add(HumanoidBone.FOOT_L, rx = -14f * legScale)
        add(HumanoidBone.FOOT_R, rx = -14f * legScale)

        // 双手自然搭在膝上（(x=∓0.12, y=0.61, z=-0.20)）；播放上肢演出动画时让出肘肩
        val ak = k * armKeepFactor
        if (ak > 0.001f) {
            add(HumanoidBone.UPPER_ARM_L, ry = -42f * ak, rz = -6f * ak)
            add(HumanoidBone.UPPER_ARM_R, ry = 42f * ak, rz = 6f * ak)
            add(HumanoidBone.LOWER_ARM_L, ry = -27f * ak)
            add(HumanoidBone.LOWER_ARM_R, ry = 27f * ak)
            add(HumanoidBone.HAND_L, rz = 6f * ak, ry = -8f * ak)
            add(HumanoidBone.HAND_R, rz = -6f * ak, ry = 8f * ak)
        }

        // 头部轻柔侧倾，眼神灵动
        add(HumanoidBone.HEAD, rz = -3.5f * k, rx = -2.5f * k)
        add(HumanoidBone.NECK, rz = -1.5f * k)
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

    /** 挥手：右臂高高举起朝向镜头外侧轻快挥摆，左手自然轻扶裙侧 */
    private fun applyWave(p: Float) {
        val raise = envelope(p, 0.16f, 0.84f)
        if (raise <= 0f) return
        val wave = sin(p * 2f * PI.toFloat() * 2.8f) * raise
        add(HumanoidBone.SPINE, rx = -2f * raise, rz = -4.5f * raise)
        add(HumanoidBone.CHEST, rz = -3.0f * raise)
        add(HumanoidBone.HIPS, rz = 3.0f * raise)
        add(HumanoidBone.HEAD, rx = 3.0f * raise, ry = 6.0f * raise, rz = 8.0f * raise)
        add(HumanoidBone.NECK, rz = 4.0f * raise)
        // 右臂高举向前略迎（总 rz ≈ +108° 高出头顶）
        add(HumanoidBone.UPPER_ARM_R, rx = 12f * raise, ry = 16f * raise, rz = 178f * raise)
        add(HumanoidBone.LOWER_ARM_R, ry = (36f + wave * 24f) * raise, rx = -6f * raise)
        add(HumanoidBone.HAND_R, rz = wave * 22f * raise, ry = -wave * 10f * raise)
        // 左手微展轻扶身侧
        add(HumanoidBone.UPPER_ARM_L, rx = 4f * raise, ry = -14f * raise, rz = -8f * raise)
        add(HumanoidBone.LOWER_ARM_L, ry = -24f * raise)
    }

    /** 点头：明快肯定二连点头（rx < 0 前倾低头） */
    private fun applyNod(p: Float) {
        val env = bell(p)
        val nodPhase = p * 2f * PI.toFloat() * 2.0f
        val nod = (sin(nodPhase) * 0.75f + sin(nodPhase * 0.5f) * 0.25f)
        add(HumanoidBone.HEAD, rx = (-13f - nod * 12f) * env, rz = sin(p * PI.toFloat()) * 2.5f)
        add(HumanoidBone.NECK, rx = (-6f - nod * 5f) * env)
        add(HumanoidBone.CHEST, rx = -3.5f * env)
        add(HumanoidBone.UPPER_ARM_L, ry = -8f * env, rz = -4f * env)
        add(HumanoidBone.UPPER_ARM_R, ry = 8f * env, rz = 4f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = -14f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = 14f * env)
    }

    /** 摇头：双手身前摆手否认 + 轻快左右摆头 */
    private fun applyShakeHead(p: Float) {
        val env = bell(p)
        val shake = sin(p * 2f * PI.toFloat() * 2.2f)
        add(HumanoidBone.HEAD, ry = shake * 22f * env, rz = -shake * 5f * env)
        add(HumanoidBone.NECK, ry = shake * 9f * env)
        add(HumanoidBone.CHEST, ry = shake * 3.5f * env)
        // 双手抬在胸前摆手
        add(HumanoidBone.UPPER_ARM_L, rx = 12f * env, ry = -28f * env, rz = -10f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = 12f * env, ry = 28f * env, rz = 10f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = (-60f + shake * 12f) * env)
        add(HumanoidBone.LOWER_ARM_R, ry = (60f + shake * 12f) * env)
    }

    /** 鞠躬：脊柱与头端正前倾礼貌鞠躬，双手合敛于裙前 (-Z) */
    private fun applyBow(p: Float) {
        val env = envelope(p, 0.22f, 0.78f)
        if (env <= 0f) return
        val standOnly = (1f - sitBlend).coerceIn(0f, 1f)
        add(HumanoidBone.SPINE, rx = -20f * env)
        add(HumanoidBone.CHEST, rx = -16f * env)
        add(HumanoidBone.NECK, rx = -10f * env)
        add(HumanoidBone.HEAD, rx = -8f * env)
        add(HumanoidBone.UPPER_ARM_L, rx = 24f * env, ry = -42f * env, rz = 9f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = 24f * env, ry = 42f * env, rz = -9f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = -15f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = 15f * env)
        add(HumanoidBone.UPPER_LEG_L, rx = 4f * env * standOnly)
        add(HumanoidBone.UPPER_LEG_R, rx = 4f * env * standOnly)
    }

    /** 跳跃：蓄力屈膝 → 展翅欢呼腾空 → 平稳落地缓冲 */
    private fun applyJump(p: Float) {
        val hipsY: Float
        val legBend: Float
        val armsUp: Float
        when {
            p < 0.20f -> {
                val k = smoothIn(p / 0.20f)
                hipsY = -0.10f * k; legBend = k; armsUp = -0.16f * k
            }
            p < 0.48f -> {
                val k = smoothIn((p - 0.20f) / 0.28f)
                hipsY = -0.10f + 0.38f * k; legBend = 1f - k * 0.55f; armsUp = -0.16f + 1.18f * k
            }
            p < 0.65f -> {
                val k = (p - 0.48f) / 0.17f
                hipsY = 0.28f * (1f - k) * (1f - k); legBend = 0.45f * (1f - k) + k * 0.5f; armsUp = 1.02f * (1f - k)
            }
            p < 0.82f -> {
                val k = smoothIn((p - 0.65f) / 0.17f)
                hipsY = -0.08f * k; legBend = 0.5f + 0.5f * k; armsUp = 0.22f * (1f - k)
            }
            else -> {
                val k = 1f - smoothIn((p - 0.82f) / 0.18f)
                hipsY = -0.08f * k; legBend = k; armsUp = 0f
            }
        }
        val standOnly = (1f - sitBlend).coerceIn(0f, 1f)
        hipsOffsetY += hipsY
        airborne = (hipsY / 0.28f).coerceIn(0f, 1f)
        add(HumanoidBone.UPPER_LEG_L, rx = 34f * legBend * standOnly, rz = -3f * legBend * standOnly)
        add(HumanoidBone.UPPER_LEG_R, rx = 34f * legBend * standOnly, rz = 3f * legBend * standOnly)
        add(HumanoidBone.LOWER_LEG_L, rx = -54f * legBend * standOnly)
        add(HumanoidBone.LOWER_LEG_R, rx = -54f * legBend * standOnly)
        add(HumanoidBone.FOOT_L, rx = 16f * legBend * standOnly)
        add(HumanoidBone.FOOT_R, rx = 16f * legBend * standOnly)
        add(HumanoidBone.UPPER_ARM_L, rz = -140f * armsUp, ry = -14f * armsUp)
        add(HumanoidBone.UPPER_ARM_R, rz = 140f * armsUp, ry = 14f * armsUp)
        add(HumanoidBone.HEAD, rx = 8f * armsUp)
    }

    /** 傲娇生闷气：躯干微转 + 双臂交叉环抱于胸前 + 骄傲偏头歪睨 */
    private fun applyPout(p: Float) {
        val env = envelope(p, 0.14f, 0.84f)
        if (env <= 0f) return
        val standOnly = (1f - sitBlend).coerceIn(0f, 1f)
        add(HumanoidBone.HIPS, ry = 12f * env)
        add(HumanoidBone.SPINE, rx = 3f * env, ry = 8f * env)
        add(HumanoidBone.CHEST, rx = 4f * env, ry = 6f * env)
        add(HumanoidBone.HEAD, rx = 8f * env, ry = -22f * env, rz = -7f * env)
        add(HumanoidBone.NECK, rx = 3f * env, ry = -10f * env)
        // figure.glb FK 验证：左前臂横过胸前 (-Z)，右前臂轻叠外侧，零背部穿模
        add(HumanoidBone.UPPER_ARM_L, rx = -33f * env, ry = -84f * env, rz = -63f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = -30f * env, ry = 84f * env, rz = 60f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = -84f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = 88f * env)
        add(HumanoidBone.UPPER_LEG_L, rx = 4f * env * standOnly, rz = -3f * env * standOnly)
        add(HumanoidBone.UPPER_LEG_R, rx = -6f * env * standOnly, rz = 3f * env * standOnly)
    }

    /** 害羞娇颤：双手捧脸护颊于身前 (-Z) + 羞怯轻抖 + 站姿内八屈膝 */
    private fun applyBlush(p: Float) {
        val env = envelope(p, 0.18f, 0.80f)
        if (env <= 0f) return
        val standOnly = (1f - sitBlend).coerceIn(0f, 1f)
        val tremble = sin(p * 2f * PI.toFloat() * 4.0f) * 2.2f * env
        add(HumanoidBone.HEAD, rx = -10f * env, ry = (-4f * env) + tremble, rz = 5f * env)
        add(HumanoidBone.NECK, rx = -4f * env, ry = -2f * env, rz = 2f * env)
        add(HumanoidBone.CHEST, rx = -3f * env)
        // figure.glb FK 验证：双手落在脸颊前方 (∓0.10, 1.39, -0.16)，肘部位于身前
        add(HumanoidBone.UPPER_ARM_L, rx = 39f * env, ry = -9f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = 39f * env, ry = 9f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = -118f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = 118f * env)
        // 仅在站立时内八字屈膝，避免破坏坐姿小腿曲度
        add(HumanoidBone.UPPER_LEG_L, rz = 4.5f * env * standOnly, rx = 6f * env * standOnly)
        add(HumanoidBone.UPPER_LEG_R, rz = -4.5f * env * standOnly, rx = 6f * env * standOnly)
        add(HumanoidBone.LOWER_LEG_L, rx = -8f * env * standOnly)
        add(HumanoidBone.LOWER_LEG_R, rx = -8f * env * standOnly)
    }

    /** 打哈欠：慵懒仰头伸腰 + 右手轻轻掩在唇前 */
    private fun applyYawn(p: Float) {
        val env = envelope(p, 0.24f, 0.66f)
        if (env <= 0f) return
        add(HumanoidBone.SPINE, rx = 6f * env)
        add(HumanoidBone.CHEST, rx = 8f * env)
        add(HumanoidBone.NECK, rx = 10f * env)
        add(HumanoidBone.HEAD, rx = 14f * env, ry = -4f * env)
        // 右手稳稳举至唇前 (x ≈ 0, y ≈ 1.39, z ≈ -0.17)
        add(HumanoidBone.UPPER_ARM_R, rx = 42f * env, ry = 36f * env, rz = -12f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = 87f * env)
        // 左手微展慵懒伸展
        add(HumanoidBone.UPPER_ARM_L, ry = -10f * env, rz = -14f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = -14f * env)
    }

    /** 困倦瞌睡：头部重重前垂打盹（rx < 0 向前垂落） + 缓慢呼吸 */
    private fun applySleep() {
        add(HumanoidBone.HEAD, rx = -22f, rz = 7f, ry = -4f)
        add(HumanoidBone.NECK, rx = -12f)
        add(HumanoidBone.CHEST, rx = -6f)
        add(HumanoidBone.SPINE, rx = -3.5f)
        add(HumanoidBone.UPPER_ARM_L, ry = -8f, rz = -4f)
        add(HumanoidBone.UPPER_ARM_R, ry = 8f, rz = 4f)
        add(HumanoidBone.LOWER_ARM_L, ry = -14f)
        add(HumanoidBone.LOWER_ARM_R, ry = 14f)
    }

    /** 嚣张得意：挺胸仰首 + 双手经典大弧度叉腰（手肘向两侧张开，双拳搭腰胯） */
    private fun applySmug(p: Float) {
        val env = envelope(p, 0.16f, 0.84f)
        if (env <= 0f) return
        add(HumanoidBone.HIPS, ry = -4f * env, rz = 5f * env)
        add(HumanoidBone.SPINE, rx = 8f * env, rz = -3f * env)
        add(HumanoidBone.CHEST, rx = 9f * env)
        add(HumanoidBone.HEAD, rx = 8f * env, ry = 4f * env, rz = -5f * env)
        // figure.glb FK 验证：肘部大弧度外展 (±0.264, 1.160)，双手精准叉在腰间 (±0.136, 0.995)
        add(HumanoidBone.UPPER_ARM_L, rx = -87f * env, ry = -48f * env, rz = -66f * env)
        add(HumanoidBone.UPPER_ARM_R, rx = -87f * env, ry = 48f * env, rz = 66f * env)
        add(HumanoidBone.LOWER_ARM_L, ry = -57f * env)
        add(HumanoidBone.LOWER_ARM_R, ry = 57f * env)
    }

    // ============================================================ 触摸反应层

    private fun applyTouchReaction(input: AnimationInput) {
        val part = input.touchReaction ?: return
        val elapsed = input.touchElapsed
        if (elapsed < 0f || elapsed > TOUCH_DURATION) return
        val p = (elapsed / TOUCH_DURATION).coerceIn(0f, 1f)
        val env = envelope(p, 0.14f, 0.82f)
        if (env <= 0f) return
        when (part) {
            TouchPart.HEAD -> {
                // 摸头：眯眼低顺蹭蹭依偎 + 双肩微耸（HEAD rz > 2 与 |rx| > 2）
                val purr = sin(p * 2f * PI.toFloat() * 2.4f) * 2.2f * env
                add(HumanoidBone.HEAD, rx = -6f * env, rz = 11f * env, ry = purr)
                add(HumanoidBone.NECK, rx = -3f * env, rz = 4.5f * env)
                add(HumanoidBone.CHEST, rz = 3.0f * env, rx = sin(p * 2f * PI.toFloat() * 2.0f) * 1.4f * env)
                add(HumanoidBone.UPPER_ARM_L, rz = -5f * env)
                add(HumanoidBone.UPPER_ARM_R, rz = 5f * env)
            }
            TouchPart.CHEEK -> {
                // 戳脸：歪头轻躲 + 右手举至颊旁轻捂护脸
                add(HumanoidBone.HEAD, rx = -6f * env, ry = 10f * env, rz = -12f * env)
                add(HumanoidBone.NECK, rx = -2f * env, ry = 4f * env, rz = -5f * env)
                add(HumanoidBone.CHEST, rx = -2f * env)
                add(HumanoidBone.UPPER_ARM_R, rx = 36f * env, ry = 36f * env, rz = -10f * env)
                add(HumanoidBone.LOWER_ARM_R, ry = 96f * env)
                add(HumanoidBone.UPPER_ARM_L, ry = -14f * env, rz = -8f * env)
                add(HumanoidBone.LOWER_ARM_L, ry = -24f * env)
            }
            TouchPart.HAND -> {
                // 牵手：微微前倾 + 右手温暖向前伸向镜头 (-Z)
                add(HumanoidBone.CHEST, rx = -4f * env)
                add(HumanoidBone.HEAD, rx = -4f * env, ry = -6f * env, rz = 6f * env)
                add(HumanoidBone.NECK, rx = -2f * env, ry = -2f * env, rz = 3f * env)
                add(HumanoidBone.UPPER_ARM_R, rx = 15f * env, ry = 30f * env)
                add(HumanoidBone.LOWER_ARM_R, ry = 36f * env)
                add(HumanoidBone.HAND_R, rz = -10f * env)
                add(HumanoidBone.UPPER_ARM_L, rx = 4f * env, ry = -10f * env, rz = -6f * env)
                add(HumanoidBone.LOWER_ARM_L, ry = -16f * env)
            }
            TouchPart.BODY -> {
                // 拥抱：低头害羞贴近 + 双臂在胸前 (-Z) 轻抱合拢 + 温柔心跳微震
                val heartbeat = sin(p * 2f * PI.toFloat() * 3.2f) * 1.2f * env
                add(HumanoidBone.HEAD, rx = -8f * env, rz = 5f * env)
                add(HumanoidBone.NECK, rx = -4f * env, rz = 2f * env)
                add(HumanoidBone.CHEST, rx = -5f * env + heartbeat)
                add(HumanoidBone.UPPER_ARM_L, rx = 3f * env, ry = -33f * env, rz = -15f * env)
                add(HumanoidBone.UPPER_ARM_R, rx = 3f * env, ry = 33f * env, rz = 15f * env)
                add(HumanoidBone.LOWER_ARM_L, ry = -78f * env)
                add(HumanoidBone.LOWER_ARM_R, ry = 78f * env)
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
