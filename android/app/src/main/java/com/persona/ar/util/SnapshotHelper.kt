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
 * 拍立得照片生成与系统相册保存引擎 (AR Snapshot & Polaroid Engine)
 * 将现实 AR 环境、二次元纸片人立绘、拍立得相纸边框与次元纪念签名合成为一张高质量实体相纸感照片。
 */
object SnapshotHelper {

    suspend fun captureAndSavePolaroid(
        context: Context,
        visualState: CharacterVisualState,
        quote: String = "我就站在你的现实世界中哦~"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // 拍立得画幅尺寸 (3:4 经典比例)
            val width = 1080
            val height = 1440
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 1. 拍立得相纸底色 (温润象牙白)
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

            // 照片背景渐变 (现实温和室内光)
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

            // 照片视口边框 (轻微内嵌阴影)
            val innerStrokePaint = Paint().apply {
                color = Color.parseColor("#281C38")
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawRoundRect(photoRect, 16f, 16f, innerStrokePaint)

            // 3. 在视口中央绘制二次元纸片人立绘象征
            val charCenterX = width / 2f
            val charCenterY = photoTop + photoHeight / 2f + 40f

            // 绘制角色地面柔和阴影
            val shadowPaint = Paint().apply {
                color = Color.argb(45, 40, 28, 56)
                isAntiAlias = true
            }
            canvas.drawOval(
                RectF(charCenterX - 180f, photoBottom - 90f, charCenterX + 180f, photoBottom - 30f),
                shadowPaint
            )

            // 绘制萌系立绘头部与发型
            val skinPaint = Paint().apply {
                color = Color.parseColor("#FFF4ED")
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                RectF(charCenterX - 110f, charCenterY - 140f, charCenterX + 110f, charCenterY + 70f),
                70f, 70f, skinPaint
            )

            // 发色
            val hairColor = try {
                Color.parseColor(visualState.model?.hairColorHex ?: "#6B4E71")
            } catch (e: Exception) {
                Color.parseColor("#6B4E71")
            }
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

            // 大萌眼
            val eyeColor = try {
                Color.parseColor(visualState.model?.eyeColorHex ?: "#5D54A8")
            } catch (e: Exception) {
                Color.parseColor("#5D54A8")
            }
            val eyePaint = Paint().apply {
                color = eyeColor
                isAntiAlias = true
            }
            canvas.drawOval(RectF(charCenterX - 68f, charCenterY - 35f, charCenterX - 28f, charCenterY + 25f), eyePaint)
            canvas.drawOval(RectF(charCenterX + 28f, charCenterY - 35f, charCenterX + 68f, charCenterY + 25f), eyePaint)

            // 眼睛高光
            val highlightPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            canvas.drawCircle(charCenterX - 54f, charCenterY - 18f, 7f, highlightPaint)
            canvas.drawCircle(charCenterX + 42f, charCenterY - 18f, 7f, highlightPaint)

            // 微笑小猫嘴
            val mouthPaint = Paint().apply {
                color = Color.parseColor("#281C38")
                strokeWidth = 5f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            val mouthPath = Path().apply {
                moveTo(charCenterX - 20f, charCenterY + 36f)
                quadTo(charCenterX - 10f, charCenterY + 48f, charCenterX, charCenterY + 40f)
                quadTo(charCenterX + 10f, charCenterY + 48f, charCenterX + 20f, charCenterY + 36f)
            }
            canvas.drawPath(mouthPath, mouthPaint)

            // 4. 拍立得下方留白区：拍立得手写签名与次元信息
            val titlePaint = Paint().apply {
                color = Color.parseColor("#281C38")
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val roleName = visualState.name.ifBlank { "星野 Hoshino" }
            canvas.drawText("✦ Persona AR · 次元回响 × $roleName ✦", margin + 8f, 1180f, titlePaint)

            val quotePaint = Paint().apply {
                color = Color.parseColor("#5D54A8")
                textSize = 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                isAntiAlias = true
            }
            canvas.drawText("“$quote”", margin + 8f, 1235f, quotePaint)

            val dateStr = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date())
            val datePaint = Paint().apply {
                color = Color.parseColor("#8E82A0")
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
            }
            canvas.drawText("Shot on Persona AR Core Engine  •  $dateStr", margin + 8f, 1285f, datePaint)

            // 5. 保存到 Android 媒体库 (MediaStore)
            val fileName = "PersonaAR_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PersonaAR")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            imageUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { stream: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            }
            imageUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
