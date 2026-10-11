package com.lumiscan.app.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.imaging.Pt
import com.lumiscan.app.imaging.Quad
import com.lumiscan.app.ui.theme.Brand
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where an image of [imgW]×[imgH] lands when fitted (contain) into a box. */
data class Fit(val left: Float, val top: Float, val scale: Float, val imgW: Int, val imgH: Int) {
    val width get() = imgW * scale
    val height get() = imgH * scale
    fun toScreen(p: Pt) = Offset(left + p.x * scale, top + p.y * scale)
    fun toImage(o: Offset) = Pt((o.x - left) / scale, (o.y - top) / scale)

    companion object {
        fun of(imgW: Int, imgH: Int, box: IntSize, pad: Float = 0f): Fit {
            if (imgW <= 0 || imgH <= 0 || box.width <= 0) return Fit(0f, 0f, 1f, max(imgW, 1), max(imgH, 1))
            val s = min((box.width - 2 * pad) / imgW, (box.height - 2 * pad) / imgH)
            return Fit((box.width - imgW * s) / 2, (box.height - imgH * s) / 2, s, imgW, imgH)
        }
    }
}

private fun DrawScope.drawFitted(img: ImageBitmap, fit: Fit) {
    drawImage(
        img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()), dstSize = IntSize(fit.width.toInt(), fit.height.toInt()),
    )
}

/**
 * Crop editor: draggable corners and edges over the photo, outside area dimmed, with a
 * magnifier loupe while dragging. [quad] is in [imgW]×[imgH] coordinates ([bitmap] may be smaller).
 */
@Composable
fun QuadEditor(bitmap: Bitmap, imgW: Int, imgH: Int, quad: Quad, onChange: (Quad) -> Unit, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val pad = with(density) { 24.dp.toPx() }
    val fit = remember(box, imgW, imgH) { Fit.of(imgW, imgH, box, pad) }
    val cur by rememberUpdatedState(quad)
    val change by rememberUpdatedState(onChange)
    var active by remember { mutableIntStateOf(-1) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val hit = with(density) { 40.dp.toPx() }
    val valid = quad.isConvex()

    Canvas(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(fit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val handles = handlePoints(cur).map { fit.toScreen(it) }
                        val best = handles.indices.minByOrNull { hypot(handles[it].x - o.x, handles[it].y - o.y) } ?: -1
                        active = if (best >= 0 && hypot(handles[best].x - o.x, handles[best].y - o.y) < hit) best else -1
                        touch = o
                    },
                    onDrag = { ch, d ->
                        ch.consume()
                        touch = ch.position
                        if (active < 0) return@detectDragGestures
                        val dx = d.x / fit.scale; val dy = d.y / fit.scale
                        val q = cur
                        fun mv(p: Pt) = Pt((p.x + dx).coerceIn(0f, imgW.toFloat()), (p.y + dy).coerceIn(0f, imgH.toFloat()))
                        val nq = when (active) {
                            in 0..3 -> q.with(active, mv(q.points[active]))
                            else -> {
                                val a = active - 4; val b = (a + 1) % 4
                                q.with(a, mv(q.points[a])).with(b, mv(q.points[b]))
                            }
                        }
                        change(nq)
                    },
                    onDragEnd = { active = -1; touch = null },
                    onDragCancel = { active = -1; touch = null },
                )
            },
    ) {
        drawFitted(image, fit)
        val pts = quad.points.map { fit.toScreen(it) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        clipPath(path, ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.5f), Offset(fit.left, fit.top), Size(fit.width, fit.height))
        }
        val lineColor = if (valid) Brand else Color(0xFFF04438)
        drawPath(path, Brand.copy(alpha = 0.12f))
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        handlePoints(quad).forEachIndexed { i, p ->
            val s = fit.toScreen(p)
            if (i < 4) {
                drawCircle(Color.White, 10.dp.toPx(), s)
                drawCircle(lineColor, 10.dp.toPx(), s, style = Stroke(3.dp.toPx()))
            } else {
                drawCircle(Color.White, 6.dp.toPx(), s)
                drawCircle(lineColor, 6.dp.toPx(), s, style = Stroke(2.dp.toPx()))
            }
        }
        val t = touch
        if (t != null && active >= 0) {
            val focus = fit.toScreen(handlePoints(quad)[active])
            val r = 56.dp.toPx()
            val m = Offset(if (focus.x < size.width / 2) size.width - r - 12.dp.toPx() else r + 12.dp.toPx(), r + 12.dp.toPx())
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(m, r)) }
            clipPath(circle) {
                drawRect(Color.Black, m - Offset(r, r), Size(2 * r, 2 * r))
                withTransform({ translate(m.x - focus.x, m.y - focus.y); scale(2.5f, 2.5f, focus) }) {
                    drawFitted(image, fit)
                    drawPath(path, lineColor, style = Stroke(width = 0.8.dp.toPx()))
                }
            }
            drawCircle(Color.White, r, m, style = Stroke(2.dp.toPx()))
            drawLine(Brand, m - Offset(10.dp.toPx(), 0f), m + Offset(10.dp.toPx(), 0f), 1.5.dp.toPx())
            drawLine(Brand, m - Offset(0f, 10.dp.toPx()), m + Offset(0f, 10.dp.toPx()), 1.5.dp.toPx())
        }
    }
}

private fun handlePoints(q: Quad): List<Pt> {
    val p = q.points
    return p + (0 until 4).map { i -> val a = p[i]; val b = p[(i + 1) % 4]; Pt((a.x + b.x) / 2, (a.y + b.y) / 2) }
}

/** Shows [after]; pressing and holding shows [before] (按住对比). */
@Composable
fun HoldCompare(before: Bitmap?, after: Bitmap?, modifier: Modifier = Modifier, dark: Boolean = true) {
    var showBefore by remember { mutableStateOf(false) }
    val shown = if (showBefore) before else after
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(onPress = { showBefore = true; tryAwaitRelease(); showBefore = false })
        },
    ) {
        if (shown != null) {
            // Leave room at the top for the hint pill so it never covers the picture.
            val top = if (before != null && after != null) 44.dp else 12.dp
            Image(shown.asImageBitmap(), null, Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = top), contentScale = ContentScale.Fit)
        }
        if (before != null && after != null) {
            Text(
                if (showBefore) "原图" else "按住查看原图",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = if (dark) 0.5f else 0.35f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** Before/after view with a draggable split line. */
@Composable
fun SliderCompare(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    val a = remember(after) { after.asImageBitmap() }
    val b = remember(before) { before.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var split by remember { mutableFloatStateOf(0.5f) }
    val fit = remember(box, after) { Fit.of(after.width, after.height, box, 12f) }
    Canvas(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(fit) {
                detectDragGestures { ch, _ ->
                    ch.consume()
                    split = ((ch.position.x - fit.left) / fit.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(fit) {
                detectTapGestures { o -> split = ((o.x - fit.left) / fit.width).coerceIn(0f, 1f) }
            },
    ) {
        drawFitted(a, fit)
        val x = fit.left + fit.width * split
        clipRect(left = fit.left, top = fit.top, right = x, bottom = fit.top + fit.height) {
            drawImage(b, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
                dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()), dstSize = IntSize(fit.width.toInt(), fit.height.toInt()))
        }
        drawLine(Color.White, Offset(x, fit.top), Offset(x, fit.top + fit.height), 2.dp.toPx())
        drawCircle(Color.White, 14.dp.toPx(), Offset(x, fit.top + fit.height / 2))
        drawCircle(Brand, 14.dp.toPx(), Offset(x, fit.top + fit.height / 2), style = Stroke(2.dp.toPx()))
    }
}

/**
 * Raw single-finger stroke tracking for drawing: unlike drag detection it starts at the
 * touch-down point (no touch-slop gap) and a tap yields a one-point stroke.
 */
fun Modifier.inkInput(key: Any?, onStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            onStart(down.position)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.positionChanged()) { onMove(change.position); change.consume() }
            }
            onEnd()
        }
    }

class MaskStroke(val points: List<Pt>, val width: Float)

/** Brush strokes painted over an image, stored in image pixel coordinates. */
class MaskState {
    val strokes = mutableStateListOf<MaskStroke>()
    val current = mutableStateListOf<Pt>()
    val isEmpty get() = strokes.isEmpty()
    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
    fun clear() { strokes.clear(); current.clear() }

    /** White strokes on black at the image's resolution. */
    fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = ACanvas(bmp)
        c.drawColor(AColor.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            paint.strokeWidth = s.width
            if (s.points.size == 1) {
                paint.style = Paint.Style.FILL
                c.drawCircle(s.points[0].x, s.points[0].y, s.width / 2, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            val path = android.graphics.Path()
            path.moveTo(s.points[0].x, s.points[0].y)
            s.points.drop(1).forEach { path.lineTo(it.x, it.y) }
            c.drawPath(path, paint)
        }
        return bmp
    }
}

/**
 * Image with a paint brush layer. [brushDp] is the on-screen brush diameter.
 * When [paintEnabled] is false the image can be pinch-zoomed instead.
 */
@Composable
fun BrushImage(bitmap: Bitmap, mask: MaskState, brushDp: Float, modifier: Modifier = Modifier, color: Color = Color(0x99FF3B30)) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(box, bitmap) { Fit.of(bitmap.width, bitmap.height, box, 12f) }
    val density = LocalDensity.current
    val brushPx = with(density) { brushDp.dp.toPx() }
    val brushNow by rememberUpdatedState(brushPx)
    Canvas(
        modifier
            .onSizeChanged { box = it }
            .inkInput(
                fit,
                onStart = { o -> mask.current.clear(); mask.current += fit.toImage(o) },
                onMove = { o -> mask.current += fit.toImage(o) },
                onEnd = { mask.strokes += MaskStroke(mask.current.toList(), brushNow / fit.scale); mask.current.clear() },
            ),
    ) {
        drawFitted(image, fit)
        clipRect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height) {
            fun drawStroke(points: List<Pt>, w: Float) {
                if (points.isEmpty()) return
                if (points.size == 1) {
                    drawCircle(color, w / 2, fit.toScreen(points[0])); return
                }
                val path = Path()
                val s0 = fit.toScreen(points[0]); path.moveTo(s0.x, s0.y)
                points.drop(1).forEach { val s = fit.toScreen(it); path.lineTo(s.x, s.y) }
                drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            mask.strokes.forEach { drawStroke(it.points, it.width * fit.scale) }
            drawStroke(mask.current, brushPx)
        }
    }
}

/** Pinch-zoom / double-tap image that leaves single-finger swipes to a parent pager when not zoomed. */
@Composable
fun ZoomableImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1.01f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = size.width * (scale - 1) / 2; val maxY = size.height * (scale - 1) / 2
                            val p = offset + event.calculatePan()
                            offset = Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
    ) {
        Image(
            bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}
