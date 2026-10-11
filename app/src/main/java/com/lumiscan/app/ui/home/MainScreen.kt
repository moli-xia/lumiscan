package com.lumiscan.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.DocKind
import com.lumiscan.app.data.DocumentData
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.Route
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.scan.ScanSession
import com.lumiscan.app.ui.account.Avatar
import com.lumiscan.app.ui.account.expiryText
import com.lumiscan.app.ui.common.AiBadge
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.ConfirmDialog
import com.lumiscan.app.ui.common.FitText
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.TextInputDialog
import com.lumiscan.app.ui.common.formatSize
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.pdf.FileIcon
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.BrandDark
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var focusSearch by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf(Triple("首页", Icons.Outlined.Home, 0), Triple("文件", Icons.Outlined.Folder, 1), Triple("我的", Icons.Outlined.Person, 2))
                    .forEach { (label, icon, i) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i; focusSearch = false },
                            icon = { Icon(icon, label) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Brand, selectedTextColor = Brand, indicatorColor = Color.Transparent,
                                unselectedIconColor = Sub, unselectedTextColor = Sub,
                            ),
                        )
                    }
            }
        },
    ) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                0 -> HomeTab(onSearch = { focusSearch = true; tab = 1 }, onAllDocs = { tab = 1 })
                1 -> FilesTab(focusSearch)
                else -> MeTab()
            }
        }
    }
}

@Composable
private fun HomeTab(onSearch: () -> Unit, onAllDocs: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val docs by ScanApp.instance.repository.docs.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在导入 ${uris.size} 张图片"
            ScanSession.start(ScanMode.DOC, null)
            uris.forEach { u ->
                withContext(Dispatchers.IO) { BitmapIO.importUri(ctx, u) }?.let { ScanSession.add(it, ScanApp.instance.settings.autoCrop) }
            }
            busy = null
            ScanSession.proceed(nav)
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    File(ScanApp.instance.workDir, "import_${System.currentTimeMillis()}.pdf").also { out ->
                        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    }
                }.getOrNull()
            } ?: return@launch Share.toast(ctx, "无法读取该文件")
            nav.push(Route.PdfView(f.absolutePath))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("光点扫描", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("扫描识别 · AI 文档助手 · PDF 工具", fontSize = 12.sp, color = Sub)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color.White)
                .clickable(onClick = onSearch)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = Sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("搜索文档名称或文字内容", color = Sub, fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))

        // Main scan card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF4C8DFF), BrandDark)))
                .clickable { Tools.openCamera(nav, ScanMode.DOC) }
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.CameraAlt, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        FitText("拍照扫描", 22f, color = Color.White, fontWeight = FontWeight.Bold)
                        FitText("自动切边 · 智能增强 · 一键生成PDF", 13f, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardChip("导入图片", Icons.Outlined.AddPhotoAlternate, Modifier.weight(1f)) {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    CardChip("导入PDF", Icons.Outlined.UploadFile, Modifier.weight(1f)) { pickPdf.launch(arrayOf("application/pdf")) }
                    CardChip("证件扫描", Icons.Outlined.Badge, Modifier.weight(1f)) { Tools.openCamera(nav, ScanMode.ID_CARD) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AiEntry(Tools.aiDoc, "总结 · 问答 · 纠错 · 翻译", Modifier.weight(1f)) { Tools.launch(nav, Tools.aiDoc.action) }
            AiEntry(Tools.solve, "拍照得到解题步骤", Modifier.weight(1f)) { Tools.launch(nav, Tools.solve.action) }
        }
        Spacer(Modifier.height(12.dp))

        Card {
            ToolGrid(Tools.home) { Tools.launch(nav, it.action) }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Text("图像处理", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tools.imageTools.forEach { t -> FeatureChip(t) { Tools.launch(nav, t.action) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PDF 工具", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                Row(Modifier.clickable { nav.push(Route.AllTools) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("更多", fontSize = 12.sp, color = Sub)
                    Icon(Icons.Outlined.ChevronRight, null, tint = Sub, modifier = Modifier.size(16.dp))
                }
            }
            ToolGrid(Tools.homePdf) { Tools.launch(nav, it.action) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近文档", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            if (docs.isNotEmpty()) Text("全部", fontSize = 13.sp, color = Brand, modifier = Modifier.clickable(onClick = onAllDocs).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        if (docs.isEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(28.dp), contentAlignment = Alignment.Center) {
                Text("还没有文档，点击上方「拍照扫描」开始吧", color = Sub, fontSize = 13.sp)
            }
        } else {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                docs.take(5).forEach { d -> DocRow(d, selecting = false, selected = false, onClick = { nav.push(Route.Doc(d.id)) }, onLongClick = {}) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    LoadingOverlay(busy)
}

@Composable
private fun AiEntry(t: ToolDef, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(t.tint.copy(alpha = 0.16f), Color.White)))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(t.tint), contentAlignment = Alignment.Center) {
                Icon(t.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            AiBadge(Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FitText(t.title, 15f, color = Ink, fontWeight = FontWeight.SemiBold)
            FitText(subtitle, 11f, color = Sub)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) { content() }
}

@Composable
private fun CardChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        FitText(label, 13f, color = Color.White)
    }
}

@Composable
fun ToolGrid(tools: List<ToolDef>, columns: Int = 4, onClick: (ToolDef) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        tools.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClick(t) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(t.tint.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(t.icon, t.title, tint = t.tint, modifier = Modifier.size(26.dp)) }
                            if (t.ai) AiBadge(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        FitText(t.title, 12f, Modifier.padding(horizontal = 2.dp), color = Ink)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FeatureChip(t: ToolDef, onClick: () -> Unit) {
    Box(
        Modifier
            .widthIn(min = 96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(t.tint.copy(alpha = 0.12f), t.tint.copy(alpha = 0.04f))))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1, softWrap = false)
        }
        if (t.ai) AiBadge(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
    }
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

@Composable
fun DocRow(d: DocumentData, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val repo = ScanApp.instance.repository
    val first = d.pages.firstOrNull()
    val thumb = rememberBitmap(first?.let { repo.imageFile(d, it).absolutePath }, 300)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(Bg).border(1.dp, Line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val kind = when (d.kind) { DocKind.ID_CARD -> "证件"; DocKind.ID_PHOTO -> "证件照"; DocKind.IMAGE -> "图片"; DocKind.SCAN -> "扫描件" }
                Text(kind, fontSize = 10.sp, color = Brand, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Brand.copy(alpha = 0.08f)).padding(horizontal = 4.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text("${d.pages.size}页 · ${dateFmt.format(Date(d.updatedAt))}", fontSize = 12.sp, color = Sub)
            }
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (selected) Brand else Sub,
            )
        } else {
            Icon(Icons.Outlined.ChevronRight, null, tint = Line)
        }
    }
}

@Composable
private fun FilesTab(autoFocus: Boolean) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val repo = ScanApp.instance.repository
    val docs by repo.docs.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var seg by rememberSaveable { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mergeName by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    var exportsVersion by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    val shown = remember(docs, query) { repo.search(query) }
    val exports = remember(exportsVersion, seg, query) {
        ScanApp.instance.exportDir.listFiles().orEmpty()
            .filter { query.isBlank() || it.name.contains(query, true) }
            .sortedByDescending { it.lastModified() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) "已选择 ${selected.size} 项" else "我的文件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            if (selecting) {
                Text("全选", color = Brand, modifier = Modifier.clickable { selected = shown.map { it.id }.toSet() }.padding(8.dp))
                IconButton(onClick = { selecting = false; selected = emptySet() }) { Icon(Icons.Outlined.Close, "取消") }
            }
        }
        SearchField(query, { query = it }, "搜索文档名称或识别出的文字", Modifier.padding(horizontal = 16.dp).focusRequester(focus))
        Row(Modifier.padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("扫描文档 ${shown.size}", "导出文件 ${exports.size}").forEachIndexed { i, s ->
                Text(
                    s, fontSize = 13.sp, color = if (seg == i) Color.White else Ink,
                    modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(if (seg == i) Brand else Color.White)
                        .clickable { seg = i; selecting = false; selected = emptySet() }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        if (seg == 0) {
            if (shown.isEmpty()) Empty(if (query.isBlank()) "暂无文档" else "没有找到相关文档")
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                items(shown, key = { it.id }) { d ->
                    DocRow(
                        d, selecting, d.id in selected,
                        onClick = {
                            if (selecting) selected = if (d.id in selected) selected - d.id else selected + d.id
                            else nav.push(Route.Doc(d.id))
                        },
                        onLongClick = { selecting = true; selected = selected + d.id },
                    )
                }
            }
            if (selecting) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SelAction("合并", Icons.Outlined.Description, selected.size >= 2) { mergeName = true }
                    SelAction("分享PDF", Icons.Outlined.Share, selected.isNotEmpty()) {
                        scope.launch {
                            busy = "正在生成 PDF"
                            val files = withContext(Dispatchers.IO) {
                                selected.mapNotNull { repo.get(it) }.map { d ->
                                    com.lumiscan.app.export.PdfTools.fromImages(
                                        repo.imageFiles(d), Share.exportFile(d.name, "pdf"),
                                        quality = ScanApp.instance.settings.exportQuality,
                                    )
                                }
                            }
                            busy = null
                            Share.share(ctx, files)
                        }
                    }
                    SelAction("删除", Icons.Outlined.Delete, selected.isNotEmpty()) { confirmDelete = true }
                }
            }
        } else {
            if (exports.isEmpty()) Empty("导出的 PDF / Word / Excel 等文件会显示在这里")
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(exports, key = { it.absolutePath }) { f ->
                    ExportRow(f, onChanged = { exportsVersion++ })
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("删除文档", "确定删除选中的 ${selected.size} 个文档？此操作不可恢复。", "删除", onDismiss = { confirmDelete = false }) {
            scope.launch {
                withContext(Dispatchers.IO) { repo.delete(selected) }
                selected = emptySet(); selecting = false
            }
        }
    }
    if (mergeName) {
        TextInputDialog("合并为新文档", initial = "合并文档 ${ScanSession.timestamp()}", onDismiss = { mergeName = false }) { name ->
            mergeName = false
            scope.launch {
                val order = shown.filter { it.id in selected }.map { it.id }.reversed()
                val d = withContext(Dispatchers.IO) { repo.merge(order, name) }
                selecting = false; selected = emptySet()
                if (d != null) nav.push(Route.Doc(d.id))
            }
        }
    }
    LoadingOverlay(busy)
    LaunchedEffect(autoFocus) { if (autoFocus && seg == 0) runCatching { focus.requestFocus() } }
}

/** Compact pill search box that grows with the font size instead of clipping. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = Sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, color = Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) Icon(Icons.Outlined.Close, "清除", tint = Sub, modifier = Modifier.size(18.dp).clickable { onChange("") })
            }
        },
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(text, color = Sub, fontSize = 13.sp) }
}

@Composable
private fun SelAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (enabled) Ink else Sub.copy(alpha = 0.5f))
        Text(label, fontSize = 11.sp, color = if (enabled) Ink else Sub.copy(alpha = 0.5f), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ExportRow(f: File, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        ConfirmDialog("删除文件", "确定删除「${f.name}」？此操作不可恢复。", "删除", onDismiss = { confirm = false }) { f.delete(); onChanged() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable { if (f.extension.equals("pdf", true)) nav.push(Route.PdfView(f.absolutePath)) else Share.open(ctx, f) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(f, 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}", fontSize = 12.sp, color = Sub)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多", tint = Sub) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("分享") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; Share.share(ctx, listOf(f)) })
                DropdownMenuItem(text = { Text("保存到手机") }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = { menu = false; Share.saveAll(ctx, listOf(f)) })
                DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; confirm = true })
            }
        }
    }
}

@Composable
private fun MeTab() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val settings = ScanApp.instance.settings
    var filterDialog by remember { mutableStateOf(false) }
    var qualityDialog by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(dirSize(ScanApp.instance.cacheDir)) }

    val profile = ScanApp.instance.account.profile
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2B2F3A), Color(0xFF14161C))))
                .clickable { nav.push(Route.Account) }
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile?.displayName, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile?.displayName ?: "未登录", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile?.group?.name ?: "—", color = Color(0xFF3A2A00), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF5B83D)).padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                            profile?.group?.expiryText()?.let { Text("  $it", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp) }
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Color.White.copy(alpha = 0.6f))
                }
                profile?.quota?.let { q ->
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        MeStat("AI 对话剩余", if (q.dailyRequests < 0) "不限" else "${q.dailyLeft} 次", Modifier.weight(1f))
                        MeStat("AI 图像剩余", if (q.dailyImages < 0) "不限" else "${q.imagesLeft} 次", Modifier.weight(1f))
                        MeStat("识别页数剩余", if (q.dailyOcrPages < 0) "不限" else "${q.ocrLeft} 页", Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
            SettingRow("默认滤镜", settings.defaultFilter.label) { filterDialog = true }
            SettingSwitch("拍摄后自动切边", settings.autoCrop) { settings.updateAutoCrop(it) }
            SettingRow("导出画质", settings.exportQuality.label) { qualityDialog = true }
            SettingRow("我的签名", "${ScanApp.instance.signatures.files.size} 个") { nav.push(Route.Signatures) }
            SettingRow("证件照制作", "") { Tools.openCamera(nav, ScanMode.ID_PHOTO) }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
            SettingRow("账号与安全", profile?.username.orEmpty()) { nav.push(Route.Account) }
            SettingRow("意见反馈", "") { nav.push(Route.Feedback) }
            SettingRow("用户协议", "") { nav.push(Route.Policy("terms")) }
            SettingRow("隐私政策", "") { nav.push(Route.Policy("privacy")) }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
            SettingRow("清理缓存", formatSize(cacheSize)) {
                ScanApp.instance.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                com.lumiscan.app.ui.common.ImageCache.clear()
                cacheSize = dirSize(ScanApp.instance.cacheDir)
                Share.toast(ctx, "缓存已清理")
            }
            SettingRow("文档占用空间", formatSize(dirSize(File(ScanApp.instance.filesDir, "docs")))) {}
            SettingRow("关于", "v${com.lumiscan.app.BuildConfig.VERSION_NAME}") { about = true }
        }
    }
    if (filterDialog) {
        ChoiceDialog("默认滤镜", com.lumiscan.app.data.FilterType.entries.map { it.label }, settings.defaultFilter.ordinal, { filterDialog = false }) {
            settings.updateDefaultFilter(com.lumiscan.app.data.FilterType.entries[it])
        }
    }
    if (qualityDialog) {
        ChoiceDialog("导出画质", com.lumiscan.app.data.ExportQuality.entries.map { "${it.label}（长边${it.maxSide}px）" }, settings.exportQuality.ordinal, { qualityDialog = false }) {
            settings.updateExportQuality(com.lumiscan.app.data.ExportQuality.entries[it])
        }
    }
    if (about) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { about = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { about = false }) { Text("好的") } },
            title = { Text("光点扫描 v${com.lumiscan.app.BuildConfig.VERSION_NAME}") },
            text = {
                Text(
                    "文档扫描、文字识别、转Word/Excel、证件扫描与证件照、去手写、去水印、拍照翻译、拍照计数、PDF 工具箱，以及 AI 文档助手和拍题解答。\n\n" +
                        "光点扫描是联网应用，需要登录使用。AI 功能按账号分组提供每日额度，提交的内容会发送到服务器并由第三方大模型处理；" +
                        "扫描、识别等图像处理在手机上完成，扫描的文档保存在本机。详见《隐私政策》。\n\n" +
                        "开源组件：OpenCV、ML Kit、PdfBox-Android、CameraX、Jetpack Compose。",
                    fontSize = 13.sp,
                )
            },
        )
    }
}

@Composable
private fun MeStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = Color(0xFFF5D38A), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Sub)
        Icon(Icons.Outlined.ChevronRight, null, tint = Line)
    }
}

@Composable
private fun SettingSwitch(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(i); onDismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (i == selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (i == selected) Brand else Sub)
                        Spacer(Modifier.width(10.dp))
                        Text(o)
                    }
                }
            }
        },
    )
}

private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
