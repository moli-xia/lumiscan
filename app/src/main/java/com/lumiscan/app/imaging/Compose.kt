package com.lumiscan.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class IdCardType(val label: String, val sides: List<String>, val mmW: Float?, val mmH: Float?) {
    ID_CARD("身份证", listOf("人像面", "国徽面"), 85.6f, 54f),
    BANK_CARD("银行卡", listOf("正面", "背面"), 85.6f, 54f),
    DRIVER("驾驶证", listOf("正页", "副页"), 88f, 60f),
    HOUSEHOLD("户口本", listOf("户主页", "本人页"), 105f, 143f),
    PASSPORT("护照", listOf("资料页"), 125f, 88f),
    LICENSE("营业执照", listOf("正本"), null, null),
    OTHER("其他证件", listOf("正面", "背面"), null, null);

    /** Card aspect ratio (w/h) or null when the natural crop ratio should be kept. */
    val aspect: Float? get() = if (mmW != null && mmH != null) mmW / mmH else null

    /** Opened like a booklet (the facing page may be in view): the outline may stop at the fold. */
    val booklet: Boolean get() = this == HOUSEHOLD || this == PASSPORT || this == DRIVER

    /**
     * The dashed guide box the camera shows for this document, as fractions of the 3:4 portrait
     * preview (= the photo): left, top, right, bottom.
     */
    fun guide(): List<Float> {
        val a = aspect ?: (210f / 297f)
        // Preview is 3:4 (w = 3, h = 4 units).
        val bw = if (a >= 1f) 0.86f else 0.7f * 4f / 3f * a
        val bh = bw * 3f / 4f / a
        return listOf((1 - bw) / 2, (1 - bh) / 2, (1 + bw) / 2, (1 + bh) / 2)
    }
}

/** A4 composition for 证件扫描 and the tiled text watermark used on exports. */
object PageCompose {

    const val A4_W = 2480
    const val A4_H = 3508
    private const val PX_PER_MM = 300f / 25.4f

    /** Output pixel size (300dpi) used when flattening one side of [type]. */
    fun cardPixelSize(type: IdCardType, naturalW: Int, naturalH: Int): Pair<Int, Int> {
        if (type.mmW == null || type.mmH == null) return naturalW to naturalH
        // Keep at least real 300dpi size, more if the capture has more detail.
        val base = max(1f, min(naturalW / (type.mmW * PX_PER_MM), 2f))
        return (type.mmW * PX_PER_MM * base).toInt() to (type.mmH * PX_PER_MM * base).toInt()
    }

    fun composeA4(type: IdCardType, sides: List<Bitmap>): Bitmap {
        val page = Bitmap.createBitmap(A4_W, A4_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val margin = 180f
        val gap = 140f
        val availW = A4_W - 2 * margin
        val availH = A4_H - 2 * margin
        val sizes = sides.map { b ->
            if (type.mmW != null && type.mmH != null) {
                type.mmW * PX_PER_MM to type.mmH * PX_PER_MM
            } else {
                val s = min(availW / b.width, (availH - gap * (sides.size - 1)) / sides.size / b.height)
                b.width * s to b.height * s
            }
        }
        val total = sizes.sumOf { it.second.toDouble() }.toFloat() + gap * (sides.size - 1)
        val fit = min(1f, min(availH / total, availW / sizes.maxOf { it.first }))
        var y = margin + max(0f, (availH - total * fit) * 0.35f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        sides.forEachIndexed { i, b ->
            val (w, h) = sizes[i].first * fit to sizes[i].second * fit
            val x = (A4_W - w) / 2
            c.drawBitmap(b, null, RectF(x, y, x + w, y + h), paint)
            y += h + gap * fit
        }
        return page
    }

    /** Draws a repeated diagonal text watermark across the whole image. */
    fun watermark(src: Bitmap, text: String, alpha: Int = 70, color: Int = Color.rgb(120, 120, 120), sizeFactor: Float = 1f): Bitmap {
        if (text.isBlank()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha.coerceIn(10, 255)
            textSize = min(out.width, out.height) * 0.05f * sizeFactor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tw = paint.measureText(text)
        val stepX = tw + paint.textSize * 3
        val stepY = paint.textSize * 5
        val diag = hypot(out.width.toFloat(), out.height.toFloat())
        c.save()
        c.rotate(-30f, out.width / 2f, out.height / 2f)
        var y = out.height / 2f - diag / 2
        var row = 0
        while (y < out.height / 2f + diag / 2) {
            var x = out.width / 2f - diag / 2 - (if (row % 2 == 0) 0f else stepX / 2)
            while (x < out.width / 2f + diag / 2) {
                c.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY; row++
        }
        c.restore()
        return out
    }

    /** Transparent bitmap of the tiled watermark sized for a PDF page overlay. */
    fun watermarkLayer(width: Int, height: Int, text: String, alpha: Int, color: Int, sizeFactor: Float): Bitmap {
        val clear = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return watermark(clear, text, alpha, color, sizeFactor).also { if (it !== clear) clear.recycle() }
    }

    /** Stacks images vertically at a common width (长图). */
    fun longImage(pages: List<Bitmap>, width: Int = 1240, gap: Int = 16): Bitmap {
        val heights = pages.map { (it.height * width.toFloat() / it.width).toInt() }
        val out = Bitmap.createBitmap(width, heights.sum() + gap * (pages.size - 1).coerceAtLeast(0), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(235, 235, 235))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0f
        pages.forEachIndexed { i, b ->
            c.drawBitmap(b, null, RectF(0f, y, width.toFloat(), y + heights[i]), paint)
            y += heights[i] + gap
        }
        return out
    }
}
