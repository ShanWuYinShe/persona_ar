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
 * 真实 AR 现实与虚拟模型融合合影引擎 (AR Reality Blending & Polaroid Engine)
 * 将真实的摄像头物理采集画面、自适应环境光照、二次元虚拟伴侣立绘、物理接触阴影与契约印记深度合成。
 */
object SnapshotHelper {

    suspend fun captureAndSavePolaroid(
        context: Context,
        visualState: CharacterVisualState,
        quote: String = "我就站在你的现实世界中哦~",
        cameraFrameBitmap: Bitmap? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // 拍立得画幅尺寸 (3:4 经典比例)
            val width = 1080
            val height = 1440
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 1. 拍立得相纸底色 (温润象牙白相纸边框)
            val paperPaint = Paint().apply {
                color = Color.parseColor("#FBF9F5")
                isAntiAlias = true
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)

            // 2. 照片视口区域 (Photo Inner Window)
            val margin = 54f
            val photoTop = 54f
            val photoBottom = 1120f
            val photoWidth = width - margin * 2
            val photoHeight = photoBottom - photoTop
            val photoRect = RectF(margin, photoTop, width - margin, photoBottom)

            // 3. 绘制现实摄像头采集的物理真实画面 (Real Camera Frame)
            if (cameraFrameBitmap != null && !cameraFrameBitmap.isRecycled) {
                // 计算 Center-Crop 矩阵，将摄像头画面无黑边填充至照片视口
                val srcWidth = cameraFrameBitmap.width.toFloat()
                val srcHeight = cameraFrameBitmap.height.toFloat()
                val scale = maxOf(photoWidth / srcWidth, photoHeight / srcHeight)
                val scaledW = srcWidth * scale
                val scaledH = srcHeight * scale
                val dx = margin + (photoWidth - scaledW) / 2f
                val dy = photoTop + (photoHeight - scaledH) / 2f

                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(dx, dy)
                }

                // 创建圆角遮罩以防止溢出照片相框
                canvas.save()
                val clipPath = Path().apply {
                    addRoundRect(photoRect, 16f, 16f, Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                canvas.drawBitmap(cameraFrameBitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            } else {
                // 回退背景渐变 (温和室内光)
                val bgGradient = LinearGradient(
                    margin, photoTop, margin, photoBottom,
                    Color.parseColor("#EAE5DF"),
                    Color.parseColor("#D5CFC7"),
                    Shader.TileMode.CLAMP
                )
                val bgPaint = Paint().apply {
                    shader = bgGradient
                    isAntiAlias = true
                }
                canvas.drawRoundRect(photoRect, 16f, 16f, bgPaint)
            }

            // 照片视口边框 (轻微内嵌质感深色描边)
            val innerStrokePaint = Paint().apply {
                color = Color.parseColor("#281C38")
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawRoundRect(photoRect, 16f, 16f, innerStrokePaint)

            // 4. 在现实物理画面中融入二次元角色模型
            val charCenterX = width / 2f
            val charCenterY = photoTop + photoHeight / 2f + 50f
            val envLight = visualState.environmentalLighting.coerceIn(0.35f, 1.25f)
            val isLowLight = visualState.isLowLight

            // A. 地面自适应柔和接触阴影 (与真实桌面的纹理自然渗透融合)
            val shadowAlpha = (65 * envLight).toInt().coerceIn(25, 90)
            val shadowPaint = Paint().apply {
                color = if (isLowLight) Color.argb(shadowAlpha + 20, 15, 8, 28) else Color.argb(shadowAlpha, 30, 20, 45)
                isAntiAlias = true
            }
            canvas.drawOval(
                RectF(charCenterX - 190f, photoBottom - 95f, charCenterX + 190f, photoBottom - 32f),
                shadowPaint
            )

            // B. 伴侣脚下契约魔法微光印记
            val ringPaint = Paint().apply {
                color = if (isLowLight) Color.argb(100, 0, 229, 255) else Color.argb(60, 0, 229, 255)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                isAntiAlias = true
            }
            canvas.drawOval(
                RectF(charCenterX - 160f, photoBottom - 82f, charCenterX + 160f, photoBottom - 45f),
                ringPaint
            )

            // C. 角色立绘主体 (应用现实物理环境光调和)
            fun applyLightToColor(hex: String?, fallback: Int): Int {
                val base = try { Color.parseColor(hex ?: "") } catch (e: Exception) { fallback }
                val r = (Color.red(base) * envLight).toInt().coerceIn(0, 255)
                val g = (Color.green(base) * envLight).toInt().coerceIn(0, 255)
                val b = if (isLowLight) {
                    (Color.blue(base) * envLight * 1.08f).toInt().coerceIn(0, 255)
                } else {
                    (Color.blue(base) * envLight).toInt().coerceIn(0, 255)
                }
                return Color.argb(Color.alpha(base), r, g, b)
            }

            // 头部与发型
            val skinColor = applyLightToColor(null, Color.parseColor("#FFF4ED"))
            val skinPaint = Paint().apply {
                color = skinColor
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                RectF(charCenterX - 110f, charCenterY - 140f, charCenterX + 110f, charCenterY + 70f),
                70f, 70f, skinPaint
            )

            // 发色
            val hairColor = applyLightToColor(visualState.model?.hairColorHex, Color.parseColor("#6B4E71"))
            val hairPaint = Paint().apply {
                color = hairColor
                isAntiAlias = true
            }
            // 齐刘海
            canvas.drawRoundRect(
                RectF(charCenterX - 116f, charCenterY - 160f, charCenterX + 116f, charCenterY - 40f),
                60f, 60f, hairPaint
            )
            // 双马尾
            canvas.drawOval(RectF(charCenterX - 200f, charCenterY - 100f, charCenterX - 90f, charCenterY + 110f), hairPaint)
            canvas.drawOval(RectF(charCenterX + 90f, charCenterY - 100f, charCenterX + 200f, charCenterY + 110f), hairPaint)

            // 腮红
            val blushPaint = Paint().apply {
                color = Color.argb(120, 255, 107, 129)
                isAntiAlias = true
            }
            canvas.drawCircle(charCenterX - 65f, charCenterY + 10f, 22f, blushPaint)
            canvas.drawCircle(charCenterX + 65f, charCenterY + 10f, 22f, blushPaint)

            // 大萌眼 (视线注视镜头方向)
            val eyeColor = applyLightToColor(visualState.model?.eyeColorHex, Color.parseColor("#5D54A8"))
            val eyePaint = Paint().apply {
                color = eyeColor
                isAntiAlias = true
            }
            val gazeShiftX = visualState.gazeShiftX.coerceIn(-1f, 1f) * 6f
            val gazeShiftY = visualState.gazeShiftY.coerceIn(-1f, 1f) * 4f

            canvas.drawOval(RectF(charCenterX - 68f + gazeShiftX, charCenterY - 35f + gazeShiftY, charCenterX - 28f + gazeShiftX, charCenterY + 25f + gazeShiftY), eyePaint)
            canvas.drawOval(RectF(charCenterX + 28f + gazeShiftX, charCenterY - 35f + gazeShiftY, charCenterX + 68f + gazeShiftX, charCenterY + 25f + gazeShiftY), eyePaint)

            // 眼睛高光
            val highlightPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            canvas.drawCircle(charCenterX - 45f + gazeShiftX * 0.4f, charCenterY - 15f + gazeShiftY * 0.4f, 8f, highlightPaint)
            canvas.drawCircle(charCenterX + 51f + gazeShiftX * 0.4f, charCenterY - 15f + gazeShiftY * 0.4f, 8f, highlightPaint)

            // 甜美微笑着色
            val mouthPaint = Paint().apply {
                color = Color.parseColor("#E05275")
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            val mouthPath = Path().apply {
                moveTo(charCenterX - 18f, charCenterY + 42f)
                quadTo(charCenterX, charCenterY + 56f, charCenterX + 18f, charCenterY + 42f)
            }
            canvas.drawPath(mouthPath, mouthPaint)

            // 水手服领结 (环境光调和)
            val ribbonColor = applyLightToColor(visualState.model?.previewColorHex, Color.parseColor("#FF5277"))
            val ribbonPaint = Paint().apply {
                color = ribbonColor
                isAntiAlias = true
            }
            canvas.drawCircle(charCenterX, charCenterY + 88f, 18f, ribbonPaint)

            // 5. 拍立得底部留白手写区域 (Polaroid Signature Area)
            val titlePaint = Paint().apply {
                color = Color.parseColor("#281C38")
                textSize = 38f
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText("✦ 现实交汇 · 次元同框 ✦", margin + 10f, 1195f, titlePaint)

            val quotePaint = Paint().apply {
                color = Color.parseColor("#5A4B6E")
                textSize = 28f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                isAntiAlias = true
            }
            val displayQuote = if (quote.length > 28) quote.take(28) + "..." else quote
            canvas.drawText("“$displayQuote”", margin + 10f, 1248f, quotePaint)

            // 日期戳与角色签名
            val metaPaint = Paint().apply {
                color = Color.parseColor("#9D8FA8")
                textSize = 24f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }
            val dateStr = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date())
            val partnerName = visualState.name.ifBlank { "星野 Hoshino" }
            canvas.drawText("LOCATION: REALITY  •  DATE: $dateStr", margin + 10f, 1310f, metaPaint)
            canvas.drawText("PARTNER: $partnerName  •  Zero-GMS AR Engine", margin + 10f, 1348f, metaPaint)

            // 6. 保存至 Android MediaStore 系统相册
            saveBitmapToGallery(context, bitmap, partnerName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
