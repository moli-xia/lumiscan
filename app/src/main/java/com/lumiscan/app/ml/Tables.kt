package com.lumiscan.app.ml

import android.graphics.Rect
import com.lumiscan.app.imaging.CellMerge
import com.lumiscan.app.imaging.TableGrid
import kotlin.math.max
import kotlin.math.min

/** Recognised table: text per cell plus merged ranges (origin cell holds the text). */
data class TableModel(val cells: List<List<String>>, val merges: List<CellMerge> = emptyList()) {
    val rows: Int get() = cells.size
    val cols: Int get() = cells.maxOfOrNull { it.size } ?: 0

    fun cell(r: Int, c: Int): String = cells.getOrNull(r)?.getOrNull(c).orEmpty()

    fun mergeAt(r: Int, c: Int): CellMerge? = merges.firstOrNull { it.row == r && it.col == c }

    /** True when (r,c) is hidden inside another cell's merge. */
    fun isCovered(r: Int, c: Int): Boolean = merges.any {
        r in it.row until it.row + it.rowSpan && c in it.col until it.col + it.colSpan && !(r == it.row && c == it.col)
    }

    fun withCell(r: Int, c: Int, text: String): TableModel =
        copy(cells = cells.mapIndexed { ri, row -> if (ri == r) row.mapIndexed { ci, v -> if (ci == c) text else v } else row })

    fun toTsv(): String = cells.joinToString("\n") { row -> row.joinToString("\t") { it.replace("\n", " ") } }

    val isEmpty: Boolean get() = cells.all { row -> row.all { it.isBlank() } }
}

object TableBuilder {

    /** Fills a detected ruled grid with OCR symbols (character-level boxes for precise cells). */
    fun fromGrid(grid: TableGrid, ocr: OcrResult): TableModel {
        val rows = grid.rows; val cols = grid.cols
        val origin = IntArray(rows * cols) { it }
        for (m in grid.merges) for (r in m.row until m.row + m.rowSpan) for (c in m.col until m.col + m.colSpan) {
            origin[r * cols + c] = m.row * cols + m.col
        }
        class Piece(val line: Int, val word: Int, val y: Int, val x: Int, val text: String)
        val buckets = Array(rows * cols) { ArrayList<Piece>() }
        val slack = 6
        fun cellOf(b: Rect): Int? {
            val cx = b.centerX(); val cy = b.centerY()
            if (cx < grid.xs.first() - slack || cx > grid.xs.last() + slack || cy < grid.ys.first() - slack || cy > grid.ys.last() + slack) return null
            return origin[slot(grid.ys, cy) * cols + slot(grid.xs, cx)]
        }
        ocr.lines.forEachIndexed { li, line ->
            line.words.forEachIndexed { wi, word ->
                val symCells = word.symbols.map { cellOf(it.box) }
                if (symCells.isEmpty() || symCells.distinct().size == 1) {
                    // Whole-word text is more accurate than per-symbol text; use it unless the
                    // word straddles a cell border.
                    val cell = symCells.firstOrNull() ?: cellOf(word.box)
                    if (cell != null) buckets[cell] += Piece(li, wi, line.box.top, word.box.left, word.text)
                } else {
                    word.symbols.forEachIndexed { si, s ->
                        val cell = symCells[si] ?: return@forEachIndexed
                        buckets[cell] += Piece(li, wi, line.box.top, s.box.left, s.text)
                    }
                }
            }
        }
        val cells = List(rows) { r ->
            List(cols) { c ->
                val pieces = buckets[r * cols + c]
                pieces.groupBy { it.line }.values
                    .sortedBy { g -> g.minOf { it.y } }
                    .map { lineGroup ->
                        lineGroup.groupBy { it.word }.values
                            .sortedBy { g -> g.minOf { it.x } }
                            .map { w -> w.sortedBy { it.x }.joinToString("") { it.text } }
                            .fold("") { acc, s -> TextJoin.glue(acc, s) }
                    }
                    .fold("") { acc, s -> TextJoin.glue(acc, s) }
            }
        }
        return TableModel(cells, grid.merges)
    }

    /**
     * Borderless table: splits OCR lines at wide gaps into segments, clusters segments into rows
     * by vertical overlap and into columns by horizontal bands.
     */
    fun fromLayout(ocr: OcrResult, region: Rect? = null): TableModel {
        data class Seg(val text: String, val box: Rect)
        val segs = ArrayList<Seg>()
        for (line in ocr.lines) {
            if (region != null && !region.contains(line.box.centerX(), line.box.centerY())) continue
            val words = line.words.sortedBy { it.box.left }
            if (words.isEmpty()) continue
            val ch = max(8, line.box.height())
            var curText = ""
            var cur: Rect? = null
            for (w in words) {
                val gap = if (cur == null) 0 else w.box.left - cur.right
                if (cur != null && gap > ch * 1.3f) {
                    segs += Seg(curText, cur); curText = ""; cur = null
                }
                curText = TextJoin.glue(curText, w.text)
                cur = if (cur == null) Rect(w.box) else Rect(cur).apply { union(w.box) }
            }
            if (cur != null) segs += Seg(curText, cur)
        }
        if (segs.isEmpty()) return TableModel(emptyList())

        // Rows
        val rows = ArrayList<MutableList<Seg>>()
        for (s in segs.sortedBy { it.box.centerY() }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val top = row.minOf { it.box.top }; val bottom = row.maxOf { it.box.bottom }
                val overlap = min(bottom, s.box.bottom) - max(top, s.box.top)
                if (overlap > 0.5f * min(bottom - top, s.box.height())) { row += s; continue }
            }
            rows += mutableListOf(s)
        }
        // Column bands from typical-width segments (wide titles would merge every column).
        val medW = segs.map { it.box.width() }.sorted()[segs.size / 2]
        val bands = ArrayList<IntArray>()
        for (s in segs.filter { it.box.width() <= medW * 3 }.sortedBy { it.box.left }) {
            val b = bands.lastOrNull()
            if (b != null && s.box.left <= b[1]) b[1] = max(b[1], s.box.right) else bands += intArrayOf(s.box.left, s.box.right)
        }
        if (bands.isEmpty()) bands += intArrayOf(0, ocr.width)
        fun bandOf(r: Rect): Int {
            var best = 0; var bestOv = Int.MIN_VALUE
            bands.forEachIndexed { i, b ->
                val ov = min(b[1], r.right) - max(b[0], r.left)
                val score = if (ov > 0) ov else -min(kotlin.math.abs(b[0] - r.centerX()), kotlin.math.abs(b[1] - r.centerX()))
                if (score > bestOv) { bestOv = score; best = i }
            }
            return best
        }
        val cells = rows.map { row ->
            val out = MutableList(bands.size) { "" }
            row.sortedBy { it.box.left }.forEach { s -> val i = bandOf(s.box); out[i] = TextJoin.glue(out[i], s.text) }
            out.toList()
        }
        return TableModel(cells)
    }

    private fun slot(lines: List<Int>, v: Int): Int {
        for (i in 0 until lines.size - 1) if (v < lines[i + 1]) return i
        return lines.size - 2
    }
}
