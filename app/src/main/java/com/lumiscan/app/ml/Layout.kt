package com.lumiscan.app.ml

import android.graphics.Bitmap
import com.lumiscan.app.imaging.TableDetector
import com.lumiscan.app.imaging.TableGrid
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Align { LEFT, CENTER, RIGHT }

/** Platform-neutral document content produced by recognition and written by the exporters. */
sealed interface DocItem {
    data class Para(val text: String, val sizePt: Float = 11f, val bold: Boolean = false, val align: Align = Align.LEFT) : DocItem
    data class Table(val table: TableModel) : DocItem
    data class Image(val path: String, val width: Int, val height: Int) : DocItem
    data object PageBreak : DocItem
}

/** Rebuilds a page's structure (headings, paragraphs, alignment, tables) from OCR geometry. */
object LayoutBuilder {

    /** Usable A4 width in points with 1-inch margins. */
    const val CONTENT_WIDTH_PT = 451f

    suspend fun analyze(bitmap: Bitmap): List<DocItem> {
        val ocr = OcrEngine.recognize(bitmap)
        val grid = TableDetector.detect(bitmap)
        return build(ocr, grid)
    }

    fun build(ocr: OcrResult, grid: TableGrid?): List<DocItem> {
        if (ocr.blocks.isEmpty() && grid == null) return emptyList()
        val ptPerPx = CONTENT_WIDTH_PT / ocr.width.coerceAtLeast(1)
        val placed = ArrayList<Pair<Int, DocItem>>()

        if (grid != null) {
            val t = TableBuilder.fromGrid(grid, ocr)
            if (!t.isEmpty) placed += grid.bounds.top to DocItem.Table(t)
        }
        val tableRect = grid?.bounds
        val allHeights = ocr.lines.map { it.box.height() }.sorted()
        val bodyHeight = allHeights.getOrNull(allHeights.size / 2)?.toFloat() ?: 20f

        for (block in ocr.blocks) {
            // Strongly rotated lines are stamps / diagonal watermarks, not body text.
            val lines = block.lines.filter { l ->
                kotlin.math.abs(l.angle) < 15f && (tableRect == null || !tableRect.contains(l.box.centerX(), l.box.centerY()))
            }
            if (lines.isEmpty()) continue
            val charH = lines.map { it.box.height() }.sorted()[lines.size / 2].toFloat()
            val left = lines.minOf { it.box.left }
            val right = lines.maxOf { it.box.right }
            // Split the block into paragraphs at indented starts and short (ended) lines.
            val paras = ArrayList<MutableList<OcrLine>>()
            lines.forEachIndexed { i, l ->
                val prev = lines.getOrNull(i - 1)
                val newPara = prev == null ||
                    l.box.left > left + charH * 1.5f ||
                    prev.box.right < right - charH * 2.5f
                if (newPara) paras += mutableListOf(l) else paras.last() += l
            }
            for (p in paras) {
                val text = TextJoin.tidy(TextJoin.joinLines(p.map { it.text }))
                if (text.isBlank()) continue
                val h = p.map { it.box.height() }.sorted()[p.size / 2].toFloat()
                val size = ((h * ptPerPx * 0.8f) * 2).roundToInt() / 2f
                val pl = p.minOf { it.box.left }; val pr = p.maxOf { it.box.right }
                val center = (pl + pr) / 2f
                val pageCenter = ocr.width / 2f
                val align = when {
                    abs(center - pageCenter) < ocr.width * 0.06f && (pr - pl) < ocr.width * 0.8f && p.size <= 3 -> Align.CENTER
                    pl > ocr.width * 0.5f && pr > ocr.width * 0.82f -> Align.RIGHT
                    else -> Align.LEFT
                }
                val bold = h > bodyHeight * 1.3f
                placed += p.first().box.top to DocItem.Para(text, size.coerceIn(7f, 36f), bold, align)
            }
        }
        return placed.sortedBy { it.first }.map { it.second }
    }
}
