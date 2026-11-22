package com.rainyworks.flow.core.engine.text

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import com.rainyworks.flow.core.model.TextAlign
import com.rainyworks.flow.core.model.TextAnimationType.TYPEWRITER
import com.rainyworks.flow.core.model.TextContent
import java.util.Locale
import kotlin.math.ceil

internal class TitleLayout(
    private val content: TextContent,
    typeface: Typeface,
    private val fontPx: Float,
    maxWidth: Int,
    locale: Locale,
) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        textSize = fontPx
        textLocale = locale
        letterSpacing = content.letterSpacing
        strokeJoin = Paint.Join.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text: CharSequence = if (content.enter.type == TYPEWRITER || content.exit.type == TYPEWRITER) {
        SpannableString(content.text)
    } else content.text
    val width = ceil(Layout.getDesiredWidth(text, paint)).toInt().coerceIn(1, maxWidth)
    private val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(when (content.align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        })
        .setLineSpacing(0f, content.lineHeight.coerceAtLeast(0f))
        .build()
    val height get() = layout.height
    private val gradient = content.fill.gradientEndArgb?.let {
        LinearGradient(0f, 0f, 0f, height.toFloat(), content.fill.argb.toInt(), it.toInt(), Shader.TileMode.CLAMP)
    }
    private val blurRadius = (content.shadow?.blur ?: 0f) * fontPx
    private val blur = if (blurRadius > 0f) BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL) else null
    private var visibleEnd = text.length
    private var hardwareShadow: Bitmap? = null
    private val hidden = object : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            tp.shader = null
            tp.alpha = 0
        }
    }

    fun draw(canvas: Canvas, end: Int, cursor: Boolean) {
        if (end != visibleEnd && text is SpannableString) {
            text.removeSpan(hidden)
            if (end < text.length) text.setSpan(hidden, end, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            visibleEnd = end
            hardwareShadow = null
        }
        content.background?.let {
            boxPaint.color = it.argb.toInt()
            val pad = fontPx * it.padding.coerceAtLeast(0f)
            val radius = fontPx * it.cornerRadius.coerceAtLeast(0f)
            canvas.drawRoundRect(-pad, -pad, width + pad, height + pad, radius, radius, boxPaint)
        }
        if (end > 0) {
            content.shadow?.let {
                val save = canvas.save()
                try {
                    canvas.translate(it.offsetX * fontPx, it.offsetY * fontPx)
                    paint.color = it.argb.toInt()
                    paint.maskFilter = blur
                    if (blur != null && canvas.isHardwareAccelerated) drawHardwareShadow(canvas)
                    else layout.draw(canvas)
                } finally {
                    paint.maskFilter = null
                    canvas.restoreToCount(save)
                }
            }
            content.stroke?.takeIf { it.width > 0f }?.let {
                paint.color = it.argb.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = fontPx * it.width
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
            }
        }
        paint.color = if (gradient == null) content.fill.argb.toInt() else Color.WHITE
        paint.shader = gradient
        if (end > 0) layout.draw(canvas)
        if (cursor) {
            val line = layout.getLineForOffset(end)
            val x = layout.getPrimaryHorizontal(end)
            val baseline = layout.getLineBaseline(line)
            val halfWidth = (fontPx * 0.035f).coerceAtLeast(0.5f)
            canvas.drawRect(x - halfWidth, baseline + paint.ascent(), x + halfWidth, baseline + paint.descent(), paint)
        }
        paint.shader = null
    }

    // Hardware canvases do not implement Paint.setMaskFilter. Rasterize just this pass in software.
    private fun drawHardwareShadow(canvas: Canvas) {
        val margin = ceil(fontPx + 3f * blurRadius).toInt()
        val shadow = hardwareShadow ?: Bitmap.createBitmap(
            width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888,
        ).also {
            it.density = Bitmap.DENSITY_NONE
            val software = Canvas(it)
            software.translate(margin.toFloat(), margin.toFloat())
            layout.draw(software)
            hardwareShadow = it
        }
        canvas.drawBitmap(shadow, -margin.toFloat(), -margin.toFloat(), null)
    }
}
