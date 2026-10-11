package com.lumiscan.app.imaging

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc
import kotlin.math.max

data class CellMerge(val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int)

/** Ruled table found in an image: column x-lines, row y-lines (image pixels) and merged cells. */
data class TableGrid(val xs: List<Int>, val ys: List<Int>, val merges: List<CellMerge>, val bounds: Rect) {
    val rows get() = ys.size - 1
    val cols get() = xs.size - 1
}

/** Detects ruled (bordered) tables by isolating long horizontal and vertical strokes. */
object TableDetector {

    private const val WORK = 2000

    fun detect(src: Bitmap): TableGrid? {
        val s = WORK.toFloat() / max(src.width, src.height)
        val scale = if (s < 1f) s else 1f
        val small = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val grid = runCatching { detectInternal(small) }.getOrNull()
        if (small !== src) small.recycle()
        grid ?: return null
        fun sc(v: Int) = (v / scale).toInt()
        return grid.copy(
            xs = grid.xs.map(::sc),
            ys = grid.ys.map(::sc),
            bounds = Rect(sc(grid.bounds.left), sc(grid.bounds.top), sc(grid.bounds.right), sc(grid.bounds.bottom)),
        )
    }

    private fun detectInternal(bmp: Bitmap): TableGrid? {
        val gray = Cv.gray(bmp)
        val w = gray.cols(); val h = gray.rows()
        val bin = Mat()
        Imgproc.adaptiveThreshold(gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 15, 8.0)

        val hLen = max(20, w / 28)
        val vLen = max(20, h / 40)
        val horiz = Mat(); val vert = Mat()
        Imgproc.morphologyEx(bin, horiz, Imgproc.MORPH_CLOSE, Cv.rect(3, 1))
        Imgproc.morphologyEx(horiz, horiz, Imgproc.MORPH_OPEN, Cv.rect(hLen, 1))
        Imgproc.morphologyEx(bin, vert, Imgproc.MORPH_CLOSE, Cv.rect(1, 3))
        Imgproc.morphologyEx(vert, vert, Imgproc.MORPH_OPEN, Cv.rect(1, vLen))
        Imgproc.dilate(horiz, horiz, Cv.rect(1, 3))
        Imgproc.dilate(vert, vert, Cv.rect(3, 1))

        val grid = Mat()
        Core.bitwise_or(horiz, vert, grid)
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(grid, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val box = contours.map { Imgproc.boundingRect(it) }.maxByOrNull { it.width.toLong() * it.height }
        contours.forEach { it.release() }
        val hb = Cv.bytes(horiz)
        val vb = Cv.bytes(vert)
        Cv.release(gray, bin, horiz, vert, grid, hier)
        if (box == null || box.width < w * 0.25 || box.height < h * 0.04) return null
        val bx0 = box.x; val bx1 = box.x + box.width - 1
        val by0 = box.y; val by1 = box.y + box.height - 1

        val rowProfile = IntArray(h)
        for (y in by0..by1) { var c = 0; for (x in bx0..bx1) if (hb[y * w + x].toInt() != 0) c++; rowProfile[y] = c }
        val colProfile = IntArray(w)
        for (x in bx0..bx1) { var c = 0; for (y in by0..by1) if (vb[y * w + x].toInt() != 0) c++; colProfile[x] = c }

        val rawYs = cluster((by0..by1).filter { rowProfile[it] >= box.width * 0.12 })
        val rawXs = cluster((bx0..bx1).filter { colProfile[it] >= box.height * 0.12 })
        // A ruling must meet at least two perpendicular rulings; long runs of text strokes
        // inside cells never reach the cell borders, so they are rejected here.
        fun hTouches(y: Int, x: Int) = (-3..3).any { dy -> val yy = y + dy; yy in 0 until h && (-8..8).any { dx -> val xx = x + dx; xx in 0 until w && hb[yy * w + xx].toInt() != 0 } }
        fun vTouches(x: Int, y: Int) = (-8..8).any { dy -> val yy = y + dy; yy in 0 until h && (-3..3).any { dx -> val xx = x + dx; xx in 0 until w && vb[yy * w + xx].toInt() != 0 } }
        val xsChecked = rawXs.filter { x -> rawYs.count { y -> vTouches(x, y) } >= 2 }
        val ysChecked = rawYs.filter { y -> xsChecked.count { x -> hTouches(y, x) } >= 2 }
        val ys = withEdges(ysChecked, by0, by1)
        val xs = withEdges(xsChecked.filter { x -> ysChecked.count { y -> vTouches(x, y) } >= 2 }, bx0, bx1)
        if (ys.size < 2 || xs.size < 2 || (ys.size - 1) * (xs.size - 1) < 2) return null

        val rows = ys.size - 1; val cols = xs.size - 1
        val parent = IntArray(rows * cols) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        fun vCoverage(x: Int, y0: Int, y1: Int): Float {
            val m = max(2, (y1 - y0) / 8)
            var hit = 0; var tot = 0
            for (y in (y0 + m)..(y1 - m)) {
                tot++
                if ((-4..4).any { d -> val xx = x + d; xx in 0 until w && vb[y * w + xx].toInt() != 0 }) hit++
            }
            return if (tot == 0) 1f else hit.toFloat() / tot
        }
        fun hCoverage(y: Int, x0: Int, x1: Int): Float {
            val m = max(2, (x1 - x0) / 8)
            var hit = 0; var tot = 0
            for (x in (x0 + m)..(x1 - m)) {
                tot++
                if ((-4..4).any { d -> val yy = y + d; yy in 0 until h && hb[yy * w + x].toInt() != 0 }) hit++
            }
            return if (tot == 0) 1f else hit.toFloat() / tot
        }
        for (r in 0 until rows) for (c in 0 until cols - 1) {
            if (vCoverage(xs[c + 1], ys[r], ys[r + 1]) < 0.5f) union(r * cols + c, r * cols + c + 1)
        }
        for (r in 0 until rows - 1) for (c in 0 until cols) {
            if (hCoverage(ys[r + 1], xs[c], xs[c + 1]) < 0.5f) union(r * cols + c, (r + 1) * cols + c)
        }
        val groups = (0 until rows * cols).groupBy { find(it) }
        val merges = groups.values.filter { it.size > 1 }.map { cells ->
            val rs = cells.map { it / cols }; val cs = cells.map { it % cols }
            CellMerge(rs.min(), cs.min(), rs.max() - rs.min() + 1, cs.max() - cs.min() + 1)
        }
        return TableGrid(xs, ys, merges, Rect(bx0, by0, bx1, by1))
    }

    /** Collapses runs of adjacent line pixels into single line centres; drops near-duplicates. */
    private fun cluster(idx: List<Int>): List<Int> {
        if (idx.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var start = idx[0]; var prev = idx[0]
        for (i in idx.drop(1)) {
            if (i - prev > 6) { out += (start + prev) / 2; start = i }
            prev = i
        }
        out += (start + prev) / 2
        val dedup = ArrayList<Int>()
        for (v in out) if (dedup.isEmpty() || v - dedup.last() >= 12) dedup += v
        return dedup
    }

    private fun withEdges(lines: List<Int>, lo: Int, hi: Int): List<Int> {
        val l = lines.toMutableList()
        if (l.isEmpty() || l.first() - lo > 12) l.add(0, lo)
        if (hi - l.last() > 12) l.add(hi)
        return l
    }
}
