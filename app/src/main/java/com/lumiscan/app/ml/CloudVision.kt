package com.lumiscan.app.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Base64
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.remote.Api
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.data.remote.CountRequest
import com.lumiscan.app.data.remote.DetectRequest
import com.lumiscan.app.data.remote.EditOptions
import com.lumiscan.app.data.remote.EditRequest
import com.lumiscan.app.data.remote.OcrBlock
import com.lumiscan.app.data.remote.OcrRequest
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.CellMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The cloud (百炼) side of the AI tools. Every call can fail — offline, daily quota used up,
 * feature not in the user's group, AI not configured — and throws [ApiException] with a message
 * ready to show; callers then fall back to the on-device algorithm.
 */
object CloudVision {

    private class Encoded(val b64: String, val width: Int, val height: Int)

    private fun encode(bmp: Bitmap, maxSide: Int, quality: Int, minSide: Int = 0): Encoded {
        var scaled = BitmapIO.scaleToMax(bmp, maxSide)
        val shortest = minOf(scaled.width, scaled.height)
        if (shortest < minSide) {
            val s = minSide.toFloat() / shortest
            val up = Bitmap.createScaledBitmap(scaled, kotlin.math.ceil(scaled.width * s).toInt(), kotlin.math.ceil(scaled.height * s).toInt(), true)
            if (scaled !== bmp) scaled.recycle()
            scaled = up
        }
        val bytes = BitmapIO.jpegBytes(scaled, quality)
        val e = Encoded(Base64.encodeToString(bytes, Base64.NO_WRAP), scaled.width, scaled.height)
        if (scaled !== bmp) scaled.recycle()
        return e
    }

    private fun quota(q: com.lumiscan.app.data.remote.QuotaInfo?) = ScanApp.instance.account.updateQuota(q)

    // ---------------------------------------------------------------- OCR

    /** Plain text of a page (tables come back as tab-separated rows). */
    suspend fun ocrText(bmp: Bitmap): String {
        val e = withContext(Dispatchers.Default) { encode(bmp, 2400, 88) }
        val r = Api.ocr(OcrRequest(e.b64, e.width, e.height, "text"))
        quota(r.quota)
        return r.text
    }

    suspend fun ocrTextFile(path: String): String {
        val bmp = withContext(Dispatchers.IO) { BitmapIO.decode(path, 2400) } ?: throw ApiException(0, "image", "图片读取失败")
        return try { ocrText(bmp) } finally { bmp.recycle() }
    }

    /** Titles, paragraphs and tables (with merged cells) of a page. */
    suspend fun ocrLayout(bmp: Bitmap): List<OcrBlock> {
        val e = withContext(Dispatchers.Default) { encode(bmp, 2400, 88) }
        val r = Api.ocr(OcrRequest(e.b64, e.width, e.height, "layout"))
        quota(r.quota)
        return r.blocks
    }

    /** Word structure from cloud layout blocks; empty when nothing was recognised. */
    fun toDocItems(blocks: List<OcrBlock>): List<DocItem> = blocks.mapNotNull { b ->
        when {
            b.type == "table" -> toTable(b)?.let { DocItem.Table(it) }
            b.text.isBlank() -> null
            else -> {
                val text = TextJoin.tidy(TextJoin.joinLines(b.text.lines().filter { it.isNotBlank() }))
                val title = b.type == "title" || b.type.endsWith("title")
                val cx = (b.x0 + b.x1) / 2
                val align = when {
                    abs(cx - 0.5) < 0.06 && b.x1 - b.x0 < 0.8 && (title || text.length < 40) -> Align.CENTER
                    b.x0 > 0.5 && b.x1 > 0.82 -> Align.RIGHT
                    else -> Align.LEFT
                }
                val size = when {
                    title && text.length <= 30 -> 18f
                    title -> 14f
                    b.type == "header" || b.type == "footer" -> 9f
                    else -> 11f
                }
                DocItem.Para(text, size, bold = title, align = if (title && align == Align.LEFT && text.length <= 30 && abs(cx - 0.5) < 0.15) Align.CENTER else align)
            }
        }
    }

    fun toTables(blocks: List<OcrBlock>): List<TableModel> = blocks.filter { it.type == "table" }.mapNotNull { toTable(it) }

    private fun toTable(b: OcrBlock): TableModel? {
        if (b.cells.isEmpty()) return null
        val rows = maxOf(b.rows, b.cells.maxOf { it.r1 } + 1)
        val cols = maxOf(b.cols, b.cells.maxOf { it.c1 } + 1)
        val grid = Array(rows) { Array(cols) { "" } }
        val merges = ArrayList<CellMerge>()
        for (c in b.cells) {
            if (c.r0 !in 0 until rows || c.c0 !in 0 until cols) continue
            grid[c.r0][c.c0] = c.text.replace("\n", " ").trim()
            val rs = (c.r1 - c.r0 + 1).coerceAtLeast(1)
            val cs = (c.c1 - c.c0 + 1).coerceAtLeast(1)
            if (rs > 1 || cs > 1) merges += CellMerge(c.r0, c.c0, rs, cs)
        }
        return TableModel(grid.map { it.toList() }, merges).takeUnless { it.isEmpty }
    }

    // ---------------------------------------------------------------- detection

    /** Boxes (page fractions) of handwriting or watermarks; the pixels are removed on-device. */
    suspend fun detect(bmp: Bitmap, target: String): List<RectF> {
        val e = withContext(Dispatchers.Default) { encode(bmp, 2000, 85) }
        val r = Api.detect(DetectRequest(e.b64, target))
        quota(r.quota)
        return r.boxes.map { RectF(it.x0.toFloat(), it.y0.toFloat(), it.x1.toFloat(), it.y1.toFloat()) }
    }

    /** What the model counted and the centre of each object (image fractions). */
    class Counted(val label: String, val points: List<android.graphics.PointF>)

    /** 拍照计数: [hint] optionally names the objects to count (e.g. "钢管"). */
    suspend fun count(bmp: Bitmap, hint: String): Counted {
        val e = withContext(Dispatchers.Default) { encode(bmp, 2000, 88) }
        val r = Api.count(CountRequest(e.b64, hint.trim()))
        quota(r.quota)
        return Counted(r.label, r.points.map { android.graphics.PointF(it.x.toFloat(), it.y.toFloat()) })
    }

    // ---------------------------------------------------------------- image editing

    /** Runs an image model and returns the decoded result; [minSide] upsizes small images first. */
    suspend fun edit(feature: String, bmp: Bitmap, maxSide: Int, options: EditOptions = EditOptions(), minSide: Int = 0): Bitmap {
        val e = withContext(Dispatchers.Default) { encode(bmp, maxSide, 92, minSide) }
        val r = Api.edit(EditRequest(feature, e.b64, e.width, e.height, options))
        quota(r.quota)
        val bytes = Api.download(r.url)
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ApiException(0, "decode", "结果图片无法读取")
        }
    }
}
