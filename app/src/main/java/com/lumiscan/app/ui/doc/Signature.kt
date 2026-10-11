package com.lumiscan.app.ui.doc

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.lumiscan.app.ui.common.inkInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.ui.common.PrimaryButton
import com.lumiscan.app.ui.common.SecondaryButton
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class InkStroke(val points: List<Offset>, val color: Color, val width: Float)

/** Full-width drawing pad; returns a tightly-cropped transparent PNG via [onSave]. */
@Composable
fun SignaturePad(onCancel: () -> Unit, onSave: (File) -> Unit) {
    val strokes = remember { mutableStateListOf<InkStroke>() }
    val current = remember { mutableStateListOf<Offset>() }
    var color by remember { mutableStateOf(Color.Black) }
    var width by remember { mutableFloatStateOf(8f) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Column(Modifier.fillMaxSize().background(Color.White).navigationBarsPadding().padding(16.dp)) {
        Text("在下方空白处书写签名", color = Sub, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF7F8FA)).border(1.dp, Line, RoundedCornerShape(12.dp)),
        ) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { padSize = it }
                    .inkInput(
                        Unit,
                        onStart = { current.clear(); current += it },
                        onMove = { current += it },
                        onEnd = {
                            // A tap becomes a dot: duplicate the point so it renders as a round cap.
                            val pts = if (current.size == 1) listOf(current[0], current[0] + Offset(0.5f, 0.5f)) else current.toList()
                            strokes += InkStroke(pts, color, width); current.clear()
                        },
                    ),
            ) {
                fun draw(points: List<Offset>, c: Color, w: Float) {
                    if (points.size < 2) return
                    val p = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(p, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                strokes.forEach { draw(it.points, it.color, it.width) }
                draw(current, color, width)
                drawLine(Line, Offset(size.width * 0.1f, size.height * 0.75f), Offset(size.width * 0.9f, size.height * 0.75f), 2f)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(Color.Black, Color(0xFF1A4FD8), Color(0xFFD92D20)).forEach { c ->
                Box(Modifier.padding(end = 10.dp).size(28.dp).clip(CircleShape).background(c).border(3.dp, if (c == color) Brand.copy(alpha = 0.4f) else Color.Transparent, CircleShape).clickable { color = c })
            }
            Text("粗细", fontSize = 13.sp, color = Sub)
            Slider(width, { width = it }, valueRange = 3f..20f, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("取消", Modifier.weight(1f), onCancel)
            SecondaryButton("清空", Modifier.weight(1f)) { strokes.clear() }
            PrimaryButton("保存", Modifier.weight(1f), enabled = strokes.isNotEmpty()) {
                onSave(renderSignature(strokes, padSize.width, padSize.height))
            }
        }
    }
}

private fun renderSignature(strokes: List<InkStroke>, w: Int, h: Int): File {
    val all = strokes.flatMap { it.points }
    val pad = 24f
    val maxW = strokes.maxOf { it.width }
    val left = max(0f, all.minOf { it.x } - pad - maxW); val top = max(0f, all.minOf { it.y } - pad - maxW)
    val right = min(w.toFloat(), all.maxOf { it.x } + pad + maxW); val bottom = min(h.toFloat(), all.maxOf { it.y } + pad + maxW)
    val scale = 2f
    val bmp = Bitmap.createBitmap(((right - left) * scale).roundToInt().coerceAtLeast(1), ((bottom - top) * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = ACanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    for (s in strokes) {
        if (s.points.size < 2) continue
        paint.color = s.color.toArgb(); paint.strokeWidth = s.width * scale
        val p = android.graphics.Path()
        p.moveTo((s.points[0].x - left) * scale, (s.points[0].y - top) * scale)
        s.points.drop(1).forEach { p.lineTo((it.x - left) * scale, (it.y - top) * scale) }
        c.drawPath(p, paint)
    }
    val store = ScanApp.instance.signatures
    val f = BitmapIO.savePng(bmp, store.newFile())
    bmp.recycle()
    store.refresh()
    return f
}

/** Picks a photo of a handwritten signature and turns it into a transparent signature image. */
@Composable
fun rememberSignatureImport(onImported: (File) -> Unit): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val bmp = BitmapIO.decodeUri(ctx, uri, 2400) ?: return@runCatching null
                    val sig = com.lumiscan.app.imaging.ImageTools.extractSignature(bmp) ?: return@runCatching null
                    val store = ScanApp.instance.signatures
                    BitmapIO.savePng(sig, store.newFile()).also { store.refresh() }
                }.getOrNull()
            }
            if (f != null) onImported(f) else com.lumiscan.app.export.Share.toast(ctx, "没有识别到签名，请在白纸上用深色笔书写后拍照")
        }
    }
    return { launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Lets the user pick a saved signature, draw a new one, or import one from a photo. */
@Composable
fun SignaturePicker(onDismiss: () -> Unit, onPicked: (File) -> Unit) {
    val store = ScanApp.instance.signatures
    var drawing by remember { mutableStateOf(store.files.isEmpty()) }
    val importPhoto = rememberSignatureImport { onPicked(it) }
    if (drawing) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxWidth().height(520.dp).padding(12.dp).clip(RoundedCornerShape(16.dp))) {
                SignaturePad(onCancel = { if (store.files.isEmpty()) onDismiss() else drawing = false }) { onPicked(it) }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择签名") },
        text = {
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(280.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Box(Modifier.height(80.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Brand, RoundedCornerShape(10.dp)).clickable { drawing = true }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Add, null, tint = Brand); Text("手写签名", color = Brand) }
                    }
                }
                item {
                    Box(Modifier.height(80.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Brand, RoundedCornerShape(10.dp)).clickable { importPhoto() }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Brand); Text("照片导入", color = Brand) }
                    }
                }
                items(store.files, key = { it.absolutePath }) { f ->
                    val b = rememberBitmap(f.absolutePath, 400)
                    Box(Modifier.height(80.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF5F6F8)).clickable { onPicked(f) }.padding(6.dp)) {
                        b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Normalised placement rect (fractions of the page). */
data class Placement(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Shows [page] fitted and lets the user drag [stamp] around and resize it with the corner handle.
 */
@Composable
fun StampPlacer(page: Bitmap, stamp: Bitmap, placement: Placement, onChange: (Placement) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val s = min(boxW / page.width, boxH / page.height)
        val pw = page.width * s; val ph = page.height * s
        val left = (boxW - pw) / 2; val top = (boxH - ph) / 2
        val cur by androidx.compose.runtime.rememberUpdatedState(placement)
        val pageImg = remember(page) { page.asImageBitmap() }
        val stampImg = remember(stamp) { stamp.asImageBitmap() }
        Image(pageImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        val sx = left + placement.x * pw; val sy = top + placement.y * ph
        val sw = placement.w * pw; val sh = placement.h * ph
        with(density) {
            Box(
                Modifier
                    .offset { IntOffset(sx.roundToInt(), sy.roundToInt()) }
                    .size(sw.toDp(), sh.toDp())
                    .border(1.dp, Brand, RoundedCornerShape(2.dp))
                    .pointerInput(pw, ph) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            val c = cur
                            onChange(c.copy(x = (c.x + d.x / pw).coerceIn(0f, 1f - c.w), y = (c.y + d.y / ph).coerceIn(0f, 1f - c.h)))
                        }
                    },
            ) {
                Image(stampImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Box(
                Modifier
                    .offset { IntOffset((sx + sw - 14.dp.toPx()).roundToInt(), (sy + sh - 14.dp.toPx()).roundToInt()) }
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Brand)
                    .pointerInput(pw, ph) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            val c = cur
                            val aspect = stamp.height.toFloat() / stamp.width * pw / ph
                            val nw = (c.w + d.x / pw).coerceIn(0.05f, 1f - c.x)
                            val nh = nw * aspect
                            if (c.y + nh <= 1f) onChange(c.copy(w = nw, h = nh))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.OpenInFull, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

fun initialPlacement(page: Bitmap, stamp: Bitmap): Placement {
    val w = 0.32f
    val h = w * stamp.height / stamp.width * page.width / page.height
    return Placement(0.6f, (0.82f - h).coerceAtLeast(0f), w, h)
}

/** Manage saved signatures. */
@Composable
fun SignaturesScreen() {
    val store = ScanApp.instance.signatures
    var drawing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(com.lumiscan.app.ui.theme.Bg)) {
        com.lumiscan.app.ui.common.AppTopBar("我的签名")
        if (drawing) {
            SignaturePad(onCancel = { drawing = false }) { drawing = false }
            return@Column
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(store.files, key = { it.absolutePath }) { f ->
                val b = rememberBitmap(f.absolutePath, 400)
                Box(Modifier.height(110.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                    b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    IconButton(onClick = { store.delete(f) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                        Icon(Icons.Outlined.Delete, "删除", tint = Sub, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (store.files.isEmpty()) Text("还没有签名", color = Sub, modifier = Modifier.align(Alignment.CenterHorizontally).padding(20.dp))
        val importPhoto = rememberSignatureImport { }
        Text("提示：可在白纸上用深色笔签名后拍照导入，自动去除背景", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("照片导入", Modifier.weight(1f)) { importPhoto() }
            PrimaryButton("手写签名", Modifier.weight(1f)) { drawing = true }
        }
        Spacer(Modifier.width(1.dp))
    }
}
