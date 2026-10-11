package com.lumiscan.app.imaging

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class HandwritingOptions(
    val blue: Boolean = true,
    val red: Boolean = true,
    val green: Boolean = true,
    val pencil: Boolean = false,
    /** Exam/paper mode also cleans the page to crisp black-on-white. */
    val cleanPage: Boolean = false,
)

data class RestoreOptions(
    val denoise: Boolean = true,
    val scratches: Boolean = true,
    val color: Boolean = true,
    val sharpen: Boolean = true,
    val upscale: Boolean = false,
    val sharpenAmount: Float = 0.8f,
)

/** Pixel-level tools behind 去手写 / 试卷还原 / 去水印 / 智能消除 / 老照片修复 / 变清晰. */
object ImageTools {

    /**
     * Removes pen strokes by colour. Printed text is near-neutral (black/grey), while ballpoint
     * and gel pens are saturated blue/red/green; pencil is lighter grey than print.
     */
    fun removeHandwriting(src: Bitmap, o: HandwritingOptions, aiBoxes: List<RectF> = emptyList()): Bitmap {
        val rgb = Cv.rgb(src)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV_FULL)
        val mask = if (aiBoxes.isEmpty()) Mat.zeros(rgb.size(), org.opencv.core.CvType.CV_8UC1) else inkInBoxes(rgb, aiBoxes)
        val tmp = Mat()
        // HSV_FULL hue range is 0..255.
        fun addRange(h1: Double, h2: Double) {
            Core.inRange(hsv, Scalar(h1, 45.0, 40.0), Scalar(h2, 255.0, 255.0), tmp)
            Core.bitwise_or(mask, tmp, mask)
        }
        if (o.blue) addRange(120.0, 200.0)
        if (o.red) { addRange(0.0, 18.0); addRange(225.0, 255.0) }
        if (o.green) addRange(55.0, 120.0)
        if (o.pencil) {
            val gray = Mat()
            val norm = DocImage.normalizeBackground(rgb)
            Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGB2GRAY)
            // Mid-grey strokes (print is darker than ~95 after normalisation).
            Core.inRange(gray, Scalar(95.0), Scalar(205.0), tmp)
            Core.bitwise_or(mask, tmp, mask)
            Cv.release(gray, norm)
        }
        Imgproc.dilate(mask, mask, Cv.ellipse(3))

        // In the background-normalised image paper is pure white, so strokes become white.
        val norm = DocImage.normalizeBackground(rgb)
        val white = Mat(rgb.size(), rgb.type(), Scalar(255.0, 255.0, 255.0))
        white.copyTo(norm, mask)
        var out = norm
        if (o.cleanPage) {
            val bw = DocImage.blackWhite(out)
            Imgproc.cvtColor(bw, bw, Imgproc.COLOR_GRAY2RGB)
            out.release(); out = bw
        } else {
            val l = DocImage.levels(out, 15.0, 240.0, 1.1)
            out.release(); out = l
        }
        val bmp = Cv.bitmap(out)
        Cv.release(rgb, hsv, mask, tmp, white, out)
        return bmp
    }

    /**
     * All ink inside AI-detected handwriting boxes (page fractions), except long straight rules
     * (underlines, table borders) so the blank lines of a form survive. Printed text outside the
     * boxes is never touched.
     */
    private fun inkInBoxes(rgb: Mat, boxes: List<RectF>): Mat {
        val w = rgb.cols(); val h = rgb.rows()
        val norm = DocImage.normalizeBackground(rgb)
        val gray = Mat()
        Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGB2GRAY)
        val ink = Mat()
        Imgproc.threshold(gray, ink, 215.0, 255.0, Imgproc.THRESH_BINARY_INV)
        // Coloured strokes can be lighter than 215 in grey.
        val hsv = Mat()
        Imgproc.cvtColor(norm, hsv, Imgproc.COLOR_RGB2HSV)
        val colored = Mat()
        Core.inRange(hsv, Scalar(0.0, 60.0, 40.0), Scalar(180.0, 255.0, 255.0), colored)
        Core.bitwise_or(ink, colored, ink)

        val boxMask = Mat.zeros(rgb.size(), org.opencv.core.CvType.CV_8UC1)
        val pad = min(w, h) * 0.012
        for (b in boxes) {
            Imgproc.rectangle(
                boxMask,
                org.opencv.core.Point(b.left * w - pad, b.top * h - pad),
                org.opencv.core.Point(b.right * w + pad, b.bottom * h + pad),
                Scalar(255.0), -1,
            )
        }
        // Printed rules are black/grey; flat stretches of a coloured pen stroke must not count.
        val neutral = Mat()
        Core.bitwise_not(colored, neutral)
        Core.bitwise_and(ink, neutral, neutral)
        val len = max(30, min(w, h) / 12).toDouble()
        val horiz = Mat(); val vert = Mat()
        Imgproc.morphologyEx(neutral, horiz, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(len, 1.0)))
        Imgproc.morphologyEx(neutral, vert, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, len)))
        neutral.release()
        Core.bitwise_or(horiz, vert, horiz)
        Imgproc.dilate(horiz, horiz, Cv.rect(3, 3))
        Core.bitwise_not(horiz, horiz)
        val out = Mat()
        Core.bitwise_and(ink, boxMask, out)
        Core.bitwise_and(out, horiz, out)
        Cv.release(norm, gray, ink, hsv, colored, boxMask, horiz, vert)
        return out
    }

    /**
     * Lightens semi-transparent document watermarks: light, low-contrast pixels above [threshold]
     * become paper white while dark text survives. With AI [boxes] the removal is limited to the
     * watermark and can be stronger there.
     */
    fun removeDocWatermark(src: Bitmap, threshold: Int, boxes: List<RectF> = emptyList()): Bitmap {
        val rgb = Cv.rgb(src)
        val norm = DocImage.normalizeBackground(rgb)
        val gray = Mat()
        Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGB2GRAY)
        val hsv = Mat()
        Imgproc.cvtColor(norm, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        val light = Mat()
        val t = if (boxes.isEmpty()) threshold else (threshold - 35).coerceAtLeast(110)
        Imgproc.threshold(gray, light, t.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        // Pale coloured watermarks: bright value even if the grey is a little darker.
        val pale = Mat()
        Core.inRange(hsv, Scalar(0.0, 25.0, (t + 15).coerceAtMost(250).toDouble()), Scalar(180.0, 255.0, 255.0), pale)
        Core.bitwise_or(light, pale, light)
        // Keep the anti-aliased fringe of real text and pen strokes (dark or strongly saturated).
        val strong = Mat()
        Imgproc.threshold(gray, strong, 150.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val sat = Mat()
        Imgproc.threshold(ch[1], sat, 110.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(strong, sat, strong)
        Imgproc.dilate(strong, strong, Cv.rect(5, 5))
        Core.bitwise_not(strong, strong)
        Core.bitwise_and(light, strong, light)
        if (boxes.isNotEmpty()) {
            val w = rgb.cols(); val h = rgb.rows()
            val boxMask = Mat.zeros(rgb.size(), org.opencv.core.CvType.CV_8UC1)
            val pad = min(w, h) * 0.015
            for (b in boxes) {
                Imgproc.rectangle(
                    boxMask,
                    org.opencv.core.Point(b.left * w - pad, b.top * h - pad),
                    org.opencv.core.Point(b.right * w + pad, b.bottom * h + pad),
                    Scalar(255.0), -1,
                )
            }
            Core.bitwise_and(light, boxMask, light)
            boxMask.release()
            // Only the watermark's own pixels change: they take the local paper colour of the
            // original photo; everything else (background, print, pen) stays untouched.
            Imgproc.dilate(light, light, Cv.ellipse(3))
            val bg = Mat()
            Imgproc.dilate(rgb, bg, Cv.rect(15, 15))
            Imgproc.medianBlur(bg, bg, 21)
            bg.copyTo(rgb, light)
            val bmp = Cv.bitmap(rgb)
            Cv.release(bg, strong, sat, norm, gray, hsv, light, pale)
            ch.forEach { it.release() }
            rgb.release()
            return bmp
        }
        norm.setTo(Scalar(255.0, 255.0, 255.0), light)
        Cv.release(strong, sat)
        val out = DocImage.levels(norm, 10.0, 245.0, 1.05)
        val bmp = Cv.bitmap(out)
        Cv.release(rgb, norm, gray, hsv, light, pale, out)
        ch.forEach { it.release() }
        return bmp
    }

    /** Paints the [mask] area solid red: hides what to remove so the AI eraser fills it from context. */
    fun markRed(src: Bitmap, mask: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val m = Bitmap.createScaledBitmap(mask, w, h, false)
        val px = IntArray(w * h); val mp = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        m.getPixels(mp, 0, w, 0, 0, w, h)
        if (m !== mask) m.recycle()
        for (i in px.indices) {
            // Mask brightness (white strokes on black) is the weight of the red mark; inside the
            // strokes it is opaque, so the model can't simply redraw the object underneath.
            val a = (((mp[i] shr 16) and 255) / 255f * 1.5f).coerceAtMost(1f)
            if (a <= 0.02f) continue
            val p = px[i]
            val r = ((p shr 16) and 255) * (1 - a) + 255 * a
            val g = ((p shr 8) and 255) * (1 - a)
            val b = (p and 255) * (1 - a)
            px[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Takes the AI result only inside the painted [mask] (grown and feathered) and keeps every
     * other pixel of [original]; the image model may also change things the user did not mark.
     */
    fun compositeRegion(original: Bitmap, edited: Bitmap, mask: Bitmap): Bitmap {
        val w = original.width; val h = original.height
        val ed = Cv.rgb(Bitmap.createScaledBitmap(edited, w, h, true))
        val base = Cv.rgb(original)
        val m = Cv.gray(Bitmap.createScaledBitmap(mask, w, h, false))
        Imgproc.threshold(m, m, 20.0, 255.0, Imgproc.THRESH_BINARY)
        val grow = (max(w, h) * 0.012).toInt().coerceAtLeast(3) or 1
        Imgproc.dilate(m, m, Cv.ellipse(grow))
        Imgproc.GaussianBlur(m, m, Size(grow * 2.0 + 1, grow * 2.0 + 1), 0.0)
        val alpha = Mat()
        m.convertTo(alpha, org.opencv.core.CvType.CV_32F, 1.0 / 255)
        val a3 = Mat()
        Core.merge(listOf(alpha, alpha, alpha), a3)
        val fb = Mat(); val fe = Mat()
        base.convertTo(fb, org.opencv.core.CvType.CV_32FC3)
        ed.convertTo(fe, org.opencv.core.CvType.CV_32FC3)
        // out = base + (edited - base) * alpha
        Core.subtract(fe, fb, fe)
        Core.multiply(fe, a3, fe)
        Core.add(fb, fe, fb)
        val out = Mat()
        fb.convertTo(out, org.opencv.core.CvType.CV_8UC3)
        val bmp = Cv.bitmap(out)
        Cv.release(ed, base, m, alpha, a3, fb, fe, out)
        return bmp
    }

    /**
     * Mean grey-level difference (0–255) between [original] and [edited] outside the [mask] area,
     * compared at 128 px wide. Small (≈5–15) when the model only changed the masked area; large when
     * it moved, re-framed or re-toned the picture, so pasting its pixels back would not line up.
     */
    fun driftOutside(original: Bitmap, edited: Bitmap, mask: Bitmap): Double {
        val w = 128
        val h = max(1, w * original.height / original.width)
        fun px(b: Bitmap, filter: Boolean) = IntArray(w * h).also { Bitmap.createScaledBitmap(b, w, h, filter).getPixels(it, 0, w, 0, 0, w, h) }
        val a = px(original, true); val e = px(edited, true); val m = px(mask, false)
        // Leave out the masked area plus a few pixels around it.
        val skip = BooleanArray(w * h)
        val r = 4
        for (y in 0 until h) for (x in 0 until w) if ((m[y * w + x] shr 8 and 0xFF) > 20) {
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) skip[yy * w + xx] = true
        }
        fun grey(p: Int) = 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
        var sum = 0.0; var n = 0
        for (i in a.indices) if (!skip[i]) { sum += abs(grey(a[i]) - grey(e[i])); n++ }
        return if (n == 0) 0.0 else sum / n
    }

    /**
     * Turns an AI portrait on a flat green background into a cut-out: alpha from the distance to
     * the background colour (sampled at the borders), with green spill removed from the edges.
     */
    fun chromaKey(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // Background colour: median of the top and side borders (the bottom is usually the body).
        val samples = ArrayList<Int>()
        val step = max(1, max(w, h) / 200)
        for (x in 0 until w step step) { samples += px[x]; samples += px[(h / 10) * w + x] }
        for (y in 0 until h * 2 / 3 step step) { samples += px[y * w]; samples += px[y * w + w - 1] }
        fun median(sel: (Int) -> Int) = samples.map(sel).sorted()[samples.size / 2]
        val br = median { (it shr 16) and 255 }; val bg = median { (it shr 8) and 255 }; val bb = median { it and 255 }
        val out = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255; var g = (p shr 8) and 255; val b = p and 255
            val dr = r - br; val dg = g - bg; val db = b - bb
            val d = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
            // Greenness relative to the other channels separates hair edges from the backdrop.
            val green = g - max(r, b)
            var a = ((d - 40) / 70.0).coerceIn(0.0, 1.0)
            if (green < 20) a = max(a, ((d - 25) / 50.0).coerceIn(0.0, 1.0))
            // Clearly green-dominant pixels are backdrop even where its shade drifts.
            a = min(a, ((45 - green) / 30.0).coerceIn(0.0, 1.0))
            if (a < 1.0 && green > 0) g = max(r, b) + (green * a).toInt()
            out[i] = ((a * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Fills the painted [mask] (white = remove) from the surrounding pixels. */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        val rgb = Cv.rgb(src)
        val m = Cv.gray(Bitmap.createScaledBitmap(mask, src.width, src.height, false))
        Imgproc.threshold(m, m, 20.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.dilate(m, m, Cv.ellipse(5))
        val area = Core.countNonZero(m)
        val out = Mat()
        if (area == 0) {
            rgb.copyTo(out)
        } else {
            // Large regions: solve at reduced scale first so the fill carries structure, then
            // refine at full resolution around the region.
            val longest = max(rgb.cols(), rgb.rows())
            if (area > 0.01 * rgb.total() && longest > 1200) {
                val s = 1000.0 / longest
                val sm = Mat(); val smMask = Mat(); val smOut = Mat()
                Imgproc.resize(rgb, sm, Size(), s, s, Imgproc.INTER_AREA)
                Imgproc.resize(m, smMask, Size(), s, s, Imgproc.INTER_NEAREST)
                Photo.inpaint(sm, smMask, smOut, 5.0, Photo.INPAINT_TELEA)
                val up = Mat()
                Imgproc.resize(smOut, up, rgb.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
                val seeded = rgb.clone()
                up.copyTo(seeded, m)
                val inner = Mat()
                Imgproc.erode(m, inner, Cv.ellipse(9))
                val ring = Mat()
                Core.subtract(m, inner, ring)
                Photo.inpaint(seeded, ring, out, 4.0, Photo.INPAINT_TELEA)
                Cv.release(sm, smMask, smOut, up, seeded, inner, ring)
            } else {
                Photo.inpaint(rgb, m, out, 6.0, Photo.INPAINT_TELEA)
            }
        }
        val bmp = Cv.bitmap(out)
        Cv.release(rgb, m, out)
        return bmp
    }

    /** Paints the masked region with the estimated paper colour (for documents). */
    fun whiteOut(src: Bitmap, mask: Bitmap): Bitmap {
        val rgb = Cv.rgb(src)
        val m = Cv.gray(Bitmap.createScaledBitmap(mask, src.width, src.height, false))
        Imgproc.threshold(m, m, 20.0, 255.0, Imgproc.THRESH_BINARY)
        // Local paper colour: a large closing removes text, the median smooths it.
        val bg = Mat()
        Imgproc.dilate(rgb, bg, Cv.rect(15, 15))
        Imgproc.medianBlur(bg, bg, 21)
        bg.copyTo(rgb, m)
        val bmp = Cv.bitmap(rgb)
        Cv.release(rgb, m, bg)
        return bmp
    }

    /**
     * Lifts a handwritten signature off a photo of paper: the paper becomes transparent, ink keeps
     * its colour with soft edges, and the result is cropped to the ink. Null if no ink is found.
     */
    fun extractSignature(src: Bitmap): Bitmap? {
        val rgb = Cv.rgb(BitmapIO.scaleToMax(src, 1600))
        val norm = DocImage.normalizeBackground(rgb)
        val gray = Mat()
        Imgproc.cvtColor(norm, gray, Imgproc.COLOR_RGB2GRAY)
        // Alpha ramps from 0 (paper, >200) to 255 (ink, <110).
        val alpha = Mat()
        Core.LUT(gray, DocImage.lut { v -> ((200 - v) / 90.0 * 255).coerceIn(0.0, 255.0) }, alpha)
        Imgproc.morphologyEx(alpha, alpha, Imgproc.MORPH_OPEN, Cv.ellipse(3)) // drop paper specks
        val bin = Mat()
        Imgproc.threshold(alpha, bin, 60.0, 255.0, Imgproc.THRESH_BINARY)
        if (Core.countNonZero(bin) < 50) { Cv.release(rgb, norm, gray, alpha, bin); return null }
        val box = Imgproc.boundingRect(bin)
        val pad = 12
        val r = org.opencv.core.Rect(
            (box.x - pad).coerceAtLeast(0), (box.y - pad).coerceAtLeast(0),
            (box.width + 2 * pad).coerceAtMost(rgb.cols() - (box.x - pad).coerceAtLeast(0)),
            (box.height + 2 * pad).coerceAtMost(rgb.rows() - (box.y - pad).coerceAtLeast(0)),
        )
        // Ink colour: darken the original a little so faint pens read well on documents.
        val ink = Mat()
        rgb.submat(r).convertTo(ink, -1, 0.7, 0.0)
        val ch = ArrayList<Mat>()
        Core.split(ink, ch)
        ch += alpha.submat(r).clone()
        val rgba = Mat()
        Core.merge(ch, rgba)
        val out = Cv.bitmap(rgba, premultiply = true)
        Cv.release(rgb, norm, gray, alpha, bin, ink, rgba)
        ch.forEach { it.release() }
        return out
    }

    /** Old photo restoration: denoise, scratch/dust removal, colour cast & contrast, sharpen. */
    fun restorePhoto(src: Bitmap, o: RestoreOptions): Bitmap {
        var cur = Cv.rgb(src)
        if (o.upscale && max(cur.cols(), cur.rows()) <= 1800) {
            val up = Mat()
            Imgproc.resize(cur, up, Size(), 2.0, 2.0, Imgproc.INTER_CUBIC)
            cur.release(); cur = up
        }
        if (o.denoise) {
            val d = Mat()
            Photo.fastNlMeansDenoisingColored(cur, d, 5f, 5f, 7, 15)
            cur.release(); cur = d
        }
        if (o.scratches) {
            // After denoising, scratches stand out as long straight bright/dark lines.
            val gray = Mat()
            Imgproc.cvtColor(cur, gray, Imgproc.COLOR_RGB2GRAY)
            val k = Cv.rect(9, 9)
            val top = Mat(); val black = Mat()
            Imgproc.morphologyEx(gray, top, Imgproc.MORPH_TOPHAT, k)
            Imgproc.morphologyEx(gray, black, Imgproc.MORPH_BLACKHAT, k)
            Core.add(top, black, top)
            Imgproc.threshold(top, top, 28.0, 255.0, Imgproc.THRESH_BINARY)
            val lines = Mat()
            val minLen = 0.08 * min(cur.cols(), cur.rows())
            Imgproc.HoughLinesP(top, lines, 1.0, Math.PI / 180, 60, minLen, 8.0)
            val lineMask = Mat.zeros(gray.size(), gray.type())
            for (i in 0 until lines.rows()) {
                val l = lines.get(i, 0) ?: continue
                Imgproc.line(lineMask, org.opencv.core.Point(l[0], l[1]), org.opencv.core.Point(l[2], l[3]), Scalar(255.0), 7)
            }
            // Inpaint only the scratch pixels along the detected lines.
            val mask = Mat()
            Core.bitwise_and(top, lineMask, mask)
            if (Core.countNonZero(mask) > 0) {
                Imgproc.dilate(mask, mask, Cv.ellipse(5))
                val o2 = Mat()
                Photo.inpaint(cur, mask, o2, 4.0, Photo.INPAINT_TELEA)
                cur.release(); cur = o2
            }
            Cv.release(gray, k, top, black, lines, lineMask, mask)
        }
        if (o.color) {
            val wb = grayWorld(cur)
            cur.release(); cur = wb
            val c = clahe(cur, 1.5)
            cur.release(); cur = c
            val s = DocImage.boostSaturation(cur, 1.12)
            cur.release(); cur = s
        }
        if (o.sharpen) {
            val s = DocImage.sharpen(cur, 1.4, o.sharpenAmount.toDouble())
            cur.release(); cur = s
        }
        val bmp = Cv.bitmap(cur)
        cur.release()
        return bmp
    }

    /** "图片变清晰": upscale small images, edge-aware detail boost, denoise and unsharp mask. */
    fun enhanceClarity(src: Bitmap, amount: Float, upscale: Boolean): Bitmap {
        var cur = Cv.rgb(src)
        if (upscale && max(cur.cols(), cur.rows()) < 2000) {
            val f = min(2.0, 2400.0 / max(cur.cols(), cur.rows()))
            val up = Mat()
            Imgproc.resize(cur, up, Size(), f, f, Imgproc.INTER_LANCZOS4)
            cur.release(); cur = up
        }
        val dn = Mat()
        Photo.fastNlMeansDenoisingColored(cur, dn, 3f, 3f, 5, 13)
        cur.release(); cur = dn
        val de = Mat()
        Photo.detailEnhance(cur, de, 8f, 0.12f + 0.1f * amount)
        val blend = Mat()
        Core.addWeighted(cur, 1 - 0.6 * amount, de, 0.6 * amount, 0.0, blend)
        cur.release(); de.release(); cur = blend
        val sh = DocImage.sharpen(cur, 1.2, 0.5 + amount.toDouble())
        cur.release(); cur = sh
        val c = clahe(cur, 1.2)
        cur.release(); cur = c
        val bmp = Cv.bitmap(cur)
        cur.release()
        return bmp
    }

    private fun grayWorld(rgb: Mat): Mat {
        val mean = Core.mean(rgb).`val`
        val avg = (mean[0] + mean[1] + mean[2]) / 3
        val ch = ArrayList<Mat>()
        Core.split(rgb, ch)
        for (i in 0 until 3) {
            // Partial correction keeps some of the photo's original mood.
            val g = 1 + ((avg / mean[i].coerceAtLeast(1.0)) - 1) * 0.7
            ch[i].convertTo(ch[i], -1, g, 0.0)
        }
        val out = Mat()
        Core.merge(ch, out)
        ch.forEach { it.release() }
        return out
    }

    private fun clahe(rgb: Mat, clip: Double): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val c = Imgproc.createCLAHE(clip, Size(8.0, 8.0))
        c.apply(ch[0], ch[0])
        Core.merge(ch, lab)
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release(); ch.forEach { it.release() }
        return out
    }
}
