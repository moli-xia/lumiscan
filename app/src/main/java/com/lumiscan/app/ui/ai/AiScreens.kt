package com.lumiscan.app.ui.ai

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.remote.AiMessage
import com.lumiscan.app.data.remote.AiRequest
import com.lumiscan.app.data.remote.Api
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.data.remote.QuotaInfo
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.ml.CloudVision
import com.lumiscan.app.ml.OcrEngine
import com.lumiscan.app.ml.TranslatorEngine
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.retain
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.ui.account.dailyText
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.rememberBitmap
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.home.Tools
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.BrandSoft
import com.lumiscan.app.ui.theme.Danger
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One request/answer pair in an AI conversation; [typed] marks a question the user wrote. */
class AiTurn(val feature: String, val title: String, val question: String?, val lang: String? = null, val typed: Boolean = false) {
    var answer by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    val loading: Boolean get() = answer == null && error == null
}

@Composable
private fun QuotaChip(q: QuotaInfo?) {
    q ?: return
    Text(
        "今日剩余 ${q.dailyText()}", fontSize = 12.sp, color = Brand,
        modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(10.dp)).background(BrandSoft).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** AI 文档助手: summary, OCR clean-up, translation and Q&A over a document's text. */
@Composable
fun AiAssistScreen(initialText: String?, images: List<String>) {
    val ctx = LocalContext.current
    val account = ScanApp.instance.account
    val quota = account.profile?.quota
    var doc by retain("doc") { mutableStateOf(initialText) }
    val turns = retain("turns") { mutableStateListOf<AiTurn>() }
    var input by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    val listState = rememberLazyListState()

    LaunchedEffect(images) {
        if (doc != null || images.isEmpty()) return@LaunchedEffect
        val parts = mutableListOf<String>()
        var notice: String? = null
        images.forEachIndexed { i, p ->
            busy = if (images.size > 1) "AI 正在识别文字 ${i + 1}/${images.size}" else "AI 正在识别文字"
            val cloud = if (notice == null) {
                try { CloudVision.ocrTextFile(p) } catch (e: ApiException) { notice = e.message; null }
            } else null
            parts += cloud ?: withContext(Dispatchers.Default) { runCatching { OcrEngine.recognizeFile(p)?.paragraphs }.getOrNull().orEmpty() }
        }
        busy = null
        notice?.let { Share.toast(ctx, "AI 识别不可用（$it），已使用本地识别") }
        doc = parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    val text = doc.orEmpty()
    val limit = quota?.maxInputChars ?: -1
    // The question and earlier turns count towards the group's per-request limit; the document is trimmed to fit.
    fun docFor(extra: Int) = if (limit < 0) text else text.take((limit - extra).coerceAtLeast(200))
    val trimmed = limit in 0 until text.length + 100

    fun run(feature: String, title: String, question: String? = null, lang: String? = null) {
        if (text.isBlank()) {
            Share.toast(ctx, if (doc == null) "正在识别文字，请稍候" else "没有识别到文字")
            return
        }
        val history = if (feature == "qa") {
            turns.filter { it.feature == "qa" && it.answer != null }.takeLast(4)
                .flatMap { listOf(AiMessage("user", it.question.orEmpty()), AiMessage("assistant", it.answer!!)) }
        } else emptyList()
        val extra = (question?.length ?: 0) + history.sumOf { it.content.length } + 20
        val turn = AiTurn(feature, title, question, lang, typed = title == "提问")
        turns += turn
        scope.launch {
            listState.animateScrollToItem(turns.size + 1)
            try {
                val r = Api.ai(AiRequest(feature, docFor(extra), question, lang, history = history))
                account.updateQuota(r.quota)
                turn.answer = r.content
            } catch (e: ApiException) {
                turn.error = e.message
            }
            listState.animateScrollToItem(turns.size + 1)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppTopBar("AI 文档助手", actions = { QuotaChip(quota) })
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("doc") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("文档内容", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                            Text("${text.count { !it.isWhitespace() }} 字", fontSize = 12.sp, color = Sub)
                        }
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                text.ifBlank { if (doc == null) "正在识别…" else "没有识别到文字" },
                                fontSize = 14.sp, lineHeight = 22.sp, color = if (text.isBlank()) Sub else Ink,
                                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (text.length > 120) {
                            Text(if (expanded) "收起" else "展开全文", color = Brand, fontSize = 13.sp, modifier = Modifier.clickable { expanded = !expanded }.padding(top = 6.dp))
                        }
                        if (trimmed) {
                            Text("文档较长，超出当前分组单次 $limit 字的上限，AI 只会读取前面的部分。", fontSize = 12.sp, color = Color(0xFFB54708), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                item("actions") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip("总结要点", Icons.Outlined.Summarize) { run("summary", "总结要点") }
                        ActionChip("纠错润色", Icons.Outlined.Spellcheck) { run("polish", "纠错润色") }
                        Box {
                            ActionChip("翻译", Icons.Outlined.Translate) { langMenu = true }
                            DropdownMenu(langMenu, { langMenu = false }) {
                                TranslatorEngine.languages.forEach { l ->
                                    DropdownMenuItem({ Text("翻译为${l.label}") }, { langMenu = false; run("translate", "翻译为${l.label}", lang = l.label) })
                                }
                            }
                        }
                        ActionChip("提炼待办", Icons.Outlined.EditNote) { run("qa", "提炼待办", "请列出文档中需要执行的事项、截止时间和负责人（如有）。") }
                    }
                }
                items(turns, key = { System.identityHashCode(it) }) { t ->
                    TurnCard(
                        t,
                        onRetry = { turns.remove(t); run(t.feature, t.title, t.question, t.lang) },
                        onReplace = if (t.feature == "polish") ({ a: String -> doc = a; Share.toast(ctx, "已用润色后的文本替换文档内容") }) else null,
                    )
                }
                if (turns.isNotEmpty()) item("note") { Disclaimer() }
            }
            InputBar(input, { input = it.take(1000) }, "就文档内容提问，例如：合同的付款条件是什么？") {
                val q = input.trim()
                if (q.isNotEmpty()) {
                    input = ""
                    run("qa", "提问", q)
                }
            }
        }
        LoadingOverlay(busy)
    }
}

/** 拍题解答: sends the photo to the vision model, then allows follow-up questions. */
@Composable
fun SolveScreen(image: String) {
    val nav = LocalNav.current
    val account = ScanApp.instance.account
    val turns = retain("turns") { mutableStateListOf<AiTurn>() }
    var encoded by retain("b64") { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    val scope = rememberSafeScope()
    val listState = rememberLazyListState()
    val bmp = rememberBitmap(image, 1400)

    fun run(question: String?) {
        // Earlier answers (and follow-up questions) go back to the model as conversation history.
        val history = buildList {
            turns.filter { it.answer != null }.forEachIndexed { i, t ->
                if (i > 0) add(AiMessage("user", t.question.orEmpty()))
                add(AiMessage("assistant", t.answer!!))
            }
        }
        val turn = AiTurn("solve", if (question == null) "解答" else "追问", question, typed = question != null)
        turns += turn
        scope.launch {
            listState.animateScrollToItem(turns.size)
            try {
                val img = encoded ?: withContext(Dispatchers.Default) {
                    val b = BitmapIO.decode(image, 1600) ?: throw ApiException(0, "image", "图片读取失败，请重新拍摄")
                    Base64.encodeToString(BitmapIO.jpegBytes(b, 85), Base64.NO_WRAP).also { b.recycle() }
                }.also { encoded = it }
                val r = Api.ai(AiRequest("solve", question = question, image = img, history = history))
                account.updateQuota(r.quota)
                turn.answer = r.content
            } catch (e: ApiException) {
                turn.error = e.message
            }
            listState.animateScrollToItem(turns.size)
        }
    }

    LaunchedEffect(image) { if (turns.isEmpty()) run(null) }

    Column(Modifier.fillMaxSize().background(Bg).imePadding()) {
        AppTopBar("拍题解答", actions = {
            Text("重新拍", color = Brand, fontSize = 14.sp, modifier = Modifier.clickable {
                nav.reset()
                Tools.openCamera(nav, ScanMode.SOLVE)
            }.padding(12.dp))
        })
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("image") {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1F2024))) {
                    bmp?.let { Image(it.asImageBitmap(), "题目", Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(8.dp), contentScale = ContentScale.Fit) }
                }
            }
            items(turns, key = { System.identityHashCode(it) }) { t ->
                TurnCard(t, onRetry = { turns.remove(t); run(t.question) }, onReplace = null)
            }
            if (turns.any { it.answer != null }) item("note") { Disclaimer() }
        }
        InputBar(input, { input = it.take(500) }, "继续追问，例如：第二步为什么这样做？", enabled = turns.lastOrNull()?.loading != true) {
            val q = input.trim()
            if (q.isNotEmpty()) {
                input = ""
                run(q)
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Brand, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, color = Ink, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TurnCard(t: AiTurn, onRetry: () -> Unit, onReplace: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (t.typed) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    t.question.orEmpty(), color = Color.White, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)).background(Brand).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Brand, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.title, fontSize = 13.sp, color = Brand, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)).background(Color.White).padding(14.dp)) {
            when {
                t.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Brand, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (t.feature == "solve") "AI 正在解题…" else "AI 正在思考…", color = Sub, fontSize = 14.sp)
                }
                t.error != null -> {
                    Text(t.error!!, color = Danger, fontSize = 14.sp, lineHeight = 21.sp)
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("重试") }
                }
                else -> {
                    val answer = t.answer!!
                    SelectionContainer { Text(answer, fontSize = 15.sp, lineHeight = 24.sp, color = Ink) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SmallAction("复制", Icons.Outlined.ContentCopy) { Share.copyText(ctx, answer) }
                        SmallAction("分享", Icons.Outlined.Share) { Share.shareText(ctx, answer) }
                        if (onReplace != null) SmallAction("替换原文", Icons.Outlined.EditNote) { onReplace(answer) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 12.sp, color = Sub)
    }
}

@Composable
private fun Disclaimer() {
    Text("内容由 AI 生成，可能有误，请核实后使用", fontSize = 11.sp, color = Sub, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, hint: String, enabled: Boolean = true, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, maxLines = 4,
            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
            cursorBrush = SolidColor(Brand),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(21.dp)).background(Bg).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(hint, color = Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        val active = enabled && value.isNotBlank()
        IconButton(
            onClick = onSend, enabled = active,
            modifier = Modifier.size(42.dp).clip(CircleShape).background(if (active) Brand else Line),
        ) { Icon(Icons.AutoMirrored.Outlined.Send, "发送", tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}
