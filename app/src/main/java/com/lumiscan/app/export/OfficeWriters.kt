package com.lumiscan.app.export

import android.graphics.BitmapFactory
import com.lumiscan.app.ml.Align
import com.lumiscan.app.ml.DocItem
import com.lumiscan.app.ml.TableModel
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

private fun esc(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        else -> if (c.code < 0x20 && c != '\n' && c != '\t') Unit else append(c)
    }
}

private fun ZipOutputStream.entry(name: String, content: String) = entry(name, content.toByteArray(Charsets.UTF_8))
private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
}

/** Minimal but standards-compliant .docx writer (paragraphs, tables with merges, images, page breaks). */
object DocxWriter {

    private const val NS = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""
    private const val CONTENT_TWIPS = 9026 // A4 width 11906 - 2 × 1440 margins
    private const val EMU_PER_PT = 12700L

    fun write(items: List<DocItem>, out: File): File {
        val images = ArrayList<Pair<String, File>>()
        val body = StringBuilder()
        for (item in items) when (item) {
            is DocItem.Para -> body.append(para(item))
            is DocItem.Table -> body.append(table(item.table)).append("<w:p/>")
            is DocItem.Image -> {
                val f = File(item.path)
                if (!f.exists()) continue
                val idx = images.size + 1
                images += "rIdImg$idx" to f
                body.append(image(idx, item.width, item.height))
            }
            DocItem.PageBreak -> body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
        }
        val doc = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $NS><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="851" w:footer="992" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { z ->
            z.entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            z.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            images.forEachIndexed { i, (id, _) ->
                rels.append("""<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image${i + 1}.jpeg"/>""")
            }
            rels.append("</Relationships>")
            z.entry("word/_rels/document.xml.rels", rels.toString())
            z.entry("word/styles.xml", STYLES)
            z.entry("word/document.xml", doc)
            images.forEachIndexed { i, (_, f) -> z.entry("word/media/image${i + 1}.jpeg", f.readBytes()) }
        }
        return out
    }

    private fun runs(text: String, sizePt: Float, bold: Boolean): String {
        val sz = (sizePt * 2).roundToInt()
        val rPr = "<w:rPr>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr>"
        return text.split('\n').joinToString("<w:r><w:br/></w:r>") { line ->
            "<w:r>$rPr<w:t xml:space=\"preserve\">${esc(line)}</w:t></w:r>"
        }
    }

    private fun para(p: DocItem.Para): String {
        val jc = when (p.align) { Align.CENTER -> "center"; Align.RIGHT -> "right"; Align.LEFT -> "both" }
        return "<w:p><w:pPr><w:jc w:val=\"$jc\"/></w:pPr>${runs(p.text, p.sizePt, p.bold)}</w:p>"
    }

    private fun table(t: TableModel): String {
        val cols = t.cols.coerceAtLeast(1)
        val colW = CONTENT_TWIPS / cols
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"$CONTENT_TWIPS\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>")
        }
        sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$colW\"/>") }
        sb.append("</w:tblGrid>")
        for (r in 0 until t.rows) {
            sb.append("<w:tr>")
            var c = 0
            while (c < cols) {
                val origin = t.mergeAt(r, c)
                val coveringFromAbove = t.merges.firstOrNull { m -> m.col == c && r > m.row && r < m.row + m.rowSpan }
                when {
                    origin != null -> {
                        sb.append(cell(t.cell(r, c), colW * origin.colSpan, origin.colSpan, if (origin.rowSpan > 1) "restart" else null))
                        c += origin.colSpan
                    }
                    coveringFromAbove != null -> {
                        sb.append(cell("", colW * coveringFromAbove.colSpan, coveringFromAbove.colSpan, "continue"))
                        c += coveringFromAbove.colSpan
                    }
                    t.isCovered(r, c) -> c++
                    else -> { sb.append(cell(t.cell(r, c), colW, 1, null)); c++ }
                }
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun cell(text: String, width: Int, span: Int, vMerge: String?): String {
        val pr = StringBuilder("<w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
        if (span > 1) pr.append("<w:gridSpan w:val=\"$span\"/>")
        when (vMerge) { "restart" -> pr.append("<w:vMerge w:val=\"restart\"/>"); "continue" -> pr.append("<w:vMerge/>") }
        pr.append("<w:vAlign w:val=\"center\"/></w:tcPr>")
        return "<w:tc>$pr<w:p>${if (text.isEmpty()) "" else runs(text, 10.5f, false)}</w:p></w:tc>"
    }

    private fun image(idx: Int, wPx: Int, hPx: Int): String {
        var wPt = 451f
        var hPt = wPt * hPx / wPx.coerceAtLeast(1)
        if (hPt > 680f) { wPt *= 680f / hPt; hPt = 680f }
        val cx = (wPt * EMU_PER_PT).toLong(); val cy = (hPt * EMU_PER_PT).toLong()
        return """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$idx" name="Picture $idx"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$idx" name="image$idx.jpeg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdImg$idx"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    fun imageItem(file: File): DocItem.Image {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return DocItem.Image(file.absolutePath, o.outWidth, o.outHeight)
    }

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="宋体" w:cs="Times New Roman"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="312" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style></w:styles>"""
}

/** Minimal .xlsx writer: one sheet per table, bordered wrapped cells, merges, auto column widths. */
object XlsxWriter {

    fun write(sheets: List<Pair<String, TableModel>>, out: File): File {
        out.parentFile?.mkdirs()
        val list = sheets.ifEmpty { listOf("Sheet1" to TableModel(emptyList())) }
        ZipOutputStream(FileOutputStream(out)).use { z ->
            val ct = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            list.indices.forEach { ct.append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            ct.append("</Types>")
            z.entry("[Content_Types].xml", ct.toString())
            z.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            val wb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            val usedNames = HashSet<String>()
            list.forEachIndexed { i, (name, _) ->
                var n = name.replace(Regex("[\\\\/?*\\[\\]:]"), "").take(28).ifBlank { "Sheet${i + 1}" }
                while (!usedNames.add(n)) n = "$n${i + 1}"
                wb.append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            wb.append("</sheets></workbook>")
            z.entry("xl/workbook.xml", wb.toString())
            val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            list.indices.forEach { rels.append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
            rels.append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            z.entry("xl/_rels/workbook.xml.rels", rels.toString())
            z.entry("xl/styles.xml", STYLES)
            list.forEachIndexed { i, (_, t) -> z.entry("xl/worksheets/sheet${i + 1}.xml", sheet(t)) }
        }
        return out
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private val NUMBER = Regex("^-?(0|[1-9]\\d{0,13})(\\.\\d+)?$")

    private fun sheet(t: TableModel): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        if (t.cols > 0) {
            sb.append("<cols>")
            for (c in 0 until t.cols) {
                val len = (0 until t.rows).maxOfOrNull { r -> t.cell(r, c).lines().maxOfOrNull { l -> l.sumOf { ch -> if (ch.code > 0x2E80) 2.0 else 1.1 } } ?: 0.0 } ?: 0.0
                val w = min(60.0, (len + 2).coerceAtLeast(8.0))
                sb.append("""<col min="${c + 1}" max="${c + 1}" width="${"%.1f".format(java.util.Locale.US, w)}" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        for (r in 0 until t.rows) {
            sb.append("<row r=\"${r + 1}\">")
            for (c in 0 until t.cols) {
                val ref = "${colName(c)}${r + 1}"
                val v = t.cell(r, c).trim()
                when {
                    v.isEmpty() -> sb.append("<c r=\"$ref\" s=\"1\"/>")
                    NUMBER.matches(v) -> sb.append("<c r=\"$ref\" s=\"1\"><v>$v</v></c>")
                    else -> sb.append("<c r=\"$ref\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (t.merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${t.merges.size}\">")
            t.merges.forEach { m ->
                sb.append("<mergeCell ref=\"${colName(m.col)}${m.row + 1}:${colName(m.col + m.colSpan - 1)}${m.row + m.rowSpan}\"/>")
            }
            sb.append("</mergeCells>")
        }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="等线"/><family val="2"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
