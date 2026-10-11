package com.lumiscan.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Timer10
import androidx.compose.material.icons.outlined.Timer3
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumiscan.app.ScanApp
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.imaging.IdCardType
import com.lumiscan.app.imaging.Pt
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.scan.ScanSession
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.common.ConfirmDialog
import com.lumiscan.app.ui.common.FitText
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.SelectChip
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val cameraModes = listOf(
    ScanMode.SOLVE, ScanMode.TRANSLATE, ScanMode.OCR, ScanMode.DOC, ScanMode.AI_DOC, ScanMode.WORD, ScanMode.EXCEL, ScanMode.ID_CARD,
    ScanMode.ID_PHOTO, ScanMode.HANDWRITING, ScanMode.EXAM, ScanMode.COUNT, ScanMode.WATERMARK,
    ScanMode.ERASE, ScanMode.OLD_PHOTO, ScanMode.SHARPEN,
)

private enum class Flash(val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OFF(Icons.Outlined.FlashOff), AUTO(Icons.Outlined.FlashAuto), ON(Icons.Outlined.FlashOn), TORCH(Icons.Outlined.FlashlightOn)
}

@Composable
fun CameraScreen(initialMode: ScanMode, appendTo: String?) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val owner = LocalLifecycleOwner.current
    val settings = ScanApp.instance.settings
    val pages = ScanSession.pages
    val mode = ScanSession.mode
    val modeNow by rememberUpdatedState(mode)

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    // "Don't ask again": the system dialog no longer appears, so send the user to app settings.
    var blocked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        val activity = ctx as? android.app.Activity
        blocked = !ok && activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (ScanSession.mode != initialMode && pages.isEmpty()) ScanSession.start(initialMode, appendTo)
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    // Permission may be granted in system settings while we are in the background.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (!granted && hasPermission()) granted = true
        onPauseOrDispose { }
    }

    // Selfie-style tools default to the front camera; the user can switch either way.
    var front by remember { mutableStateOf(mode.prefersFront) }
    LaunchedEffect(mode) { front = mode.prefersFront }
    var hasFront by remember { mutableStateOf(false) }
    var hasBack by remember { mutableStateOf(true) }
    var hasFlash by remember { mutableStateOf(true) }
    var timer by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomRange by remember { mutableStateOf(1f..1f) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var liveQuad by remember { mutableStateOf<List<Pt>?>(null) }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var grid by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    var capturing by remember { mutableStateOf(false) }
    val shutterFlash = remember { Animatable(0f) }
    val shutterSound = remember { android.media.MediaActionSound().apply { load(android.media.MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(granted, front) {
        if (!granted) return@DisposableEffect onDispose { }
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        var lastRun = 0L
        var misses = 0
        // A different outline (not jitter) is shown once two frames in a row agree on it.
        var pending: List<Pt>? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            hasFront = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            hasBack = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            // Fall back to whichever camera exists (tablets/emulators may have only one).
            val useFront = if (front) hasFront || !hasBack else !hasBack && hasFront
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // ~12MP is plenty for documents; full 50–200MP sensor output only costs memory and time.
            val captureRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val capture = ImageCapture.Builder()
                .setResolutionSelector(captureRes)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val now = System.currentTimeMillis()
                if (modeNow.crop && now - lastRun > 120) {
                    lastRun = now
                    runCatching {
                        val bmp = BitmapIO.rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
                        val q = ScanSession.detectEdges(bmp)
                        if (q != null) {
                            misses = 0
                            // The front preview is mirrored, the analysis frame is not.
                            val n = q.points.map { Pt(if (useFront) 1f - it.x / bmp.width else it.x / bmp.width, it.y / bmp.height) }
                                .let { if (useFront) listOf(it[1], it[0], it[3], it[2]) else it }
                            val prev = liveQuad
                            fun gap(a: List<Pt>, b: List<Pt>) = a.indices.maxOf { kotlin.math.hypot(a[it].x - b[it].x, a[it].y - b[it].y) }
                            liveQuad = when {
                                prev == null -> n
                                gap(prev, n) < 0.12f -> { pending = null; n.mapIndexed { i, pt -> Pt(prev[i].x * 0.4f + pt.x * 0.6f, prev[i].y * 0.4f + pt.y * 0.6f) } }
                                pending?.let { gap(it, n) < 0.05f } == true -> { pending = null; n }
                                else -> { pending = n; prev }
                            }
                        } else if (++misses > 3) liveQuad = null
                        bmp.recycle()
                    }
                } else if (!modeNow.crop) liveQuad = null
                proxy.close()
            }
            runCatching {
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, selector, preview, capture, analysis)
                camera = cam
                imageCapture = capture
                hasFlash = cam.cameraInfo.hasFlashUnit()
                if (!hasFlash) flash = Flash.OFF
                zoom = 1f
                cam.cameraInfo.zoomState.value?.let { zoomRange = it.minZoomRatio..it.maxZoomRatio }
            }.onFailure { Share.toast(ctx, "相机启动失败：${it.message}") }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    fun proceedIfDone() {
        if (!mode.multi || pages.size >= ScanSession.requiredCount()) ScanSession.proceed(nav)
    }

    val pickMulti = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在导入"
            val limit = ScanSession.requiredCount() - pages.size
            uris.take(limit.coerceAtLeast(1)).forEach { u ->
                withContext(Dispatchers.IO) { BitmapIO.importUri(ctx, u) }?.let { ScanSession.add(it, settings.autoCrop && mode.crop) }
            }
            busy = null
            if (mode == ScanMode.ID_CARD) proceedIfDone() else ScanSession.proceed(nav)
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在导入"
            withContext(Dispatchers.IO) { BitmapIO.importUri(ctx, uri) }?.let { ScanSession.add(it, settings.autoCrop && mode.crop) }
            busy = null
            proceedIfDone()
        }
    }

    fun shoot() {
        val ic = imageCapture ?: return Share.toast(ctx, "相机未就绪")
        if (capturing) return
        capturing = true
        ic.flashMode = when (flash) { Flash.ON -> ImageCapture.FLASH_MODE_ON; Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO; else -> ImageCapture.FLASH_MODE_OFF }
        scope.launch { shutterFlash.snapTo(0.7f); shutterFlash.animateTo(0f, tween(250)) }
        val audio = ctx.getSystemService(android.media.AudioManager::class.java)
        if (audio?.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
        val file = BitmapIO.newWorkFile()
        // Save selfies the way others see you (not mirrored like the preview) — right for ID photos.
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        busy = if (mode.multi) null else "处理中"
                        ScanSession.add(file, settings.autoCrop && mode.crop, fromCamera = true)
                        file.delete()
                        busy = null
                        capturing = false
                        proceedIfDone()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Share.toast(ctx, "拍照失败：${exception.message}")
                }
            },
        )
    }

    /** Shutter press: immediate, or after the self-timer; pressing again cancels a countdown. */
    fun press() {
        if (!granted || capturing) return
        if (countdown > 0) { countdown = 0; return }
        if (timer == 0) { shoot(); return }
        scope.launch {
            countdown = timer
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                if (countdown == 0) return@launch // cancelled
                countdown--
            }
            shoot()
        }
    }
    val pressNow by rememberUpdatedState(::press)
    DisposableEffect(Unit) {
        ShutterKeys.onShutter = { pressNow() }
        onDispose { ShutterKeys.onShutter = null }
    }

    var confirmExit by remember { mutableStateOf(false) }
    BackHandler(enabled = pages.isNotEmpty()) { confirmExit = true }
    if (confirmExit) {
        ConfirmDialog("放弃拍摄", "已拍摄 ${pages.size} 张，退出后将不会保存，确定放弃吗？", "放弃", onDismiss = { confirmExit = false }) {
            ScanSession.pages.clear(); nav.pop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (pages.isNotEmpty()) confirmExit = true else nav.pop() }) { Icon(Icons.Outlined.Close, "关闭", tint = Color.White) }
                Text(mode.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
                    Share.toast(ctx, if (timer == 0) "定时拍摄已关闭" else "定时 ${timer} 秒")
                }) {
                    Icon(when (timer) { 3 -> Icons.Outlined.Timer3; 10 -> Icons.Outlined.Timer10; else -> Icons.Outlined.TimerOff }, "定时拍摄", tint = if (timer > 0) Color(0xFFFFD54F) else Color.White)
                }
                IconButton(onClick = { grid = !grid }) { Icon(Icons.Outlined.GridOn, "网格", tint = if (grid) Brand else Color.White) }
                if (hasFlash) {
                    IconButton(onClick = {
                        flash = Flash.entries[(flash.ordinal + 1) % Flash.entries.size]
                        camera?.cameraControl?.enableTorch(flash == Flash.TORCH)
                    }) { Icon(flash.icon, "闪光灯", tint = if (flash == Flash.OFF) Color.White else Color(0xFFFFD54F)) }
                }
                if (hasFront && hasBack && pages.isNotEmpty() && mode.multi) {
                    IconButton(onClick = { front = !front }) { Icon(Icons.Outlined.Cameraswitch, "切换摄像头", tint = Color.White) }
                }
            }
            if (mode == ScanMode.ID_CARD) {
                LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                    itemsIndexed(IdCardType.entries) { _, t ->
                        SelectChip(t.label, ScanSession.idType == t, dark = true) {
                            if (pages.isEmpty()) ScanSession.idType = t else Share.toast(ctx, "请先完成当前证件的拍摄")
                        }
                    }
                }
            }
            // Preview: 3:4, as large as the space between the bars allows (never squeezes the controls).
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(3f / 4f)) {
                if (granted) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("需要相机权限才能拍摄", color = Color.White)
                        TextButton(onClick = {
                            if (blocked) {
                                ctx.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)),
                                )
                            } else permission.launch(Manifest.permission.CAMERA)
                        }) { Text(if (blocked) "去设置开启相机权限" else "授予权限") }
                        Text("也可以点击左下角从相册导入", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
                Overlay(mode, liveQuad, grid)
                // Pinch to zoom, tap to focus.
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(camera) {
                            detectTransformGestures { _, _, z, _ ->
                                val cam = camera ?: return@detectTransformGestures
                                zoom = (zoom * z).coerceIn(zoomRange.start, zoomRange.endInclusive)
                                cam.cameraControl.setZoomRatio(zoom)
                            }
                        }
                        .pointerInput(camera) {
                            detectTapGestures { o ->
                                val cam = camera ?: return@detectTapGestures
                                val point = previewView.meteringPointFactory.createPoint(o.x, o.y)
                                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                                focusAt = o
                            }
                        },
                )
                focusAt?.let { at ->
                    LaunchedEffect(at) { kotlinx.coroutines.delay(900); focusAt = null }
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color(0xFFFFD54F), 34.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                    }
                }
                if (zoom > 1.01f) {
                    Text(
                        "%.1fx".format(zoom), color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.5f)).clickable { zoom = 1f; camera?.cameraControl?.setZoomRatio(1f) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (countdown > 0) {
                    Text("$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                }
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = shutterFlash.value)))
                val hint = when (mode) {
                    ScanMode.ID_CARD -> {
                        val t = ScanSession.idType
                        val side = t.sides.getOrNull(pages.size) ?: t.sides.last()
                        "请拍摄${t.label}「$side」(${(pages.size + 1).coerceAtMost(t.sides.size)}/${t.sides.size})"
                    }
                    else -> mode.hint
                }
                if (hint.isNotEmpty()) {
                    Text(
                        hint, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                            .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            }
            // Mode selector
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (cameraModes.indexOf(mode) - 2).coerceAtLeast(0))
            LazyRow(state = listState, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(cameraModes) { _, m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                        if (m == mode) return@clickable
                        if (pages.isNotEmpty()) Share.toast(ctx, "已拍摄 ${pages.size} 张，请先完成")
                        else ScanSession.start(m, appendTo)
                    }) {
                        Text(m.label, color = if (m == mode) Color.White else Color.White.copy(alpha = 0.55f), fontSize = 14.sp, fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (m == mode) Brand else Color.Transparent))
                    }
                }
            }
            // Controls
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable {
                    val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (mode.multi && ScanSession.requiredCount() - pages.size > 1) pickMulti.launch(req) else pickOne.launch(req)
                }) {
                    Icon(Icons.Outlined.PhotoLibrary, "相册", tint = Color.White, modifier = Modifier.size(28.dp))
                    FitText("导入图片", 11f, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                        .background(if (capturing || countdown > 0) Color.White.copy(alpha = 0.5f) else Color.White).clickable(enabled = granted) { press() },
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                    if (pages.isNotEmpty() && mode.multi) {
                        val last = rememberBitmap(pages.last().source, 200)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { ScanSession.proceed(nav) }) {
                            Box {
                                Box(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray).border(2.dp, Color.White, RoundedCornerShape(6.dp))) {
                                    if (last != null) Image(last.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text(
                                    "${pages.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp).clip(CircleShape).background(Brand).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                            Text("完成", color = Color.White, fontSize = 11.sp)
                        }
                    } else if (hasFront && hasBack) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { front = !front }) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Cameraswitch, "切换摄像头", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            FitText(if (front) "前置" else "后置", 11f, color = Color.White)
                        }
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
}

@Composable
private fun Overlay(mode: ScanMode, quad: List<Pt>?, grid: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (grid) {
            val c = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                drawLine(c, Offset(w * i / 3, 0f), Offset(w * i / 3, h), 1f)
                drawLine(c, Offset(0f, h * i / 3), Offset(w, h * i / 3), 1f)
            }
        }
        when (mode) {
            ScanMode.ID_CARD -> {
                val (l, t, r, b) = ScanSession.idType.guide()
                drawRoundRect(Color.White, Offset(l * w, t * h), GSize((r - l) * w, (b - t) * h), CornerRadius(16f), style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f))))
            }
            ScanMode.ID_PHOTO -> {
                val cx = w / 2; val cy = h * 0.38f
                val ow = w * 0.42f; val oh = h * 0.36f
                drawOval(Color.White, Offset(cx - ow / 2, cy - oh / 2), GSize(ow, oh), style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f))))
                val sh = Path().apply {
                    moveTo(w * 0.1f, h * 0.95f)
                    cubicTo(w * 0.14f, h * 0.68f, w * 0.3f, h * 0.62f, cx - ow * 0.25f, cy + oh * 0.48f)
                    moveTo(w * 0.9f, h * 0.95f)
                    cubicTo(w * 0.86f, h * 0.68f, w * 0.7f, h * 0.62f, cx + ow * 0.25f, cy + oh * 0.48f)
                }
                drawPath(sh, Color.White, style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f))))
            }
            else -> Unit
        }
        if (quad != null && mode.crop) {
            val path = Path().apply {
                moveTo(quad[0].x * w, quad[0].y * h)
                quad.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                close()
            }
            drawPath(path, Brand.copy(alpha = 0.18f))
            drawPath(path, Brand, style = Stroke(3.dp.toPx()))
            quad.forEach { drawCircle(Brand, 5.dp.toPx(), Offset(it.x * w, it.y * h)) }
        }
    }
}
