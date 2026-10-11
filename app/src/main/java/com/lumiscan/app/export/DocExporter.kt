package com.lumiscan.app.export

import com.lumiscan.app.data.ExportQuality
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.PageCompose
import com.lumiscan.app.ml.DocItem
import com.lumiscan.app.ml.RecognizeRun
import com.lumiscan.app.ml.TableModel
import java.io.File

enum class ExportFormat(val label: String) { PDF("PDF"), IMAGES("图片"), LONG_IMAGE("长图"), WORD("Word"), EXCEL("Excel"), TXT("TXT") }

data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val quality: ExportQuality = ExportQuality.HIGH,
    val password: String = "",
    val watermark: String = "",
    /** Word export: also append the page image after the recognised text. */
    val wordWithImage: Boolean = false,
)

/** Converts a list of page images into a deliverable file in the export directory. */
object DocExporter {

    suspend fun export(name: String, pages: List<File>, o: ExportOptions, run: RecognizeRun = RecognizeRun(), onProgress: (String) -> Unit = {}): List<File> =
        when (o.format) {
            ExportFormat.PDF -> listOf(
                PdfTools.fromImages(pages, Share.exportFile(name, "pdf"), o.pageSize, o.quality, o.watermark, o.password) {
                    onProgress("正在生成 PDF $it/${pages.size}")
                }
            )
            ExportFormat.IMAGES -> pages.mapIndexed { i, f ->
                onProgress("正在导出图片 ${i + 1}/${pages.size}")
                val bmp = BitmapIO.decode(f.absolutePath, o.quality.maxSide)!!
                val marked = if (o.watermark.isNotBlank()) PageCompose.watermark(bmp, o.watermark) else bmp
                BitmapIO.saveJpeg(marked, Share.exportFile("${name}_${i + 1}", "jpg"), o.quality.jpeg)
            }
            ExportFormat.LONG_IMAGE -> {
                onProgress("正在拼接长图")
                val bmps = pages.mapNotNull { BitmapIO.decode(it.absolutePath, 1600) }
                val long = PageCompose.longImage(bmps)
                val marked = if (o.watermark.isNotBlank()) PageCompose.watermark(long, o.watermark, sizeFactor = 0.25f) else long
                bmps.forEach { it.recycle() }
                listOf(BitmapIO.saveJpeg(marked, Share.exportFile(name, "jpg"), o.quality.jpeg))
            }
            ExportFormat.WORD -> listOf(DocxWriter.write(wordItems(pages, o.wordWithImage, run, onProgress), Share.exportFile(name, "docx")))
            ExportFormat.EXCEL -> listOf(XlsxWriter.write(tables(pages, run, onProgress).mapIndexed { i, t -> "第${i + 1}页" to t }, Share.exportFile(name, "xlsx")))
            ExportFormat.TXT -> {
                val text = pages.mapIndexed { i, f ->
                    onProgress("正在识别文字 ${i + 1}/${pages.size}")
                    val bmp = BitmapIO.decode(f.absolutePath, 2600) ?: return@mapIndexed ""
                    run.text(bmp).also { bmp.recycle() }
                }.joinToString("\n\n")
                listOf(Share.exportFile(name, "txt").apply { writeText(text) })
            }
        }

    suspend fun wordItems(pages: List<File>, withImage: Boolean, run: RecognizeRun = RecognizeRun(), onProgress: (String) -> Unit = {}): List<DocItem> {
        val items = ArrayList<DocItem>()
        pages.forEachIndexed { i, f ->
            onProgress("AI 正在识别版面 ${i + 1}/${pages.size}")
            if (i > 0) items += DocItem.PageBreak
            val bmp = BitmapIO.decode(f.absolutePath, 2600) ?: return@forEachIndexed
            items += run.wordItems(bmp)
            bmp.recycle()
            if (withImage) items += DocxWriter.imageItem(f)
        }
        return items
    }

    suspend fun tables(pages: List<File>, run: RecognizeRun = RecognizeRun(), onProgress: (String) -> Unit = {}): List<TableModel> =
        pages.mapIndexed { i, f ->
            onProgress("AI 正在识别表格 ${i + 1}/${pages.size}")
            val bmp = BitmapIO.decode(f.absolutePath, 2600) ?: return@mapIndexed TableModel(emptyList())
            run.table(bmp).also { bmp.recycle() }
        }
}
