package com.lumiscan.app.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.ExportQuality
import com.lumiscan.app.export.DocxWriter
import com.lumiscan.app.export.PdfPageSize
import com.lumiscan.app.export.PdfPages
import com.lumiscan.app.export.PdfTools
import com.lumiscan.app.export.Share
import com.lumiscan.app.export.XlsxWriter
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.PageCompose
import com.lumiscan.app.imaging.TableDetector
import com.lumiscan.app.ml.DocItem
import com.lumiscan.app.ml.RecognizeRun
import com.lumiscan.app.ml.LayoutBuilder
import com.lumiscan.app.ml.OcrEngine
import com.lumiscan.app.ml.TableBuilder
import com.lumiscan.app.ml.TextJoin
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.retain
import com.lumiscan.app.nav.Route
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.BarAction
import com.lumiscan.app.ui.common.BottomActions
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.PrimaryButton
import com.lumiscan.app.ui.common.ResultFiles
import com.lumiscan.app.ui.common.SecondaryButton
import com.lumiscan.app.ui.common.TextInputDialog
import com.lumiscan.app.ui.common.formatSize
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.doc.ChipFlow
import com.lumiscan.app.ui.doc.Placement
import com.lumiscan.app.ui.doc.SignaturePicker
import com.lumiscan.app.ui.doc.StampPlacer
import com.lumiscan.app.ui.doc.initialPlacement
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PdfToolKind(val title: String, val desc: String) {
    FROM_IMAGES("图片转PDF", "多张图片按顺序合成一个 PDF"),
    TO_WORD("PDF转Word", "提取文字、段落与表格，生成可编辑 Word；扫描件自动 OCR"),
    TO_EXCEL("PDF转Excel", "识别每页中的表格，生成 Excel（每页一个工作表）"),
    TO_IMAGES("PDF转图片", "每一页导出为高清图片并保存到相册"),
    TO_LONG_IMAGE("PDF转长图", "所有页面拼接成一张长图"),
    TO_TXT("PDF提取文字", "提取全部文字，扫描件自动 OCR 识别"),
    MERGE("PDF合并", "多个 PDF 按顺序合并为一个"),
    SPLIT("PDF拆分", "每页拆成一个文件，或按自定义页码范围拆分"),
    PAGES("页面管理", "提取、删除或旋转指定页面"),
    COMPRESS("PDF压缩", "重新压缩内嵌图片，文字与矢量内容保持不变"),
    ENCRYPT("PDF加密", "为 PDF 设置打开密码（AES 加密）"),
    DECRYPT("PDF解密", "输入密码后移除打开密码与权限限制"),
    WATERMARK("PDF加水印", "为每一页添加平铺文字水印"),
    SIGN("PDF签名", "在指定页面放置手写签名"),
    VIEW("PDF阅读", "打开并浏览 PDF 文件"),
}

@Composable
fun FileIcon(f: File, size: Dp) {
    val (label, color) = when (f.extension.lowercase()) {
        "pdf" -> "PDF" to Color(0xFFE5483D)
        "docx" -> "W" to Color(0xFF2B579A)
        "xlsx" -> "X" to Color(0xFF1D7044)
        "txt" -> "TXT" to Color(0xFF667085)
        "jpg", "jpeg", "png" -> "IMG" to Color(0xFFFF8A00)
        else -> f.extension.uppercase().take(3) to Sub
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (label.length > 1) 11.sp else 18.sp)
    }
}

private suspend fun copyToWork(ctx: Context, uri: Uri, ext: String): File? = withContext(Dispatchers.IO) {
    runCatching {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "文件"
        val dir = File(ScanApp.instance.workDir, "in_${System.nanoTime()}").apply { mkdirs() }
        File(dir, "$name.$ext").also { out -> ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
    }.getOrNull()
}

/** Paragraphs from a PDF text layer: lines are joined until a short line or sentence end. */
private fun paragraphsFromText(text: String): List<DocItem> {
    val lines = text.lines().map { it.trimEnd() }
    val maxLen = lines.maxOfOrNull { it.length } ?: 0
    val out = ArrayList<DocItem>()
    var cur = ""
    fun flush() { if (cur.isNotBlank()) out += DocItem.Para(cur.trim()); cur = "" }
    for (line in lines) {
        if (line.isBlank()) { flush(); continue }
        cur = TextJoin.glue(cur, line.trim())
        if (line.length < maxLen * 0.7 || line.last() in "。！？.!?:：;；") flush()
    }
    flush()
    return out
}

private fun parseRanges(spec: String, count: Int): List<IntRange> = spec.split(',', '，', ' ', ';', '；').mapNotNull { part ->
    val p = part.trim()
    if (p.isEmpty()) return@mapNotNull null
    val nums = p.split('-', '~', '—').mapNotNull { it.trim().toIntOrNull() }
    when (nums.size) {
        1 -> (nums[0] - 1).coerceIn(0, count - 1).let { it..it }
        2 -> (nums[0] - 1).coerceIn(0, count - 1)..(nums[1] - 1).coerceIn(0, count - 1)
        else -> null
    }
}.filter { !it.isEmpty() }

@Composable
fun PdfToolScreen(tool: PdfToolKind, initial: String?) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val inputs = retain("inputs") { mutableStateListOf<File>().apply { initial?.let { add(File(it)) } } }
    val images = retain("images") { mutableStateListOf<String>() }
    var password by retain("password") { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var results by retain("results") { mutableStateOf<List<File>>(emptyList()) }
    var resultText by retain("resultText") { mutableStateOf<String?>(null) }
    var info by retain("info") { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }

    // Options
    var pageSize by retain("pageSize") { mutableStateOf(PdfPageSize.A4) }
    var quality by retain("quality") { mutableStateOf(ScanApp.instance.settings.exportQuality) }
    var withImage by retain("withImage") { mutableStateOf(false) }
    var imgWidth by retain("imgWidth") { mutableIntStateOf(1) }
    var splitEach by retain("splitEach") { mutableStateOf(true) }
    var ranges by retain("ranges") { mutableStateOf("") }
    var level by retain("level") { mutableIntStateOf(1) }
    var pwd1 by retain("pwd1") { mutableStateOf("") }
    var pwd2 by retain("pwd2") { mutableStateOf("") }
    var wmText by retain("wmText") { mutableStateOf("") }
    var wmAlpha by retain("wmAlpha") { mutableFloatStateOf(0.3f) }
    var wmSize by retain("wmSize") { mutableFloatStateOf(1f) }
    var wmColor by retain("wmColor") { mutableIntStateOf(0) }

    fun checkPassword(f: File) {
        scope.launch {
            val enc = withContext(Dispatchers.IO) { runCatching { PdfTools.isEncrypted(f) }.getOrDefault(false) }
            if (enc && tool != PdfToolKind.DECRYPT) askPassword = true
        }
    }
    var started by retain("started") { mutableStateOf(false) }

    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val f = copyToWork(ctx, uri, "pdf") ?: return@launch Share.toast(ctx, "无法读取文件")
            if (tool == PdfToolKind.VIEW) { nav.replace(Route.PdfView(f.absolutePath)); return@launch }
            inputs.clear(); inputs += f; results = emptyList(); resultText = null; info = null; password = null
            checkPassword(f)
        }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val locked = ArrayList<String>()
            uris.forEach { u ->
                val f = copyToWork(ctx, u, "pdf") ?: return@forEach
                if (withContext(Dispatchers.IO) { runCatching { PdfTools.isEncrypted(f) }.getOrDefault(false) }) locked += f.name else inputs += f
            }
            if (locked.isNotEmpty()) Share.toast(ctx, "${locked.joinToString("、")} 已加密，请先用「PDF解密」去除密码再合并")
            results = emptyList()
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        scope.launch {
            busy = "正在导入图片"
            uris.forEach { u -> withContext(Dispatchers.IO) { BitmapIO.importUri(ctx, u) }?.let { images += it.absolutePath } }
            busy = null
            results = emptyList()
        }
    }
    fun pick() {
        when (tool) {
            PdfToolKind.FROM_IMAGES -> pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            PdfToolKind.MERGE -> pickMany.launch(arrayOf("application/pdf"))
            else -> pickOne.launch(arrayOf("application/pdf"))
        }
    }
    // First visit only: returning from a result viewer must not reopen the picker.
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (initial == null) pick() else inputs.firstOrNull()?.let { checkPassword(it) }
    }

    val input = inputs.firstOrNull()
    val baseName = input?.nameWithoutExtension ?: "图片合成"

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = "处理中"
            results = emptyList(); resultText = null; info = null
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure {
                    val msg = it.message.orEmpty()
                    Share.toast(ctx, if ("password" in msg.lowercase()) "密码错误或文件已加密" else "处理失败：$msg")
                }
            busy = null
        }
    }

    suspend fun plainInput(): File = PdfTools.plainCopy(input!!, password, File(ScanApp.instance.workDir, "plain_${System.nanoTime()}.pdf"))

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(tool.title)
            Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(tool.desc, fontSize = 13.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                // Inputs
                if (tool == PdfToolKind.FROM_IMAGES) {
                    if (images.isNotEmpty()) {
                        LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images, key = { it }) { p ->
                                val b = rememberBitmap(p, 240)
                                Box(Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
                                    b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    Text("${images.indexOf(p) + 1}", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp))
                                    Icon(Icons.Outlined.Close, "移除", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { images.remove(p) })
                                }
                            }
                        }
                    }
                } else {
                    inputs.forEachIndexed { i, f ->
                        InputCard(f, password, onRemove = { inputs.removeAt(i); results = emptyList() },
                            onUp = if (tool == PdfToolKind.MERGE && i > 0) ({ inputs.add(i - 1, inputs.removeAt(i)) }) else null,
                            onDown = if (tool == PdfToolKind.MERGE && i < inputs.lastIndex) ({ inputs.add(i + 1, inputs.removeAt(i)) }) else null)
                    }
                }
                SecondaryButton(
                    when (tool) {
                        PdfToolKind.FROM_IMAGES -> if (images.isEmpty()) "选择图片" else "继续添加图片"
                        PdfToolKind.MERGE -> if (inputs.isEmpty()) "选择 PDF 文件" else "继续添加 PDF"
                        else -> if (inputs.isEmpty()) "选择 PDF 文件" else "更换文件"
                    },
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { pick() }

                val ready = if (tool == PdfToolKind.FROM_IMAGES) images.isNotEmpty() else if (tool == PdfToolKind.MERGE) inputs.size >= 2 else input != null
                if (ready) {
                    Spacer(Modifier.height(8.dp))
                    when (tool) {
                        PdfToolKind.FROM_IMAGES -> {
                            OptLabel("纸张"); ChipFlow(PdfPageSize.entries.map { it.label }, pageSize.ordinal) { pageSize = PdfPageSize.entries[it] }
                            OptLabel("画质"); ChipFlow(ExportQuality.entries.map { it.label }, quality.ordinal) { quality = ExportQuality.entries[it] }
                        }
                        PdfToolKind.TO_WORD -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("每页附带原版页面图片", fontSize = 14.sp, modifier = Modifier.weight(1f)); Switch(withImage, { withImage = it })
                        }
                        PdfToolKind.TO_IMAGES, PdfToolKind.TO_LONG_IMAGE -> {
                            OptLabel("清晰度"); ChipFlow(listOf("标准", "高清", "超清"), imgWidth) { imgWidth = it }
                        }
                        PdfToolKind.SPLIT -> {
                            ChipFlow(listOf("每页一个文件", "自定义范围"), if (splitEach) 0 else 1) { splitEach = it == 0 }
                            if (!splitEach) OutlinedTextField(ranges, { ranges = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("如 1-3,4-6,8", color = Sub) }, singleLine = true)
                        }
                        PdfToolKind.COMPRESS -> {
                            OptLabel("压缩强度"); ChipFlow(listOf("轻度（清晰优先）", "中等（推荐）", "强力（体积最小）"), level) { level = it }
                        }
                        PdfToolKind.ENCRYPT -> {
                            OutlinedTextField(pwd1, { pwd1 = it }, Modifier.fillMaxWidth(), label = { Text("设置密码") }, singleLine = true)
                            OutlinedTextField(pwd2, { pwd2 = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("确认密码") }, singleLine = true)
                        }
                        PdfToolKind.DECRYPT -> OutlinedTextField(pwd1, { pwd1 = it }, Modifier.fillMaxWidth(), label = { Text("当前打开密码") }, singleLine = true)
                        PdfToolKind.WATERMARK -> {
                            OutlinedTextField(wmText, { wmText = it }, Modifier.fillMaxWidth(), label = { Text("水印文字") }, singleLine = true)
                            OptLabel("透明度"); Slider(wmAlpha, { wmAlpha = it }, valueRange = 0.1f..0.8f)
                            OptLabel("字号"); Slider(wmSize, { wmSize = it }, valueRange = 0.5f..2f)
                            OptLabel("颜色"); ChipFlow(listOf("灰色", "红色", "蓝色"), wmColor) { wmColor = it }
                        }
                        PdfToolKind.PAGES -> PagesManager(input!!, password) { op, sel ->
                            task {
                                val out = Share.exportFile(baseName + "_" + op, "pdf")
                                when (op) {
                                    "提取" -> PdfTools.extract(input, sel.sorted(), out, password)
                                    "删除" -> PdfTools.deletePages(input, sel, out, password)
                                    else -> PdfTools.rotatePages(input, sel, 90, out, password)
                                }
                                results = listOf(out)
                            }
                        }
                        PdfToolKind.SIGN -> SignPdf(input!!, password) { index, stamp, pl ->
                            task {
                                val out = Share.exportFile(baseName + "_签名", "pdf")
                                PdfTools.stamp(input, index, stamp, pl.x, pl.y, pl.w, pl.h, out, password)
                                results = listOf(out)
                            }
                        }
                        else -> Unit
                    }
                    if (tool != PdfToolKind.PAGES && tool != PdfToolKind.SIGN && tool != PdfToolKind.VIEW) {
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("开始处理", Modifier.fillMaxWidth()) {
                            when (tool) {
                                PdfToolKind.FROM_IMAGES -> task {
                                    results = listOf(PdfTools.fromImages(images.map { File(it) }, Share.exportFile("图片转PDF", "pdf"), pageSize, quality) { n ->
                                        scope.launch(Dispatchers.Main) { busy = "生成中 $n/${images.size}" }
                                    })
                                }
                                PdfToolKind.TO_WORD -> task {
                                    val run = RecognizeRun()
                                    val texts = PdfTools.extractText(input!!, password)
                                    val items = ArrayList<DocItem>()
                                    PdfPages(plainInput()).use { pages ->
                                        for (i in 0 until pages.count) {
                                            withContext(Dispatchers.Main) { busy = "转换中 ${i + 1}/${pages.count}" }
                                            if (i > 0) items += DocItem.PageBreak
                                            val text = texts.getOrNull(i).orEmpty()
                                            if (text.length > 20) items += paragraphsFromText(text)
                                            else {
                                                val bmp = pages.render(i, 2000)
                                                items += run.wordItems(bmp)
                                                bmp.recycle()
                                            }
                                            if (withImage) {
                                                val bmp = pages.render(i, 1400)
                                                items += DocxWriter.imageItem(BitmapIO.saveJpeg(bmp, BitmapIO.newWorkFile(), 85))
                                                bmp.recycle()
                                            }
                                        }
                                    }
                                    results = listOf(DocxWriter.write(items, Share.exportFile(baseName, "docx")))
                                    run.notice?.let { withContext(Dispatchers.Main) { Share.toast(ctx, "AI 识别不可用（$it），扫描页已使用本地识别") } }
                                }
                                PdfToolKind.TO_EXCEL -> task {
                                    val run = RecognizeRun()
                                    val sheets = PdfPages(plainInput()).use { pages ->
                                        (0 until pages.count).map { i ->
                                            withContext(Dispatchers.Main) { busy = "AI 识别表格 ${i + 1}/${pages.count}" }
                                            val bmp = pages.render(i, 2400)
                                            val t = run.table(bmp)
                                            bmp.recycle()
                                            "第${i + 1}页" to t
                                        }
                                    }
                                    results = listOf(XlsxWriter.write(sheets, Share.exportFile(baseName, "xlsx")))
                                    run.notice?.let { withContext(Dispatchers.Main) { Share.toast(ctx, "AI 识别不可用（$it），已使用本地识别") } }
                                }
                                PdfToolKind.TO_TXT -> task {
                                    val run = RecognizeRun()
                                    val texts = PdfTools.extractText(input!!, password).toMutableList()
                                    PdfPages(plainInput()).use { pages ->
                                        for (i in 0 until pages.count) if (texts.getOrNull(i).orEmpty().length < 10) {
                                            withContext(Dispatchers.Main) { busy = "识别第 ${i + 1} 页" }
                                            val bmp = pages.render(i, 2000)
                                            val t = run.text(bmp)
                                            bmp.recycle()
                                            if (i < texts.size) texts[i] = t else texts += t
                                        }
                                    }
                                    val all = texts.joinToString("\n\n")
                                    run.notice?.let { withContext(Dispatchers.Main) { Share.toast(ctx, "AI 识别不可用（$it），扫描页已使用本地识别") } }
                                    resultText = all
                                    results = listOf(Share.exportFile(baseName, "txt").apply { writeText(all) })
                                }
                                PdfToolKind.TO_IMAGES -> task {
                                    val width = listOf(1240, 1800, 2480)[imgWidth]
                                    val files = PdfPages(plainInput()).use { pages ->
                                        (0 until pages.count).map { i ->
                                            withContext(Dispatchers.Main) { busy = "导出第 ${i + 1}/${pages.count} 页" }
                                            val bmp = pages.render(i, width)
                                            BitmapIO.saveJpeg(bmp, Share.exportFile("${baseName}_${i + 1}", "jpg"), 92).also { bmp.recycle() }
                                        }
                                    }
                                    files.forEach { Share.saveToDevice(ctx, it) }
                                    info = "已将 ${files.size} 张图片保存到相册"
                                    results = files
                                }
                                PdfToolKind.TO_LONG_IMAGE -> task {
                                    val width = listOf(1080, 1440, 1800)[imgWidth]
                                    val long = PdfPages(plainInput()).use { pages ->
                                        val bmps = (0 until pages.count).map { pages.render(it, width) }
                                        PageCompose.longImage(bmps, width).also { bmps.forEach { b -> b.recycle() } }
                                    }
                                    val f = BitmapIO.saveJpeg(long, Share.exportFile(baseName + "_长图", "jpg"), 90)
                                    long.recycle()
                                    Share.saveToDevice(ctx, f)
                                    info = "长图已保存到相册"
                                    results = listOf(f)
                                }
                                PdfToolKind.MERGE -> task { results = listOf(PdfTools.merge(inputs.toList(), Share.exportFile("合并文档", "pdf"))) }
                                PdfToolKind.SPLIT -> task {
                                    val count = PdfTools.pageCount(input!!, password)
                                    val r = if (splitEach) (0 until count).map { it..it } else parseRanges(ranges, count)
                                    if (r.isEmpty()) error("请输入有效的页码范围")
                                    results = PdfTools.split(input, r, ScanApp.instance.exportDir, baseName, password)
                                }
                                PdfToolKind.COMPRESS -> task {
                                    val (side, q) = listOf(2400 to 85, 1600 to 70, 1100 to 55)[level]
                                    val out = PdfTools.compress(input!!, side, q, Share.exportFile(baseName + "_压缩", "pdf"), password)
                                    val before = input.length(); val after = out.length()
                                    info = "压缩前 ${formatSize(before)} → 压缩后 ${formatSize(after)}（减少 ${((1 - after.toFloat() / before) * 100).toInt().coerceAtLeast(0)}%）"
                                    results = listOf(out)
                                }
                                PdfToolKind.ENCRYPT -> {
                                    if (pwd1.isEmpty() || pwd1 != pwd2) { Share.toast(ctx, "两次输入的密码不一致"); return@PrimaryButton }
                                    task { results = listOf(PdfTools.encrypt(input!!, pwd1, Share.exportFile(baseName + "_加密", "pdf"), password)) }
                                }
                                PdfToolKind.DECRYPT -> task { results = listOf(PdfTools.decrypt(input!!, pwd1, Share.exportFile(baseName + "_解密", "pdf"))) }
                                PdfToolKind.WATERMARK -> {
                                    if (wmText.isBlank()) { Share.toast(ctx, "请输入水印文字"); return@PrimaryButton }
                                    val color = listOf(AColor.rgb(110, 110, 110), AColor.rgb(220, 40, 40), AColor.rgb(40, 90, 220))[wmColor]
                                    task { results = listOf(PdfTools.watermark(input!!, wmText, (wmAlpha * 255).toInt(), color, wmSize, Share.exportFile(baseName + "_水印", "pdf"), password)) }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                info?.let { Text(it, color = Color(0xFF12B76A), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("处理完成", fontWeight = FontWeight.SemiBold, color = Ink)
                    ResultFiles(results.take(30))
                    if (results.size > 30) Text("…共 ${results.size} 个文件，可在「文件 › 导出文件」中查看", fontSize = 12.sp, color = Sub)
                }
                resultText?.let { t ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("识别结果", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { Share.copyText(ctx, t) }) { Icon(Icons.Outlined.ContentCopy, "复制", tint = Brand) }
                    }
                    SelectionContainer {
                        Text(t, fontSize = 14.sp, color = Ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp))
                    }
                }
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
        LoadingOverlay(busy)
    }
    if (askPassword) {
        TextInputDialog("该 PDF 已加密", placeholder = "请输入打开密码", password = true, onDismiss = { askPassword = false; inputs.clear() }) { p ->
            askPassword = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.pageCount(inputs.first(), p) }.isSuccess }
                if (ok) password = p else { Share.toast(ctx, "密码错误"); askPassword = true }
            }
        }
    }
}

@Composable
private fun OptLabel(t: String) = Text(t, fontSize = 13.sp, color = Sub, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

@Composable
private fun InputCard(f: File, password: String?, onRemove: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    val pages by produceState<Int?>(null, f, password) { value = withContext(Dispatchers.IO) { runCatching { PdfTools.pageCount(f, password) }.getOrNull() } }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(f, 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
            Text("${pages?.let { "$it 页 · " } ?: "已加密 · "}${formatSize(f.length())}", fontSize = 12.sp, color = Sub)
        }
        onUp?.let { IconButton(onClick = it, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ArrowUpward, "上移", Modifier.size(18.dp)) } }
        onDown?.let { IconButton(onClick = it, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ArrowDownward, "下移", Modifier.size(18.dp)) } }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "移除", Modifier.size(18.dp), tint = Sub) }
    }
}

/** Opens a renderer on an unencrypted copy of [file] for thumbnails/previews. */
@Composable
private fun rememberPdfPages(file: File, password: String?): PdfPages? {
    val pages by produceState<PdfPages?>(null, file, password) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfPages(PdfTools.plainCopy(file, password, File(ScanApp.instance.workDir, "plain_${System.nanoTime()}.pdf"))) }.getOrNull()
        }
    }
    // Capture the value: reading the delegated state inside onDispose would see the *new* renderer.
    val current = pages
    DisposableEffect(current) { onDispose { current?.close() } }
    return current
}

@Composable
private fun PageThumb(pages: PdfPages, i: Int, width: Int, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, pages, i) { value = withContext(Dispatchers.IO) { runCatching { pages.render(i, width) }.getOrNull() } }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun PagesManager(file: File, password: String?, onAction: (String, Set<Int>) -> Unit) {
    val pages = rememberPdfPages(file, password) ?: return Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("已选 ${selected.size}/${pages.count} 页", fontSize = 13.sp, color = Sub, modifier = Modifier.weight(1f))
        Text(if (selected.size == pages.count) "取消全选" else "全选", color = Brand, fontSize = 13.sp,
            modifier = Modifier.clickable { selected = if (selected.size == pages.count) emptySet() else (0 until pages.count).toSet() }.padding(6.dp))
    }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0 until pages.count).toList(), key = { it }) { i ->
            Box(
                Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)).border(2.dp, if (i in selected) Brand else Line, RoundedCornerShape(6.dp))
                    .clickable { selected = if (i in selected) selected - i else selected + i },
            ) {
                PageThumb(pages, i, 260, Modifier.fillMaxSize().padding(3.dp))
                Icon(if (i in selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (i in selected) Brand else Sub, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
                Text("${i + 1}", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("提取", "删除", "旋转90°").forEach { op ->
            PrimaryButton(op + "所选", Modifier.weight(1f), enabled = selected.isNotEmpty() && !(op == "删除" && selected.size == pages.count)) { onAction(op, selected) }
        }
    }
}

@Composable
private fun SignPdf(file: File, password: String?, onApply: (Int, Bitmap, Placement) -> Unit) {
    val pages = rememberPdfPages(file, password) ?: return Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    var index by remember { mutableIntStateOf(pages.count - 1) }
    var picking by remember { mutableStateOf(false) }
    var sigFile by remember { mutableStateOf<File?>(null) }
    val sig = rememberBitmap(sigFile?.absolutePath, 1200)
    val pageBmp by produceState<Bitmap?>(null, pages, index) { value = withContext(Dispatchers.IO) { runCatching { pages.render(index, 1400) }.getOrNull() } }
    var placement by remember { mutableStateOf<Placement?>(null) }
    LaunchedEffect(sig, pageBmp) { val s = sig; val p = pageBmp; if (s != null && p != null) placement = initialPlacement(p, s) }

    OptLabel("选择页面")
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0 until pages.count).toList()) { i ->
            Box(Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, if (i == index) Brand else Line, RoundedCornerShape(6.dp)).clickable { index = i }) {
                PageThumb(pages, i, 140, Modifier.fillMaxSize())
                Text("${i + 1}", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    val p = pageBmp
    Box(Modifier.fillMaxWidth().aspectRatio(p?.let { it.width.toFloat() / it.height } ?: 0.72f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE4E7EC))) {
        val s = sig; val pl = placement
        if (p != null && s != null && pl != null) StampPlacer(p, s, pl, { placement = it }, Modifier.fillMaxSize())
        else if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(if (sigFile == null) "选择签名" else "更换签名", Modifier.weight(1f)) { picking = true }
        PrimaryButton("签署并生成", Modifier.weight(1f), enabled = sig != null && placement != null) {
            onApply(index, sig!!, placement!!)
        }
    }
    if (picking) SignaturePicker(onDismiss = { picking = false }) { sigFile = it; picking = false }
}

@Composable
fun PdfViewScreen(path: String) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberSafeScope()
    val file = remember(path) { File(path) }
    var password by remember { mutableStateOf<String?>(null) }
    var needPassword by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        needPassword = withContext(Dispatchers.IO) { runCatching { PdfTools.isEncrypted(file) }.getOrDefault(false) }
        checked = true
    }
    val pages = if (checked && (!needPassword || password != null)) rememberPdfPages(file, password) else null
    val widthPx = (LocalConfiguration.current.screenWidthDp * 2.2f).toInt().coerceAtMost(1600)

    Column(Modifier.fillMaxSize().background(Color(0xFFE9EBEF))) {
        AppTopBar(file.name, actions = {
            IconButton(onClick = { Share.share(ctx, listOf(file)) }) { Icon(Icons.Outlined.Share, "分享") }
            IconButton(onClick = { Share.saveAll(ctx, listOf(file)) }) { Icon(Icons.Outlined.Download, "保存") }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (pages == null) {
                if (!needPassword || password != null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items((0 until pages.count).toList(), key = { it }) { i ->
                        val aspect by produceState(0.707f, pages, i) { value = withContext(Dispatchers.IO) { runCatching { pages.aspect(i) }.getOrDefault(0.707f) } }
                        Column {
                            PageThumb(pages, i, widthPx, Modifier.fillMaxWidth().aspectRatio(aspect))
                            Text("${i + 1} / ${pages.count}", fontSize = 11.sp, color = Sub, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        Box {
            BottomActions(
                listOf(
                    BarAction("转Word", Icons.Outlined.Description) { nav.push(Route.PdfTool(PdfToolKind.TO_WORD, path)) },
                    BarAction("转图片", Icons.Outlined.Image) { nav.push(Route.PdfTool(PdfToolKind.TO_IMAGES, path)) },
                    BarAction("签名", Icons.Outlined.Draw) { nav.push(Route.PdfTool(PdfToolKind.SIGN, path)) },
                    BarAction("压缩", Icons.Outlined.Compress) { nav.push(Route.PdfTool(PdfToolKind.COMPRESS, path)) },
                    BarAction("加密", Icons.Outlined.Lock) { nav.push(Route.PdfTool(PdfToolKind.ENCRYPT, path)) },
                    BarAction("更多", Icons.Outlined.MoreHoriz) { more = true },
                ),
            )
            DropdownMenu(more, { more = false }) {
                listOf(
                    PdfToolKind.TO_EXCEL, PdfToolKind.TO_TXT, PdfToolKind.TO_LONG_IMAGE, PdfToolKind.SPLIT, PdfToolKind.PAGES,
                    PdfToolKind.WATERMARK, PdfToolKind.DECRYPT, PdfToolKind.MERGE,
                ).forEach { k ->
                    DropdownMenuItem({ Text(k.title) }, { more = false; nav.push(Route.PdfTool(k, path)) })
                }
            }
        }
    }
    if (needPassword && password == null && checked) {
        TextInputDialog("该 PDF 已加密", placeholder = "请输入打开密码", password = true, onDismiss = { nav.pop() }) { p ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.pageCount(file, p) }.isSuccess }
                if (ok) password = p else Share.toast(ctx, "密码错误")
            }
        }
    }
}
