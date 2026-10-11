package com.lumiscan.app.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BrandingWatermark
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Portrait
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumiscan.app.nav.Navigator
import com.lumiscan.app.nav.Route
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.scan.ScanSession
import com.lumiscan.app.ui.pdf.PdfToolKind

sealed interface ToolAction {
    data class Scan(val mode: ScanMode) : ToolAction
    data class Pdf(val kind: PdfToolKind) : ToolAction
    data object Signatures : ToolAction
    data object All : ToolAction
}

/** [ai]: backed by a cloud model — shows the AI badge. */
data class ToolDef(val title: String, val icon: ImageVector, val tint: Color, val action: ToolAction, val ai: Boolean = false)

private val Blue = Color(0xFF2F6BFF)
private val Green = Color(0xFF12B76A)
private val Orange = Color(0xFFFF8A00)
private val Purple = Color(0xFF7A5AF8)
private val Red = Color(0xFFF04438)
private val Cyan = Color(0xFF06AED4)
private val Pink = Color(0xFFEE46BC)

object Tools {
    val docScan = ToolDef("文件扫描", Icons.Outlined.DocumentScanner, Blue, ToolAction.Scan(ScanMode.DOC))
    val ocr = ToolDef("提取文字", Icons.Outlined.TextFields, Blue, ToolAction.Scan(ScanMode.OCR), ai = true)
    val word = ToolDef("转Word", Icons.Outlined.Description, Color(0xFF2B579A), ToolAction.Scan(ScanMode.WORD), ai = true)
    val excel = ToolDef("转Excel", Icons.Outlined.TableChart, Color(0xFF1D7044), ToolAction.Scan(ScanMode.EXCEL), ai = true)
    val idCard = ToolDef("证件扫描", Icons.Outlined.Badge, Orange, ToolAction.Scan(ScanMode.ID_CARD))
    val idPhoto = ToolDef("证件照", Icons.Outlined.Portrait, Cyan, ToolAction.Scan(ScanMode.ID_PHOTO), ai = true)
    val translate = ToolDef("拍照翻译", Icons.Outlined.Translate, Purple, ToolAction.Scan(ScanMode.TRANSLATE), ai = true)
    val count = ToolDef("拍照计数", Icons.Outlined.Numbers, Green, ToolAction.Scan(ScanMode.COUNT), ai = true)
    val handwriting = ToolDef("去手写", Icons.Outlined.Gesture, Pink, ToolAction.Scan(ScanMode.HANDWRITING), ai = true)
    val exam = ToolDef("试卷还原", Icons.Outlined.Quiz, Orange, ToolAction.Scan(ScanMode.EXAM), ai = true)
    val watermark = ToolDef("去水印", Icons.Outlined.BrandingWatermark, Cyan, ToolAction.Scan(ScanMode.WATERMARK), ai = true)
    val erase = ToolDef("智能消除", Icons.Outlined.AutoFixHigh, Purple, ToolAction.Scan(ScanMode.ERASE), ai = true)
    val oldPhoto = ToolDef("老照片修复", Icons.Outlined.AutoAwesome, Orange, ToolAction.Scan(ScanMode.OLD_PHOTO), ai = true)
    val sharpen = ToolDef("图片变清晰", Icons.Outlined.Hd, Blue, ToolAction.Scan(ScanMode.SHARPEN), ai = true)
    val signature = ToolDef("手写签名", Icons.Outlined.Draw, Ink2, ToolAction.Signatures)
    val all = ToolDef("全部工具", Icons.Outlined.GridView, Blue, ToolAction.All)
    val aiDoc = ToolDef("AI 文档助手", Icons.Outlined.Psychology, Purple, ToolAction.Scan(ScanMode.AI_DOC), ai = true)
    val solve = ToolDef("拍题解答", Icons.Outlined.School, Orange, ToolAction.Scan(ScanMode.SOLVE), ai = true)

    val pdfFromImages = ToolDef("图片转PDF", Icons.Outlined.Image, Red, ToolAction.Pdf(PdfToolKind.FROM_IMAGES))
    val pdfToWord = ToolDef("PDF转Word", Icons.Outlined.Description, Color(0xFF2B579A), ToolAction.Pdf(PdfToolKind.TO_WORD))
    val pdfToExcel = ToolDef("PDF转Excel", Icons.Outlined.TableChart, Color(0xFF1D7044), ToolAction.Pdf(PdfToolKind.TO_EXCEL))
    val pdfToImages = ToolDef("PDF转图片", Icons.Outlined.Image, Orange, ToolAction.Pdf(PdfToolKind.TO_IMAGES))
    val pdfToLong = ToolDef("PDF转长图", Icons.Outlined.ViewDay, Orange, ToolAction.Pdf(PdfToolKind.TO_LONG_IMAGE))
    val pdfToTxt = ToolDef("PDF提取文字", Icons.Outlined.Article, Blue, ToolAction.Pdf(PdfToolKind.TO_TXT))
    val pdfMerge = ToolDef("PDF合并", Icons.Outlined.CallMerge, Red, ToolAction.Pdf(PdfToolKind.MERGE))
    val pdfSplit = ToolDef("PDF拆分", Icons.Outlined.CallSplit, Red, ToolAction.Pdf(PdfToolKind.SPLIT))
    val pdfPages = ToolDef("页面管理", Icons.Outlined.FileCopy, Red, ToolAction.Pdf(PdfToolKind.PAGES))
    val pdfCompress = ToolDef("PDF压缩", Icons.Outlined.Compress, Green, ToolAction.Pdf(PdfToolKind.COMPRESS))
    val pdfEncrypt = ToolDef("PDF加密", Icons.Outlined.Lock, Purple, ToolAction.Pdf(PdfToolKind.ENCRYPT))
    val pdfDecrypt = ToolDef("PDF解密", Icons.Outlined.LockOpen, Purple, ToolAction.Pdf(PdfToolKind.DECRYPT))
    val pdfWatermark = ToolDef("PDF加水印", Icons.Outlined.WaterDrop, Cyan, ToolAction.Pdf(PdfToolKind.WATERMARK))
    val pdfSign = ToolDef("PDF签名", Icons.Outlined.Draw, Blue, ToolAction.Pdf(PdfToolKind.SIGN))
    val pdfView = ToolDef("PDF阅读", Icons.Outlined.PictureAsPdf, Red, ToolAction.Pdf(PdfToolKind.VIEW))

    val home = listOf(ocr, word, excel, idCard, idPhoto, handwriting, translate, all)
    val imageTools = listOf(watermark, erase, oldPhoto, sharpen, exam, count)
    val homePdf = listOf(pdfFromImages, pdfToWord, pdfMerge, pdfCompress)

    val categories = listOf(
        "AI 助手" to listOf(aiDoc, solve),
        "扫描识别" to listOf(docScan, ocr, word, excel, idCard, translate, count),
        "AI 图像处理" to listOf(idPhoto, handwriting, exam, watermark, erase, oldPhoto, sharpen),
        "PDF 工具" to listOf(
            pdfFromImages, pdfToWord, pdfToExcel, pdfToImages, pdfToLong, pdfToTxt, pdfMerge, pdfSplit,
            pdfPages, pdfCompress, pdfEncrypt, pdfDecrypt, pdfWatermark, pdfSign, pdfView,
        ),
        "签名" to listOf(signature),
    )

    fun launch(nav: Navigator, action: ToolAction) {
        when (action) {
            is ToolAction.Scan -> openCamera(nav, action.mode)
            is ToolAction.Pdf -> nav.push(Route.PdfTool(action.kind))
            ToolAction.Signatures -> nav.push(Route.Signatures)
            ToolAction.All -> nav.push(Route.AllTools)
        }
    }

    fun openCamera(nav: Navigator, mode: ScanMode, appendTo: String? = null) {
        ScanSession.start(mode, appendTo)
        nav.push(Route.Camera(mode, appendTo))
    }
}

private val Ink2 = Color(0xFF344054)
