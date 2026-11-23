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
 */
class FrameAnimator(
    private val context: Context
) {
    /**
     * 一个动作的精灵表。整张表只解码一次并常驻，绘制时按 [frameCount] 等分取帧。
     * @param sheet 整张精灵表位图（含全部帧）
     * @param frameCount 横向帧数（等分份数）
     * @param fps 播放帧率
     * @param loop 是否循环（待机为 true；挥手/鞠躬等一次性动作为 false）
     */
    class Clip(
        val sheet: ImageBitmap,
        val frameCount: Int,
        val fps: Float,
        val loop: Boolean
    ) {
        /** 单帧在源图中的宽度（像素）：整表宽 / 帧数，各帧完全一致 */
        val frameWidth: Int = sheet.width / frameCount
        /** 单帧在源图中的高度（像素）：取整表高度 */
        val frameHeight: Int = sheet.height

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val cache = HashMap<String, Clip?>()

    /** 动作 → (精灵表文件名, 帧数, fps, 是否循环) */
    private val actionSpec: Map<ActionType, ActionClipSpec> = mapOf(
        ActionType.IDLE to ActionClipSpec("idle", 6, 6f, true),
        ActionType.WAVE to ActionClipSpec("wave", 6, 9f, false),
        ActionType.BOW to ActionClipSpec("bow", 6, 7f, false),
        ActionType.YAWN to ActionClipSpec("yawn", 6, 6f, false),
        ActionType.BLUSH to ActionClipSpec("shy", 6, 7f, false),
        ActionType.NOD to ActionClipSpec("nod", 6, 9f, false),
        ActionType.SHAKE_HEAD to ActionClipSpec("shake", 6, 9f, false),
        ActionType.SIT to ActionClipSpec("sit", 6, 6f, false)
    )

    private class ActionClipSpec(
        val fileName: String,
        val frameCount: Int,
        val fps: Float,
        val loop: Boolean
    )

    /** 读取某个动作的精灵表；不存在则返回 null（调用方回退到静态立绘） */
    fun clipFor(action: ActionType): Clip? {
        val key = action.name
        if (cache.containsKey(key)) return cache[key]
        val spec = actionSpec[action]
        val clip: Clip? = if (spec == null) {
            null
        } else {
            try {
                context.assets.open("animations/${spec.fileName}.png").use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp == null || bmp.width < spec.frameCount) {
                        null
                    } else {
                        Clip(bmp.asImageBitmap(), spec.frameCount, spec.fps, spec.loop)
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
     * 按经过时间计算当前帧序号（0-based）。
     * @param elapsedSeconds 动作开始至今的秒数
     * @return 帧序号；一次性动作超出时长后停在最后一帧
     */
    fun frameIndexAt(clip: Clip, elapsedSeconds: Float): Int {
        val raw = (elapsedSeconds * clip.fps).toInt()
        return if (clip.loop) {
            ((raw % clip.frameCount) + clip.frameCount) % clip.frameCount
        } else {
            raw.coerceIn(0, clip.frameCount - 1)
        }
    }

    /** 一次性动作总时长（秒），用于判断动画是否播完 */
    fun durationSeconds(clip: Clip): Float = clip.frameCount / clip.fps
}
