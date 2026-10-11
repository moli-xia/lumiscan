package com.lumiscan.app.imaging

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the outline of a document (paper, card, book page) in a photo.
 * Several edge/threshold strategies are tried on a downscaled copy and the largest
 * plausible convex quadrilateral wins.
 */
object EdgeDetector {

    private const val WORK = 512
    private const val REFINE = 1600

    /**
     * Outline of the document in [bitmap] pixels, or null. Paper pages are pulled slightly inwards so
     * detection jitter never leaves a sliver of background. A [card] (ID card, bank card, licence …)
     * must never lose its edge instead: its sides are fitted to the card's edges at higher resolution
     * (corners where the sides meet, not on the rounded corners) and a thin margin is kept around it.
     * [foldTrim] stops an outline running off the frame at a booklet's fold; rigid cards have none.
     */
    fun detect(bitmap: Bitmap, card: Boolean = false, foldTrim: Boolean = true): Quad? {
        val longest = max(bitmap.width, bitmap.height)
        val s = if (longest > WORK) WORK.toFloat() / longest else 1f
        val small = if (s < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true) else bitmap
        val m = Cv.rgba(small)
        val q = runCatching { detect(m, foldTrim) }.getOrNull()
        m.release()
        if (small !== bitmap) small.recycle()
        if (q == null) return null
        if (!card) return q.scale(1f / s).shrink(0.006f)
        val full = q.scale(1f / s)
        val r = if (longest > REFINE) REFINE.toFloat() / longest else 1f
        val mid = if (r < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * r).roundToInt(), (bitmap.height * r).roundToInt(), true) else bitmap
        val gray = Cv.gray(mid)
        if (mid !== bitmap) mid.recycle()
        Imgproc.GaussianBlur(gray, gray, Size(0.0, 0.0), 1.0)
        val refined = runCatching { refineSides(gray, full.scale(r)) }.getOrNull()?.scale(1f / r) ?: full
        gray.release()
        val short = minOf(Quad.dist(refined.tl, refined.bl), Quad.dist(refined.tr, refined.br), Quad.dist(refined.tl, refined.tr), Quad.dist(refined.bl, refined.br))
        return (offsetSides(refined, short * CARD_MARGIN) ?: refined).clamp(bitmap.width - 1f, bitmap.height - 1f)
    }

    /** Margin kept around a card, as a fraction of its short side (≈0.8 mm on an ID card). */
    const val CARD_MARGIN = 0.015f

    /** A side as a line: a point on it and its unit direction, running like the quad's corners. */
    private class Line(val px: Float, val py: Float, val ux: Float, val uy: Float) {
        /** Outward normal (corners run clockwise, y down). */
        val nx get() = uy
        val ny get() = -ux
        fun shifted(d: Float) = Line(px + nx * d, py + ny * d, ux, uy)
    }

    private fun lineOf(a: Pt, b: Pt): Line {
        val len = Quad.dist(a, b).coerceAtLeast(1e-3f)
        return Line(a.x, a.y, (b.x - a.x) / len, (b.y - a.y) / len)
    }

    private fun meet(a: Line, b: Line): Pt? {
        val cross = a.ux * b.uy - a.uy * b.ux
        if (abs(cross) < 1e-3f) return null
        val t = ((b.px - a.px) * b.uy - (b.py - a.py) * b.ux) / cross
        return Pt(a.px + a.ux * t, a.py + a.uy * t)
    }

    /** Corners of the quad whose sides are [lines] (side k runs from corner k to corner k+1). */
    private fun cornersOf(lines: List<Line>): Quad? {
        val c = (0 until 4).map { k -> meet(lines[(k + 3) % 4], lines[k]) ?: return null }
        return Quad(c[0], c[1], c[2], c[3]).takeIf { it.isConvex() }
    }

    /** Moves every side of [q] outwards by [d] pixels. */
    private fun offsetSides(q: Quad, d: Float): Quad? {
        val p = q.points
        return cornersOf((0 until 4).map { k -> lineOf(p[k], p[(k + 1) % 4]).shifted(d) })
    }

    /**
     * Moves each side of [q] onto the strongest edge just across it in [gray] and returns the corners
     * where the fitted sides meet. Only the middle of each side is sampled, so rounded card corners
     * don't pull the sides inwards. Of similar edges the outermost wins (a printed border or the
     * card's own rim inside it must not cut the card). Null when the result is implausible.
     */
    private fun refineSides(gray: Mat, q: Quad): Quad? {
        val w = gray.cols(); val h = gray.rows()
        val g = Cv.bytes(gray)
        fun at(x: Float, y: Float): Float? {
            if (x < 0 || y < 0 || x > w - 1.001f || y > h - 1.001f) return null
            val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
            fun v(xx: Int, yy: Int) = (g[yy * w + xx].toInt() and 0xFF).toFloat()
            val top = v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx
            val bottom = v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx
            return top * (1 - fy) + bottom * fy
        }
        val p = q.points
        val short = (0 until 4).minOf { Quad.dist(p[it], p[(it + 1) % 4]) }
        val reach = max(4f, short * 0.03f).roundToInt()
        val lines = (0 until 4).map { k ->
            val side = lineOf(p[k], p[(k + 1) % 4])
            val len = Quad.dist(p[k], p[(k + 1) % 4])
            val pts = ArrayList<Pt>()
            val profile = FloatArray(2 * reach + 3)
            for (i in 0 until 40) {
                val t = 0.15f + 0.7f * i / 39
                val cx = side.px + side.ux * len * t; val cy = side.py + side.uy * len * t
                var ok = true
                for (j in profile.indices) {
                    val d = (j - reach - 1).toFloat()
                    profile[j] = at(cx + side.nx * d, cy + side.ny * d) ?: run { ok = false; 0f }
                    if (!ok) break
                }
                if (!ok) continue
                var best = 0f
                val grad = FloatArray(2 * reach + 1) { j -> abs(profile[j + 2] - profile[j]) }
                for (v in grad) best = max(best, v)
                if (best < 12f) continue
                val j = grad.indices.last { grad[it] >= best * 0.7f }
                val d = (j - reach).toFloat()
                pts += Pt(cx + side.nx * d, cy + side.ny * d)
            }
            if (pts.size < 12) side else fitLine(pts, side) ?: side
        }
        val out = cornersOf(lines) ?: return null
        val moved = out.points.zip(p).maxOf { (a, b) -> Quad.dist(a, b) }
        return out.takeIf { moved < short * 0.08f && anglesOk(it) }
    }

    /** Total-least-squares line through [pts] with one round of outlier rejection, oriented like [like]. */
    private fun fitLine(pts: List<Pt>, like: Line): Line? {
        fun fit(ps: List<Pt>): Line {
            val mx = ps.sumOf { it.x.toDouble() } / ps.size; val my = ps.sumOf { it.y.toDouble() } / ps.size
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (q in ps) { val dx = q.x - mx; val dy = q.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
            val ang = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
            var ux = kotlin.math.cos(ang).toFloat(); var uy = kotlin.math.sin(ang).toFloat()
            if (ux * like.ux + uy * like.uy < 0) { ux = -ux; uy = -uy }
            return Line(mx.toFloat(), my.toFloat(), ux, uy)
        }
        fun res(l: Line, q: Pt) = abs((q.x - l.px) * l.nx + (q.y - l.py) * l.ny)
        val first = fit(pts)
        val med = pts.map { res(first, it) }.sorted()[pts.size / 2]
        val inliers = pts.filter { res(first, it) <= max(1.5f, med * 2.5f) }
        if (inliers.size < 8) return null
        return fit(inliers)
    }

    private fun Quad.shrink(f: Float): Quad {
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4
        fun m(p: Pt) = Pt(p.x + (cx - p.x) * f * 2, p.y + (cy - p.y) * f * 2)
        return Quad(m(tl), m(tr), m(br), m(bl))
    }

    /** [rgba] should already be small (≈500px). Returns corners in its pixel space. */
    fun detect(rgba: Mat, foldTrim: Boolean = true): Quad? {
        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        val imageArea = w * h
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val candidates = ArrayList<Cand>()
        val kernel = Cv.rect(3, 3)

        // 1. Canny on luminance with thresholds derived from the median.
        val med = median(gray)
        val edges = Mat()
        Imgproc.Canny(gray, edges, max(10.0, 0.66 * med), max(40.0, 1.33 * med))
        Imgproc.dilate(edges, edges, kernel)
        collect(edges, imageArea, candidates)

        // 2. Canny on saturation (white paper on coloured desks, coloured cards).
        val hsv = Mat()
        Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        val sat = ch[1]
        Imgproc.GaussianBlur(sat, sat, Size(5.0, 5.0), 0.0)
        val satEdges = Mat()
        Imgproc.Canny(sat, satEdges, 30.0, 90.0)
        Imgproc.dilate(satEdges, satEdges, kernel)
        collect(satEdges, imageArea, candidates)

        // 3. Otsu threshold: bright page against a darker background.
        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, Cv.rect(9, 9))
        collect(bin, imageArea, candidates)

        // 4. Bright pictures (white paper or a pale card on a light desk): the median-based thresholds
        // above are then far higher than the faint step between paper and table.
        val faint = Mat()
        if (med > 110) {
            Imgproc.Canny(gray, faint, 20.0, 50.0)
            Imgproc.dilate(faint, faint, kernel)
            collect(faint, imageArea, candidates)
        }

        Cv.release(edges, hsv, satEdges, bin, kernel, faint)
        ch.forEach { it.release() }

        // Prefer the largest closed quad that is not just the frame itself. Regions whose corners
        // sit on the image border (sky, walls, desk areas) are background, not a page.
        val best = candidates
            .filter { it.area < imageArea * 0.985 && it.quad.points.count { p -> borders(p, w, h) != 0 } < 3 }
            .sortedByDescending { it.score }
            .firstOrNull { separatesBackground(rgba, it.quad) }
        val quad = best?.let { if (foldTrim) trimAtFold(gray, it.quad, w, h) else it.quad }
        gray.release()
        return quad?.clamp(w.toFloat() - 1, h.toFloat() - 1)
    }

    /** Bit mask of the frame borders [p] lies on: 1 top, 2 right, 4 bottom, 8 left. */
    private fun borders(p: Pt, w: Double, h: Double): Int {
        val mx = w * 0.015; val my = h * 0.015
        return (if (p.y < my) 1 else 0) or (if (p.x > w - 1 - mx) 2 else 0) or (if (p.y > h - 1 - my) 4 else 0) or (if (p.x < mx) 8 else 0)
    }

    /**
     * The side of [q] where the paper runs off the frame, if any: a side near the frame border with
     * no room outside it, or with more paper (not background) just outside it. Sides are numbered
     * like the edges p[k]→p[k+1] of the quad: 0 top, 1 right, 2 bottom, 3 left.
     */
    private fun cutSide(gray: Mat, q: Quad, w: Double, h: Double): Int? {
        val p = q.points
        val gw = gray.cols(); val gh = gray.rows()
        val g = Cv.bytes(gray)
        fun near(pt: Pt, side: Int) = when (side) { 0 -> pt.y < h * 0.06; 1 -> pt.x > w * 0.94; 2 -> pt.y > h * 0.94; else -> pt.x < w * 0.06 }
        for (k in 0 until 4) {
            val a = p[k]; val b = p[(k + 1) % 4]
            if (borders(a, w, h) and borders(b, w, h) != 0) return k
            if (!near(a, k) || !near(b, k)) continue
            // Outward normal of a→b (corners run clockwise, y down).
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = kotlin.math.hypot(dx, dy)
            val nx = dy / len; val ny = -dx / len
            val inside = ArrayList<Int>(); val outside = ArrayList<Int>()
            var tries = 0
            for (i in 2..18) {
                val t = i / 20f
                val cx = a.x + dx * t; val cy = a.y + dy * t
                for (d in 4..8 step 2) {
                    fun at(f: Int): Int? {
                        val x = (cx + nx * d * f).roundToInt(); val y = (cy + ny * d * f).roundToInt()
                        return if (x in 0 until gw && y in 0 until gh) g[y * gw + x].toInt() and 0xFF else null
                    }
                    at(-1)?.let(inside::add)
                    tries++
                    at(1)?.let(outside::add)
                }
            }
            if (outside.size < tries * 0.3) return k // hugging the frame: nothing beyond the side
            val mi = inside.sorted()[inside.size / 2]; val mo = outside.sorted()[outside.size / 2]
            if (abs(mi - mo) < 15) return k // the paper carries on past the side
        }
        return null
    }

    /**
     * A page running off the frame — typically an open booklet with the facing page half in view —
     * is outlined up to the frame edge. When a straight line crosses it parallel to that cut side,
     * from the paper's top edge to its bottom edge (the fold, or the edge of the facing page), the
     * outline stops at that line. Rules and table borders stay inside the margins and don't count.
     */
    private fun trimAtFold(gray: Mat, q: Quad, w: Double, h: Double): Quad {
        val p = q.points
        val k = cutSide(gray, q, w, h) ?: return q
        // Canonical frame with the cut side on the right: a→b top, b→c the cut side, d→a the far side.
        val a = p[(k + 3) % 4]; val b = p[k]; val c = p[(k + 1) % 4]; val d = p[(k + 2) % 4]
        fun len(u: Pt, v: Pt) = kotlin.math.hypot((u.x - v.x).toDouble(), (u.y - v.y).toDouble())
        val cw = 400.0
        val chh = (cw * (len(b, c) + len(a, d)) / (len(a, b) + len(d, c))).coerceIn(200.0, 900.0)
        val src = MatOfPoint2f(*listOf(a, b, c, d).map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(cw, 0.0), Point(cw, chh), Point(0.0, chh))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val rect = Mat()
        Imgproc.warpPerspective(gray, rect, m, Size(cw, chh), Imgproc.INTER_LINEAR)
        // Horizontal brightness change. A fold is often only a faint shading step, but it is there in
        // every row; text strokes are strong but only in some rows at any one x.
        val gx = Mat()
        Imgproc.Sobel(rect, gx, org.opencv.core.CvType.CV_16S, 1, 0, 3)
        Core.convertScaleAbs(gx, gx)
        val ew = gx.cols(); val eh = gx.rows()
        val e = Cv.bytes(gx)
        Cv.release(rect, gx, m)
        val y0 = (eh * 0.02).toInt(); val y1 = (eh * 0.98).toInt()
        val topEnd = (eh * 0.10).toInt(); val bottomStart = (eh * 0.90).toInt()
        val v = IntArray(y1 - y0)
        val hist = IntArray(256)
        fun percentile(from: Int, to: Int, f: Double): Int {
            hist.fill(0)
            for (i in from until to) hist[v[i]]++
            var need = ((to - from) * f).toInt(); var g = 0
            while (g < 255) { need -= hist[g]; if (need < 0) break; g++ }
            return g
        }
        class Line(val top: Double, val bottom: Double, val core: Int, val ends: Int)
        val lines = ArrayList<Line>()
        // The fold must leave at least 45% of the outline and cut off at least 6%.
        for (x0 in (ew * 0.45).toInt()..(ew * 0.94).toInt() step 2) { // ±1 px per row covers the gaps
            for (tilt in -10..10) {
                val shift = tilt * ew * 0.01 // x difference between the bottom and the top of the line
                for (y in y0 until y1) {
                    val x = (x0 + shift * (y.toDouble() / eh - 0.5)).roundToInt().coerceIn(1, ew - 2)
                    val i = y * ew + x
                    v[y - y0] = max(e[i].toInt() and 0xFF, max(e[i - 1].toInt() and 0xFF, e[i + 1].toInt() and 0xFF))
                }
                // Present in most rows, and also right up to the paper's top and bottom edges (rules
                // and table borders stop at the margins).
                val ends = minOf(percentile(0, topEnd - y0, 0.5), percentile(bottomStart - y0, y1 - y0, 0.5))
                lines += Line(x0 - shift / 2, x0 + shift / 2, percentile(0, y1 - y0, 0.25), ends)
            }
        }
        // Typical text / blank columns set the noise level.
        val noise = lines.map { it.core }.sorted()[lines.size / 2]
        val need = max(8, (noise * 2.5).roundToInt())
        val best = lines.filter { it.core >= need && it.ends >= need * 0.6 }.maxByOrNull { it.core }
        if (best == null) { Cv.release(src, dst); return q }
        val back = MatOfPoint2f()
        val inv = Imgproc.getPerspectiveTransform(dst, src)
        Core.perspectiveTransform(MatOfPoint2f(Point(best.top, 0.0), Point(best.bottom, chh)), back, inv)
        val (nb, nc) = back.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) }
        Cv.release(src, dst, inv, back)
        val trimmed = q.with(k, nb).with((k + 1) % 4, nc)
        return if (trimmed.isConvex() && anglesOk(trimmed)) trimmed else q
    }

    /**
     * A page outline separates paper from a different-looking background. Ruled tables or boxes
     * inside an already-flat page have the same paper on both sides and are rejected.
     */
    private fun separatesBackground(rgba: Mat, q: Quad): Boolean {
        fun poly(f: Float): MatOfPoint {
            val cx = q.points.sumOf { it.x.toDouble() } / 4; val cy = q.points.sumOf { it.y.toDouble() } / 4
            return MatOfPoint(*q.points.map { Point(cx + (it.x - cx) * f, cy + (it.y - cy) * f) }.toTypedArray())
        }
        fun ring(outerF: Float, innerF: Float): Mat {
            val a = Mat.zeros(rgba.size(), org.opencv.core.CvType.CV_8UC1)
            val b = Mat.zeros(rgba.size(), org.opencv.core.CvType.CV_8UC1)
            Imgproc.fillConvexPoly(a, poly(outerF), org.opencv.core.Scalar(255.0))
            Imgproc.fillConvexPoly(b, poly(innerF), org.opencv.core.Scalar(255.0))
            Core.subtract(a, b, a)
            b.release()
            return a
        }
        // Rings on either side of the outline, skipping the outline stroke itself.
        val outside = ring(1.10f, 1.03f)
        val inside = ring(0.97f, 0.90f)
        val px = Cv.bytes(rgba)
        val o = medianColor(px, Cv.bytes(outside))
        val i = medianColor(px, Cv.bytes(inside))
        Cv.release(outside, inside)
        if (o == null || i == null) return true // outline hugs the frame: nothing to compare
        val d = kotlin.math.sqrt((0 until 3).sumOf { ((i[it] - o[it]) * (i[it] - o[it])).toDouble() })
        return d > 28
    }

    /** Per-channel median of RGBA pixels where [mask] is set (the dominant "paper" colour). */
    private fun medianColor(rgba: ByteArray, mask: ByteArray): IntArray? {
        val hist = Array(3) { IntArray(256) }
        var n = 0
        for (p in mask.indices) {
            if (mask[p].toInt() == 0) continue
            n++
            for (c in 0 until 3) hist[c][rgba[p * 4 + c].toInt() and 0xFF]++
        }
        if (n < 50) return null
        return IntArray(3) { c ->
            var acc = 0; var v = 0
            while (v < 255) { acc += hist[c][v]; if (acc >= n / 2) break; v++ }
            v
        }
    }

    private class Cand(val quad: Quad, val area: Double, val score: Double)

    private fun collect(binary: Mat, imageArea: Double, out: MutableList<Cand>) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = imageArea * 0.12
        contours
            .map { it to Imgproc.contourArea(it) }
            .sortedByDescending { it.second }
            .take(10)
            .forEach { (c, _) ->
                quadFrom(c, minArea)?.let { out += it }
            }
        contours.forEach { it.release() }
    }

    private fun quadFrom(contour: MatOfPoint, minArea: Double): Cand? {
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val pts = contour.toArray()
        val hull = hullIdx.toArray().map { pts[it] }
        hullIdx.release()
        if (hull.size < 4) return null
        val hull2f = MatOfPoint2f(*hull.toTypedArray())
        val hullArea = Imgproc.contourArea(hull2f)
        if (hullArea < minArea) { hull2f.release(); return null }
        // Closed outlines fill their hull; broken edges (open contours) are kept but ranked lower.
        val closed = Imgproc.contourArea(contour) >= hullArea * 0.6
        val peri = Imgproc.arcLength(hull2f, true)
        var eps = 0.015
        var result: Cand? = null
        while (eps <= 0.1) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
            val a = approx.toArray()
            approx.release()
            if (a.size == 4) {
                val q = Quad.order(a.map { Pt(it.x.toFloat(), it.y.toFloat()) })
                val qa = polyArea(q)
                if (q.isConvex() && qa >= minArea && anglesOk(q)) result = Cand(q, qa, if (closed) qa else qa * 0.75)
                break
            }
            if (a.size < 4) break
            eps += 0.01
        }
        hull2f.release()
        return result
    }

    /** Rejects extremely skewed shapes (interior angles outside 35°..145°). */
    private fun anglesOk(q: Quad): Boolean {
        val p = q.points
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val cos = (v1x * v2x + v1y * v2y) / (kotlin.math.hypot(v1x, v1y) * kotlin.math.hypot(v2x, v2y) + 1e-6f)
            if (abs(cos) > 0.82f) return false
        }
        return true
    }

    private fun polyArea(q: Quad): Double {
        val p = q.points
        var s = 0.0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2
    }

    private fun median(gray: Mat): Double {
        val bytes = Cv.bytes(gray)
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xFF]++
        var acc = 0
        val half = bytes.size / 2
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= half) return i.toDouble()
        }
        return 127.0
    }

    fun toPoints(q: Quad): MatOfPoint2f = MatOfPoint2f(*q.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
}
