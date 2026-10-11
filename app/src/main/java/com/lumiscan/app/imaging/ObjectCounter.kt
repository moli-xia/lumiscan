package com.lumiscan.app.imaging

import android.graphics.Bitmap
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CountMark(val x: Float, val y: Float, val r: Float)

/**
 * 拍照计数 on the phone (also the fallback when AI counting is unavailable): counts repeated
 * objects — pills, coins, pipe / rebar ends, logs — as blobs in a Laplacian-of-Gaussian scale space.
 *
 * Nothing is thresholded globally: the objects' size and whether they are lighter or darker than
 * their surroundings are read from the blob responses (the size and polarity whose blobs cover the
 * most of the picture), so uneven light, textured tables and bundles filling the whole frame work.
 * Edges and lines are rejected by the Hessian test; candidates that look unlike the confident
 * detections (background texture) are dropped; packed objects are separated by their spacing.
 */
object ObjectCounter {

    private const val WORK = 1000
    private const val STEP = 1.18
    /** Largest ratio of principal curvatures that is still a blob, not an edge. */
    private const val RHO = 4f

    private class Level(val r: Float, val fx: Float, val w: Int, val h: Int, val resp: FloatArray, val blob: BooleanArray) {
        /** Response at full-resolution point (x, y). */
        fun at(x: Float, y: Float): Float {
            val xi = (x * fx).roundToInt().coerceIn(0, w - 1)
            val yi = (y * fx).roundToInt().coerceIn(0, h - 1)
            return resp[yi * w + xi]
        }
    }

    private class Cand(val x: Float, val y: Float, val r: Float, val v: Float, val level: Int)

    /** [sensitivity] 0..1: higher accepts fainter objects. */
    fun count(src: Bitmap, sensitivity: Float = 0.5f): List<CountMark> {
        val s = min(1f, WORK.toFloat() / max(src.width, src.height))
        val rgb = Cv.rgb(src)
        if (s < 1f) Imgproc.resize(rgb, rgb, Size((src.width * s).roundToInt().toDouble(), (src.height * s).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val w = rgb.cols(); val h = rgb.rows()
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        gray.convertTo(gray, CvType.CV_32F, 1.0 / 255)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val labBytes = Cv.bytes(lab)
        Cv.release(rgb, lab)

        val levels = ArrayList<Level>()
        var r = 4.0
        while (r <= min(w, h) / 6.0) {
            levels += level(gray, r, w, h)
            r *= STEP
        }
        gray.release()
        if (levels.size < 3) return emptyList()

        val cands = mapOf(1 to ArrayList<Cand>(), -1 to ArrayList<Cand>())
        levels.forEachIndexed { i, lv -> collect(levels, i, lv, cands) }
        val all = (cands[1]!! + cands[-1]!!).map { it.v }.sorted()
        if (all.isEmpty()) return emptyList()
        val top = all[(all.size * 0.995).toInt().coerceAtMost(all.lastIndex)]

        // Object size and polarity: the blob scale (±1 step) whose clear blobs cover the most area.
        // Counting by number would favour small texture specks; area favours the real objects.
        var best = 0.0; var sign = 1; var bi = -1
        for ((sg, list) in cands) {
            val area = DoubleArray(levels.size)
            for (c in list) if (c.v >= 0.25f * top) area[c.level] += (c.v * c.r * c.r).toDouble()
            for (i in levels.indices) {
                val a = area[i] + (if (i > 0) area[i - 1] else 0.0) + (if (i < levels.lastIndex) area[i + 1] else 0.0)
                if (a > best) { best = a; sign = sg; bi = i }
            }
        }
        if (bi < 0) return emptyList()
        val r0 = levels[bi].r
        val sel = cands[sign]!!.filter { kotlin.math.abs(it.level - bi) <= 2 }
        if (sel.isEmpty()) return emptyList()

        // Thresholds relative to the typical strong response of this kind of object.
        val vs = sel.map { it.v }.sortedDescending()
        val strongest = vs.take(max(3, vs.size / 10))
        val ref = strongest[strongest.size / 2]
        val strong = ref * (0.45f - 0.2f * sensitivity.coerceIn(0f, 1f))
        val weak = strong * 0.45f

        // Objects share a look: a candidate needs enough confident detections of a similar colour.
        val colors = LabMeans(labBytes, w, h)
        val seeds = sel.filter { it.v >= strong }.map { colors.around(it) }
        val need = max(3, (seeds.size * 0.08).toInt())
        val accepted = sel.filter { c ->
            when {
                c.v < weak -> false
                seeds.size < 8 -> c.v >= strong
                else -> {
                    val m = colors.around(c)
                    var n = 0
                    for (sd in seeds) {
                        val d0 = sd[0] - m[0]; val d1 = sd[1] - m[1]; val d2 = sd[2] - m[2]
                        if (d0 * d0 + d1 * d1 + d2 * d2 < 14f * 14f && ++n >= need) break
                    }
                    n >= need
                }
            }
        }.sortedByDescending { it.v }

        // Packed objects sit about two radii apart, more than the blob scale suggests (neighbours
        // shrink it): use the spacing of the confident detections to merge duplicates in one object.
        val pre = ArrayList<Cand>()
        for (c in accepted) if (c.v >= strong && pre.none { hypot(it.x - c.x, it.y - c.y) < 0.9f * r0 }) pre += c
        val nn = if (pre.size >= 4) pre.map { a -> pre.minOf { b -> if (a === b) Float.MAX_VALUE else hypot(a.x - b.x, a.y - b.y) } }.sorted()[pre.size / 2] else 0f
        val sep = (0.6f * nn).coerceIn(0.9f * r0, 1.6f * r0)
        val kept = ArrayList<Cand>()
        for (c in accepted) {
            if (kept.none { hypot(it.x - c.x, it.y - c.y) < maxOf(0.9f * c.r, sep, 0.9f * it.r) }) kept += c
        }
        // One mark size for all: the objects are alike, and per-blob scales only look noisy.
        val markR = kept.map { it.r }.sorted().getOrElse(kept.size / 2) { r0 }
        return kept.map { CountMark(it.x / s, it.y / s, markR / s) }
    }

    /** Scale-normalised −LoG (bright blobs positive) at radius [r], computed where σ ≈ 3 px. */
    private fun level(gray: Mat, r: Double, w: Int, h: Int): Level {
        val sigma = r / sqrt(2.0)
        val f = min(1.0, 3.0 / sigma)
        val m = if (f < 1) Mat().also {
            Imgproc.resize(gray, it, Size(max(8, (w * f).roundToInt()).toDouble(), max(8, (h * f).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } else gray
        val fx = m.cols().toFloat() / w
        val se = sigma * fx
        val g = Mat()
        Imgproc.GaussianBlur(m, g, Size(0.0, 0.0), se)
        val dxx = Mat(); val dyy = Mat(); val dxy = Mat()
        Imgproc.Sobel(g, dxx, CvType.CV_32F, 2, 0, 3)
        Imgproc.Sobel(g, dyy, CvType.CV_32F, 0, 2, 3)
        Imgproc.Sobel(g, dxy, CvType.CV_32F, 1, 1, 3)
        val lw = g.cols(); val lh = g.rows(); val n = lw * lh
        val a = FloatArray(n).also { dxx.get(0, 0, it) }
        val b = FloatArray(n).also { dyy.get(0, 0, it) }
        val c = FloatArray(n).also { dxy.get(0, 0, it) }
        Cv.release(g, dxx, dyy, dxy)
        if (m !== gray) m.release()
        val k = (se * se).toFloat()
        val lim = (RHO + 1) * (RHO + 1) / RHO
        val resp = FloatArray(n)
        val blob = BooleanArray(n)
        for (i in 0 until n) {
            val tr = a[i] + b[i]
            val det = a[i] * b[i] - c[i] * c[i]
            resp[i] = -tr * k
            blob[i] = det > 0 && tr * tr < det * lim
        }
        return Level(r.toFloat(), fx, lw, lh, resp, blob)
    }

    /** Blob centres of level [i]: 3×3 extrema that are also extrema across the neighbouring scales. */
    private fun collect(levels: List<Level>, i: Int, lv: Level, out: Map<Int, MutableList<Cand>>) {
        val w = lv.w; val h = lv.h; val a = lv.resp
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                val v = a[p]
                if (v == 0f || !lv.blob[p]) continue
                val sg = if (v > 0) 1 else -1
                val sv = v * sg
                var peak = true
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue
                        if (a[yy * w + xx] * sg > sv) { peak = false; break@loop }
                    }
                }
                if (!peak) continue
                val fx = x / lv.fx; val fy = y / lv.fx
                if (i > 0 && levels[i - 1].at(fx, fy) * sg > sv) continue
                if (i < levels.lastIndex && levels[i + 1].at(fx, fy) * sg > sv) continue
                out.getValue(sg) += Cand(fx, fy, lv.r, sv, i)
            }
        }
    }

    /** Mean Lab colour of square windows, from per-channel integral images. */
    private class LabMeans(lab: ByteArray, private val w: Int, private val h: Int) {
        private val sums = Array(3) { ch ->
            val s = LongArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0L
                for (x in 0 until w) {
                    row += lab[(y * w + x) * 3 + ch].toInt() and 0xFF
                    s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row
                }
            }
            s
        }

        /** Mean colour of the central part (half the radius) of candidate [c]. */
        fun around(c: Cand): FloatArray {
            val hr = c.r * 0.5f
            val x0 = (c.x - hr).toInt().coerceIn(0, w - 1); val x1 = (c.x + hr + 1).toInt().coerceIn(x0 + 1, w)
            val y0 = (c.y - hr).toInt().coerceIn(0, h - 1); val y1 = (c.y + hr + 1).toInt().coerceIn(y0 + 1, h)
            val n = ((x1 - x0) * (y1 - y0)).toFloat()
            return FloatArray(3) { ch ->
                val s = sums[ch]; val W = w + 1
                (s[y1 * W + x1] - s[y0 * W + x1] - s[y1 * W + x0] + s[y0 * W + x0]) / n
            }
        }
    }
}
