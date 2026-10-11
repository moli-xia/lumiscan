package com.lumiscan.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.ui.pdf.PdfToolKind

sealed interface Route {
    data object Main : Route
    data object AllTools : Route
    data class Camera(val mode: ScanMode, val appendTo: String? = null) : Route
    data object Crop : Route
    data object Filters : Route
    data class Doc(val id: String) : Route
    data class DocPages(val id: String, val start: Int) : Route
    data class PageEdit(val id: String, val index: Int) : Route
    data class Ocr(val images: List<String>, val docId: String? = null) : Route
    data class Word(val images: List<String>) : Route
    data class Excel(val images: List<String>) : Route
    data class Translate(val image: String) : Route
    data class Count(val image: String) : Route
    data class IdPhoto(val image: String) : Route
    data class Handwriting(val images: List<String>, val exam: Boolean) : Route
    data class Erase(val image: String, val watermark: Boolean, val docId: String? = null, val index: Int = -1) : Route
    data class Restore(val image: String, val oldPhoto: Boolean) : Route
    data class Sign(val docId: String, val index: Int) : Route
    data object Signatures : Route
    data class PdfTool(val tool: PdfToolKind, val initial: String? = null) : Route
    data class PdfView(val path: String) : Route
    data object Account : Route
    data object Feedback : Route
    data class Policy(val kind: String) : Route
    /** AI 文档助手 over [text], or over the OCR of [images] when [text] is null. */
    data class AiAssist(val text: String?, val images: List<String> = emptyList()) : Route
    data class Solve(val image: String) : Route
}

/** One back-stack entry; [store] keeps screen state alive while the entry is on the stack. */
class Entry(val route: Route) {
    val id: Long = nextId++
    internal val store = HashMap<String, Any?>()

    private companion object {
        var nextId = 0L
    }
}

/**
 * Minimal back stack; screens read [current] and call push/pop. Lives in the Application so it
 * survives activity recreation (font size / theme / window changes).
 */
class Navigator {
    val stack = mutableStateListOf(Entry(Route.Main))
    val current: Route get() = stack.last().route
    val top: Entry get() = stack.last()

    fun push(r: Route) {
        stack.add(Entry(r))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replace(r: Route) {
        stack[stack.lastIndex] = Entry(r)
    }

    /** Clears everything above home, then pushes [routes]. */
    fun reset(vararg routes: Route) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        routes.forEach { push(it) }
    }

    /** Pops until [predicate] matches the top (or only home is left). */
    fun popTo(predicate: (Route) -> Boolean) {
        while (stack.size > 1 && !predicate(stack.last().route)) stack.removeAt(stack.lastIndex)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/** The back-stack entry a piece of UI belongs to (screens stay composed briefly while animating out). */
val LocalEntry = staticCompositionLocalOf<Entry?> { null }

/**
 * Like `remember`, but the value lives as long as the screen's back-stack entry: it survives
 * navigating to a sub-page and back, where `remember` would start over.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> retain(key: String, init: () -> T): T {
    val entry = LocalEntry.current ?: return remember { init() }
    return entry.store.getOrPut(key, init) as T
}

/** Leaves a screen whose data is gone — but only while it is still the visible entry. */
@Composable
fun LeaveScreen() {
    val nav = LocalNav.current
    val me = LocalEntry.current
    LaunchedEffect(Unit) { if (me == null || nav.top === me) nav.pop() }
}
