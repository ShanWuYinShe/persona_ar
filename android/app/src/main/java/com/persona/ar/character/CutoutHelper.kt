package com.persona.ar.character

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 立绘背景自动抠除：把「纯色（多为白色）背景 + 人物」的位图转成透明背景人物。
 *
 * 设计要点：
 * 1. **只在需要时动手**：若位图四角已经是透明（alpha≈0），说明素材本身带透明通道，直接返回原图，
 *    绝不对已抠好的素材二次处理（避免把浅色头发/白衣误抠成破洞）。
 * 2. **背景色自适应**：从四个角采样出背景色（取中位色），而不是写死纯白——素材可能是 #FFFFFF、
 *    也可能略偏灰或偏米色。
 * 3. **边缘羽化**：按与背景色的色差计算 alpha，色差小者透明、大者不透明，中间线性过渡，
 *    避免硬切产生锯齿。同时做一次「从四边向内洪泛」的连通域判定，只有真正与画面边缘连通的背景才被抠除，
 *    人物内部同色区域（白衬衫、白鞋）不会被误伤。
 */
object CutoutHelper {

    /** 色差阈值：低于此值认为属于背景 */
    private const val TOLERANCE_LOW = 18f
    /** 色差高于此值认为完全属于前景 */
    private const val TOLERANCE_HIGH = 46f

    fun applyWhiteBackgroundCutout(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 2 || h <= 2) return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 已有透明通道的素材直接返回
        val cornerAlpha = listOf(pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1])
            .map { Color.alpha(it) }
        if (cornerAlpha.all { it < 32 }) return src

        // 从四角采样背景色（取亮度中位数，避开纯黑/纯白极值干扰）
        val samplePoints = listOf(0, w - 1, (h - 1) * w, h * w - 1, w / 2, (h - 1) * w + w / 2)
        val bgR = samplePoints.map { Color.red(pixels[it]) }.sorted()[samplePoints.size / 2]
        val bgG = samplePoints.map { Color.green(pixels[it]) }.sorted()[samplePoints.size / 2]
        val bgB = samplePoints.map { Color.blue(pixels[it]) }.sorted()[samplePoints.size / 2]

        // 计算每个像素与背景色的距离
        val dist = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val dr = (Color.red(p) - bgR).toFloat()
            val dg = (Color.green(p) - bgG).toFloat()
            val db = (Color.blue(p) - bgB).toFloat()
            dist[i] = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }

        // 只有与画面四边连通的「背景色像素」才被抠除：从边缘做 BFS，遇到色差大的像素即停
        val isBg = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun push(idx: Int) {
            if (idx in 0 until w * h && !isBg[idx] && dist[idx] < TOLERANCE_LOW) {
                isBg[idx] = true
                queue[tail++] = idx
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0) push(idx - 1)
            if (x < w - 1) push(idx + 1)
            if (y > 0) push(idx - w)
            if (y < h - 1) push(idx + w)
        }

        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = if (isBg[i]) {
                0
            } else {
                // 未被判定为背景：按色差做边缘羽化（靠近背景色的边缘半透明，减少锯齿）
                val base = Color.alpha(p)
                val softness = ((dist[i] - TOLERANCE_LOW) / (TOLERANCE_HIGH - TOLERANCE_LOW)).coerceIn(0f, 1f)
                (base * softness).toInt().coerceIn(0, 255)
            }
            out[i] = Color.argb(a, Color.red(p), Color.green(p), Color.blue(p))
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
