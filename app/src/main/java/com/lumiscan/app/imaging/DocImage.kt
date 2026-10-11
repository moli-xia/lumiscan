package com.lumiscan.app.imaging

import android.graphics.Bitmap
import com.lumiscan.app.data.FilterType
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Perspective correction and the scan enhancement filters. */
object DocImage {

    /** Flattens [quad] of [src] into a rectangle. Pass [outW]/[outH] to force a size (e.g. ID cards). */
    fun warp(src: Bitmap, quad: Quad, outW: Int? = null, outH: Int? = null): Bitmap {
        var (w, h) = quad.outputSize(src.width, src.height)
        if (outW != null && outH != null) { w = outW; h = outH }
        val cap = BitmapIO.MAX_SIDE.toFloat() / max(w, h)
        if (cap < 1f) { w = (w * cap).toInt(); h = (h * cap).toInt() }
        val full = Quad.full(src.width.toFloat(), src.height.toFloat())
        if (outW == null && quad == full) return src.copy(Bitmap.Config.ARGB_8888, true)

        val m = Cv.rgba(src)
        val srcPts = EdgeDetector.toPoints(quad)
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0))
        val t = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(m, out, t, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        val bmp = Cv.bitmap(out)
        Cv.release(m, srcPts, dstPts, t, out)
        return bmp
    }

    fun applyFilter(src: Bitmap, filter: FilterType): Bitmap {
        if (filter == FilterType.ORIGINAL) return src.copy(Bitmap.Config.ARGB_8888, true)
        val rgb = Cv.rgb(src)
        val out: Mat = when (filter) {
            FilterType.ENHANCE -> {
                val n = normalizeBackground(rgb)
                val l = levels(n, 25.0, 235.0, 1.15)
                sharpen(l, 1.0, 0.6).also { n.release(); l.release() }
            }
            FilterType.NO_SHADOW -> {
                val n = normalizeBackground(rgb)
                levels(n, 5.0, 245.0, 1.0).also { n.release() }
            }
            FilterType.BRIGHTEN -> {
                val o = Mat()
                rgb.convertTo(o, -1, 1.12, 28.0)
                val s = boostSaturation(o, 1.15)
                sharpen(s, 1.0, 0.3).also { o.release(); s.release() }
            }
            FilterType.BW -> blackWhite(rgb)
            FilterType.GRAY -> {
                val n = normalizeBackground(rgb)
                val g = Mat()
                Imgproc.cvtColor(n, g, Imgproc.COLOR_RGB2GRAY)
                val l = levels(g, 20.0, 240.0, 1.1)
                n.release(); g.release()
                l
            }
            FilterType.INK_SAVE -> {
                val n = normalizeBackground(rgb)
                val g = Mat()
                Imgproc.cvtColor(n, g, Imgproc.COLOR_RGB2GRAY)
                // Clean white background, text kept mid-grey to use less toner.
                val lut = lut { v -> if (v > 190) 255.0 else 70.0 + v * 0.55 }
                val o = Mat()
                Core.LUT(g, lut, o)
                Cv.release(n, g, lut)
                o
            }
            FilterType.ORIGINAL -> rgb.clone()
        }
        val bmp = Cv.bitmap(out)
        Cv.release(rgb, out)
        return bmp
    }

    /**
     * Removes uneven lighting/shadows: estimates the paper colour per channel with a large
     * morphological closing (computed at 1/4 scale for speed) and divides it out.
     */
    fun normalizeBackground(rgb: Mat): Mat {
        val channels = ArrayList<Mat>()
        Core.split(rgb, channels)
        val small = Size((rgb.cols() / 4).coerceAtLeast(8).toDouble(), (rgb.rows() / 4).coerceAtLeast(8).toDouble())
        val k = Cv.rect(7, 7)
        val result = ArrayList<Mat>()
        for (c in channels) {
            val s = Mat()
            Imgproc.resize(c, s, small, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.dilate(s, s, k)
            Imgproc.medianBlur(s, s, 11)
            val bg = Mat()
            Imgproc.resize(s, bg, c.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val d = Mat()
            Core.divide(c, bg, d, 255.0)
            result += d
            Cv.release(s, bg)
        }
        val out = Mat()
        Core.merge(result, out)
        channels.forEach { it.release() }
        result.forEach { it.release() }
        k.release()
        return out
    }

    /** Linear levels stretch (black point [lo], white point [hi]) followed by a gamma. */
    fun levels(src: Mat, lo: Double, hi: Double, gamma: Double): Mat {
        val lut = lut { v ->
            val t = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            255.0 * t.pow(gamma)
        }
        val out = Mat()
        Core.LUT(src, lut, out)
        lut.release()
        return out
    }

    fun sharpen(src: Mat, sigma: Double, amount: Double): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), sigma)
        val out = Mat()
        Core.addWeighted(src, 1 + amount, blur, -amount, 0.0, out)
        blur.release()
        return out
    }

    fun boostSaturation(rgb: Mat, factor: Double): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        ch[1].convertTo(ch[1], -1, factor, 0.0)
        Core.merge(ch, hsv)
        val out = Mat()
        Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2RGB)
        hsv.release(); ch.forEach { it.release() }
        return out
    }

    fun blackWhite(rgb: Mat): Mat {
        val n = normalizeBackground(rgb)
        val g = Mat()
        Imgproc.cvtColor(n, g, Imgproc.COLOR_RGB2GRAY)
        val block = ((max(g.cols(), g.rows()) / 50) or 1).coerceAtLeast(15)
        val adaptive = Mat()
        Imgproc.adaptiveThreshold(g, adaptive, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
        // Light greys (pale watermarks, paper texture) stay white even where they form edges.
        val light = Mat()
        Imgproc.threshold(g, light, 175.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(adaptive, light, adaptive)
        // Fill solid dark areas that adaptive thresholding would hollow out.
        val dark = Mat()
        Imgproc.threshold(g, dark, 110.0, 255.0, Imgproc.THRESH_BINARY)
        val out = Mat()
        Core.bitwise_and(adaptive, dark, out)
        Cv.release(n, g, adaptive, dark, light)
        return out
    }

    fun lut(f: (Double) -> Double): Mat {
        val m = Mat(1, 256, CvType.CV_8UC1)
        val b = ByteArray(256) { i -> f(i.toDouble()).roundToInt().coerceIn(0, 255).toByte() }
        m.put(0, 0, b)
        return m
    }
}
