package com.lumiscan.app.ui.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.lumiscan.app.data.DocKind
import com.lumiscan.app.data.FilterType
import com.lumiscan.app.data.NewPage
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.DocImage
import com.lumiscan.app.imaging.EdgeDetector
import com.lumiscan.app.imaging.Quad
import com.lumiscan.app.nav.LeaveScreen
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.Route
import com.lumiscan.app.scan.PendingPage
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.scan.ScanSession
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.BarAction
import com.lumiscan.app.ui.common.BottomActions
import com.lumiscan.app.ui.common.ImageCache
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.PrimaryButton
import com.lumiscan.app.ui.common.QuadEditor
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Dark = Color(0xFF111111)

/** Rotating the flattened output 90° clockwise = shifting which corner is "top-left". */
fun Quad.rotatedOutputCw(): Quad = Quad(bl, tl, tr, br)

@Composable
fun CropScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val pages = ScanSession.pages
    if (pages.isEmpty()) {
        LeaveScreen()
        return
    }
    var index by remember { mutableIntStateOf(0) }
    val current = pages[index.coerceIn(0, pages.lastIndex)]
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }

    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("调整边框  ${index + 1}/${pages.size}", dark = true)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bmp = rememberBitmap(current.source, 1600)
                if (bmp != null) {
                    androidx.compose.runtime.key(current.id, current.source) {
                        QuadEditor(
                            bmp, current.w, current.h,
                            current.quad ?: Quad.full(current.w.toFloat(), current.h.toFloat()),
                            onChange = { current.quad = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }
            }
            if (pages.size > 1) {
                LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                        val t = rememberBitmap(p.source, 200)
                        Box(
                            Modifier.size(46.dp, 58.dp).clip(RoundedCornerShape(6.dp))
                                .border(2.dp, if (i == index) Brand else Color.Transparent, RoundedCornerShape(6.dp))
                                .background(Color.DarkGray).clickable { index = i },
                        ) {
                            if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text("${i + 1}", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp))
                        }
                    }
                }
            }
            BottomActions(
                listOf(
                    BarAction("旋转", Icons.Outlined.Rotate90DegreesCw) {
                        scope.launch {
                            busy = "旋转中"
                            withContext(Dispatchers.Default) {
                                val b = BitmapIO.decode(current.source) ?: return@withContext
                                val r = BitmapIO.rotate(b, 90)
                                val f = BitmapIO.saveJpeg(r, BitmapIO.newWorkFile(), 95)
                                val q = (current.quad ?: Quad.full(b.width.toFloat(), b.height.toFloat())).rotateCw(b.width.toFloat(), b.height.toFloat())
                                withContext(Dispatchers.Main) {
                                    current.source = f.absolutePath; current.w = r.width; current.h = r.height; current.quad = q
                                }
                                b.recycle(); r.recycle()
                            }
                            busy = null
                        }
                    },
                    BarAction(if (isFull(current)) "自动识别" else "全图", if (isFull(current)) Icons.Outlined.Crop else Icons.Outlined.CropFree) {
                        if (isFull(current)) {
                            scope.launch {
                                busy = "识别边框"
                                val q = withContext(Dispatchers.Default) { BitmapIO.decode(current.source, 1600)?.let { b -> ScanSession.detectEdges(b)?.scale(current.w.toFloat() / b.width) } }
                                current.quad = q ?: ScanSession.fallbackQuad(current.w, current.h) ?: Quad.inset(current.w.toFloat(), current.h.toFloat())
                                if (q == null) Share.toast(ctx, "未识别到边框，请手动调整")
                                busy = null
                            }
                        } else current.quad = Quad.full(current.w.toFloat(), current.h.toFloat())
                    },
                    BarAction("删除", Icons.Outlined.Delete) {
                        pages.remove(current)
                        if (pages.isEmpty()) nav.pop() else index = index.coerceAtMost(pages.lastIndex)
                    },
                    BarAction("继续拍", Icons.Outlined.AddAPhoto, enabled = ScanSession.mode.multi && ScanSession.mode != ScanMode.ID_CARD) {
                        // Came from the camera: go back to it; came from an import: open the camera.
                        if (nav.stack.getOrNull(nav.stack.lastIndex - 1)?.route is Route.Camera) nav.pop()
                        else nav.replace(Route.Camera(ScanSession.mode, ScanSession.appendTo))
                    },
                ),
                dark = true,
                insetBottom = false,
            )
            Box(Modifier.fillMaxWidth().background(Color(0xFF111111)).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                PrimaryButton("下一步", Modifier.fillMaxWidth()) {
                    val bad = pages.indexOfFirst { it.quad?.isConvex() == false }
                    if (bad >= 0) {
                        index = bad; Share.toast(ctx, "第${bad + 1}页边框无效，请调整"); return@PrimaryButton
                    }
                    scope.launch {
                        busy = "正在矫正 0/${pages.size}"
                        ScanSession.cropAll { busy = "正在矫正 $it/${pages.size}" }
                        busy = null
                        ScanSession.afterCrop(nav)
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
}

private fun isFull(p: PendingPage): Boolean {
    val q = p.quad ?: return true
    return q == Quad.full(p.w.toFloat(), p.h.toFloat())
}

@Composable
fun FilterStrip(base: Bitmap?, selected: FilterType, dark: Boolean = true, onSelect: (FilterType) -> Unit) {
    val thumbs by produceState<Map<FilterType, Bitmap>>(emptyMap(), base) {
        val b = base ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val small = BitmapIO.scaleToMax(b, 420)
                FilterType.entries.associateWith { DocImage.applyFilter(small, it) }
            }.getOrDefault(emptyMap())
        }
    }
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(FilterType.entries) { _, f ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSelect(f) }) {
                Box(
                    Modifier.size(58.dp, 72.dp).clip(RoundedCornerShape(8.dp))
                        .border(2.dp, if (f == selected) Brand else Color.Transparent, RoundedCornerShape(8.dp))
                        .background(Color.DarkGray),
                ) {
                    thumbs[f]?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(4.dp))
                Text(f.label, fontSize = 11.sp, color = if (f == selected) Brand else if (dark) Color.White else Color.DarkGray, fontWeight = if (f == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun FilteredPreview(path: String?, filter: FilterType, modifier: Modifier) {
    val result by produceState<Bitmap?>(null, path, filter) {
        val p = path ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { ImageCache.get(p, 1400)?.let { DocImage.applyFilter(it, filter) } }.getOrNull()
        }
    }
    Box(modifier) {
        val r = result
        if (r != null) Image(r.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp))
        else CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
    }
}

@Composable
fun FiltersScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val pages = ScanSession.pages
    if (pages.isEmpty() || pages.any { it.cropped == null }) {
        LeaveScreen()
        return
    }
    val pager = rememberPagerState { pages.size }
    var applyAll by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    var version by remember { mutableIntStateOf(0) }
    val cur = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]

    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("滤镜  ${pager.currentPage + 1}/${pages.size}", dark = true)
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { pages[it].id }) { i ->
                val p = pages[i]
                androidx.compose.runtime.key(version) { FilteredPreview(p.cropped, p.filter, Modifier.fillMaxSize()) }
            }
            val base = rememberBitmap(cur.cropped, 800)
            FilterStrip(base, cur.filter) { f -> if (applyAll) pages.forEach { it.filter = f } else cur.filter = f }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("应用到全部页面", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(applyAll, { applyAll = it }, colors = SwitchDefaults.colors(checkedTrackColor = Brand))
            }
            BottomActions(
                listOf(
                    BarAction("旋转", Icons.Outlined.Rotate90DegreesCw) {
                        scope.launch {
                            busy = "旋转中"
                            withContext(Dispatchers.Default) {
                                val b = BitmapIO.decode(cur.cropped!!) ?: return@withContext
                                val r = BitmapIO.rotate(b, 90)
                                cur.cropped = BitmapIO.saveJpeg(r, BitmapIO.newWorkFile(), 95).absolutePath
                                cur.quad = (cur.quad ?: Quad.full(cur.w.toFloat(), cur.h.toFloat())).rotatedOutputCw()
                                b.recycle(); r.recycle()
                            }
                            version++
                            busy = null
                        }
                    },
                    BarAction("调整边框", Icons.Outlined.Crop) { nav.pop() },
                    BarAction("默认滤镜", Icons.Outlined.Tune) {
                        ScanApp.instance.settings.updateDefaultFilter(cur.filter)
                        Share.toast(ctx, "已设为默认滤镜：${cur.filter.label}")
                    },
                ),
                dark = true,
                insetBottom = false,
            )
            Box(Modifier.fillMaxWidth().background(Dark).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                PrimaryButton("保存", Modifier.fillMaxWidth()) {
                    scope.launch {
                        busy = "正在保存"
                        val id = withContext(Dispatchers.Default) { saveSession { busy = "正在保存 $it/${pages.size}" } }
                        busy = null
                        val append = ScanSession.appendTo
                        ScanSession.pages.clear()
                        if (append != null) nav.popTo { it is Route.Doc && it.id == append } else nav.reset(Route.Doc(id))
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
}

/** Applies each page's filter at full resolution and stores the pages as a document. */
private suspend fun saveSession(onProgress: suspend (Int) -> Unit): String {
    val repo = ScanApp.instance.repository
    val newPages = ScanSession.pages.mapIndexed { i, p ->
        val b = BitmapIO.decode(p.cropped!!)!!
        val out = DocImage.applyFilter(b, p.filter)
        val f = BitmapIO.saveJpeg(out, BitmapIO.newWorkFile(), 92)
        b.recycle(); out.recycle()
        withContext(Dispatchers.Main) { onProgress(i + 1) }
        NewPage(File(p.source), f, p.quad?.toList(), p.filter)
    }
    val append = ScanSession.appendTo
    val doc = append?.let { repo.addPages(it, newPages) }
        ?: repo.create("扫描文档 ${ScanSession.timestamp()}", DocKind.SCAN, newPages)
    return doc.id
}

/** Re-edit a stored page: crop from its original capture, choose filter, rotate. */
@Composable
fun PageEditScreen(docId: String, index: Int) {
    val nav = LocalNav.current
    val repo = ScanApp.instance.repository
    val doc = remember { repo.get(docId) }
    val page = doc?.pages?.getOrNull(index)
    if (doc == null || page == null) {
        LeaveScreen()
        return
    }
    var sourcePath by remember { mutableStateOf(repo.sourceFile(doc, page).absolutePath) }
    val size = remember(sourcePath) { BitmapIO.imageSize(sourcePath) }
    var quad by remember { mutableStateOf(Quad.fromList(page.quad) ?: Quad.full(size.first.toFloat(), size.second.toFloat())) }
    var filter by remember { mutableStateOf(page.filter) }
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    var sourceChanged by remember { mutableStateOf(false) }

    val warped by produceState<Bitmap?>(null, sourcePath, quad, tab) {
        if (tab == 0) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { ImageCache.get(sourcePath, 1600)?.let { b -> DocImage.warp(b, quad.scale(b.width.toFloat() / size.first)) } }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("编辑页面", dark = true)
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                listOf("裁剪", "滤镜").forEachIndexed { i, s ->
                    Text(
                        s, color = if (tab == i) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 15.sp,
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable { tab = i }.padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) {
                    val bmp = rememberBitmap(sourcePath, 1600)
                    if (bmp != null) androidx.compose.runtime.key(sourcePath) {
                        QuadEditor(bmp, size.first, size.second, quad, { quad = it }, Modifier.fillMaxSize())
                    }
                } else {
                    val w = warped
                    val filtered by produceState<Bitmap?>(null, w, filter) {
                        value = w?.let { withContext(Dispatchers.Default) { runCatching { DocImage.applyFilter(it, filter) }.getOrNull() } }
                    }
                    filtered?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit) }
                }
            }
            if (tab == 1) FilterStrip(warped, filter) { filter = it }
            BottomActions(
                listOf(
                    BarAction("旋转", Icons.Outlined.Rotate90DegreesCw) {
                        scope.launch {
                            busy = "旋转中"
                            withContext(Dispatchers.Default) {
                                val b = BitmapIO.decode(sourcePath) ?: return@withContext
                                val r = BitmapIO.rotate(b, 90)
                                val f = BitmapIO.saveJpeg(r, BitmapIO.newWorkFile(), 95)
                                val q = quad.rotateCw(b.width.toFloat(), b.height.toFloat())
                                withContext(Dispatchers.Main) { quad = q; sourcePath = f.absolutePath; sourceChanged = true }
                                b.recycle(); r.recycle()
                            }
                            busy = null
                        }
                    },
                    BarAction("全图", Icons.Outlined.CropFree) { quad = Quad.full(size.first.toFloat(), size.second.toFloat()) },
                    BarAction("自动识别", Icons.Outlined.Crop) {
                        scope.launch {
                            val q = withContext(Dispatchers.Default) { ImageCache.get(sourcePath, 1600)?.let { b -> EdgeDetector.detect(b)?.scale(size.first.toFloat() / b.width) } }
                            if (q != null) quad = q
                        }
                    },
                ),
                dark = true,
                insetBottom = false,
            )
            Box(Modifier.fillMaxWidth().background(Dark).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                PrimaryButton("完成", Modifier.fillMaxWidth()) {
                    if (!quad.isConvex()) return@PrimaryButton
                    scope.launch {
                        busy = "正在保存"
                        withContext(Dispatchers.Default) {
                            val b = BitmapIO.decode(sourcePath)!!
                            val w = DocImage.warp(b, quad.scale(b.width.toFloat() / size.first))
                            val out = DocImage.applyFilter(w, filter)
                            val f = BitmapIO.saveJpeg(out, BitmapIO.newWorkFile(), 92)
                            repo.updatePage(docId, index, f, quad.toList(), filter, if (sourceChanged) File(sourcePath) else null)
                            b.recycle(); w.recycle(); out.recycle()
                        }
                        busy = null
                        nav.pop()
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
}
