package com.lumiscan.app.ui.tools

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.DocKind
import com.lumiscan.app.data.FilterType
import com.lumiscan.app.data.NewPage
import com.lumiscan.app.export.PdfTools
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.CountMark
import com.lumiscan.app.imaging.HandwritingOptions
import com.lumiscan.app.imaging.ImageTools
import com.lumiscan.app.imaging.ObjectCounter
import com.lumiscan.app.imaging.RestoreOptions
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.data.remote.EditOptions
import com.lumiscan.app.ml.CloudVision
import com.lumiscan.app.ml.IdCutout
import com.lumiscan.app.ml.NoFaceException
import com.lumiscan.app.ml.IdPhotoMaker
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.retain
import com.lumiscan.app.nav.Route
import com.lumiscan.app.scan.ScanSession
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.BarAction
import com.lumiscan.app.ui.common.BottomActions
import com.lumiscan.app.ui.common.BrushImage
import com.lumiscan.app.ui.common.Fit
import com.lumiscan.app.ui.common.HoldCompare
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.MaskState
import com.lumiscan.app.ui.common.ResultFiles
import com.lumiscan.app.ui.common.SelectChip
import com.lumiscan.app.ui.common.SliderCompare
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

private val Dark = Color(0xFF111111)

@Composable
private fun DarkLabel(t: String) = Text(t, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> SelectChip(o, i == selected, dark = true) { onSelect(i) } }
    }
}

@Composable
private fun DarkSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

// ---------------------------------------------------------------- 拍照计数

/**
 * AI counting is a recognition call (≈1 分钱, counted as one recognition page), so it runs right
 * away like OCR; the on-phone counter is the fallback and the "本地计数" option.
 */
@Composable
fun CountScreen(image: String) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val src = rememberBitmap(image, 2000)
    var useAi by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    // Last AI answer, kept while switching to the local count and back.
    var aiResult by remember { mutableStateOf<CloudVision.Counted?>(null) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var run by remember { mutableIntStateOf(0) }
    val marks = remember { mutableStateListOf<CountMark>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(src, useAi, run) {
        val s = src ?: return@LaunchedEffect
        if (useAi) {
            val r = aiResult ?: try {
                busy = "AI 正在计数"
                CloudVision.count(s, hint).also { aiResult = it }
            } catch (e: ApiException) {
                busy = null
                Share.toast(ctx, aiFallbackMessage(e)); useAi = false
                return@LaunchedEffect
            } catch (e: Exception) {
                busy = null
                Share.toast(ctx, "AI 计数失败，已改用本地计数"); useAi = false
                return@LaunchedEffect
            }
            marks.clear(); marks.addAll(aiMarks(r, s.width, s.height))
            busy = null
            if (r.points.isEmpty()) Share.toast(ctx, "AI 没有找到要数的物体，可在下方写明要数什么后重新识别")
            return@LaunchedEffect
        }
        busy = "正在计数"
        val r = withContext(Dispatchers.Default) { runCatching { ObjectCounter.count(s, sensitivity) }.getOrDefault(emptyList()) }
        marks.clear(); marks.addAll(r)
        busy = null
    }
    fun recount() { focus.clearFocus(); aiResult = null; run++ }
    val fit = remember(box, src) { src?.let { Fit.of(it.width, it.height, box, 8f) } }
    val typicalR = marks.map { it.r }.sorted().let { if (it.isEmpty()) 20f else it[it.size / 2] }

    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("拍照计数", dark = true)
            AiModeRow(useAi, { useAi = it }, aiLabel = "AI 计数", localLabel = "本地计数", recognition = true)
            Text("共 ${marks.size} 个", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            val label = aiResult?.label?.takeIf { useAi && it.isNotBlank() }
            Text(
                (if (label != null) "AI 识别为「$label」 · " else "") + "点击空白处添加，点击标记删除",
                color = Sub, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }) {
                val s = src; val f = fit
                if (s != null && f != null) {
                    val img = remember(s) { s.asImageBitmap() }
                    Canvas(
                        Modifier.fillMaxSize().clipToBounds().pointerInput(f, typicalR) {
                            detectTapGestures { o ->
                                val p = f.toImage(o)
                                val hit = marks.indexOfFirst { hypot(it.x - p.x, it.y - p.y) < it.r.coerceAtLeast(12f / f.scale) }
                                if (hit >= 0) marks.removeAt(hit) else if (p.x in 0f..s.width.toFloat() && p.y in 0f..s.height.toFloat()) marks += CountMark(p.x, p.y, typicalR)
                            }
                        },
                    ) {
                        drawImage(img, dstOffset = IntOffset(f.left.toInt(), f.top.toInt()), dstSize = IntSize(f.width.toInt(), f.height.toInt()))
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                        marks.forEachIndexed { i, m ->
                            val c = Offset(f.left + m.x * f.scale, f.top + m.y * f.scale)
                            val r = (m.r * f.scale).coerceIn(7f, 60f)
                            drawCircle(Brand.copy(alpha = 0.35f), r, c)
                            drawCircle(Brand, r, c, style = Stroke(2f))
                            paint.textSize = (r * 0.9f).coerceIn(14f, 34f)
                            drawContext.canvas.nativeCanvas.drawText("${i + 1}", c.x, c.y + paint.textSize / 3, paint)
                        }
                    }
                }
            }
            if (useAi) {
                DarkLabel("要数的物体（选填，数错时写明再识别，如：钢管、药片）")
                HintField(hint, { hint = it.take(20) }, "AI 自动判断", onDone = ::recount)
            } else {
                DarkLabel("灵敏度（漏数时调高，多数时调低）")
                Slider(sensitivity, { sensitivity = it }, onValueChangeFinished = { run++ }, modifier = Modifier.padding(horizontal = 16.dp))
            }
            BottomActions(
                listOf(
                    BarAction("重新识别", Icons.Outlined.Refresh) { recount() },
                    BarAction("保存结果图", Icons.Outlined.Download, enabled = src != null) {
                        val s = src ?: return@BarAction
                        val out = s.copy(Bitmap.Config.ARGB_8888, true)
                        val c = ACanvas(out)
                        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(110, 47, 107, 255) }
                        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(47, 107, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
                        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                        marks.forEachIndexed { i, m ->
                            c.drawCircle(m.x, m.y, m.r, fill); c.drawCircle(m.x, m.y, m.r, stroke)
                            text.textSize = (m.r * 0.9f).coerceIn(16f, 60f)
                            c.drawText("${i + 1}", m.x, m.y + text.textSize / 3, text)
                        }
                        val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(170, 0, 0, 0) }
                        c.drawRect(0f, 0f, out.width.toFloat(), out.height * 0.07f, banner)
                        text.textSize = out.height * 0.045f; text.textAlign = Paint.Align.LEFT
                        c.drawText("计数结果：${marks.size} 个", out.width * 0.03f, out.height * 0.052f, text)
                        Share.toast(ctx, if (Share.saveBitmap(ctx, out, "计数结果")) "已保存到相册" else "保存失败")
                    },
                ),
                dark = true,
            )
        }
        LoadingOverlay(busy)
    }
}

/** Marks for AI points, sized from their spacing (packed objects touch: about half the gap). */
private fun aiMarks(r: CloudVision.Counted, w: Int, h: Int): List<CountMark> {
    val pts = r.points.map { it.x * w to it.y * h }
    val short = minOf(w, h).toFloat()
    val nn = pts.map { a -> pts.minOfOrNull { b -> if (a === b) Float.MAX_VALUE else hypot(a.first - b.first, a.second - b.second) } ?: Float.MAX_VALUE }
        .filter { it < Float.MAX_VALUE }.sorted()
    val radius = (if (nn.isEmpty()) short * 0.04f else nn[nn.size / 2] * 0.42f).coerceIn(short * 0.008f, short * 0.05f)
    return pts.map { (x, y) -> CountMark(x, y, radius) }
}

/** Single-line text box for the dark tool screens. */
@Composable
private fun HintField(value: String, onChange: (String) -> Unit, placeholder: String, onDone: () -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color.White),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f)).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
                    inner()
                }
                Text(
                    "识别", color = Brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onDone() }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
    )
}

// ---------------------------------------------------------------- AI / 本地 mode

/** "AI 智能 / 本地处理" switch shown on every image tool, with today's remaining AI image count. */
@Composable
private fun AiModeRow(
    ai: Boolean, onChange: (Boolean) -> Unit, aiLabel: String = "AI 智能", localLabel: String = "本地处理", dark: Boolean = true,
    /** Draws from the recognition allowance (pages) instead of the AI image one. */
    recognition: Boolean = false,
) {
    val q = ScanApp.instance.account.profile?.quota
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SelectChip("✦ $aiLabel", ai, dark = dark) { if (!ai) onChange(true) }
        Spacer(Modifier.width(8.dp))
        SelectChip(localLabel, !ai, dark = dark) { if (ai) onChange(false) }
        Spacer(Modifier.weight(1f))
        if (q != null) {
            Text(
                when {
                    recognition -> if (q.dailyOcrPages < 0) "AI 识别不限次" else "今日 AI 识别剩余 ${q.ocrLeft} 次"
                    q.dailyImages < 0 -> "AI 图像不限次"
                    else -> "今日 AI 剩余 ${q.imagesLeft} 次"
                },
                fontSize = 11.sp, color = if (dark) Color.White.copy(alpha = 0.6f) else Sub,
            )
        }
    }
}

private fun aiFallbackMessage(e: ApiException) = "AI 不可用（${e.message}），已改用本地处理"

/**
 * Tools whose AI call is an image model (≈0.14–0.2 元 each) open with the free on-phone result and
 * call the model when the user taps AI, unless the admin turned on running it right away.
 */
private fun imageAiOnOpen() = ScanApp.instance.account.config?.imageAiAuto == true

// ---------------------------------------------------------------- 证件照

private val outfits = listOf("" to "原服装", "suit_men" to "男士西装", "suit_women" to "女士西装", "shirt_white" to "白衬衫")

@Composable
fun IdPhotoScreen(image: String) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val src = rememberBitmap(image, 2000)
    var useAi by remember { mutableStateOf(imageAiOnOpen()) }
    var outfit by remember { mutableStateOf("") }
    val aiCuts = remember { mutableStateMapOf<String, IdCutout>() }
    var localCut by remember { mutableStateOf<IdCutout?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var spec by remember { mutableStateOf(IdPhotoMaker.specs[0]) }
    var bg by remember { mutableStateOf(IdPhotoMaker.backgrounds[1]) }
    var beauty by remember { mutableFloatStateOf(0.3f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>("正在检测人像") }
    val scope = rememberSafeScope { busy = null }

    LaunchedEffect(src, useAi, outfit) {
        val s = src ?: return@LaunchedEffect
        if (useAi) {
            if (aiCuts[outfit] != null) return@LaunchedEffect
            try {
                // No face → stop before spending an AI call.
                busy = "正在检测人像"
                withContext(Dispatchers.Default) { IdPhotoMaker.checkFace(s) }
                busy = if (outfit.isEmpty()) "AI 正在精细抠图（约 20 秒）" else "AI 正在换装（约 20 秒）"
                // ≤1536 px keeps the call in the model's cheaper 1K tier; ID photos are only ~600 px.
                val ai = CloudVision.edit("id_photo", s, 1536, EditOptions(outfit = outfit))
                aiCuts[outfit] = withContext(Dispatchers.Default) { IdPhotoMaker.cutoutFromKeyed(ai) }
            } catch (e: NoFaceException) {
                error = e.message
            } catch (e: ApiException) {
                Share.toast(ctx, aiFallbackMessage(e))
                outfit = ""
                useAi = false
            } catch (e: Exception) {
                Share.toast(ctx, "AI 抠图失败，已改用本地抠图")
                outfit = ""
                useAi = false
            }
            busy = null
        } else {
            if (localCut != null) return@LaunchedEffect
            busy = "正在抠图"
            runCatching { withContext(Dispatchers.Default) { IdPhotoMaker.cutout(s) } }
                .onSuccess { localCut = it }
                .onFailure { error = it.message ?: "处理失败" }
            busy = null
        }
    }
    val cut = if (useAi) aiCuts[outfit] else localCut
    val photo by produceState<Bitmap?>(null, cut, spec, bg, beauty, offsetY, zoom) {
        val c = cut ?: return@produceState
        value = withContext(Dispatchers.Default) { IdPhotoMaker.render(c, spec, bg, beauty, offsetY, zoom) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFEFF1F5))) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("证件照", actions = {
                Text("重拍", color = Brand, fontSize = 15.sp, modifier = Modifier.clickable { nav.pop() }.padding(12.dp))
            })
            AiModeRow(useAi, { useAi = it; if (!it) outfit = "" }, aiLabel = "AI 抠图", localLabel = "本地抠图", dark = false)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val p = photo
                when {
                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = Ink)
                        Spacer(Modifier.height(10.dp))
                        Text("重新拍摄", color = Brand, modifier = Modifier.clickable { nav.pop() }.padding(8.dp))
                    }
                    p != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            p.asImageBitmap(), null,
                            Modifier.fillMaxHeight(0.82f).aspectRatio(spec.pxW.toFloat() / spec.pxH).border(1.dp, Color(0xFFD0D5DD)),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${spec.name} · ${spec.mmW}×${spec.mmH}mm · ${spec.pxW}×${spec.pxH}px · 300dpi", fontSize = 12.sp, color = Sub)
                    }
                    else -> CircularProgressIndicator()
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.White).padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("尺寸", "背景", "换装", "美颜", "位置").forEachIndexed { i, t ->
                        Text(t, color = if (tab == i) Brand else Ink, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable { tab = i }.padding(10.dp))
                    }
                }
                when (tab) {
                    0 -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IdPhotoMaker.specs.forEach { s -> SelectChip(s.name, s == spec) { spec = s } }
                    }
                    1 -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IdPhotoMaker.backgrounds.forEach { b ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { bg = b }) {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape)
                                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(b.top), Color(b.bottom))))
                                        .border(if (b == bg) 3.dp else 1.dp, if (b == bg) Brand else Color(0xFFD0D5DD), CircleShape),
                                )
                                Text(b.name, fontSize = 11.sp, color = Ink)
                            }
                        }
                    }
                    2 -> Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            outfits.forEach { (key, label) -> SelectChip(label, useAi && outfit == key || !useAi && key.isEmpty()) { outfit = key; if (key.isNotEmpty()) useAi = true } }
                        }
                        Text("AI 换装会重新生成人物服装，每种服装消耗 1 次 AI 图像额度", fontSize = 11.sp, color = Sub, modifier = Modifier.padding(top = 6.dp))
                    }
                    3 -> Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("磨皮美白 ${(beauty * 100).toInt()}", fontSize = 13.sp, color = Sub)
                        Slider(beauty, { beauty = it }, valueRange = 0f..1f)
                    }
                    else -> Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("上下位置", fontSize = 13.sp, color = Sub)
                        Slider(offsetY, { offsetY = it }, valueRange = -0.08f..0.08f)
                        Text("人像大小", fontSize = 13.sp, color = Sub)
                        Slider(zoom, { zoom = it }, valueRange = 0.85f..1.15f)
                    }
                }
                BottomActions(
                    listOf(
                        BarAction("保存电子照", Icons.Outlined.Download, enabled = photo != null) {
                            photo?.let { Share.toast(ctx, if (Share.saveBitmap(ctx, it, "证件照_${spec.name}")) "已保存到相册" else "保存失败") }
                        },
                        BarAction("保存排版照", Icons.Outlined.Print, enabled = photo != null) {
                            val p = photo ?: return@BarAction
                            scope.launch {
                                val sheet = withContext(Dispatchers.Default) { IdPhotoMaker.printSheet(p, spec) }
                                val ok = Share.saveBitmap(ctx, sheet, "证件照排版_${spec.name}")
                                Share.toast(ctx, if (ok) "已保存6寸排版（${IdPhotoMaker.copiesPerSheet(spec)}张），可直接冲印" else "保存失败")
                            }
                        },
                        BarAction("存入文档", Icons.Outlined.Save, enabled = photo != null) {
                            val p = photo ?: return@BarAction
                            scope.launch {
                                val id = withContext(Dispatchers.IO) {
                                    val f = BitmapIO.saveJpeg(p, BitmapIO.newWorkFile(), 95)
                                    ScanApp.instance.repository.create("证件照 ${spec.name} ${ScanSession.timestamp()}", DocKind.ID_PHOTO, listOf(NewPage(File(image), f, null, FilterType.ORIGINAL))).id
                                }
                                nav.reset(Route.Doc(id))
                            }
                        },
                    ),
                )
            }
        }
        LoadingOverlay(busy)
    }
}

// ---------------------------------------------------------------- 去手写 / 试卷还原

@Composable
fun HandwritingScreen(images: List<String>, exam: Boolean) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var useAi by retain("ai") { mutableStateOf(true) }
    // With AI the model finds the strokes; colour removal is an optional extra.
    var opts by retain("opts") { mutableStateOf(HandwritingOptions(blue = false, red = false, green = false, pencil = false, cleanPage = exam)) }
    var page by retain("page") { mutableIntStateOf(0) }
    val processed = retain("processed") { mutableStateMapOf<Int, String>() }
    val aiBoxes = retain("boxes") { mutableStateMapOf<Int, List<RectF>>() }
    var version by retain("version") { mutableIntStateOf(0) }
    var brush by remember { mutableStateOf(false) }
    var brushSize by remember { mutableFloatStateOf(24f) }
    val mask = remember { MaskState() }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    var exported by retain("exported") { mutableStateOf<List<File>>(emptyList()) }

    fun switchAi(on: Boolean) {
        useAi = on
        opts = if (on) opts.copy(blue = false, red = false, green = false, pencil = false)
        else opts.copy(blue = true, red = true, green = true, pencil = exam)
        processed.clear(); version++
    }

    suspend fun process(i: Int): String {
        processed[i]?.let { return it }
        val b = withContext(Dispatchers.IO) { BitmapIO.decode(images[i])!! }
        var boxes: List<RectF> = emptyList()
        if (useAi) {
            boxes = aiBoxes[i] ?: try {
                busy = "AI 正在定位笔迹"
                CloudVision.detect(b, "handwriting").also { aiBoxes[i] = it }
            } catch (e: ApiException) {
                Share.toast(ctx, aiFallbackMessage(e))
                switchAi(false)
                emptyList()
            }
            busy = "正在去除笔迹"
        }
        val o = opts
        return withContext(Dispatchers.Default) {
            val out = ImageTools.removeHandwriting(b, o, boxes)
            BitmapIO.saveJpeg(out, BitmapIO.newWorkFile(), 92).absolutePath.also { b.recycle(); out.recycle() }
        }.also { processed[i] = it }
    }

    LaunchedEffect(page, version) {
        if (processed[page] == null) {
            busy = "正在去除笔迹"
            runCatching { process(page) }.onFailure { Share.toast(ctx, "处理失败：${it.message}") }
            busy = null
        }
    }
    val before = rememberBitmap(images[page], 1600)
    val after = rememberBitmap(processed[page], 1600)

    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(if (exam) "试卷还原" else "去手写", dark = true)
            if (images.size > 1) ChipRow(images.indices.map { "第${it + 1}页" }, page) { page = it; mask.clear() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (brush && after != null) BrushImage(after, mask, brushSize, Modifier.fillMaxSize())
                else HoldCompare(before, after, Modifier.fillMaxSize())
            }
            if (brush) {
                DarkLabel("涂抹残留笔迹后点击「擦除」 · 笔刷大小")
                Slider(brushSize, { brushSize = it }, valueRange = 8f..60f, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                AiModeRow(useAi, ::switchAi, aiLabel = "AI 识别笔迹")
                if (useAi) DarkLabel("AI 定位手写内容后在原图上擦除，印刷文字和表格线保持原样")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun toggle(o: HandwritingOptions) { opts = o; processed.clear(); version++ }
                    SelectChip(if (useAi) "另去全部蓝色" else "蓝色笔迹", opts.blue, dark = true) { toggle(opts.copy(blue = !opts.blue)) }
                    SelectChip(if (useAi) "另去全部红色" else "红色笔迹", opts.red, dark = true) { toggle(opts.copy(red = !opts.red)) }
                    SelectChip(if (useAi) "另去全部绿色" else "绿色笔迹", opts.green, dark = true) { toggle(opts.copy(green = !opts.green)) }
                    if (!useAi) SelectChip("铅笔/浅色", opts.pencil, dark = true) { toggle(opts.copy(pencil = !opts.pencil)) }
                    SelectChip("黑白净化", opts.cleanPage, dark = true) { toggle(opts.copy(cleanPage = !opts.cleanPage)) }
                }
            }
            if (exported.isNotEmpty()) ResultFiles(exported, Modifier.padding(horizontal = 12.dp))
            BottomActions(
                if (brush) listOf(
                    BarAction("撤销", Icons.AutoMirrored.Outlined.Undo, enabled = !mask.isEmpty) { mask.undo() },
                    BarAction("擦除", Icons.Outlined.AutoFixHigh, enabled = !mask.isEmpty) {
                        val a = processed[page] ?: return@BarAction
                        scope.launch {
                            busy = "擦除中"
                            processed[page] = withContext(Dispatchers.Default) {
                                val b = BitmapIO.decode(a)!!
                                val m = mask.render(after!!.width, after.height)
                                val out = ImageTools.whiteOut(b, m)
                                BitmapIO.saveJpeg(out, BitmapIO.newWorkFile(), 92).absolutePath
                            }
                            mask.clear(); busy = null
                        }
                    },
                    BarAction("完成", Icons.Outlined.Brush) { brush = false; mask.clear() },
                ) else listOf(
                    BarAction("手动擦除", Icons.Outlined.Brush, enabled = after != null) { brush = true },
                    BarAction("导出PDF", Icons.Outlined.PictureAsPdf) {
                        scope.launch {
                            busy = "正在生成 PDF"
                            val files = images.indices.map { process(it) }.map { File(it) }
                            busy = "正在生成 PDF"
                            exported = listOf(withContext(Dispatchers.IO) { PdfTools.fromImages(files, Share.exportFile(if (exam) "试卷还原" else "去手写", "pdf")) })
                            busy = null
                        }
                    },
                    BarAction("保存为文档", Icons.Outlined.Description) {
                        scope.launch {
                            busy = "正在保存"
                            val pages = images.indices.map { i -> NewPage(File(images[i]), File(process(i)), null, FilterType.ORIGINAL) }
                            val id = withContext(Dispatchers.IO) {
                                ScanApp.instance.repository.create("${if (exam) "试卷还原" else "去手写"} ${ScanSession.timestamp()}", DocKind.SCAN, pages).id
                            }
                            busy = null
                            nav.reset(Route.Doc(id))
                        }
                    },
                ),
                dark = true,
            )
        }
        LoadingOverlay(busy)
    }
}

// ---------------------------------------------------------------- 去水印 / 智能消除

@Composable
fun EraseScreen(image: String, watermark: Boolean, docId: String?, index: Int) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val original = rememberBitmap(image, 2400)
    val history = remember { mutableStateListOf<Bitmap>() }
    val current = history.lastOrNull() ?: original
    val mask = remember { MaskState() }
    var tab by remember { mutableIntStateOf(if (watermark) 0 else 1) }
    var useAi by remember { mutableStateOf(true) }
    var watermarkBoxes by remember { mutableStateOf<List<RectF>?>(null) }
    var threshold by remember { mutableFloatStateOf(185f) }
    var brushSize by remember { mutableFloatStateOf(28f) }
    var docMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }

    suspend fun removeWatermark(src: Bitmap): Bitmap {
        if (useAi && watermarkBoxes == null) {
            busy = "AI 正在定位水印"
            watermarkBoxes = try {
                CloudVision.detect(src, "watermark").also { if (it.isEmpty()) Share.toast(ctx, "AI 未发现水印，已按浅色内容去除") }
            } catch (e: ApiException) {
                Share.toast(ctx, aiFallbackMessage(e)); useAi = false; null
            }
            busy = "正在去除水印"
        }
        val boxes = if (useAi) watermarkBoxes.orEmpty() else emptyList()
        return withContext(Dispatchers.Default) { ImageTools.removeDocWatermark(src, threshold.toInt(), boxes) }
    }

    suspend fun erase(c: Bitmap): Bitmap {
        val m = withContext(Dispatchers.Default) { mask.render(c.width, c.height) }
        if (useAi && !docMode) {
            try {
                busy = "AI 正在消除（约 10 秒）"
                val marked = withContext(Dispatchers.Default) { ImageTools.markRed(c, m) }
                val ai = CloudVision.edit("erase", marked, 2048)
                val drift = withContext(Dispatchers.Default) { ImageTools.driftOutside(c, ai, m) }
                // The model re-drew the whole picture (moved or re-toned it): its pixels wouldn't line up.
                if (drift <= 25) return withContext(Dispatchers.Default) { ImageTools.compositeRegion(c, ai, m) }
                Share.toast(ctx, "AI 结果与原图对不齐，本次已改用本地消除")
            } catch (e: ApiException) {
                Share.toast(ctx, aiFallbackMessage(e)); useAi = false
            }
        }
        return withContext(Dispatchers.Default) { if (docMode) ImageTools.whiteOut(c, m) else ImageTools.inpaint(c, m) }
    }

    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(if (watermark) "去水印" else "智能消除", dark = true)
            if (watermark) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    listOf("文档水印", "涂抹消除").forEachIndexed { i, t ->
                        Text(t, color = if (tab == i) Color.White else Color.White.copy(alpha = 0.5f), fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { tab = i }.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val c = current
                if (c != null) {
                    if (tab == 1) BrushImage(c, mask, brushSize, Modifier.fillMaxSize())
                    else HoldCompare(original, c, Modifier.fillMaxSize())
                }
            }
            AiModeRow(useAi, { useAi = it }, aiLabel = if (tab == 0) "AI 定位水印" else "AI 消除")
            if (tab == 0) {
                DarkLabel(if (useAi) "AI 只在水印区域去除浅色内容 · 去除强度" else "去除强度（数值越小，去除越多浅色内容）")
                Slider(threshold, { threshold = it }, valueRange = 140f..235f, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                DarkLabel("涂抹要消除的水印、文字、杂物或路人 · 笔刷大小")
                Slider(brushSize, { brushSize = it }, valueRange = 8f..80f, modifier = Modifier.padding(horizontal = 16.dp))
                DarkSwitch("文档模式（以纸张底色填充，不使用 AI）", docMode) { docMode = it }
            }
            BottomActions(
                listOf(
                    BarAction("撤销", Icons.AutoMirrored.Outlined.Undo, enabled = history.isNotEmpty() || !mask.isEmpty) {
                        if (!mask.isEmpty) mask.undo() else if (history.isNotEmpty()) history.removeAt(history.lastIndex)
                    },
                    BarAction(if (tab == 0) "一键去水印" else "消除", Icons.Outlined.AutoFixHigh, enabled = current != null && (tab == 0 || !mask.isEmpty)) {
                        val c = current ?: return@BarAction
                        scope.launch {
                            busy = if (tab == 0) "正在去除水印" else "正在智能消除"
                            val out = if (tab == 0) removeWatermark(original ?: c) else erase(c)
                            if (tab == 0) history.clear()
                            history += out
                            mask.clear()
                            busy = null
                        }
                    },
                    BarAction(if (docId != null) "保存到文档" else "保存到相册", Icons.Outlined.Save, enabled = history.isNotEmpty()) {
                        val out = history.lastOrNull() ?: return@BarAction
                        scope.launch {
                            if (docId != null) {
                                withContext(Dispatchers.IO) {
                                    ScanApp.instance.repository.updatePage(docId, index, BitmapIO.saveJpeg(out, BitmapIO.newWorkFile(), 93))
                                }
                                nav.pop()
                            } else {
                                Share.toast(ctx, if (Share.saveBitmap(ctx, out, if (watermark) "去水印" else "消除")) "已保存到相册" else "保存失败")
                            }
                        }
                    },
                ),
                dark = true,
            )
        }
        LoadingOverlay(busy)
    }
}

// ---------------------------------------------------------------- 老照片修复 / 变清晰

@Composable
fun RestoreScreen(image: String, oldPhoto: Boolean) {
    val ctx = LocalContext.current
    val src = rememberBitmap(image, if (oldPhoto) 1800 else 2000)
    var useAi by remember { mutableStateOf(imageAiOnOpen()) }
    var colorize by remember { mutableStateOf(true) }
    val aiResults = remember { mutableStateMapOf<Boolean, Bitmap>() }
    var opts by remember { mutableStateOf(RestoreOptions(upscale = !oldPhoto)) }
    var amount by remember { mutableFloatStateOf(0.6f) }
    var run by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(src, run, useAi, colorize) {
        val s = src ?: return@LaunchedEffect
        if (useAi) {
            val key = !oldPhoto || colorize
            aiResults[key]?.let { result = it; return@LaunchedEffect }
            busy = if (oldPhoto) "AI 正在修复（约 10 秒）" else "AI 正在变清晰（约 30 秒）"
            try {
                val out = if (oldPhoto) CloudVision.edit("restore", s, 2048, EditOptions(colorize = colorize))
                else {
                    // The super-resolution model returns about 1 MP; larger photos keep their size.
                    // The model accepts 512–4096 px per side.
                    val up = CloudVision.edit("upscale", s, 1280, minSide = 512)
                    if (up.width >= s.width) up
                    else withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(up, s.width, s.width * up.height / up.width, true) }
                }
                aiResults[key] = out
                result = out
            } catch (e: ApiException) {
                Share.toast(ctx, aiFallbackMessage(e))
                useAi = false
            }
            busy = null
            return@LaunchedEffect
        }
        busy = if (oldPhoto) "正在修复老照片" else "正在增强清晰度"
        result = withContext(Dispatchers.Default) {
            runCatching {
                if (oldPhoto) ImageTools.restorePhoto(s, opts.copy(sharpenAmount = amount))
                else ImageTools.enhanceClarity(s, amount, opts.upscale)
            }.getOrNull()
        }
        busy = null
    }
    Box(Modifier.fillMaxSize().background(Dark)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(if (oldPhoto) "老照片修复" else "图片变清晰", dark = true)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val s = src; val r = result
                if (s != null && r != null) {
                    // Compare at the same displayed size even if the result was upscaled.
                    SliderCompare(s, r, Modifier.fillMaxSize())
                    Text("拖动对比 · 左原图 / 右效果", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp))
                } else if (s != null) Image(s.asImageBitmap(), null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            }
            AiModeRow(useAi, { useAi = it; run++ }, aiLabel = if (oldPhoto) "AI 修复" else "AI 超分")
            if (useAi) {
                if (oldPhoto) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip("智能上色", colorize, dark = true) { colorize = true }
                        SelectChip("保持原色调", !colorize, dark = true) { colorize = false }
                    }
                    DarkLabel("AI 去除划痕折痕、修复面部细节；切换选项会重新生成并消耗 1 次额度")
                } else {
                    DarkLabel("AI 超分辨率重建细节，去除模糊和噪点")
                }
            } else {
                if (oldPhoto) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip("去噪点", opts.denoise, dark = true) { opts = opts.copy(denoise = !opts.denoise); run++ }
                        SelectChip("修复划痕", opts.scratches, dark = true) { opts = opts.copy(scratches = !opts.scratches); run++ }
                        SelectChip("色彩还原", opts.color, dark = true) { opts = opts.copy(color = !opts.color); run++ }
                        SelectChip("锐化", opts.sharpen, dark = true) { opts = opts.copy(sharpen = !opts.sharpen); run++ }
                        SelectChip("2倍放大", opts.upscale, dark = true) { opts = opts.copy(upscale = !opts.upscale); run++ }
                    }
                } else {
                    DarkSwitch("超分放大（小图放大至2倍）", opts.upscale) { opts = opts.copy(upscale = it); run++ }
                }
                DarkLabel("增强强度")
                Slider(amount, { amount = it }, onValueChangeFinished = { run++ }, valueRange = 0.1f..1f, modifier = Modifier.padding(horizontal = 16.dp))
            }
            BottomActions(
                listOf(
                    BarAction("保存到相册", Icons.Outlined.Download, enabled = result != null) {
                        result?.let { Share.toast(ctx, if (Share.saveBitmap(ctx, it, if (oldPhoto) "修复" else "高清")) "已保存到相册（${it.width}×${it.height}）" else "保存失败") }
                    },
                ),
                dark = true,
            )
        }
        LoadingOverlay(busy)
    }
}
