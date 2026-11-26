package com.persona.ar.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.persona.ar.character.CharacterVisualState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 真 3D AR 拍立得合影引擎：
 * 直接合入实时 CameraX 相机物理实景画面与 Filament TextureView 截取的透明底真 3D 手办渲染帧（含真实光影与接触阴影），
 * 彻底移除任何 2D 手绘简笔画立绘。
 */
object SnapshotHelper {

    suspend fun captureAndSavePolaroid(
        context: Context,
        visualState: CharacterVisualState,
        quote: String = "我就站在你的现实世界中哦~",
        cameraFrameBitmap: Bitmap? = null,
        figure3DBitmap: Bitmap? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val width = 1080
            val height = 1440
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 1. 拍立得相纸底色
            val paperPaint = Paint().apply {
                color = Color.parseColor("#FBF9F5")
                isAntiAlias = true
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)

            // 2. 照片视口区域
            val margin = 54f
            val photoTop = 54f
            val photoBottom = 1120f
            val photoWidth = width - margin * 2
            val photoHeight = photoBottom - photoTop
            val photoRect = RectF(margin, photoTop, width - margin, photoBottom)

            val clipPath = Path().apply {
                addRoundRect(photoRect, 16f, 16f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            // 3. 绘制现实摄像头背景帧 (Center-Crop 填满视口)
            if (cameraFrameBitmap != null && !cameraFrameBitmap.isRecycled) {
                drawCenterCropped(canvas, cameraFrameBitmap, margin, photoTop, photoWidth, photoHeight)
            } else {
                val bgGradient = LinearGradient(
                    margin, photoTop, margin, photoBottom,
                    Color.parseColor("#EAE5DF"),
                    Color.parseColor("#D5CFC7"),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(photoRect, Paint().apply { shader = bgGradient; isAntiAlias = true })
            }

            // 4. 直接叠合 Filament 3D 透明前景帧（真 3D 手办 + 真实环境光照 + 接触阴影）
            if (figure3DBitmap != null && !figure3DBitmap.isRecycled) {
                drawCenterCropped(canvas, figure3DBitmap, margin, photoTop, photoWidth, photoHeight)
            }
            canvas.restore()

            // 相片边框
            val innerStrokePaint = Paint().apply {
                color = Color.parseColor("#281C38")
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawRoundRect(photoRect, 16f, 16f, innerStrokePaint)

            // 5. 拍立得底部文字签名区
            val titlePaint = Paint().apply {
                color = Color.parseColor("#281C38")
                textSize = 38f
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText("✦ 现实交汇 · 3D 次元同框 ✦", margin + 10f, 1195f, titlePaint)

            val quotePaint = Paint().apply {
                color = Color.parseColor("#5A4B6E")
                textSize = 28f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                isAntiAlias = true
            }
            val displayQuote = if (quote.length > 28) quote.take(28) + "..." else quote
            canvas.drawText("“$displayQuote”", margin + 10f, 1248f, quotePaint)

            val metaPaint = Paint().apply {
                color = Color.parseColor("#9D8FA8")
                textSize = 24f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }
            val dateStr = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date())
            val partnerName = visualState.name.ifBlank { "星野 Hoshino" }
            canvas.drawText("LOCATION: REALITY 3D  •  DATE: $dateStr", margin + 10f, 1310f, metaPaint)
            canvas.drawText("PARTNER: $partnerName  •  Filament 6DoF AR", margin + 10f, 1348f, metaPaint)

            saveBitmapToGallery(context, bitmap, partnerName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun drawCenterCropped(
        canvas: Canvas,
        src: Bitmap,
        dstLeft: Float,
        dstTop: Float,
        dstWidth: Float,
        dstHeight: Float
    ) {
        val srcW = src.width.toFloat().coerceAtLeast(1f)
        val srcH = src.height.toFloat().coerceAtLeast(1f)
        val scale = maxOf(dstWidth / srcW, dstHeight / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dx = dstLeft + (dstWidth - scaledW) * 0.5f
        val dy = dstTop + (dstHeight - scaledH) * 0.5f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        partnerName: String
    ): Uri? {
        val filename = "PersonaAR_${partnerName}_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PersonaAR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        var outputStream: OutputStream? = null
        try {
            outputStream = resolver.openOutputStream(uri)
            if (outputStream != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        } finally {
            outputStream?.close()
        }
    }
}
