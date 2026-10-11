package com.lumiscan.app.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Small helpers bridging Android bitmaps and OpenCV mats. */
object Cv {

    /** RGBA 8UC4 mat. */
    fun rgba(bitmap: Bitmap): Mat {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val m = Mat()
        Utils.bitmapToMat(src, m)
        return m
    }

    /** RGB 8UC3 mat. */
    fun rgb(bitmap: Bitmap): Mat {
        val m = rgba(bitmap)
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2RGB)
        return m
    }

    fun gray(bitmap: Bitmap): Mat {
        val m = rgba(bitmap)
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
        return m
    }

    /**
     * Converts an 8UC1/8UC3/8UC4 mat to an ARGB bitmap. Pass [premultiply] for an RGBA mat with
     * real transparency: Android bitmaps are premultiplied, and an un-premultiplied transparent
     * pixel draws as if it were opaque (its colour is added to whatever is behind it).
     */
    fun bitmap(mat: Mat, premultiply: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, out, premultiply)
        return out
    }

    fun bytes(mat: Mat): ByteArray {
        val m = if (mat.isContinuous) mat else mat.clone()
        val b = ByteArray((m.total() * m.channels()).toInt())
        m.get(0, 0, b)
        return b
    }

    fun fromBytes(rows: Int, cols: Int, data: ByteArray): Mat {
        val m = Mat(rows, cols, CvType.CV_8UC1)
        m.put(0, 0, data)
        return m
    }

    fun rect(w: Int, h: Int): Mat = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(w.toDouble(), h.toDouble()))
    fun ellipse(d: Int): Mat = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(d.toDouble(), d.toDouble()))

    fun release(vararg mats: Mat?) = mats.forEach { it?.release() }
}
