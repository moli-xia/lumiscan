package com.lumiscan.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lumiscan.app.ScanApp
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapIO {

    /** Longest side used for stored scans; keeps 300dpi A4 quality while bounding memory. */
    const val MAX_SIDE = 3200

    fun newWorkFile(ext: String = "jpg"): File =
        File(ScanApp.instance.workDir, "${UUID.randomUUID().toString().take(12)}.$ext")

    /** Decodes a file with EXIF orientation applied and the longest side capped at [maxSide]. */
    fun decode(path: String, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(path, opts) ?: return null
        val oriented = applyOrientation(raw, runCatching { ExifInterface(path) }.getOrNull())
        return capSize(oriented, maxSide)
    }

    fun decodeUri(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val exif = runCatching { cr.openInputStream(uri)?.use { s: InputStream -> ExifInterface(s) } }.getOrNull()
        return capSize(applyOrientation(raw, exif), maxSide)
    }

    /** Imports a picked/shared image into the work directory as an upright JPEG. */
    fun importUri(context: Context, uri: Uri): File? = runCatching {
        val bmp = decodeUri(context, uri) ?: return null
        saveJpeg(bmp, newWorkFile(), 95).also { bmp.recycle() }
    }.getOrNull()

    /** Re-encodes a camera file so the pixels are upright (drops EXIF rotation). */
    fun normalize(file: File): File? {
        val bmp = decode(file.absolutePath) ?: return null
        return saveJpeg(bmp, newWorkFile(), 95).also { bmp.recycle() }
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            val src = if (bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
            src.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (src !== bitmap) src.recycle()
        }
        return file
    }

    fun savePng(bitmap: Bitmap, file: File): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun jpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val src = if (bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
        src.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (src !== bitmap) src.recycle()
        return out.toByteArray()
    }

    fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        return out
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    fun scaleToMax(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).roundToInt().coerceAtLeast(1), (bitmap.height * s).roundToInt().coerceAtLeast(1), true)
    }

    fun imageSize(path: String): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        val exif = runCatching { ExifInterface(path) }.getOrNull()
        val rot = exif?.rotationDegrees ?: 0
        return if (rot == 90 || rot == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    /** Draws [overlay] on top of [base] at the normalized rect (fractions of base size). */
    fun drawOverlay(base: Bitmap, overlay: Bitmap, nx: Float, ny: Float, nw: Float, nh: Float): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val dst = android.graphics.RectF(nx * out.width, ny * out.height, (nx + nw) * out.width, (ny + nh) * out.height)
        Canvas(out).drawBitmap(overlay, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun capSize(bmp: Bitmap, maxSide: Int): Bitmap {
        val scaled = scaleToMax(bmp, maxSide)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun applyOrientation(bmp: Bitmap, exif: ExifInterface?): Bitmap {
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: return bmp
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
