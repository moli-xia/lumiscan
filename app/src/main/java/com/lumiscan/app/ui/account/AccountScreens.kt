package com.lumiscan.app.ui.account

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.lumiscan.app.BuildConfig
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.remote.Api
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.data.remote.AppConfig
import com.lumiscan.app.data.remote.GroupInfo
import com.lumiscan.app.data.remote.QuotaInfo
import com.lumiscan.app.export.Share
import com.lumiscan.app.nav.LeaveScreen
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.Route
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.LoadingOverlay
import com.lumiscan.app.ui.common.PrimaryButton
import com.lumiscan.app.ui.common.SecondaryButton
import com.lumiscan.app.ui.common.rememberSafeScope
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.BrandSoft
import com.lumiscan.app.ui.theme.Danger
import com.lumiscan.app.ui.theme.Gold
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

/** Membership end shown as the last valid day. */
fun GroupInfo.expiryText(): String? = expiresAt?.let { "有效期至 ${dayFmt.format(Date(it - 1))}" }

fun formatTokens(n: Long): String = when {
    n < 0 -> "不限"
    n >= 100_000_000 -> "%.1f 亿".format(n / 1e8)
    n >= 10_000 -> "%.1f 万".format(n / 1e4).replace(".0 ", " ")
    else -> n.toString()
}

fun QuotaInfo.dailyText(): String = if (dailyRequests < 0) "不限" else "$dailyLeft/$dailyRequests"

/** Share of this month's tokens still available, or null when unlimited. */
fun QuotaInfo.monthlyLeftPercent(): Int? =
    if (monthlyTokens < 0) null else if (monthlyTokens == 0L) 0 else (100 - monthlyUsed * 100 / monthlyTokens).toInt().coerceIn(0, 100)

@Composable
fun Avatar(name: String?, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6EA0FF), Brand))),
        contentAlignment = Alignment.Center,
    ) {
        Text(name?.trim()?.take(1)?.uppercase() ?: "光", color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/** 账号与安全: profile, group & quota, password, sign out, delete account. */
@Composable
fun AccountScreen() {
    val account = ScanApp.instance.account
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val p = account.profile
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { account.refresh() }
    if (p == null) {
        LeaveScreen()
        return
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("账号与安全", actions = {
                IconButton(onClick = {
                    refreshing = true
                    scope.launch {
                        if (account.refresh() == null) Share.toast(ctx, "刷新失败，请检查网络")
                        refreshing = false
                    }
                }, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, "刷新", tint = if (refreshing) Sub else Ink) }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.displayName, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("账号：${p.username}", fontSize = 13.sp, color = Sub)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.group.name, fontSize = 11.sp, color = Color(0xFF7A4B00), fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Gold.copy(alpha = 0.25f)).padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                            p.group.expiryText()?.let { Text("  $it", fontSize = 12.sp, color = Sub) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                QuotaCard(p.quota)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    InfoRow("昵称", p.nickname.ifBlank { "未设置" }) { dialog = "nickname" }
                    InfoRow("邮箱", p.email ?: "未设置") { dialog = "email" }
                    InfoRow("修改密码", "") { dialog = "password" }
                    InfoRow("注册时间", if (p.createdAt > 0) dayFmt.format(Date(p.createdAt)) else "—", null)
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    InfoRow("意见反馈", "") { nav.push(Route.Feedback) }
                    InfoRow("用户协议", "") { nav.push(Route.Policy("terms")) }
                    InfoRow("隐私政策", "") { nav.push(Route.Policy("privacy")) }
                }
                Spacer(Modifier.height(24.dp))
                SecondaryButton("退出登录", Modifier.fillMaxWidth()) { dialog = "logout" }
                TextButton(onClick = { dialog = "delete" }, modifier = Modifier.fillMaxWidth()) { Text("注销账号", color = Danger) }
            }
        }
        LoadingOverlay(busy)
    }

    fun perform(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            try {
                block()
            } catch (e: ApiException) {
                Share.toast(ctx, e.message ?: "操作失败")
            } finally {
                busy = null
            }
        }
    }

    when (dialog) {
        "nickname" -> TextFieldDialog("修改昵称", p.nickname, "最多 20 个字", onDismiss = { dialog = null }) { v ->
            dialog = null
            perform("正在保存") { account.updateProfile(nickname = v.trim()); Share.toast(ctx, "已保存") }
        }
        "email" -> TextFieldDialog("修改邮箱", p.email.orEmpty(), "用于联系你，可留空", KeyboardType.Email, onDismiss = { dialog = null }) { v ->
            dialog = null
            perform("正在保存") { account.updateProfile(email = v.trim()); Share.toast(ctx, "已保存") }
        }
        "password" -> PasswordDialog(onDismiss = { dialog = null }) { old, new ->
            dialog = null
            perform("正在修改密码") { account.changePassword(old, new); Share.toast(ctx, "密码已修改，其他设备已退出登录") }
        }
        "logout" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新登录才能使用。手机上的文档不会被删除。") },
            confirmButton = { TextButton(onClick = { dialog = null; perform("正在退出") { account.logout() } }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        "delete" -> DeleteAccountDialog(onDismiss = { dialog = null }) { pw ->
            dialog = null
            perform("正在注销") { account.deleteAccount(pw); Share.toast(ctx, "账号已注销") }
        }
    }
}

@Composable
fun QuotaCard(q: QuotaInfo) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
        Text("AI 额度", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(12.dp))
        QuotaLine("今日对话次数", if (q.dailyRequests < 0) "已用 ${q.dailyUsed} · 不限" else "已用 ${q.dailyUsed} / ${q.dailyRequests}", q.dailyUsed.toLong(), q.dailyRequests.toLong())
        QuotaLine("本月 Tokens", if (q.monthlyTokens < 0) "已用 ${formatTokens(q.monthlyUsed)} · 不限" else "已用 ${formatTokens(q.monthlyUsed)} / ${formatTokens(q.monthlyTokens)}", q.monthlyUsed, q.monthlyTokens)
        QuotaLine("今日图像处理", if (q.dailyImages < 0) "已用 ${q.imagesUsed} · 不限" else "已用 ${q.imagesUsed} / ${q.dailyImages}", q.imagesUsed.toLong(), q.dailyImages.toLong())
        QuotaLine("今日文字识别", if (q.dailyOcrPages < 0) "已用 ${q.ocrUsed} 页 · 不限" else "已用 ${q.ocrUsed} / ${q.dailyOcrPages} 页", q.ocrUsed.toLong(), q.dailyOcrPages.toLong())
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("单次最多 ${if (q.maxInputChars < 0) "不限" else "${q.maxInputChars} 字"}", fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
            Text("最多 ${q.maxDevices} 台设备同时登录", fontSize = 12.sp, color = Sub)
        }
        Spacer(Modifier.height(8.dp))
        Text("对话：AI 总结、问答、翻译、拍题；图像处理：证件照、去手写、去水印、消除、修复、变清晰；文字识别：提取文字、转 Word/Excel、拍照计数。次数每天 0 点重置，Tokens 每月 1 日重置。", fontSize = 12.sp, color = Sub, lineHeight = 18.sp)
        if (q.features.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                q.features.forEach { f ->
                    Text(featureLabel(f), fontSize = 11.sp, color = Brand, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BrandSoft).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

fun featureLabel(key: String) = when (key) {
    "summary" -> "文档总结"
    "qa" -> "文档问答"
    "polish" -> "纠错润色"
    "translate" -> "AI 翻译"
    "solve" -> "拍题解答"
    "ocr" -> "AI 文字识别"
    "count" -> "AI 拍照计数"
    "handwriting" -> "AI 去手写"
    "watermark" -> "AI 去水印"
    "erase" -> "AI 消除"
    "restore" -> "AI 老照片修复"
    "upscale" -> "AI 变清晰"
    "id_photo" -> "AI 证件照"
    else -> key
}

@Composable
private fun QuotaLine(label: String, value: String, used: Long, limit: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Sub)
    }
    Spacer(Modifier.height(6.dp))
    val fraction = if (limit < 0) 0f else if (limit == 0L) 1f else (used.toFloat() / limit).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        color = if (fraction >= 1f) Danger else if (fraction >= 0.8f) Color(0xFFF79009) else Brand,
        trackColor = Bg,
        drawStopIndicator = {},
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InfoRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp, 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Sub, maxLines = 1)
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, tint = Line)
    }
}

private val fieldColors @Composable get() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand, unfocusedBorderColor = Line)

@Composable
private fun TextFieldDialog(
    title: String,
    initial: String,
    hint: String,
    type: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, colors = fieldColors,
                supportingText = { Text(hint) }, keyboardOptions = KeyboardOptions(keyboardType = type),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                listOf(Triple("当前密码", old) { v: String -> old = v }, Triple("新密码（8–64 位，含字母和数字）", new) { v: String -> new = v }, Triple("再次输入新密码", again) { v: String -> again = v })
                    .forEach { (label, value, set) ->
                        OutlinedTextField(
                            value, { set(it.take(64)) }, singleLine = true, label = { Text(label) }, colors = fieldColors,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    old.isEmpty() -> "请输入当前密码"
                    new.length < 8 || new.none { it.isLetter() } || new.none { it.isDigit() } -> "新密码需 8 位以上，并同时包含字母和数字"
                    new != again -> "两次输入的新密码不一致"
                    else -> null
                }
                if (error == null) onConfirm(old, new)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("注销账号") },
        text = {
            Column {
                Text(
                    "注销后，你的账号、登录记录、AI 额度与使用记录将被永久删除，且无法恢复。\n\n手机上已保存的文档不会被删除，你可以自行清理。",
                    fontSize = 14.sp, lineHeight = 21.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("输入密码确认") }, colors = fieldColors,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (pw.isNotEmpty()) onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text("永久注销", color = if (pw.isNotEmpty()) Danger else Sub) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun FeedbackScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var content by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberSafeScope { busy = null }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppTopBar("意见反馈")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("遇到问题或有好的建议？告诉我们，我们会认真查看每一条反馈。", fontSize = 13.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    content, { content = it.take(2000) }, placeholder = { Text("请描述你遇到的问题或建议（至少 5 个字）") },
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = Brand, unfocusedBorderColor = Line),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    supportingText = { Text("${content.length}/2000") },
                )
                OutlinedTextField(
                    contact, { contact = it.take(100) }, singleLine = true, placeholder = { Text("联系方式（选填）：邮箱 / 微信 / 手机号") },
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = Brand, unfocusedBorderColor = Line),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("提交时会附带 App 版本和手机型号，便于排查问题。", fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("提交", Modifier.fillMaxWidth(), enabled = content.trim().length >= 5 && busy == null) {
                    busy = "正在提交"
                    scope.launch {
                        try {
                            Api.feedback(content.trim(), contact.trim())
                            Share.toast(ctx, "感谢反馈！")
                            nav.pop()
                        } catch (e: ApiException) {
                            Share.toast(ctx, e.message ?: "提交失败")
                        } finally {
                            busy = null
                        }
                    }
                }
            }
        }
        LoadingOverlay(busy)
    }
}

@Composable
fun PolicyScreen(kind: String) {
    val nav = LocalNav.current
    PolicyContent(kind) { nav.pop() }
}

/** After sign-in: refreshes the profile, then shows a forced/optional update or a new announcement. */
@Composable
fun StartupChecks() {
    val account = ScanApp.instance.account
    val settings = ScanApp.instance.settings
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf<AppConfig?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    var showAnnouncement by remember { mutableStateOf(false) }
    LaunchedEffect(account.token) {
        account.refresh()
        val c = account.loadConfig() ?: return@LaunchedEffect
        cfg = c
        val current = BuildConfig.VERSION_CODE
        showUpdate = c.minVersionCode > current || (c.latestVersionCode > current && settings.skippedVersion < c.latestVersionCode)
        showAnnouncement = c.announcement.isNotBlank() && c.announcement.hashCode() != settings.seenAnnouncement
    }
    val c = cfg ?: return
    val forced = c.minVersionCode > BuildConfig.VERSION_CODE
    val openUrl = {
        if (c.updateUrl.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.updateUrl))) }
            .onFailure { Share.toast(ctx, "无法打开下载地址") }
    }
    if (showUpdate) {
        AlertDialog(
            onDismissRequest = { if (!forced) { showUpdate = false; settings.skippedVersion = c.latestVersionCode } },
            properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced),
            title = { Text(if (forced) "需要更新" else "发现新版本") },
            text = {
                Text(
                    (if (forced) "当前版本已停止服务，请更新到最新版本后继续使用。" else "有新版本可以更新。") +
                        (if (c.updateNote.isNotBlank()) "\n\n${c.updateNote}" else ""),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                if (c.updateUrl.isNotBlank()) TextButton(onClick = { openUrl(); if (!forced) showUpdate = false }) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (forced) (ctx as? Activity)?.finish()
                    else { showUpdate = false; settings.skippedVersion = c.latestVersionCode }
                }) { Text(if (forced) "退出" else "以后再说") }
            },
        )
    } else if (showAnnouncement) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("公告") },
            text = { Text(c.announcement, lineHeight = 22.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { settings.seenAnnouncement = c.announcement.hashCode(); showAnnouncement = false }) { Text("知道了") }
            },
        )
    }
}
