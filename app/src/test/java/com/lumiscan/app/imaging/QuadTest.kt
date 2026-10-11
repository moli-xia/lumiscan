package com.lumiscan.app.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class QuadTest {

    /** Projects an A4 sheet through a pinhole camera (principal point at the image centre). */
    private fun photographA4(ax: Double, ay: Double, az: Double, z: Double, f: Double = 3000.0, w: Int = 3000, h: Int = 4000): Quad {
        val (cx, sx) = cos(ax) to sin(ax); val (cy, sy) = cos(ay) to sin(ay); val (cz, sz) = cos(az) to sin(az)
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cx, -sx), doubleArrayOf(0.0, sx, cx))
        val ry = arrayOf(doubleArrayOf(cy, 0.0, sy), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sy, 0.0, cy))
        val rz = arrayOf(doubleArrayOf(cz, -sz, 0.0), doubleArrayOf(sz, cz, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { a[i][it] * b[it][j] } } }
        val r = mul(rz, mul(ry, rx))
        fun project(x: Double, y: Double): Pt {
            val p = DoubleArray(3) { i -> r[i][0] * x + r[i][1] * y + (if (i == 2) z else 0.0) }
            return Pt((f * p[0] / p[2] + w / 2).toFloat(), (f * p[1] / p[2] + h / 2).toFloat())
        }
        val hw = 0.105; val hh = 0.1485
        return Quad(project(-hw, -hh), project(hw, -hh), project(hw, hh), project(-hw, hh))
    }

    @Test
    fun recoversTrueAspectUnderStrongPerspective() {
        for ((ax, ay, az) in listOf(Triple(0.35, 0.2, 0.1), Triple(-0.5, 0.3, -0.2), Triple(0.2, -0.4, 0.05))) {
            val q = photographA4(ax, ay, az, 0.6)
            val (w, h) = q.outputSize(3000, 4000)
            assertEquals("tilt ($ax,$ay,$az)", 297.0 / 210.0, h.toDouble() / w, 0.01)
        }
    }

    @Test
    fun fallsBackToEdgeLengthsWithoutImageSize() {
        val q = Quad(Pt(0f, 0f), Pt(200f, 0f), Pt(200f, 100f), Pt(0f, 100f))
        assertEquals(200 to 100, q.outputSize())
        // Fronto-parallel rectangle: same answer with the image size given.
        assertEquals(200 to 100, q.outputSize(1000, 1000))
    }

    @Test
    fun orderAndRotate() {
        val q = Quad.order(listOf(Pt(10f, 90f), Pt(90f, 10f), Pt(10f, 10f), Pt(90f, 90f)))
        assertEquals(Pt(10f, 10f), q.tl)
        assertEquals(Pt(90f, 90f), q.br)
        assertTrue(q.isConvex())
        // Rotating a 100x50 image clockwise maps its top-left corner to the top-right.
        val r = Quad.full(100f, 50f).rotateCw(100f, 50f)
        assertEquals(Pt(0f, 0f), r.tl)
        assertEquals(Pt(50f, 100f), r.br)
    }
}
