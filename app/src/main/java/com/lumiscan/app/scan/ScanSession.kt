package com.lumiscan.app.scan

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.DocKind
import com.lumiscan.app.data.FilterType
import com.lumiscan.app.data.NewPage
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.DocImage
import com.lumiscan.app.imaging.EdgeDetector
import com.lumiscan.app.imaging.IdCardType
import com.lumiscan.app.imaging.PageCompose
import com.lumiscan.app.imaging.Pt
import com.lumiscan.app.imaging.Quad
import com.lumiscan.app.imaging.clamp
import com.lumiscan.app.nav.Navigator
import com.lumiscan.app.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ScanMode(val label: String, val multi: Boolean, val crop: Boolean, val hint: String = "") {
    DOC("文件扫描", true, true, "对准文档，自动识别边框"),
    OCR("提取文字", true, true, "拍摄文字，一键提取可编辑文本"),
    WORD("转Word", true, true, "拍摄文档，还原排版生成Word"),
    EXCEL("转Excel", true, true, "拍摄表格，识别生成Excel"),
    ID_CARD("证件扫描", true, true),
    ID_PHOTO("证件照", false, false, "正面免冠，光线均匀，背景简洁"),
    TRANSLATE("拍照翻译", false, false, "拍摄外文或中文，整页翻译"),
    COUNT("拍照计数", false, false, "俯拍物体，自动计数"),
    HANDWRITING("去手写", true, true, "去除笔迹，还原空白文档"),
    EXAM("试卷还原", true, true, "擦除作答痕迹，还原空白试卷"),
    WATERMARK("去水印", false, false, "去除文档水印与图片印记"),
    ERASE("智能消除", false, false, "涂抹想要消除的内容"),
    OLD_PHOTO("老照片修复", false, false, "修复划痕、噪点与褪色"),
    SHARPEN("变清晰", false, false, "模糊图片一键变清晰"),
    SOLVE("拍题解答", false, true, "对准一道题拍摄，AI 给出解题步骤"),
    AI_DOC("AI 文档助手", true, true, "拍摄文档，AI 帮你总结、问答、纠错、翻译");

    /** Tools usually used on yourself start with the front camera. */
    val prefersFront: Boolean get() = this == ID_PHOTO
}

class PendingPage(source: String, quad: Quad?, val width: Int, val height: Int) {
    val id: String = UUID.randomUUID().toString()
    var source by mutableStateOf(source)
    var quad by mutableStateOf(quad)
    var w by mutableStateOf(width)
    var h by mutableStateOf(height)
    var filter by mutableStateOf(ScanApp.instance.settings.defaultFilter)
    /** Path of the flattened page after cropping. */
    var cropped: String? = null
}

/** Pages captured in the current camera session, shared by Camera → Crop → Filters. */
object ScanSession {
    var mode by mutableStateOf(ScanMode.DOC)
    var appendTo: String? = null
    var idType by mutableStateOf(IdCardType.ID_CARD)
    val pages = mutableStateListOf<PendingPage>()

    fun start(mode: ScanMode, appendTo: String?) {
        this.mode = mode
        this.appendTo = appendTo
        pages.clear()
    }

    /**
     * Normalises a captured/imported image and auto-detects its document edges. An ID shot with the
     * camera whose edges aren't found starts from the guide box it was lined up with.
     */
    suspend fun add(file: File, detect: Boolean, fromCamera: Boolean = false): PendingPage? = withContext(Dispatchers.Default) {
        val bmp = BitmapIO.decode(file.absolutePath) ?: return@withContext null
        val out = BitmapIO.saveJpeg(bmp, BitmapIO.newWorkFile(), 95)
        val quad = if (detect) detectEdges(bmp) ?: (if (fromCamera) fallbackQuad(bmp.width, bmp.height) else null) else null
        val page = PendingPage(out.absolutePath, quad, bmp.width, bmp.height)
        bmp.recycle()
        withContext(Dispatchers.Main) {
            // Single-photo tools work on the latest shot: a retake replaces the previous photo.
            if (!mode.multi) pages.clear()
            pages += page
        }
        page
    }

    /** Document outline for the current mode: IDs keep their whole edge, rigid cards have no fold. */
    fun detectEdges(bmp: Bitmap): Quad? =
        if (mode == ScanMode.ID_CARD) EdgeDetector.detect(bmp, card = true, foldTrim = idType.booklet) else EdgeDetector.detect(bmp)

    /** The camera's guide box for IDs (with room to spare), when no outline was found. */
    fun fallbackQuad(w: Int, h: Int): Quad? {
        if (mode != ScanMode.ID_CARD || h <= w) return null
        val (l, t, r, b) = idType.guide()
        val m = 0.03f
        return Quad(Pt((l - m) * w, (t - m) * h), Pt((r + m) * w, (t - m) * h), Pt((r + m) * w, (b + m) * h), Pt((l - m) * w, (b + m) * h)).clamp(w.toFloat(), h.toFloat())
    }

    fun requiredCount(): Int = if (mode == ScanMode.ID_CARD) idType.sides.size else Int.MAX_VALUE

    /** Called when capture is finished: continue to cropping or straight into the tool. */
    fun proceed(nav: Navigator) {
        val latest = pages.lastOrNull() ?: return
        if (mode.crop) {
            nav.push(Route.Crop); return
        }
        val path = latest.source
        nav.push(
            when (mode) {
                ScanMode.ID_PHOTO -> Route.IdPhoto(path)
                ScanMode.TRANSLATE -> Route.Translate(path)
                ScanMode.COUNT -> Route.Count(path)
                ScanMode.WATERMARK -> Route.Erase(path, watermark = true)
                ScanMode.ERASE -> Route.Erase(path, watermark = false)
                ScanMode.OLD_PHOTO -> Route.Restore(path, oldPhoto = true)
                ScanMode.SHARPEN -> Route.Restore(path, oldPhoto = false)
                else -> Route.Crop
            }
        )
    }

    /** Flattens every page with its quad (ID cards are forced to the card's real aspect). */
    suspend fun cropAll(onProgress: (Int) -> Unit) = withContext(Dispatchers.Default) {
        pages.forEachIndexed { i, p ->
            val bmp = BitmapIO.decode(p.source) ?: return@forEachIndexed
            val quad = p.quad ?: Quad.full(bmp.width.toFloat(), bmp.height.toFloat())
            val warped = if (mode == ScanMode.ID_CARD && idType.aspect != null) {
                val (nw, nh) = quad.outputSize()
                val (w, h) = PageCompose.cardPixelSize(idType, nw, nh)
                // Cards photographed portrait are rotated so the long side is horizontal.
                DocImage.warp(bmp, if (nh > nw && idType.aspect!! > 1f) quad.rotateLeft() else quad, w, h)
            } else DocImage.warp(bmp, quad)
            p.cropped = BitmapIO.saveJpeg(warped, BitmapIO.newWorkFile(), 95).absolutePath
            warped.recycle(); bmp.recycle()
            onProgress(i + 1)
        }
    }

    /** Routes to the next step after cropping. */
    suspend fun afterCrop(nav: Navigator) {
        val paths = pages.mapNotNull { it.cropped }
        when (mode) {
            ScanMode.DOC -> nav.push(Route.Filters)
            ScanMode.OCR -> nav.push(Route.Ocr(paths))
            ScanMode.WORD -> nav.push(Route.Word(paths))
            ScanMode.EXCEL -> nav.push(Route.Excel(paths))
            ScanMode.HANDWRITING -> nav.push(Route.Handwriting(paths, exam = false))
            ScanMode.EXAM -> nav.push(Route.Handwriting(paths, exam = true))
            ScanMode.SOLVE -> paths.lastOrNull()?.let { nav.push(Route.Solve(it)) }
            ScanMode.AI_DOC -> nav.push(Route.AiAssist(null, paths))
            ScanMode.ID_CARD -> {
                val id = saveIdCard()
                nav.reset(Route.Doc(id))
            }
            else -> nav.push(Route.Filters)
        }
    }

    private suspend fun saveIdCard(): String = withContext(Dispatchers.Default) {
        val sides = pages.mapNotNull { p -> p.cropped?.let { BitmapIO.decode(it) } }
        val page = PageCompose.composeA4(idType, sides)
        sides.forEach { it.recycle() }
        val file = BitmapIO.saveJpeg(page, BitmapIO.newWorkFile(), 93)
        page.recycle()
        val repo = ScanApp.instance.repository
        val newPage = NewPage(file, file, null, FilterType.ORIGINAL)
        val target = appendTo?.let { repo.addPages(it, listOf(newPage)) }
        (target ?: repo.create("${idType.label} ${timestamp()}", DocKind.ID_CARD, listOf(newPage))).id
    }

    fun timestamp(): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
}

/** Same corners, starting one step later: rotates the output 90° counter-clockwise. */
private fun Quad.rotateLeft(): Quad = Quad(tr, br, bl, tl)
