package com.lumiscan.app.ui.doc

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.ExportQuality
import com.lumiscan.app.export.DocExporter
import com.lumiscan.app.export.ExportFormat
import com.lumiscan.app.export.ExportOptions
import com.lumiscan.app.export.PdfPageSize
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.nav.LeaveScreen
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.Route
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.BarAction
import com.lumiscan.app.ui.common.BottomActions
import com.lumiscan.app.ui.common.ConfirmDialog
import com.lumiscan.app.ui.common.ImageCache
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.PrimaryButton
import com.lumiscan.app.ui.common.SecondaryButton
import com.lumiscan.app.ui.common.SelectChip
import com.lumiscan.app.ui.common.TextInputDialog
import com.lumiscan.app.ui.common.ZoomableImage
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.home.Tools
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DocScreen(id: String) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val repo = ScanApp.instance.repository
    val docs by repo.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == id }
    if (doc == null) {
        LeaveScreen()
        return
    }
    var rename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<ExportFormat?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    val files = repo.imageFiles(doc)

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(doc.name, actions = {
                IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "重命名") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(if (sorting) "完成排序" else "调整页面顺序") }, { menu = false; sorting = !sorting }, leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) })
                        DropdownMenuItem({ Text("导出为长图") }, { menu = false; export = ExportFormat.LONG_IMAGE })
                        DropdownMenuItem({ Text("导出 TXT") }, { menu = false; export = ExportFormat.TXT })
                        DropdownMenuItem({ Text("删除文档") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                    }
                }
            })
            Text(
                "共 ${doc.pages.size} 页" + if (sorting) " · 点击箭头调整顺序" else " · 点击页面查看与编辑",
                fontSize = 12.sp, color = Sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(doc.pages, key = { _, p -> p.id + p.version }) { i, p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val thumb = rememberBitmap(repo.imageFile(doc, p).absolutePath, 360)
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).background(Color.White)
                                .clickable(enabled = !sorting) { nav.push(Route.DocPages(id, i)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                            else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        if (sorting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { scope.launch(Dispatchers.IO) { repo.movePage(id, i, i - 1) } }, enabled = i > 0, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "前移", Modifier.size(18.dp))
                                }
                                Text("${i + 1}", fontSize = 12.sp, color = Ink)
                                IconButton(onClick = { scope.launch(Dispatchers.IO) { repo.movePage(id, i, i + 1) } }, enabled = i < doc.pages.lastIndex, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "后移", Modifier.size(18.dp))
                                }
                            }
                        } else {
                            Text("${i + 1}", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                item {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).border(1.dp, Brand.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { Tools.openCamera(nav, ScanMode.DOC, appendTo = id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Add, null, tint = Brand)
                            Text("添加页面", fontSize = 12.sp, color = Brand)
                        }
                    }
                }
            }
            BottomActions(
                listOf(
                    BarAction("分享PDF", Icons.Outlined.Share) {
                        scope.launch {
                            busy = "正在生成 PDF"
                            val out = withContext(Dispatchers.IO) {
                                DocExporter.export(doc.name, files, ExportOptions(quality = ScanApp.instance.settings.exportQuality))
                            }
                            busy = null
                            Share.share(ctx, out)
                        }
                    },
                    BarAction("导出", Icons.Outlined.IosShare) { export = ExportFormat.PDF },
                    BarAction("提取文字", Icons.Outlined.TextFields) { nav.push(Route.Ocr(files.map { it.absolutePath }, id)) },
                    BarAction("AI 助手", Icons.Outlined.AutoAwesome) {
                        val cached = doc.pages.map { it.ocrText }
                        val text = if (cached.all { it != null }) cached.joinToString("\n\n").trim().ifEmpty { null } else null
                        nav.push(Route.AiAssist(text, files.map { it.absolutePath }))
                    },
                    BarAction("转Word", Icons.Outlined.Description) { nav.push(Route.Word(files.map { it.absolutePath })) },
                    BarAction("转Excel", Icons.Outlined.TableChart) { nav.push(Route.Excel(files.map { it.absolutePath })) },
                ),
            )
        }
        LoadingOverlay(busy)
    }

    if (rename) {
        TextInputDialog("重命名", doc.name, onDismiss = { rename = false }) {
            rename = false
            scope.launch(Dispatchers.IO) { repo.rename(id, it.trim()) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("删除文档", "确定删除「${doc.name}」？", "删除", onDismiss = { confirmDelete = false }) {
            scope.launch {
                withContext(Dispatchers.IO) { repo.delete(listOf(id)) }
                nav.pop()
            }
        }
    }
    export?.let { fmt ->
        ExportSheet(fmt, onDismiss = { export = null }) { options, save ->
            // Runs in the screen's scope: the sheet's own scope dies when it is dismissed.
            scope.launch {
                busy = "正在导出"
                val run = com.lumiscan.app.ml.RecognizeRun()
                val out = runCatching {
                    withContext(Dispatchers.IO) {
                        DocExporter.export(doc.name, files, options, run) { msg -> scope.launch(Dispatchers.Main) { busy = msg } }
                    }
                }
                busy = null
                run.notice?.let { Share.toast(ctx, "AI 识别不可用（$it），已使用本地识别") }
                out.onSuccess { if (save) Share.saveAll(ctx, it) else Share.share(ctx, it) }
                    .onFailure { Share.toast(ctx, "导出失败：${it.message}") }
            }
        }
    }
}

/** Export options: format, paper, quality, password, watermark — then share or save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(initial: ExportFormat, onDismiss: () -> Unit, onRun: (ExportOptions, save: Boolean) -> Unit) {
    var format by remember { mutableStateOf(initial) }
    var pageSize by remember { mutableStateOf(PdfPageSize.A4) }
    var quality by remember { mutableStateOf(ScanApp.instance.settings.exportQuality) }
    var password by remember { mutableStateOf("") }
    var watermark by remember { mutableStateOf("") }
    var withImage by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }

    fun run(save: Boolean) {
        onDismiss()
        onRun(ExportOptions(format, pageSize, quality, password, watermark, withImage), save)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("导出", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Label("格式")
            ChipFlow(ExportFormat.entries.map { it.label }, format.ordinal) { format = ExportFormat.entries[it] }
            if (format == ExportFormat.PDF) {
                Label("纸张")
                ChipFlow(PdfPageSize.entries.map { it.label }, pageSize.ordinal) { pageSize = PdfPageSize.entries[it] }
            }
            if (format in setOf(ExportFormat.PDF, ExportFormat.IMAGES, ExportFormat.LONG_IMAGE)) {
                Label("画质")
                ChipFlow(ExportQuality.entries.map { it.label }, quality.ordinal) { quality = ExportQuality.entries[it] }
                EditRow("水印", watermark.ifEmpty { "未设置" }) { editing = "watermark" }
            }
            if (format == ExportFormat.PDF) {
                EditRow("打开密码", if (password.isEmpty()) "未设置" else "•".repeat(password.length)) { editing = "password" }
            }
            if (format == ExportFormat.WORD) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("每页附带原图", modifier = Modifier.weight(1f))
                    Switch(withImage, { withImage = it })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("保存到手机", Modifier.weight(1f)) { run(save = true) }
                PrimaryButton("分享", Modifier.weight(1f)) { run(save = false) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    when (editing) {
        "watermark" -> TextInputDialog("水印文字", watermark, placeholder = "如：仅供办理XX业务使用", onDismiss = { editing = null }) { watermark = it.trim(); editing = null }
        "password" -> TextInputDialog("设置打开密码", password, placeholder = "留空则不加密", password = true, onDismiss = { editing = null }) { password = it; editing = null }
    }
}

@Composable
private fun EditRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Bg).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Sub, maxLines = 1)
        Icon(Icons.Outlined.Edit, null, tint = Sub, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 13.sp, color = Sub, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

@Composable
fun ChipFlow(options: List<String>, selected: Int, dark: Boolean = false, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> SelectChip(o, i == selected, dark) { onSelect(i) } }
    }
}

@Composable
fun DocPagesScreen(id: String, start: Int) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberSafeScope()
    val repo = ScanApp.instance.repository
    val docs by repo.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == id }
    if (doc == null || doc.pages.isEmpty()) {
        LeaveScreen()
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, doc.pages.lastIndex)) { doc.pages.size }
    var confirmDelete by remember { mutableStateOf(false) }
    val index = pager.currentPage.coerceIn(0, doc.pages.lastIndex)
    val page = doc.pages[index]
    val file = repo.imageFile(doc, page)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AppTopBar("${index + 1} / ${doc.pages.size}", dark = true, actions = {
            IconButton(onClick = { Share.share(ctx, listOf(file)) }) { Icon(Icons.Outlined.Share, "分享", tint = Color.White) }
            IconButton(onClick = { Share.saveAll(ctx, listOf(file)) }) { Icon(Icons.Outlined.Download, "保存", tint = Color.White) }
        })
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { doc.pages[it].id + doc.pages[it].version }) { i ->
            val p = doc.pages[i]
            val bmp = rememberBitmap(repo.imageFile(doc, p).absolutePath, 2200)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (bmp != null) ZoomableImage(bmp, Modifier.fillMaxSize()) else CircularProgressIndicator(color = Color.White)
            }
        }
        BottomActions(
            listOf(
                BarAction("编辑", Icons.Outlined.Tune) { nav.push(Route.PageEdit(id, index)) },
                BarAction("签名", Icons.Outlined.Draw) { nav.push(Route.Sign(id, index)) },
                BarAction("消除", Icons.Outlined.AutoFixHigh) { nav.push(Route.Erase(file.absolutePath, false, id, index)) },
                BarAction("提取文字", Icons.Outlined.TextFields) { nav.push(Route.Ocr(listOf(file.absolutePath), id)) },
                BarAction("删除", Icons.Outlined.Delete) { confirmDelete = true },
            ),
            dark = true,
        )
    }
    if (confirmDelete) {
        ConfirmDialog("删除页面", "确定删除第 ${index + 1} 页？", "删除", onDismiss = { confirmDelete = false }) {
            scope.launch(Dispatchers.IO) { repo.deletePages(id, setOf(index)) }
        }
    }
}

/** Places a signature onto a stored page and bakes it into the image. */
@Composable
fun SignPageScreen(docId: String, index: Int) {
    val nav = LocalNav.current
    val repo = ScanApp.instance.repository
    val doc = remember { repo.get(docId) }
    val page = doc?.pages?.getOrNull(index)
    if (doc == null || page == null) {
        LeaveScreen()
        return
    }
    val path = repo.imageFile(doc, page).absolutePath
    val pageBmp = rememberBitmap(path, 2400)
    var sigFile by remember { mutableStateOf<File?>(null) }
    var picking by remember { mutableStateOf(true) }
    val sig by produceState<Bitmap?>(null, sigFile) { value = sigFile?.let { withContext(Dispatchers.IO) { ImageCache.get(it.absolutePath, 1200) } } }
    var placement by remember { mutableStateOf<Placement?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }

    LaunchedEffect(sig, pageBmp) {
        val s = sig; val p = pageBmp
        if (s != null && p != null) placement = initialPlacement(p, s)
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF222222))) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("添加签名", dark = true)
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                val p = pageBmp; val s = sig; val pl = placement
                if (p != null && s != null && pl != null) StampPlacer(p, s, pl, { placement = it }, Modifier.fillMaxSize())
                else if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Text("拖动签名调整位置，拖动右下角圆点调整大小", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("更换签名", Modifier.weight(1f)) { picking = true }
                PrimaryButton("完成", Modifier.weight(1f), enabled = placement != null) {
                    val p = pageBmp ?: return@PrimaryButton
                    val s = sig ?: return@PrimaryButton
                    val pl = placement ?: return@PrimaryButton
                    scope.launch {
                        busy = "正在保存"
                        withContext(Dispatchers.Default) {
                            val full = BitmapIO.decode(path) ?: p
                            val out = BitmapIO.drawOverlay(full, s, pl.x, pl.y, pl.w, pl.h)
                            val f = BitmapIO.saveJpeg(out, BitmapIO.newWorkFile(), 93)
                            repo.updatePage(docId, index, f)
                        }
                        busy = null
                        nav.pop()
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
    if (picking) {
        SignaturePicker(onDismiss = { picking = false; if (sigFile == null) nav.pop() }) { f -> sigFile = f; picking = false }
    }
}
