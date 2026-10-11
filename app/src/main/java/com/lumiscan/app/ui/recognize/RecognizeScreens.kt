package com.lumiscan.app.ui.recognize

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.remote.AiRequest
import com.lumiscan.app.data.remote.Api
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.Route
import com.lumiscan.app.export.DocExporter
import com.lumiscan.app.export.DocxWriter
import com.lumiscan.app.export.Share
import com.lumiscan.app.export.XlsxWriter
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.ml.Align
import com.lumiscan.app.ml.DocItem
import com.lumiscan.app.ml.OcrEngine
import com.lumiscan.app.ml.OcrResult
import com.lumiscan.app.ml.TableModel
import com.lumiscan.app.ml.TranslatorEngine
import com.lumiscan.app.ml.CloudVision
import com.lumiscan.app.ml.RecognizeRun
import com.lumiscan.app.ui.common.AiBadge
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.BarAction
import com.lumiscan.app.ui.common.BottomActions
import com.lumiscan.app.ui.common.HoldCompare
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.ResultFiles
import com.lumiscan.app.ui.common.SelectChip
import com.lumiscan.app.ui.common.TextInputDialog
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Stores recognised text on the document pages so the document becomes searchable. */
private fun cacheOcrText(docId: String, images: List<String>, texts: List<String>) {
    val repo = ScanApp.instance.repository
    val doc = repo.get(docId) ?: return
    images.forEachIndexed { i, p ->
        val pg = doc.pages.firstOrNull { repo.imageFile(doc, it).absolutePath == p } ?: return@forEachIndexed
        repo.setOcrText(docId, pg.id, texts.getOrNull(i).orEmpty())
    }
}

private fun nameStamp() = com.lumiscan.app.scan.ScanSession.timestamp().replace(":", "").replace(" ", "_")

/**
 * Translates OCR blocks in one AI call (blocks separated by blank lines). Null — after telling
 * the user why — when AI is unavailable or the reply doesn't line up, so the offline engine runs.
 */
private suspend fun aiTranslate(blocks: List<String>, targetLabel: String, notify: (String) -> Unit): List<String>? {
    val idx = blocks.indices.filter { blocks[it].isNotBlank() }
    if (idx.isEmpty()) return blocks
    return try {
        val r = Api.ai(AiRequest("translate", idx.joinToString("\n\n") { blocks[it].replace(Regex("\\s*\n\\s*"), " ") }, targetLang = targetLabel))
        ScanApp.instance.account.updateQuota(r.quota)
        val parts = r.content.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != idx.size) {
            notify("AI 译文段落未能对齐，已改用离线翻译")
            null
        } else blocks.toMutableList().also { out -> idx.forEachIndexed { k, i -> out[i] = parts[k] } }
    } catch (e: ApiException) {
        notify("${e.message}，已改用离线翻译")
        null
    }
}

/** 提取文字: per-page editable text with copy / translate / export. */
@Composable
fun OcrScreen(images: List<String>, docId: String?) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val results = remember { mutableStateListOf<OcrResult?>() }
    val texts = remember { mutableStateListOf<String>() }
    var page by remember { mutableIntStateOf(0) }
    var paragraphMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>("正在识别文字") }
    val scope = rememberSafeScope { busy = null }
    var showImage by remember { mutableStateOf(true) }
    var translateMenu by remember { mutableStateOf(false) }
    var translation by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<List<File>>(emptyList()) }
    val textScroll = rememberScrollState()
    var usedAi by remember { mutableStateOf(false) }

    LaunchedEffect(images) {
        if (results.isNotEmpty()) return@LaunchedEffect
        // AI (cloud) OCR first; after the first failure the remaining pages are read on-device.
        var notice: String? = null
        images.forEachIndexed { i, p ->
            busy = if (notice == null) "AI 正在识别 ${i + 1}/${images.size}" else "正在识别 ${i + 1}/${images.size}"
            val cloud = if (notice == null) {
                try { CloudVision.ocrTextFile(p) } catch (e: ApiException) { notice = e.message; null }
            } else null
            if (cloud != null) {
                usedAi = true
                results += null
                texts += cloud
            } else {
                val r = withContext(Dispatchers.Default) { runCatching { OcrEngine.recognizeFile(p) }.getOrNull() }
                results += r
                texts += r?.text.orEmpty()
            }
        }
        busy = null
        notice?.let { Share.toast(ctx, "AI 识别不可用（$it），已使用本地识别") }
        // Cache text on the stored pages so the document becomes searchable.
        if (docId != null) withContext(Dispatchers.IO) {
            runCatching { cacheOcrText(docId, images, texts.toList()) }
        }
    }
    val allText = texts.joinToString("\n\n")

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppTopBar("识别结果", actions = {
                Text(if (showImage) "隐藏原图" else "对照原图", color = Brand, fontSize = 14.sp, modifier = Modifier.clickable { showImage = !showImage }.padding(12.dp))
            })
            if (images.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.indices.forEach { i -> SelectChip("第${i + 1}页", i == page) { page = i } }
                }
            }
            if (showImage) {
                val bmp = rememberBitmap(images.getOrNull(page), 1400)
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF1F2024))) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (usedAi) AiBadge(Modifier.padding(end = 6.dp), label = "AI 识别")
                Text("共 ${allText.count { !it.isWhitespace() }} 字 · 可直接编辑", fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
                if (results.any { it != null }) Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    paragraphMode = !paragraphMode
                    results.forEachIndexed { i, r -> if (r != null) texts[i] = if (paragraphMode) r.paragraphs else r.text }
                }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WrapText, null, tint = Brand, modifier = Modifier.size(16.dp))
                    Text(if (paragraphMode) "保留换行" else "智能分段", color = Brand, fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp).verticalScroll(textScroll)) {
                if (texts.size > page) {
                    TextField(
                        value = texts[page], onValueChange = { texts[page] = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Ink),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { if (busy == null) Text("未识别到文字", color = Sub) },
                    )
                }
                translation?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("译文", fontWeight = FontWeight.SemiBold, color = Ink)
                    SelectionContainer {
                        Text(it, fontSize = 15.sp, lineHeight = 24.sp, color = Ink, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEFF4FF)).padding(12.dp))
                    }
                    Text("复制译文", color = Brand, fontSize = 13.sp, modifier = Modifier.clickable { Share.copyText(ctx, it) }.padding(vertical = 8.dp))
                }
                if (exported.isNotEmpty()) ResultFiles(exported)
                Spacer(Modifier.height(12.dp))
            }
            Box {
                BottomActions(
                    listOf(
                        BarAction("AI 助手", Icons.Outlined.AutoAwesome, enabled = allText.isNotBlank()) { nav.push(Route.AiAssist(allText)) },
                        BarAction("复制全部", Icons.Outlined.ContentCopy) { Share.copyText(ctx, allText) },
                        BarAction("翻译", Icons.Outlined.Translate) { translateMenu = true },
                        BarAction("导出TXT", Icons.Outlined.Download) {
                            exported = listOf(Share.exportFile("文字识别_${nameStamp()}", "txt").apply { writeText(allText) })
                        },
                        BarAction("导出Word", Icons.Outlined.Description) {
                            scope.launch {
                                val items = texts.flatMapIndexed { i, t ->
                                    (if (i > 0) listOf<DocItem>(DocItem.PageBreak) else emptyList()) + t.split("\n").filter { it.isNotBlank() }.map { DocItem.Para(it) }
                                }
                                exported = listOf(withContext(Dispatchers.IO) { DocxWriter.write(items, Share.exportFile("文字识别_${nameStamp()}", "docx")) })
                            }
                        },
                        BarAction("分享", Icons.Outlined.Share) { Share.shareText(ctx, allText) },
                    ),
                )
                DropdownMenu(translateMenu, { translateMenu = false }) {
                    TranslatorEngine.languages.forEach { l ->
                        DropdownMenuItem({ Text("翻译为${l.label}") }, {
                            translateMenu = false
                            scope.launch {
                                busy = "AI 正在翻译"
                                // AI translation first (better quality, no model download); the on-device engine is the fallback.
                                translation = try {
                                    val r = Api.ai(AiRequest("translate", allText, targetLang = l.label))
                                    ScanApp.instance.account.updateQuota(r.quota)
                                    r.content
                                } catch (e: ApiException) {
                                    Share.toast(ctx, "${e.message}，改用离线翻译")
                                    busy = "正在翻译"
                                    val src = TranslatorEngine.detect(allText)
                                    runCatching {
                                        // Line by line keeps the page structure (the engine merges newlines otherwise).
                                        texts.toList().map { page ->
                                            val lines = page.split("\n")
                                            TranslatorEngine.translate(lines, src, l.code) { busy = "正在准备离线翻译模型（首次需联网下载）" }.joinToString("\n")
                                        }.joinToString("\n\n")
                                    }.getOrElse { Share.toast(ctx, "翻译失败：${it.message}"); null }
                                }
                                busy = null
                                if (translation != null) textScroll.animateScrollTo(textScroll.maxValue)
                            }
                        })
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
}

/** 转Word: layout reconstruction preview + .docx export. */
@Composable
fun WordScreen(images: List<String>) {
    var items by remember { mutableStateOf<List<DocItem>?>(null) }
    var busy by remember { mutableStateOf<String?>("正在识别版面") }
    val scope = rememberSafeScope { busy = null }
    var withImage by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<List<File>>(emptyList()) }
    val ctx = LocalContext.current
    val run = remember { RecognizeRun() }

    LaunchedEffect(images) {
        if (items != null) return@LaunchedEffect
        items = withContext(Dispatchers.Default) {
            runCatching { DocExporter.wordItems(images.map { File(it) }, false, run) { m -> scope.launch(Dispatchers.Main) { busy = m } } }.getOrDefault(emptyList())
        }
        busy = null
        run.notice?.let { Share.toast(ctx, "AI 识别不可用（$it），已使用本地识别") }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("转Word")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (run.usedCloud) AiBadge(Modifier.padding(end = 6.dp), label = "AI 识别")
                    Text("预览（还原标题、段落、对齐与表格）", fontSize = 12.sp, color = Sub)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color.White).border(1.dp, Line).padding(horizontal = 22.dp, vertical = 26.dp)) {
                    val list = items.orEmpty()
                    if (list.isEmpty() && busy == null) Text("未识别到内容", color = Sub)
                    list.forEach { item ->
                        when (item) {
                            is DocItem.Para -> Text(
                                item.text, fontSize = (item.sizePt * 1.05f).coerceIn(10f, 26f).sp,
                                fontWeight = if (item.bold) FontWeight.Bold else FontWeight.Normal, color = Ink,
                                textAlign = when (item.align) { Align.CENTER -> TextAlign.Center; Align.RIGHT -> TextAlign.End; Align.LEFT -> TextAlign.Justify },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            )
                            is DocItem.Table -> TablePreview(item.table, editable = false) { _, _, _ -> }
                            is DocItem.Image -> Unit
                            DocItem.PageBreak -> Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(Line))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("每页附带原图（图文对照）", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    androidx.compose.material3.Switch(withImage, { withImage = it })
                }
                if (exported.isNotEmpty()) ResultFiles(exported)
            }
            BottomActions(
                listOf(
                    BarAction("生成Word文档", Icons.Outlined.Description, enabled = items != null) {
                        scope.launch {
                            busy = "正在生成 Word"
                            exported = listOf(withContext(Dispatchers.IO) {
                                val base = items.orEmpty()
                                val all = if (!withImage) base else {
                                    // Re-insert page images after each page's content.
                                    val out = ArrayList<DocItem>()
                                    var page = 0
                                    base.forEach { if (it == DocItem.PageBreak) { out += DocxWriter.imageItem(File(images[page++])) }; out += it }
                                    out += DocxWriter.imageItem(File(images[page.coerceAtMost(images.lastIndex)]))
                                    out
                                }
                                DocxWriter.write(all, Share.exportFile("转Word_${nameStamp()}", "docx"))
                            })
                            busy = null
                        }
                    },
                ),
            )
        }
        LoadingOverlay(busy)
    }
}

@Composable
fun TablePreview(t: TableModel, editable: Boolean, onEdit: (Int, Int, String) -> Unit) {
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Fit the columns to the screen when possible; scroll sideways only below 64dp per column.
        val cols = t.cols.coerceAtLeast(1)
        val colW = (maxWidth / cols).coerceIn(64.dp, 160.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp).border(1.dp, Ink.copy(alpha = 0.6f))) {
            for (r in 0 until t.rows) {
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    for (c in 0 until t.cols) {
                        val covered = t.isCovered(r, c)
                        Box(
                            Modifier
                                .width(colW)
                                .fillMaxHeight()
                                .border(0.5.dp, Ink.copy(alpha = 0.35f))
                                .background(if (covered) Color(0xFFF7F8FA) else Color.White)
                                .clickable(enabled = editable && !covered) { editing = r to c }
                                .padding(6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(if (covered) "" else t.cell(r, c), fontSize = 12.sp, color = Ink, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
    editing?.let { (r, c) ->
        TextInputDialog("编辑单元格 (${r + 1},${c + 1})", t.cell(r, c), onDismiss = { editing = null }) { onEdit(r, c, it); editing = null }
    }
}

/** 转Excel: editable table preview per page + .xlsx export. */
@Composable
fun ExcelScreen(images: List<String>) {
    val ctx = LocalContext.current
    val tables = remember { mutableStateListOf<TableModel>() }
    var page by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>("正在识别表格") }
    val scope = rememberSafeScope { busy = null }
    var exported by remember { mutableStateOf<List<File>>(emptyList()) }
    var showImage by remember { mutableStateOf(false) }
    val run = remember { RecognizeRun() }

    LaunchedEffect(images) {
        if (tables.isNotEmpty()) return@LaunchedEffect
        val r = withContext(Dispatchers.Default) {
            runCatching { DocExporter.tables(images.map { File(it) }, run) { m -> scope.launch(Dispatchers.Main) { busy = m } } }.getOrDefault(emptyList())
        }
        tables.addAll(r)
        busy = null
        run.notice?.let { Share.toast(ctx, "AI 识别不可用（$it），已使用本地识别") }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("转Excel", actions = {
                Text(if (showImage) "隐藏原图" else "对照原图", color = Brand, fontSize = 14.sp, modifier = Modifier.clickable { showImage = !showImage }.padding(12.dp))
            })
            if (images.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.indices.forEach { i -> SelectChip("表格${i + 1}", i == page) { page = i } }
                }
            }
            if (showImage) {
                val bmp = rememberBitmap(images.getOrNull(page), 1400)
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF1F2024))) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                val t = tables.getOrNull(page)
                if (t == null || t.isEmpty) {
                    if (busy == null) Text("未识别到表格内容", color = Sub)
                } else {
                    Text("${t.rows} 行 × ${t.cols} 列${if (t.merges.isNotEmpty()) " · ${t.merges.size} 处合并单元格" else ""} · 点击单元格可修改", fontSize = 12.sp, color = Sub)
                    TablePreview(t, editable = true) { r, c, v -> tables[page] = t.withCell(r, c, v) }
                }
                if (exported.isNotEmpty()) ResultFiles(exported)
            }
            BottomActions(
                listOf(
                    BarAction("复制表格", Icons.Outlined.ContentCopy) { tables.getOrNull(page)?.let { Share.copyText(ctx, it.toTsv()) } },
                    BarAction("生成Excel", Icons.Outlined.TableChart, enabled = tables.isNotEmpty()) {
                        scope.launch {
                            exported = listOf(withContext(Dispatchers.IO) {
                                XlsxWriter.write(tables.mapIndexed { i, t -> "表格${i + 1}" to t }, Share.exportFile("转Excel_${nameStamp()}", "xlsx"))
                            })
                        }
                    },
                    BarAction("生成Word", Icons.Outlined.Description, enabled = tables.isNotEmpty()) {
                        scope.launch {
                            exported = listOf(withContext(Dispatchers.IO) {
                                DocxWriter.write(tables.flatMapIndexed { i, t -> (if (i > 0) listOf(DocItem.PageBreak) else emptyList()) + DocItem.Table(t) }, Share.exportFile("表格_${nameStamp()}", "docx"))
                            })
                        }
                    },
                ),
            )
        }
        LoadingOverlay(busy)
    }
}

/** 拍照翻译: OCR blocks translated and painted back over the photo. */
@Composable
fun TranslateScreen(image: String) {
    val ctx = LocalContext.current
    var ocr by remember { mutableStateOf<OcrResult?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var translated by remember { mutableStateOf<List<String>>(emptyList()) }
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf<String?>("正在识别文字") }
    val scope = rememberSafeScope { busy = null }
    var srcMenu by remember { mutableStateOf(false) }
    var tgtMenu by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    val original = rememberBitmap(image, 2000)

    suspend fun translateNow() {
        val r = ocr ?: return
        val s = source ?: return
        val t = target ?: return
        busy = "AI 正在翻译"
        val blocks = r.blocks.map { com.lumiscan.app.ml.TextJoin.tidy(com.lumiscan.app.ml.TextJoin.joinLines(it.lines.map { l -> l.text })) }
        runCatching {
            translated = aiTranslate(blocks, TranslatorEngine.label(t)) { msg -> Share.toast(ctx, msg) }
                ?: TranslatorEngine.translate(blocks, s, t) { busy = "正在准备离线翻译模型（首次需联网下载约30MB）" }
            val base = original ?: BitmapIO.decode(image, 2000)
            if (base != null) overlay = withContext(Dispatchers.Default) { paintTranslation(base, r, translated) }
        }.onFailure { Share.toast(ctx, "翻译失败：${it.message ?: "请检查网络后重试"}") }
        busy = null
    }

    LaunchedEffect(image, original) {
        if (ocr != null || original == null) return@LaunchedEffect
        val r = withContext(Dispatchers.Default) { runCatching { OcrEngine.recognize(original) }.getOrNull() }
        if (r == null) { busy = null; Share.toast(ctx, "文字识别失败"); return@LaunchedEffect }
        ocr = r
        val s = TranslatorEngine.detect(r.text)
        source = s
        target = if (s == "zh") "en" else "zh"
        busy = null
        if (r.blocks.isEmpty()) Share.toast(ctx, "未识别到文字") else translateNow()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("拍照翻译", dark = true, actions = {
                Text(if (showText) "看图片" else "看文本", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable { showText = !showText }.padding(12.dp))
            })
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    LangButton(source?.let { TranslatorEngine.label(it) } ?: "自动") { srcMenu = true }
                    DropdownMenu(srcMenu, { srcMenu = false }) {
                        TranslatorEngine.sourceLanguages.forEach { l -> DropdownMenuItem({ Text(l.label) }, { srcMenu = false; source = l.code; scope.launch { translateNow() } }) }
                    }
                }
                Icon(Icons.Outlined.SwapHoriz, "交换", tint = Color.White, modifier = Modifier.padding(horizontal = 14.dp).clickable {
                    val s = source; source = target; target = s; scope.launch { translateNow() }
                })
                Box {
                    LangButton(target?.let { TranslatorEngine.label(it) } ?: "中文") { tgtMenu = true }
                    DropdownMenu(tgtMenu, { tgtMenu = false }) {
                        TranslatorEngine.languages.forEach { l -> DropdownMenuItem({ Text(l.label) }, { tgtMenu = false; target = l.code; scope.launch { translateNow() } }) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showText) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        ocr?.blocks?.forEachIndexed { i, b ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(12.dp)) {
                                SelectionContainer { Text(b.text, fontSize = 13.sp, color = Sub) }
                                Spacer(Modifier.height(6.dp))
                                SelectionContainer { Text(translated.getOrNull(i).orEmpty(), fontSize = 15.sp, color = Ink) }
                            }
                        }
                    }
                } else {
                    HoldCompare(original, overlay ?: original, Modifier.fillMaxSize())
                }
            }
            BottomActions(
                listOf(
                    BarAction("复制译文", Icons.Outlined.ContentCopy) { Share.copyText(ctx, translated.joinToString("\n")) },
                    BarAction("复制原文", Icons.Outlined.ContentCopy) { Share.copyText(ctx, ocr?.text.orEmpty()) },
                    BarAction("保存图片", Icons.Outlined.Image, enabled = overlay != null) {
                        overlay?.let { Share.toast(ctx, if (Share.saveBitmap(ctx, it, "翻译")) "已保存到相册" else "保存失败") }
                    },
                ),
                dark = true,
            )
        }
        LoadingOverlay(busy)
    }
}

@Composable
private fun LangButton(label: String, onClick: () -> Unit) {
    com.lumiscan.app.ui.common.FitText(
        label, 15f, Modifier.width(110.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2C2C2E)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        color = Color.White, textAlign = TextAlign.Center,
    )
}

/** Covers every text block with a soft background and draws the translation fitted inside. */
private fun paintTranslation(src: Bitmap, ocr: OcrResult, translated: List<String>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val c = ACanvas(out)
    val sx = out.width.toFloat() / ocr.width
    val sy = out.height.toFloat() / ocr.height
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    ocr.blocks.forEachIndexed { i, b ->
        val t = translated.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val r = RectF(b.box.left * sx, b.box.top * sy, b.box.right * sx, b.box.bottom * sy)
        r.inset(-4f, -3f)
        // Paper colour = median of samples just outside the block, so the patch blends in.
        val samples = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom, r.centerX() to r.top, r.centerX() to r.bottom)
            .map { (x, y) -> src.getPixel(x.toInt().coerceIn(0, src.width - 1), y.toInt().coerceIn(0, src.height - 1)) }
        fun med(f: (Int) -> Int) = samples.map(f).sorted()[samples.size / 2]
        val px = AColor.rgb(med(AColor::red), med(AColor::green), med(AColor::blue))
        bgPaint.color = px
        c.drawRoundRect(r, 6f, 6f, bgPaint)
        val lum = (AColor.red(px) * 0.3 + AColor.green(px) * 0.59 + AColor.blue(px) * 0.11)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (lum > 128) AColor.rgb(25, 25, 30) else AColor.WHITE }
        val w = r.width().toInt().coerceAtLeast(20)
        var size = (b.lines.map { it.box.height() }.average().toFloat() * sy * 0.8f).coerceIn(10f, 120f)
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(t, 0, t.length, tp, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).build()
            if (layout.height <= r.height() || size <= 9f) break
            size *= 0.9f
        }
        c.save()
        c.translate(r.left, r.top + ((r.height() - layout.height) / 2).coerceAtLeast(0f))
        layout.draw(c)
        c.restore()
    }
    return out
}
