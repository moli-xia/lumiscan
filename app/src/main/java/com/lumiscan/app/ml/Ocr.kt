package com.lumiscan.app.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.lumiscan.app.imaging.BitmapIO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

data class OcrSymbol(val text: String, val box: Rect)
data class OcrWord(val text: String, val box: Rect, val symbols: List<OcrSymbol>)
data class OcrLine(val text: String, val box: Rect, val words: List<OcrWord>, val angle: Float = 0f)
data class OcrBlock(val box: Rect, val lines: List<OcrLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

data class OcrResult(val width: Int, val height: Int, val blocks: List<OcrBlock>) {
    val lines: List<OcrLine> get() = blocks.flatMap { it.lines }

    /**
     * Blocks in reading order: bands of vertically overlapping blocks, top to bottom, each band
     * left to right (so table rows and side-by-side labels stay together).
     */
    val rows: List<List<OcrBlock>> by lazy {
        val out = ArrayList<MutableList<OcrBlock>>()
        for (b in blocks.sortedBy { it.box.top }) {
            val row = out.lastOrNull()
            if (row != null) {
                val top = row.minOf { it.box.top }; val bottom = row.maxOf { it.box.bottom }
                val overlap = minOf(bottom, b.box.bottom) - maxOf(top, b.box.top)
                if (overlap > 0.5f * minOf(bottom - top, b.box.height())) { row += b; continue }
            }
            out += mutableListOf(b)
        }
        out.map { r -> r.sortedBy { it.box.left } }
    }

    private fun join(row: List<OcrBlock>, f: (OcrBlock) -> String): String =
        if (row.size > 1 && row.all { it.lines.size == 1 }) row.joinToString("  ", transform = f) else row.joinToString("\n", transform = f)

    /** Text with original line breaks, in reading order. */
    val text: String get() = rows.joinToString("\n") { join(it) { b -> b.text } }

    /** Text with lines inside a block joined into flowing paragraphs. */
    val paragraphs: String get() = rows.joinToString("\n") { join(it) { b -> TextJoin.tidy(TextJoin.joinLines(b.lines.map { l -> l.text })) } }
}

/** On-device Chinese + Latin OCR (ML Kit bundled model, works offline without Play Services). */
object OcrEngine {
    private const val MAX_SIDE = 2600
    private const val UPSCALE_BELOW = 2200

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        // Chinese recognition degrades below ~30px per character; small pages are upscaled.
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest < UPSCALE_BELOW) {
            val f = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
        } else BitmapIO.scaleToMax(bitmap, MAX_SIDE)
        val s = bitmap.width.toFloat() / scaled.width
        val text = recognizer.process(InputImage.fromBitmap(scaled, 0)).await()
        if (scaled !== bitmap) scaled.recycle()
        fun r(b: Rect?): Rect = b?.let { Rect((it.left * s).toInt(), (it.top * s).toInt(), (it.right * s).toInt(), (it.bottom * s).toInt()) } ?: Rect()
        val blocks = text.textBlocks.map { block ->
            OcrBlock(r(block.boundingBox), block.lines.map { line ->
                OcrLine(line.text, r(line.boundingBox), line.elements.map { el ->
                    OcrWord(el.text, r(el.boundingBox), el.symbols.map { OcrSymbol(it.text, r(it.boundingBox)) })
                }, line.angle)
            })
        }
        return OcrResult(bitmap.width, bitmap.height, blocks)
    }

    suspend fun recognizeFile(path: String): OcrResult? {
        val bmp = BitmapIO.decode(path, MAX_SIDE) ?: return null
        return recognize(bmp).also { bmp.recycle() }
    }
}

object TextJoin {
    fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    /** Joins two fragments: no space between CJK characters, a space between Latin words. */
    fun glue(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val l = a.last(); val f = b.first()
        return if (isCjk(l) || isCjk(f) || l == '-') (if (l == '-' && !isCjk(f)) a.dropLast(1) + b else a + b) else "$a $b"
    }

    fun joinLines(lines: List<String>): String = lines.fold("") { acc, s -> glue(acc, s.trim()) }

    /** Removes the stray spaces the recogniser sometimes inserts between Chinese characters. */
    fun tidy(s: String): String = s.replace(Regex("(?<=[\\u4e00-\\u9fa5，。、；：？！])\\s+(?=[\\u4e00-\\u9fa5，。、；：？！])"), "")
}
