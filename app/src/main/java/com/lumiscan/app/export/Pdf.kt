package com.lumiscan.app.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.lumiscan.app.data.ExportQuality
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.PageCompose
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class PdfPageSize(val label: String) { A4("A4"), FIT("原图尺寸") }

/** Builds PDFs from scanned images and implements the PDF tool box on top of PdfBox. */
object PdfTools {

    fun isEncrypted(file: File): Boolean = try {
        PDDocument.load(file).use { false }
    } catch (_: InvalidPasswordException) {
        true
    }

    fun open(file: File, password: String? = null): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(file, MemoryUsageSetting.setupMixed(64L shl 20))
        else PDDocument.load(file, password, MemoryUsageSetting.setupMixed(64L shl 20))

    fun pageCount(file: File, password: String? = null): Int = open(file, password).use { it.numberOfPages }

    fun fromImages(
        images: List<File>,
        out: File,
        pageSize: PdfPageSize = PdfPageSize.A4,
        quality: ExportQuality = ExportQuality.HIGH,
        watermark: String? = null,
        password: String? = null,
        onProgress: (Int) -> Unit = {},
    ): File {
        PDDocument().use { doc ->
            images.forEachIndexed { i, f ->
                val bmp = BitmapIO.decode(f.absolutePath, quality.maxSide) ?: return@forEachIndexed
                val marked = if (!watermark.isNullOrBlank()) PageCompose.watermark(bmp, watermark) else bmp
                val img = JPEGFactory.createFromByteArray(doc, BitmapIO.jpegBytes(marked, quality.jpeg))
                val landscape = marked.width > marked.height
                val rect = when (pageSize) {
                    PdfPageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                    PdfPageSize.FIT -> {
                        val w = if (landscape) PDRectangle.A4.height else PDRectangle.A4.width
                        PDRectangle(w, w * marked.height / marked.width)
                    }
                }
                val page = PDPage(rect)
                doc.addPage(page)
                val s = min(rect.width / marked.width, rect.height / marked.height)
                val w = marked.width * s; val h = marked.height * s
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, (rect.width - w) / 2, (rect.height - h) / 2, w, h)
                }
                if (marked !== bmp) marked.recycle()
                bmp.recycle()
                onProgress(i + 1)
            }
            if (!password.isNullOrEmpty()) protect(doc, password)
            out.parentFile?.mkdirs()
            doc.save(out)
        }
        return out
    }

    private fun protect(doc: PDDocument, password: String) {
        val policy = StandardProtectionPolicy(password, password, AccessPermission())
        policy.encryptionKeyLength = 128
        doc.protect(policy)
    }

    fun merge(files: List<File>, out: File): File {
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(MemoryUsageSetting.setupMixed(64L shl 20))
        return out
    }

    /** Copies the given 0-based pages (in order) into a new PDF. */
    fun extract(file: File, pages: List<Int>, out: File, password: String? = null): File {
        open(file, password).use { src ->
            PDDocument().use { dst ->
                pages.forEach { i -> dst.importPage(src.getPage(i)) }
                dst.save(out)
            }
        }
        return out
    }

    fun split(file: File, ranges: List<IntRange>, outDir: File, baseName: String, password: String? = null): List<File> {
        outDir.mkdirs()
        return open(file, password).use { src ->
            ranges.mapIndexed { idx, range ->
                val out = File(outDir, "${baseName}_${idx + 1}(${range.first + 1}-${range.last + 1}).pdf")
                PDDocument().use { dst ->
                    range.forEach { i -> dst.importPage(src.getPage(i)) }
                    dst.save(out)
                }
                out
            }
        }
    }

    fun deletePages(file: File, pages: Set<Int>, out: File, password: String? = null): File {
        open(file, password).use { doc ->
            pages.sortedDescending().forEach { doc.removePage(it) }
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    fun rotatePages(file: File, pages: Set<Int>, degrees: Int, out: File, password: String? = null): File {
        open(file, password).use { doc ->
            pages.forEach { i -> val p = doc.getPage(i); p.rotation = ((p.rotation + degrees) % 360 + 360) % 360 }
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    fun encrypt(file: File, password: String, out: File, currentPassword: String? = null): File {
        open(file, currentPassword).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            protect(doc, password)
            doc.save(out)
        }
        return out
    }

    fun decrypt(file: File, password: String, out: File): File {
        open(file, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    /**
     * Re-encodes embedded raster images at lower resolution/quality. Text and vector content are
     * untouched, so this is lossless for "real" PDFs and very effective for scanned ones.
     */
    fun compress(file: File, maxSide: Int, jpegQuality: Int, out: File, password: String? = null): File {
        open(file, password).use { doc ->
            val seen = HashMap<Any, PDImageXObject>()
            for (page in doc.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames.toList()) {
                    val x = res.getXObject(name) as? PDImageXObject ?: continue
                    if (x.isStencil || x.softMask != null || x.bitsPerComponent < 8) continue
                    val key = x.cosObject
                    val replacement = seen.getOrPut(key) {
                        val bmp = x.image ?: return@getOrPut x
                        val scaled = BitmapIO.scaleToMax(bmp, maxSide)
                        val bytes = BitmapIO.jpegBytes(scaled, jpegQuality)
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                        JPEGFactory.createFromByteArray(doc, bytes)
                    }
                    if (replacement !== x) res.put(name, replacement)
                }
            }
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    /** Tiled diagonal text watermark rendered as a transparent image layer on every page. */
    fun watermark(file: File, text: String, alpha: Int, color: Int, sizeFactor: Float, out: File, password: String? = null): File {
        open(file, password).use { doc ->
            val cache = HashMap<Pair<Int, Int>, PDImageXObject>()
            for (page in doc.pages) {
                val box = page.cropBox
                val key = box.width.toInt() to box.height.toInt()
                val img = cache.getOrPut(key) {
                    val s = 1400f / max(box.width, box.height)
                    val layer = PageCompose.watermarkLayer((box.width * s).toInt(), (box.height * s).toInt(), text, alpha, color, sizeFactor)
                    LosslessFactory.createFromImage(doc, layer).also { layer.recycle() }
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(img, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                }
            }
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    /**
     * Stamps [stamp] (e.g. a signature) on page [index] at a rect given as fractions of the page
     * as displayed (top-left origin, page rotation applied).
     */
    fun stamp(file: File, index: Int, stamp: Bitmap, nx: Float, ny: Float, nw: Float, nh: Float, out: File, password: String? = null): File {
        open(file, password).use { doc ->
            val page = doc.getPage(index)
            val box = page.cropBox
            val W = box.width; val H = box.height
            val rot = ((page.rotation % 360) + 360) % 360
            fun map(u: Float, v: Float): Pair<Float, Float> = when (rot) {
                90 -> v * W to u * H
                180 -> (1 - u) * W to v * H
                270 -> (1 - v) * W to (1 - u) * H
                else -> u * W to (1 - v) * H
            }
            val (ax, ay) = map(nx, ny)
            val (bx, by) = map(nx + nw, ny + nh)
            val upright = BitmapIO.rotate(stamp, (360 - rot) % 360)
            val img = LosslessFactory.createFromImage(doc, upright)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(img, box.lowerLeftX + min(ax, bx), box.lowerLeftY + min(ay, by), kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay))
            }
            if (upright !== stamp) upright.recycle()
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    /** Text per page from the PDF's text layer (empty strings for scanned pages). */
    fun extractText(file: File, password: String? = null): List<String> = open(file, password).use { doc ->
        val stripper = PDFTextStripper()
        (1..doc.numberOfPages).map { p ->
            stripper.startPage = p; stripper.endPage = p
            stripper.getText(doc).trim()
        }
    }

    /** Makes a renderer-friendly (unencrypted) copy when needed. */
    fun plainCopy(file: File, password: String?, work: File): File {
        if (password.isNullOrEmpty() && !needsDecrypt(file)) return file
        return decrypt(file, password.orEmpty(), work)
    }

    private fun needsDecrypt(file: File): Boolean = runCatching { PDDocument.load(file).use { it.isEncrypted } }.getOrDefault(true)
}

/** Android framework renderer for page previews and rasterising (PDF → 图片 / 长图 / OCR). */
class PdfPages(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    val count: Int get() = renderer.pageCount

    /** Page aspect (w/h) at display orientation. */
    @Synchronized
    fun aspect(index: Int): Float = renderer.openPage(index).use { it.width.toFloat() / it.height }

    @Synchronized
    fun render(index: Int, width: Int): Bitmap = renderer.openPage(index).use { page ->
        val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    override fun close() {
        renderer.close(); fd.close()
    }
}
