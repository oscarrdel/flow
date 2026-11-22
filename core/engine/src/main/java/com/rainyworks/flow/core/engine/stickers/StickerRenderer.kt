package com.rainyworks.flow.core.engine.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import com.rainyworks.flow.core.model.MediaRef
import com.rainyworks.flow.core.model.MediaType
import com.rainyworks.flow.core.model.ShapeKind
import com.rainyworks.flow.core.model.StickerSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws stickers into transparent PNGs stored in app files, so saved projects keep them.
 * The same sticker always maps to the same file.
 */
class StickerRenderer(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "stickers")

    suspend fun render(spec: StickerSpec): MediaRef = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val (width, height) = sizeOf(spec)
        val file = File(dir, "${key(spec)}.png")
        if (file.length() <= 0) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            when (spec) {
                is StickerSpec.Shape -> drawShape(canvas, spec, width.toFloat(), height.toFloat())
                is StickerSpec.Emoji -> drawEmoji(canvas, spec.text, width.toFloat(), height.toFloat())
            }
            val partial = File(dir, "${file.name}.part")
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            partial.renameTo(file)
        }
        MediaRef(
            uri = Uri.fromFile(file).toString(),
            type = MediaType.IMAGE,
            width = width,
            height = height,
            displayName = nameOf(spec),
            sizeBytes = file.length(),
            mimeType = "image/png",
        )
    }

    private fun sizeOf(spec: StickerSpec): Pair<Int, Int> = when {
        spec is StickerSpec.Shape && spec.kind == ShapeKind.RECTANGLE -> 640 to 400
        spec is StickerSpec.Shape && spec.kind == ShapeKind.ROUNDED -> 640 to 400
        spec is StickerSpec.Shape && spec.kind == ShapeKind.ARROW -> 640 to 320
        else -> SIZE to SIZE
    }

    private fun drawShape(canvas: Canvas, spec: StickerSpec.Shape, w: Float, h: Float) {
        val stroke = minOf(w, h) * 0.06f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.argb.toInt()
            style = if (spec.filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = stroke
            strokeJoin = Paint.Join.ROUND
        }
        // Outlines are inset so the stroke is not cut at the edges.
        val inset = if (spec.filled) 2f else stroke / 2f + 2f
        val box = RectF(inset, inset, w - inset, h - inset)
        when (spec.kind) {
            ShapeKind.RECTANGLE -> canvas.drawRect(box, paint)
            ShapeKind.ROUNDED -> canvas.drawRoundRect(box, box.height() * 0.2f, box.height() * 0.2f, paint)
            ShapeKind.CIRCLE -> canvas.drawOval(box, paint)
            ShapeKind.TRIANGLE -> canvas.drawPath(Path().apply {
                moveTo(box.centerX(), box.top)
                lineTo(box.right, box.bottom)
                lineTo(box.left, box.bottom)
                close()
            }, paint)
            ShapeKind.STAR -> canvas.drawPath(star(box), paint)
            ShapeKind.HEART -> canvas.drawPath(heart(box), paint)
            ShapeKind.ARROW -> canvas.drawPath(Path().apply {
                val midY = box.centerY()
                val shaft = box.height() * 0.18f
                val headStart = box.right - box.height() * 0.75f
                moveTo(box.left, midY - shaft)
                lineTo(headStart, midY - shaft)
                lineTo(headStart, box.top)
                lineTo(box.right, midY)
                lineTo(headStart, box.bottom)
                lineTo(headStart, midY + shaft)
                lineTo(box.left, midY + shaft)
                close()
            }, paint)
        }
    }

    private fun star(box: RectF): Path = Path().apply {
        val cx = box.centerX()
        val cy = box.centerY() + box.height() * 0.04f
        val outer = minOf(box.width(), box.height()) / 2f
        val inner = outer * 0.45f
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun heart(box: RectF): Path = Path().apply {
        val w = box.width()
        val h = box.height()
        val x = box.left
        val y = box.top
        moveTo(x + w / 2f, y + h * 0.95f)
        cubicTo(x - w * 0.05f, y + h * 0.55f, x + w * 0.05f, y + h * 0.05f, x + w / 2f, y + h * 0.28f)
        cubicTo(x + w * 0.95f, y + h * 0.05f, x + w * 1.05f, y + h * 0.55f, x + w / 2f, y + h * 0.95f)
        close()
    }

    private fun drawEmoji(canvas: Canvas, text: String, w: Float, h: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = h * 0.8f
            textAlign = Paint.Align.CENTER
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        // Shrink wide text (several emoji) to fit.
        if (bounds.width() > w * 0.92f) paint.textSize *= w * 0.92f / bounds.width()
        val metrics = paint.fontMetrics
        canvas.drawText(text, w / 2f, h / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    private fun nameOf(spec: StickerSpec): String = when (spec) {
        is StickerSpec.Emoji -> "Sticker ${spec.text}"
        is StickerSpec.Shape -> when (spec.kind) {
            ShapeKind.RECTANGLE -> "Rectángulo"
            ShapeKind.ROUNDED -> "Rectángulo redondeado"
            ShapeKind.CIRCLE -> "Círculo"
            ShapeKind.TRIANGLE -> "Triángulo"
            ShapeKind.STAR -> "Estrella"
            ShapeKind.HEART -> "Corazón"
            ShapeKind.ARROW -> "Flecha"
        }
    }

    private fun key(spec: StickerSpec): String =
        MessageDigest.getInstance("SHA-1").digest("$spec|v1".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    private companion object {
        const val SIZE = 512
    }
}
