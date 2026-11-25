package com.persona.ar.character

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.persona.ar.data.model.ActionType

/**
 * 逐帧精灵动画播放器。
 *
 * 为什么用逐帧动画：2D 立绘无法靠旋转/倾斜产生真实肢体动作，
 * 只有预渲染的动作序列帧才能表现「真抬手、真弯腰」。
 *
 * **为什么不做离线切片**：精灵表按固定网格横向等分（6 帧），每帧在源图中的区域天然是
 * 「原图宽/N × 原图高」的同一尺寸矩形。运行时用 srcOffset/srcSize 直接从大图取该矩形绘制，
 * 就能保证所有帧的画布尺寸、身高基准、脚底位置完全一致，无需任何跨帧对齐补偿。
 * 反之若离线把每帧裁成独立 PNG，各帧包围盒宽高不同（弯腰帧比站直帧矮），
 * 绘制时就会出现身高抖动与脚底跳动，还得额外写补丁逻辑去修 —— 那是错误架构。
 *
 * 约定：`assets/animations/<动作名>.png` 是一张横向 N 等分的透明 PNG 精灵表。
 * 动作名 = ActionType 的小写（idle / wave / bow / yawn / shy / nod / sit）。
 * 例外：BLUSH 与 POUT（捏脸/害羞/噘嘴）共用 shy 序列帧。
 */
class FrameAnimator(
    private val context: Context
) {
    /**
     * 一个动作的精灵表。整张表只解码一次并常驻，绘制时按 [frameCount] 等分取帧。
     * @param sheet 整张精灵表位图（含全部帧）
     * @param frameCount 横向帧数（等分份数）
     * @param fps 播放帧率
     * @param loop 是否循环（待机/睡觉为 true；挥手/鞠躬等一次性动作为 false）
     * @param frameSequence 逻辑播放序列（可跳过首尾回归待机的多余帧或悬浮异常帧，null 则按 0..frameCount-1 顺序播放）
     */
    class Clip(
        val sheet: ImageBitmap,
        val frameCount: Int,
        val fps: Float,
        val loop: Boolean,
        val frameSequence: IntArray? = null
    ) {
        /** 单帧在源图中的宽度（像素）：整表宽 / 帧数，各帧完全一致 */
        val frameWidth: Int = sheet.width / frameCount
        /** 单帧在源图中的高度（像素）：取整表高度 */
        val frameHeight: Int = sheet.height
        /** 实际播放步数 */
        val stepCount: Int get() = frameSequence?.size ?: frameCount

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val cache = HashMap<String, Clip?>()

    /** 单个动作的精灵表规格：文件名、横向帧数、播放帧率、是否循环及逻辑帧序号表。 */
    internal class ActionClipSpec(
        val fileName: String,
        val frameCount: Int,
        val fps: Float,
        val loop: Boolean,
        val frameSequence: IntArray? = null
    )

    companion object {
        internal val ACTION_CLIP_SPECS: Map<ActionType, ActionClipSpec> = mapOf(
            // IDLE 跳过第 2 帧垂直上跳 37px 的异常帧，消除手机静止时的无故浮动
            ActionType.IDLE to ActionClipSpec("idle", 6, 1.8f, true, intArrayOf(0, 1, 3, 5, 3, 1)),
            ActionType.WAVE to ActionClipSpec("wave", 6, 3.6f, false, intArrayOf(0, 1, 2, 3, 4, 3, 4, 5)),
            ActionType.BOW to ActionClipSpec("bow", 6, 3.2f, false, intArrayOf(0, 1, 2, 3, 3, 4, 5)),
            ActionType.YAWN to ActionClipSpec("yawn", 6, 2.5f, false, intArrayOf(0, 1, 2, 3, 3, 4, 5)),
            // BLUSH 使用 shy 全程羞涩低头捧手；POUT 保留 shy 资产但仅取第 1/2 帧傲娇鼓腮转头态
            ActionType.BLUSH to ActionClipSpec("shy", 6, 2.8f, false, intArrayOf(0, 1, 2, 3, 3, 4, 5)),
            ActionType.POUT to ActionClipSpec("shy", 6, 3.0f, false, intArrayOf(0, 1, 2, 1, 2, 1)),
            ActionType.NOD to ActionClipSpec("nod", 6, 4.0f, false, intArrayOf(0, 1, 2, 3, 4, 2, 5)),
            // SIT 跳过宽腿深蹲的 1/2 帧，利落进入正坐第 4/5 帧并停留保持
            ActionType.SIT to ActionClipSpec("sit", 6, 3.6f, false, intArrayOf(0, 3, 4, 5)),
            // JUMP 使用高举双臂欢呼的 yawn 帧配合跳跃抛物线与挤压拉伸；SHAKE_HEAD 用正面待机帧配合水平摇头
            ActionType.JUMP to ActionClipSpec("yawn", 6, 3.8f, false, intArrayOf(0, 2, 3, 3, 2, 0)),
            ActionType.SMUG to ActionClipSpec("idle", 6, 2.0f, false, intArrayOf(0, 3, 0, 3, 0)),
            ActionType.SHAKE_HEAD to ActionClipSpec("idle", 6, 3.5f, false, intArrayOf(0, 0, 0, 0, 0, 0)),
            // SLEEP 定格在 nod 第 2 帧（闭眼低首放松态），叠加轻柔睡眠呼吸律动与 Zzz，告别张嘴打哈欠死循环
            ActionType.SLEEP to ActionClipSpec("nod", 6, 1.5f, true, intArrayOf(2))
        )

        /** 查询动作对应的精灵表规格；null 表示该动作没有预渲染序列帧。 */
        internal fun specFor(action: ActionType): ActionClipSpec? = ACTION_CLIP_SPECS[action]
    }

    /** 读取某个动作的精灵表；不存在则返回 null（调用方回退到矢量立绘） */
    fun clipFor(action: ActionType): Clip? {
        val key = action.name
        if (cache.containsKey(key)) return cache[key]
        val spec = specFor(action)
        val clip: Clip? = if (spec == null) {
            null
        } else {
            try {
                context.assets.open("animations/${spec.fileName}.png").use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp == null || bmp.width < spec.frameCount) {
                        null
                    } else {
                        Clip(bmp.asImageBitmap(), spec.frameCount, spec.fps, spec.loop, spec.frameSequence)
                    }
                }
            } catch (e: Throwable) {
                null
            }
        }
        cache[key] = clip
        return clip
    }

    /** 是否存在该动作的精灵动画 */
    fun hasClip(action: ActionType): Boolean = clipFor(action) != null

    /**
     * 按经过时间计算当前精灵图格子索引（0-based）。
     * @param elapsedSeconds 动作开始至今的秒数
     * @return 源图第 index 格；一次性动作超出时长后停在末帧
     */
    fun frameIndexAt(clip: Clip, elapsedSeconds: Float): Int {
        val count = clip.stepCount.coerceAtLeast(1)
        val raw = (elapsedSeconds.coerceAtLeast(0f) * clip.fps).toInt()
        val step = if (clip.loop) {
            ((raw % count) + count) % count
        } else {
            raw.coerceIn(0, count - 1)
        }
        val mapped = clip.frameSequence?.getOrNull(step) ?: step
        return mapped.coerceIn(0, clip.frameCount - 1)
    }

    /** 一次性动作总时长（秒），用于判断动画是否播完 */
    fun durationSeconds(clip: Clip): Float = clip.stepCount / clip.fps
}
