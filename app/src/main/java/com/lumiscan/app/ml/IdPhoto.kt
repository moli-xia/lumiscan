package com.lumiscan.app.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.Cv
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class IdPhotoSpec(val name: String, val mmW: Int, val mmH: Int, val pxW: Int, val pxH: Int)

data class IdBackground(val name: String, val top: Int, val bottom: Int = top)

class NoFaceException : Exception("未检测到人脸，请使用正面清晰的人像照片")

/** Person cut-out from a portrait with the detected face box (in cut-out pixel space). */
class IdCutout(val person: Bitmap, val face: Rect)

/** 证件照: face-anchored crop to standard sizes, background replacement, light skin smoothing. */
object IdPhotoMaker {

    val specs = listOf(
        IdPhotoSpec("一寸", 25, 35, 295, 413),
        IdPhotoSpec("二寸", 35, 49, 413, 579),
        IdPhotoSpec("小一寸", 22, 32, 260, 378),
        IdPhotoSpec("小二寸", 35, 45, 413, 531),
        IdPhotoSpec("大一寸", 33, 48, 390, 567),
        IdPhotoSpec("身份证", 26, 32, 358, 441),
        IdPhotoSpec("护照", 33, 48, 390, 567),
        IdPhotoSpec("驾驶证", 22, 32, 260, 378),
        IdPhotoSpec("社保卡", 26, 32, 358, 441),
        IdPhotoSpec("教师资格证", 25, 35, 295, 413),
        IdPhotoSpec("美国签证", 51, 51, 600, 600),
        IdPhotoSpec("日本签证", 45, 45, 531, 531),
    )

    val backgrounds = listOf(
        IdBackground("白色", Color.WHITE),
        IdBackground("蓝色", Color.rgb(67, 142, 219)),
        IdBackground("红色", Color.rgb(217, 0, 27)),
        IdBackground("浅灰", Color.rgb(230, 230, 230)),
        IdBackground("渐变蓝", Color.rgb(160, 200, 240), Color.rgb(67, 142, 219)),
        IdBackground("渐变灰", Color.rgb(245, 245, 245), Color.rgb(170, 170, 170)),
    )

    private const val WORK = 1600

    private suspend fun detectFace(img: Bitmap): Rect {
        val faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
        ).process(InputImage.fromBitmap(img, 0)).await()
        return faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
            ?: throw NoFaceException()
    }

    /** Fails fast (before any paid AI call) when the photo has no usable face. */
    suspend fun checkFace(src: Bitmap) {
        val img = BitmapIO.scaleToMax(src, WORK)
        try { detectFace(img) } finally { if (img !== src) img.recycle() }
    }

    /**
     * Cut-out from an AI portrait on a flat green backdrop (hair-accurate edges; optionally with
     * new clothes). The face box is detected again because the model may re-frame slightly.
     */
    suspend fun cutoutFromKeyed(ai: Bitmap): IdCutout {
        val img = BitmapIO.scaleToMax(ai, WORK)
        val face = detectFace(img)
        val person = com.lumiscan.app.imaging.ImageTools.chromaKey(img)
        if (img !== ai) img.recycle()
        return IdCutout(person, face)
    }

    suspend fun cutout(src: Bitmap): IdCutout {
        val img = BitmapIO.scaleToMax(src, WORK)
        val face = detectFace(img)

        val mask = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        ).process(InputImage.fromBitmap(img, 0)).await()

        val mw = mask.width; val mh = mask.height
        val buf = mask.buffer.order(ByteOrder.nativeOrder())
        buf.rewind()
        val conf = FloatArray(mw * mh)
        buf.asFloatBuffer().get(conf)
        // Soft threshold the confidence into an alpha matte, then feather it slightly.
        val alphaBytes = ByteArray(mw * mh) { i ->
            val t = ((conf[i] - 0.35f) / 0.4f).coerceIn(0f, 1f)
            (t * t * (3 - 2 * t) * 255).toInt().toByte()
        }
        val am = Cv.fromBytes(mh, mw, alphaBytes)
        if (mw != img.width || mh != img.height) Imgproc.resize(am, am, Size(img.width.toDouble(), img.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.GaussianBlur(am, am, Size(5.0, 5.0), 0.0)
        val (rgb, alpha) = refineEdges(Cv.rgb(img), am)
        val ch = ArrayList<Mat>()
        Core.split(rgb, ch)
        ch += alpha
        val rgba = Mat()
        Core.merge(ch, rgba)
        val person = Cv.bitmap(rgba, premultiply = true)
        ch.forEach { it.release() }
        Cv.release(rgb, rgba, am)
        if (img !== src) img.recycle()
        return IdCutout(person, face)
    }

    /**
     * The selfie model's mask is coarse (256 px), so a band along its edge still shows the old
     * background — a white wall leaves a light fringe on the new colour. Within that band alpha is
     * re-estimated from colour (how far each pixel lies from the local background colour towards
     * the local foreground colour) and the background is un-mixed from the pixel's colour. The
     * estimate can only remove background, never add any, and is used only where the pixel fits
     * the two-colour model (busy backgrounds — sofa, plants — don't).
     * Returns (RGB 8UC3, alpha 8UC1).
     */
    private fun refineEdges(rgb: Mat, alpha8: Mat): Pair<Mat, Mat> {
        val f32 = CvType.CV_32F
        val r = max(6, max(rgb.cols(), rgb.rows()) / 120)
        val fg = Mat(); Imgproc.threshold(alpha8, fg, 127.0, 255.0, Imgproc.THRESH_BINARY)
        val bg = Mat(); Core.bitwise_not(fg, bg)
        val k = Cv.ellipse(2 * r + 1)
        Imgproc.erode(fg, fg, k); Imgproc.erode(bg, bg, k)
        val band = Mat(); Core.bitwise_or(fg, bg, band); Core.bitwise_not(band, band)
        val img = Mat(); rgb.convertTo(img, CvType.CV_32FC3)
        fun x3(m: Mat) = Mat().also { Core.merge(listOf(m, m, m), it) }
        // Local mean colour of the sure pixels (normalised convolution).
        fun localColour(sure: Mat): Mat {
            val w = Mat(); sure.convertTo(w, f32, 1.0 / 255)
            val w3 = x3(w)
            val num = Mat(); Core.multiply(img, w3, num)
            val ks = Size(6.0 * r + 1, 6.0 * r + 1)
            Imgproc.GaussianBlur(num, num, ks, 0.0); Imgproc.GaussianBlur(w, w, ks, 0.0)
            Core.max(w, Scalar(1e-4), w)
            val d3 = x3(w)
            val out = Mat(); Core.divide(num, d3, out)
            Cv.release(w, w3, num, d3)
            return out
        }
        val fCol = localColour(fg); val bCol = localColour(bg)
        val ib = Mat(); Core.subtract(img, bCol, ib)
        val fb = Mat(); Core.subtract(fCol, bCol, fb)
        val sum = Mat(1, 3, f32, Scalar(1.0))
        val prod = Mat(); Core.multiply(ib, fb, prod)
        val num = Mat(); Core.transform(prod, num, sum)
        Core.multiply(fb, fb, prod)
        val den = Mat(); Core.transform(prod, den, sum)
        // t = projection of (I − B) on (F − B); only where the two colours really differ (> ~30 levels).
        val t = Mat(); Core.max(den, Scalar(900.0), prod); Core.divide(num, prod, t)
        Core.min(t, Scalar(1.0), t); Core.max(t, Scalar(0.0), t)
        val distinct = Mat(); Imgproc.threshold(den, distinct, 900.0, 255.0, Imgproc.THRESH_BINARY); distinct.convertTo(distinct, CvType.CV_8U)
        // Residual of the model I ≈ B + t·(F − B); large → the pixel is some third colour.
        val model = Mat(); Core.multiply(fb, x3(t), model); Core.subtract(ib, model, model)
        Core.multiply(model, model, prod)
        val res = Mat(); Core.transform(prod, res, sum)
        val fits = Mat(); Imgproc.threshold(res, fits, 3 * 45.0 * 45.0, 255.0, Imgproc.THRESH_BINARY_INV); fits.convertTo(fits, CvType.CV_8U)
        val use = Mat(); Core.bitwise_and(band, distinct, use); Core.bitwise_and(use, fits, use)
        val a = Mat(); alpha8.convertTo(a, f32, 1.0 / 255)
        val lower = Mat(); Core.min(a, t, lower)
        lower.copyTo(a, use)
        Imgproc.GaussianBlur(a, a, Size(3.0, 3.0), 0.0)
        // Colour: un-mix the background, I = t·F + (1 − t)·B  →  F = (I − (1 − t)·B) / t; mostly the
        // local foreground colour where t is small and the division would amplify noise.
        val inv = Mat(); Core.subtract(Mat(t.size(), f32, Scalar(1.0)), t, inv)
        val inv3 = x3(inv); val t3 = Mat(); Core.max(t, Scalar(0.05), prod); Core.merge(listOf(prod, prod, prod), t3)
        val unmixed = Mat(); Core.multiply(bCol, inv3, unmixed); Core.subtract(img, unmixed, unmixed); Core.divide(unmixed, t3, unmixed)
        val s = Mat(); t.convertTo(s, -1, 1 / 0.4, -0.3 / 0.4); Core.min(s, Scalar(1.0), s); Core.max(s, Scalar(0.0), s)
        val s3 = x3(s)
        Core.subtract(unmixed, fCol, unmixed); Core.multiply(unmixed, s3, unmixed); Core.add(fCol, unmixed, unmixed)
        unmixed.copyTo(img, use)
        val outRgb = Mat(); img.convertTo(outRgb, CvType.CV_8UC3)
        val outA = Mat(); a.convertTo(outA, CvType.CV_8U, 255.0)
        Cv.release(fg, bg, k, band, img, fCol, bCol, ib, fb, sum, prod, num, den, t, distinct, model, res, fits, use, a, lower, inv, inv3, t3, unmixed, s, s3, rgb)
        return outRgb to outA
    }

    /** Renders the final photo; [beauty] 0..1 smooths skin. */
    fun render(cut: IdCutout, spec: IdPhotoSpec, bg: IdBackground, beauty: Float, offsetY: Float = 0f, zoom: Float = 1f): Bitmap {
        val w = spec.pxW; val h = spec.pxH
        val face = cut.face
        // The detector box spans brows→chin; the full head (with hair) is ≈1.45× taller.
        val headPx = face.height() * 1.45f
        val targetHead = h * (if (w >= h) 0.56f else 0.62f) * zoom
        val scale = targetHead / headPx
        val headTop = face.top - face.height() * 0.45f
        val dx = w / 2f - face.exactCenterX() * scale
        val dy = h * (0.09f + offsetY) - headTop * scale

        val person = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }
        Canvas(person).drawBitmap(cut.person, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val smooth = if (beauty > 0.01f) smoothSkin(person, RectF(
            face.left * scale + dx, face.top * scale + dy, face.right * scale + dx, face.bottom * scale + dy,
        ), beauty) else person

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bgPaint = Paint().apply {
            if (bg.top != bg.bottom) shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bg.top, bg.bottom, Shader.TileMode.CLAMP)
            else color = bg.top
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        c.drawBitmap(smooth, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (smooth !== person) smooth.recycle()
        person.recycle()
        return out
    }

    private fun smoothSkin(person: Bitmap, faceRect: RectF, amount: Float): Bitmap {
        val rgba = Cv.rgba(person)
        val ch = ArrayList<Mat>()
        Core.split(rgba, ch)
        val rgb = Mat()
        Core.merge(ch.subList(0, 3), rgb)
        val smooth = Mat()
        Imgproc.bilateralFilter(rgb, smooth, 9, 30.0 + 60 * amount, 7.0)
        smooth.convertTo(smooth, -1, 1.0, 6.0 * amount)
        // Blend only inside a feathered ellipse around the face.
        val mask = Mat.zeros(rgb.size(), org.opencv.core.CvType.CV_8UC1)
        val cx = faceRect.centerX().toDouble(); val cy = faceRect.centerY().toDouble()
        Imgproc.ellipse(
            mask, org.opencv.core.Point(cx, cy),
            Size(faceRect.width() * 0.62, faceRect.height() * 0.72), 0.0, 0.0, 360.0, Scalar(255.0 * min(1f, amount * 1.2f)), -1,
        )
        val k = (max(3, (faceRect.width() / 6).toInt()) or 1).toDouble()
        Imgproc.GaussianBlur(mask, mask, Size(k, k), 0.0)
        val out = blend(rgb, smooth, mask)
        val och = ArrayList<Mat>()
        Core.split(out, och)
        och += ch[3]
        Core.merge(och, rgba)
        val bmp = Cv.bitmap(rgba)
        listOf(rgb, smooth, mask, out).forEach { it.release() }
        ch.forEach { it.release() }; och.forEach { it.release() }
        rgba.release()
        return bmp
    }

    private fun blend(a: Mat, b: Mat, mask: Mat): Mat {
        val af = Mat(); val bf = Mat(); val mf = Mat()
        a.convertTo(af, org.opencv.core.CvType.CV_32FC3)
        b.convertTo(bf, org.opencv.core.CvType.CV_32FC3)
        val m3 = Mat()
        Imgproc.cvtColor(mask, m3, Imgproc.COLOR_GRAY2RGB)
        m3.convertTo(mf, org.opencv.core.CvType.CV_32FC3, 1.0 / 255)
        val inv = Mat()
        // Mat.ones() on a 3-channel mat only fills the first channel; use an explicit (1,1,1).
        Core.subtract(Mat(mf.size(), mf.type(), Scalar(1.0, 1.0, 1.0)), mf, inv)
        Core.multiply(bf, mf, bf)
        Core.multiply(af, inv, af)
        Core.add(af, bf, af)
        val out = Mat()
        af.convertTo(out, org.opencv.core.CvType.CV_8UC3)
        listOf(bf, mf, m3, inv, af).forEach { it.release() }
        return out
    }

    /** Arranges copies on 6-inch (4×6in, 300dpi) photo paper with cutting guides. */
    fun printSheet(photo: Bitmap, spec: IdPhotoSpec): Bitmap {
        val sheetW = 1800; val sheetH = 1200
        val out = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val gap = 24
        val pw = spec.pxW; val ph = spec.pxH
        val cols = max(1, (sheetW - 60 + gap) / (pw + gap))
        val rows = max(1, (sheetH - 60 + gap) / (ph + gap))
        val gridW = cols * pw + (cols - 1) * gap
        val gridH = rows * ph + (rows - 1) * gap
        val x0 = (sheetW - gridW) / 2; val y0 = (sheetH - gridH) / 2
        val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 1f; style = Paint.Style.STROKE }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (r in 0 until rows) for (col in 0 until cols) {
            val x = x0 + col * (pw + gap); val y = y0 + r * (ph + gap)
            c.drawBitmap(photo, null, Rect(x, y, x + pw, y + ph), paint)
            c.drawRect(x - 1f, y - 1f, x + pw + 1f, y + ph + 1f, line)
        }
        return out
    }

    fun copiesPerSheet(spec: IdPhotoSpec): Int {
        val gap = 24
        return max(1, (1800 - 60 + gap) / (spec.pxW + gap)) * max(1, (1200 - 60 + gap) / (spec.pxH + gap))
    }
}
