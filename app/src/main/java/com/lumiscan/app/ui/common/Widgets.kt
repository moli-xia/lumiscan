package com.lumiscan.app.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.ui.pdf.FileIcon
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded-bitmap cache keyed by path, size and modification time. */
object ImageCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun key(path: String, maxSide: Int) = "$path#$maxSide#${File(path).lastModified()}"

    fun get(path: String, maxSide: Int): Bitmap? {
        val k = key(path, maxSide)
        cache.get(k)?.let { if (!it.isRecycled) return it }
        val b = BitmapIO.decode(path, maxSide) ?: return null
        cache.put(k, b)
        return b
    }

    fun clear() = cache.evictAll()
}

@Composable
fun rememberBitmap(path: String?, maxSide: Int = 1600): Bitmap? {
    val state = produceState<Bitmap?>(null, path, maxSide) {
        value = if (path == null) null else withContext(Dispatchers.IO) { runCatching { ImageCache.get(path, maxSide) }.getOrNull() }
    }
    return state.value
}

/**
 * A composition-bound coroutine scope whose failures (out of memory on a huge image, a missing
 * file, a native error) are reported to the user instead of crashing the app. [onError] runs on
 * the main thread, e.g. to clear a loading overlay.
 */
@Composable
fun rememberSafeScope(onError: (Throwable) -> Unit = {}): CoroutineScope {
    val ctx = LocalContext.current.applicationContext
    val base = rememberCoroutineScope()
    val latest = rememberUpdatedState(onError)
    return remember(base) {
        val handler = CoroutineExceptionHandler { _, e ->
            android.util.Log.e("LumiScan", "background task failed", e)
            Handler(Looper.getMainLooper()).post {
                latest.value(e)
                val msg = if (e is OutOfMemoryError) "图片过大，内存不足" else (e.message ?: e.javaClass.simpleName)
                Share.toast(ctx, "处理失败：$msg")
            }
        }
        CoroutineScope(base.coroutineContext + handler)
    }
}

@Composable
fun AppTopBar(
    title: String,
    dark: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalNav.current
    val fg = if (dark) Color.White else Ink
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dark) Color.Black else MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onBack?.invoke() ?: nav.pop() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = fg)
        }
        Text(
            title, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun LoadingOverlay(text: String?) {
    if (text == null) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xEE222222)) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(14.dp))
                Text(text, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Single-line text that shrinks (down to [minSp]) instead of wrapping or clipping, so labels
 * survive large system font sizes and narrow screens.
 */
@Composable
fun FitText(
    text: String,
    maxSp: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    minSp: Float = 8f,
    textAlign: TextAlign? = null,
) {
    Text(
        text, modifier, color = color, fontWeight = fontWeight, textAlign = textAlign,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minSp.sp, maxSp.sp, 0.5.sp),
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(23.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { FitText(text, 15f, fontWeight = FontWeight.Medium) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(46.dp), shape = RoundedCornerShape(23.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFF3FF), contentColor = Brand),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { FitText(text, 15f, fontWeight = FontWeight.Medium) }
}

@Composable
fun SelectChip(label: String, selected: Boolean, dark: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        selected -> Brand
        dark -> Color(0xFF2C2C2E)
        else -> Color(0xFFF0F2F7)
    }
    val fg = if (selected || dark) Color.White else Ink
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = fg, fontSize = 13.sp, maxLines = 1, softWrap = false) }
}

data class BarAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/** Icon-over-label action row used at the bottom of most screens. */
@Composable
fun BottomActions(actions: List<BarAction>, dark: Boolean = false, modifier: Modifier = Modifier, insetBottom: Boolean = true) {
    val fg = if (dark) Color.White else Ink
    Row(
        modifier
            .fillMaxWidth()
            .background(if (dark) Color(0xFF111111) else Color.White)
            .then(if (insetBottom) Modifier.navigationBarsPadding() else Modifier)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        actions.forEach { a ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = a.enabled, onClick = a.onClick)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, a.label, tint = if (a.enabled) fg else fg.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                FitText(a.label, 11f, Modifier.padding(horizontal = 2.dp), color = if (a.enabled) fg else fg.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    placeholder: String = "",
    password: Boolean = false,
    confirmText: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text(placeholder, color = Sub) },
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmText: String = "确定", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.2f MB".format(bytes / 1024f / 1024f)
}

/** Card listing produced files with open / share / save-to-phone actions. */
@Composable
fun ResultFiles(files: List<File>, modifier: Modifier = Modifier, onOpenPdf: ((File) -> Unit)? = null) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    Column(modifier) {
        files.forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileIcon(f, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink)
                    Text(formatSize(f.length()), fontSize = 12.sp, color = Sub)
                }
                IconButton(onClick = {
                    if (f.extension.equals("pdf", true)) (onOpenPdf ?: { nav.push(com.lumiscan.app.nav.Route.PdfView(it.absolutePath)) })(f)
                    else Share.open(ctx, f)
                }) { Icon(Icons.Outlined.OpenInNew, "打开", tint = Brand) }
                IconButton(onClick = { Share.share(ctx, listOf(f)) }) { Icon(Icons.Outlined.Share, "分享", tint = Brand) }
                IconButton(onClick = { Share.saveAll(ctx, listOf(f)) }) { Icon(Icons.Outlined.Download, "保存", tint = Brand) }
            }
        }
    }
}

/** The one "AI" mark used across the app for features backed by cloud models. */
@Composable
fun AiBadge(modifier: Modifier = Modifier, label: String = "AI") {
    Text(
        label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        lineHeight = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFF2F6BFF))))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun Dot(color: Color, size: Dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = modifier.padding(vertical = 8.dp))
}
