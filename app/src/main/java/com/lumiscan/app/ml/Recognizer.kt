package com.lumiscan.app.ml

import android.graphics.Bitmap
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.imaging.CellMerge
import com.lumiscan.app.imaging.TableDetector

/**
 * Page recognition for one batch (a document, a PDF, a set of photos): AI (cloud) first, the
 * on-device engine as fallback. After the first cloud failure — offline, daily pages used up,
 * not enabled — the rest of the batch stays local and [notice] explains why, once.
 */
class RecognizeRun {
    var notice: String? = null
        private set
    var usedCloud = false
        private set

    private suspend fun <T> cloud(block: suspend () -> T): T? {
        if (notice != null) return null
        return try {
            block().also { usedCloud = true }
        } catch (e: ApiException) {
            notice = e.message
            null
        }
    }

    suspend fun text(bmp: Bitmap): String =
        cloud { CloudVision.ocrText(bmp) } ?: OcrEngine.recognize(bmp).paragraphs

    suspend fun wordItems(bmp: Bitmap): List<DocItem> =
        cloud { CloudVision.toDocItems(CloudVision.ocrLayout(bmp)) }?.takeIf { it.isNotEmpty() } ?: LayoutBuilder.analyze(bmp)

    /** All tables of the page stacked into one sheet (a blank row between tables). */
    suspend fun table(bmp: Bitmap): TableModel {
        cloud { stack(CloudVision.toTables(CloudVision.ocrLayout(bmp))) }?.takeIf { !it.isEmpty }?.let { return it }
        val ocr = OcrEngine.recognize(bmp)
        val ruled = TableDetector.detect(bmp)?.let { TableBuilder.fromGrid(it, ocr) }
        return if (ruled != null && !ruled.isEmpty) ruled else TableBuilder.fromLayout(ocr)
    }

    companion object {
        fun stack(tables: List<TableModel>): TableModel {
            if (tables.size <= 1) return tables.firstOrNull() ?: TableModel(emptyList())
            val cols = tables.maxOf { it.cols }
            val rows = ArrayList<List<String>>()
            val merges = ArrayList<CellMerge>()
            tables.forEachIndexed { i, t ->
                if (i > 0) rows += List(cols) { "" }
                val offset = rows.size
                t.cells.forEach { r -> rows += r + List(cols - r.size) { "" } }
                t.merges.forEach { m -> merges += m.copy(row = m.row + offset) }
            }
            return TableModel(rows, merges)
        }
    }
}
