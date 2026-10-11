package com.lumiscan.app.imaging

import kotlin.math.hypot
import kotlin.math.max

data class Pt(val x: Float, val y: Float)

/** Four corners ordered top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    fun toList(): List<Float> = points.flatMap { listOf(it.x, it.y) }

    fun scale(s: Float) = Quad(tl * s, tr * s, br * s, bl * s)

    fun with(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    fun clamp(w: Float, h: Float) = Quad(tl.clamp(w, h), tr.clamp(w, h), br.clamp(w, h), bl.clamp(w, h))

    /**
     * Output size of the flattened quad. With the photo size known, the true aspect ratio of the
     * rectangle is recovered from the perspective (Zhang & He, "Whiteboard scanning", assuming
     * the principal point at the image centre); otherwise edge lengths are used.
     */
    fun outputSize(imageW: Int = 0, imageH: Int = 0): Pair<Int, Int> {
        val w = max(dist(tl, tr), dist(bl, br))
        val h = max(dist(tl, bl), dist(tr, br))
        val aspect = if (imageW > 0 && imageH > 0) trueAspect(imageW / 2.0, imageH / 2.0) else null
        if (aspect != null && aspect in 0.2..5.0 && kotlin.math.abs(aspect - w / h) / (w / h) < 0.35) {
            // Keep the larger measured dimension and derive the other from the recovered aspect.
            return if (w / h >= aspect) (h * aspect).toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
            else w.toInt().coerceAtLeast(1) to (w / aspect).toInt().coerceAtLeast(1)
        }
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    private fun trueAspect(u0: Double, v0: Double): Double? {
        fun v(p: Pt) = doubleArrayOf(p.x.toDouble(), p.y.toDouble(), 1.0)
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val m1 = v(tl); val m2 = v(tr); val m3 = v(bl); val m4 = v(br)
        val c14 = cross(m1, m4)
        val d2 = dot(cross(m2, m4), m3); val d3 = dot(cross(m3, m4), m2)
        if (kotlin.math.abs(d2) < 1e-9 || kotlin.math.abs(d3) < 1e-9) return null
        val k2 = dot(c14, m3) / d2
        val k3 = dot(c14, m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        if (kotlin.math.abs(n2[2]) < 1e-9 || kotlin.math.abs(n3[2]) < 1e-9) return null // fronto-parallel
        val f2 = -((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
            (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)) / (n2[2] * n3[2])
        if (f2 <= 0) return null
        // n^T (A^-T A^-1) n with A = [[f,0,u0],[0,f,v0],[0,0,1]]
        fun q(n: DoubleArray): Double {
            val x = n[0] - u0 * n[2]; val y = n[1] - v0 * n[2]
            return (x * x + y * y) / f2 + n[2] * n[2]
        }
        val r = q(n2) / q(n3)
        return if (r > 0) kotlin.math.sqrt(r) else null
    }

    /** True when the corners form a convex, non-degenerate polygon. */
    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Rotates the quad together with a 90° clockwise rotation of a [w]x[h] image. */
    fun rotateCw(w: Float, h: Float): Quad {
        fun r(p: Pt) = Pt(h - p.y, p.x)
        return order(listOf(r(tl), r(tr), r(br), r(bl)))
    }

    companion object {
        fun full(w: Float, h: Float) = Quad(Pt(0f, 0f), Pt(w, 0f), Pt(w, h), Pt(0f, h))

        fun inset(w: Float, h: Float, f: Float = 0.08f) =
            Quad(Pt(w * f, h * f), Pt(w * (1 - f), h * f), Pt(w * (1 - f), h * (1 - f)), Pt(w * f, h * (1 - f)))

        fun fromList(v: List<Float>?): Quad? {
            if (v == null || v.size != 8) return null
            return Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7]))
        }

        /** Orders 4 arbitrary points as TL, TR, BR, BL. */
        fun order(pts: List<Pt>): Quad {
            val cx = pts.sumOf { it.x.toDouble() } / 4
            val cy = pts.sumOf { it.y.toDouble() } / 4
            val sorted = pts.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            // atan2 ordering starts near -PI (left) and goes clockwise in screen space: pick TL as min(x+y)
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }

        fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
    }
}

operator fun Pt.times(s: Float) = Pt(x * s, y * s)
operator fun Pt.plus(o: Pt) = Pt(x + o.x, y + o.y)
operator fun Pt.minus(o: Pt) = Pt(x - o.x, y - o.y)
fun Pt.clamp(w: Float, h: Float) = Pt(x.coerceIn(0f, w), y.coerceIn(0f, h))
