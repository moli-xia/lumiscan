package com.lumiscan.app.ui.account

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.remote.Api
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.common.PrimaryButton
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Brand
import com.lumiscan.app.ui.theme.BrandDark
import com.lumiscan.app.ui.theme.Danger
import com.lumiscan.app.ui.theme.Ink
import com.lumiscan.app.ui.theme.Line
import com.lumiscan.app.ui.theme.Sub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "我已阅读并同意《用户协议》和《隐私政策》" with tappable titles. */
@Composable
fun PolicyLinks(before: String, after: String, onOpen: (String) -> Unit, fontSize: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    val link = TextLinkStyles(SpanStyle(color = Brand))
    val text = buildAnnotatedString {
        append(before)
        withLink(LinkAnnotation.Clickable("terms", link) { onOpen("terms") }) { append("《用户协议》") }
        append("和")
        withLink(LinkAnnotation.Clickable("privacy", link) { onOpen("privacy") }) { append("《隐私政策》") }
        append(after)
    }
    Text(text, fontSize = fontSize, color = Sub, lineHeight = fontSize * 1.6f, modifier = modifier)
}

/**
 * Full policy text. [remote] fetches the current version from the server; before the user has
 * agreed to anything we only show the copy bundled in the app.
 */
@Composable
fun PolicyContent(kind: String, remote: Boolean = true, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val title = if (kind == "privacy") "隐私政策" else "用户协议"
    var text by remember(kind) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind) {
        text = (if (remote) runCatching { Api.policy(kind).content }.getOrNull() else null)
            ?: withContext(Dispatchers.IO) { ctx.assets.open("policy/$kind.txt").bufferedReader().use { it.readText() } }
    }
    BackHandler(onBack = onBack)
    // Dark status-bar icons over the white top bar, restoring whatever the screen below used.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val ctl = (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        val prev = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = true
        onDispose { if (ctl != null && prev != null) ctl.isAppearanceLightStatusBars = prev }
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        AppTopBar(title, onBack = onBack)
        val t = text
        if (t == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand) }
        } else {
            SelectionContainer {
                Text(
                    t, fontSize = 14.sp, lineHeight = 23.sp, color = Ink,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * First launch (or after a material policy change, [updated]): privacy notice that must be
 * accepted before the app does anything online.
 */
@Composable
fun PrivacyConsentScreen(onAgree: () -> Unit, onDisagree: () -> Unit, updated: Boolean = false) {
    var policy by rememberSaveable { mutableStateOf<String?>(null) }
    policy?.let { PolicyContent(it, remote = false) { policy = null }; return }
    Box(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Text(if (updated) "隐私政策已更新" else "欢迎使用光点扫描", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text(
                (if (updated) "文字识别、证件照和图像处理功能已接入 AI 大模型，需要联网处理，我们相应更新了隐私政策。请了解：\n\n"
                else "我们非常重视你的个人信息保护。使用前请了解：\n\n") +
                    "• 光点扫描需要注册登录后使用，我们会保存你的账号信息，以及登录设备型号、IP 和时间，用于账号安全与多设备管理。\n" +
                    "• 扫描、裁剪、滤镜、PDF 工具等在手机本地完成，扫描的文档默认只保存在你的手机里。\n" +
                    "• 带「AI」标识的功能（文字识别、转 Word/Excel、翻译、拍照计数、证件照、去手写、去水印、智能消除、老照片修复、变清晰、AI 助手、拍题解答）会把你选择处理的图片或文字发送到我们的服务器，由第三方大模型服务商处理后返回结果，我们不保存这些内容。\n" +
                    "• 相机权限仅在拍摄时使用，你可以拒绝并改从相册导入。",
                fontSize = 14.sp, lineHeight = 22.sp, color = Ink,
            )
            Spacer(Modifier.height(12.dp))
            PolicyLinks("完整内容请阅读", "。点击「同意」即表示你已阅读并同意上述协议。", onOpen = { policy = it }, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("同意", Modifier.fillMaxWidth(), onClick = onAgree)
            TextButton(onClick = onDisagree, modifier = Modifier.fillMaxWidth()) { Text("不同意并退出", color = Sub) }
        }
    }
}

/** Shown instead of the app whenever no one is signed in. */
@Composable
fun LoginScreen() {
    val account = ScanApp.instance.account
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(account.signOutReason) }
    var busy by remember { mutableStateOf(false) }
    var askAgree by remember { mutableStateOf(false) }
    var policy by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val config = account.config
    val registrationClosed = register && config?.registrationOpen == false

    LaunchedEffect(Unit) { account.loadConfig() }

    fun submit() {
        val u = username.trim()
        error = when {
            u.isEmpty() -> "请输入账号"
            password.isEmpty() -> "请输入密码"
            register && password != confirm -> "两次输入的密码不一致"
            register && config?.inviteRequired == true && invite.isBlank() -> "请输入邀请码"
            else -> null
        }
        if (error != null) return
        if (!agreed) {
            askAgree = true
            return
        }
        busy = true
        scope.launch {
            try {
                if (register) account.register(u, password, invite) else account.login(u, password)
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    policy?.let { PolicyContent(it) { policy = null }; return }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color(0xFF4C8DFF), BrandDark))))
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("光点扫描", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("扫描识别 · AI 文档助手 · PDF 工具", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(20.dp),
            ) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg).padding(4.dp)) {
                    listOf("登录" to false, "注册" to true).forEach { (label, isRegister) ->
                        val selected = register == isRegister
                        Text(
                            label, textAlign = TextAlign.Center, fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Brand else Sub,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Color.White else Color.Transparent)
                                .clickable { register = isRegister; error = null }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (registrationClosed) {
                    Text("暂未开放注册，请联系管理员获取账号。", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    AuthField(username, { username = it.take(32) }, "账号", Icons.Outlined.Person, KeyboardType.Ascii, if (register) "3–32 位字母、数字或 _ . @ -" else null)
                    AuthField(
                        password, { password = it.take(64) }, "密码", Icons.Outlined.Lock, KeyboardType.Password,
                        if (register) "8–64 位，需同时包含字母和数字" else null,
                        visible = showPassword, onToggleVisible = { showPassword = !showPassword },
                        last = !register, onDone = ::submit,
                    )
                    if (register) {
                        AuthField(confirm, { confirm = it.take(64) }, "确认密码", Icons.Outlined.Lock, KeyboardType.Password, null, visible = showPassword, last = config?.inviteRequired != true, onDone = ::submit)
                        if (config?.inviteRequired == true) {
                            AuthField(invite, { invite = it.take(32) }, "邀请码", Icons.Outlined.Key, KeyboardType.Ascii, null, last = true, onDone = ::submit)
                        }
                    }
                    error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PrimaryButton(if (busy) "" else if (register) "注册并登录" else "登录", Modifier.fillMaxWidth(), enabled = !busy, onClick = ::submit)
                        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = agreed, onCheckedChange = { agreed = it },
                        colors = CheckboxDefaults.colors(checkedColor = Brand, uncheckedColor = Line),
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    PolicyLinks("我已阅读并同意", "", onOpen = { policy = it })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("忘记密码？请联系客服为你重置", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (askAgree) {
        AlertDialog(
            onDismissRequest = { askAgree = false },
            title = { Text("请阅读并同意协议") },
            text = { PolicyLinks("登录前请阅读并同意", "，了解我们如何收集、使用你的信息。", onOpen = { askAgree = false; policy = it }, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { askAgree = false; agreed = true; submit() }) { Text("同意并继续") } },
            dismissButton = { TextButton(onClick = { askAgree = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    type: KeyboardType,
    hint: String?,
    visible: Boolean = true,
    onToggleVisible: (() -> Unit)? = null,
    last: Boolean = false,
    onDone: () -> Unit = {},
) {
    val isPassword = type == KeyboardType.Password
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = Sub) },
        trailingIcon = if (isPassword && onToggleVisible != null) {
            { IconButton(onClick = onToggleVisible) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码", tint = Sub) } }
        } else null,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (last) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        supportingText = hint?.let { { Text(it, fontSize = 11.sp) } },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand, unfocusedBorderColor = Line, focusedLabelColor = Brand),
        modifier = Modifier.fillMaxWidth().padding(bottom = if (hint == null) 10.dp else 4.dp),
    )
}
