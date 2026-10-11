package com.lumiscan.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.lumiscan.app.export.Share
import com.lumiscan.app.imaging.BitmapIO
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.nav.LocalEntry
import com.lumiscan.app.nav.Navigator
import com.lumiscan.app.nav.Route
import com.lumiscan.app.scan.ScanMode
import com.lumiscan.app.scan.ScanSession
import com.lumiscan.app.ui.account.AccountScreen
import com.lumiscan.app.ui.account.FeedbackScreen
import com.lumiscan.app.ui.account.LoginScreen
import com.lumiscan.app.ui.account.PolicyScreen
import com.lumiscan.app.ui.account.PrivacyConsentScreen
import com.lumiscan.app.ui.account.StartupChecks
import com.lumiscan.app.ui.ai.AiAssistScreen
import com.lumiscan.app.ui.ai.SolveScreen
import com.lumiscan.app.ui.camera.CameraScreen
import com.lumiscan.app.ui.crop.CropScreen
import com.lumiscan.app.ui.crop.FiltersScreen
import com.lumiscan.app.ui.crop.PageEditScreen
import com.lumiscan.app.ui.doc.DocPagesScreen
import com.lumiscan.app.ui.doc.DocScreen
import com.lumiscan.app.ui.doc.SignPageScreen
import com.lumiscan.app.ui.doc.SignaturesScreen
import com.lumiscan.app.ui.home.AllToolsScreen
import com.lumiscan.app.ui.home.MainScreen
import com.lumiscan.app.ui.pdf.PdfToolScreen
import com.lumiscan.app.ui.pdf.PdfViewScreen
import com.lumiscan.app.ui.recognize.ExcelScreen
import com.lumiscan.app.ui.recognize.OcrScreen
import com.lumiscan.app.ui.recognize.TranslateScreen
import com.lumiscan.app.ui.recognize.WordScreen
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.LumiTheme
import com.lumiscan.app.ui.tools.CountScreen
import com.lumiscan.app.ui.tools.EraseScreen
import com.lumiscan.app.ui.tools.HandwritingScreen
import com.lumiscan.app.ui.tools.IdPhotoScreen
import com.lumiscan.app.ui.tools.RestoreScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val nav get() = ScanApp.instance.navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumiTheme {
                CompositionLocalProvider(LocalNav provides nav) {
                    AppHost(nav)
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    /** Volume keys take the picture while a camera screen is open (handy for selfie ID photos). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val shutter = com.lumiscan.app.ui.camera.ShutterKeys.onShutter
        if (shutter != null && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Images shared into the app start a scan; PDFs open in the PDF tool box. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val type = intent.type ?: contentResolver.getType(uris[0]).orEmpty()
        lifecycleScope.launch {
            if (type.contains("pdf")) {
                val f = withContext(Dispatchers.IO) {
                    runCatching {
                        File(ScanApp.instance.workDir, "shared_${System.currentTimeMillis()}.pdf").also { out ->
                            contentResolver.openInputStream(uris[0])!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                        }
                    }.getOrNull()
                } ?: return@launch Share.toast(this@MainActivity, "无法读取该文件")
                nav.reset(Route.PdfView(f.absolutePath))
            } else {
                ScanSession.start(ScanMode.DOC, null)
                uris.forEach { u ->
                    val f = withContext(Dispatchers.IO) { BitmapIO.importUri(this@MainActivity, u) } ?: return@forEach
                    ScanSession.add(f, ScanApp.instance.settings.autoCrop)
                }
                if (ScanSession.pages.isNotEmpty()) {
                    nav.reset()
                    ScanSession.proceed(nav)
                } else Share.toast(this@MainActivity, "无法读取分享的图片")
            }
        }
    }
}

/** Screens with a dark background need light status/navigation bar icons. */
private fun Route.isDark(): Boolean = when (this) {
    is Route.Camera, Route.Crop, Route.Filters, is Route.PageEdit, is Route.DocPages, is Route.Translate,
    is Route.Count, is Route.Handwriting, is Route.Erase, is Route.Restore, is Route.Sign -> true
    else -> false
}

/**
 * Privacy consent (first launch) → sign-in → the app. Nothing goes online before consent, and the
 * back stack survives a forced sign-out so the user lands where they were after signing in again.
 */
@Composable
private fun AppHost(nav: Navigator) {
    val app = ScanApp.instance
    val activity = LocalContext.current as? android.app.Activity
    val light = Modifier.fillMaxSize().background(Bg)
    if (!app.settings.privacyAccepted) {
        SystemBars(whiteStatusIcons = false)
        Box(light) { PrivacyConsentScreen(onAgree = { app.settings.acceptPrivacy() }, onDisagree = { activity?.finish() }, updated = app.settings.privacyUpdated) }
        return
    }
    if (!app.account.loggedIn) {
        SystemBars(whiteStatusIcons = true)
        Box(light) { LoginScreen() }
        return
    }
    StartupChecks()
    NavHost(nav)
}

/** Bar icon colours for the screens outside the back stack (both have a light bottom). */
@Composable
private fun SystemBars(whiteStatusIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !whiteStatusIcons
            isAppearanceLightNavigationBars = true
        }
    }
}

@Composable
private fun NavHost(nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    var known by remember { mutableStateOf(setOf<Long>()) }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Drop saved UI state of entries that left the back stack.
    LaunchedEffect(nav.stack.toList()) {
        val now = nav.stack.map { it.id }.toSet()
        (known - now).forEach { holder.removeState(it) }
        known = now
    }
    val view = LocalView.current
    val dark = nav.current.isDark()
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            contentKey = { it.id },
            label = "nav",
        ) { entry ->
            holder.SaveableStateProvider(entry.id) {
                CompositionLocalProvider(LocalEntry provides entry) { RouteContent(entry.route) }
            }
        }
    }
}

@Composable
private fun RouteContent(route: Route) {
    when (route) {
        Route.Main -> MainScreen()
        Route.AllTools -> AllToolsScreen()
        is Route.Camera -> CameraScreen(route.mode, route.appendTo)
        Route.Crop -> CropScreen()
        Route.Filters -> FiltersScreen()
        is Route.Doc -> DocScreen(route.id)
        is Route.DocPages -> DocPagesScreen(route.id, route.start)
        is Route.PageEdit -> PageEditScreen(route.id, route.index)
        is Route.Ocr -> OcrScreen(route.images, route.docId)
        is Route.Word -> WordScreen(route.images)
        is Route.Excel -> ExcelScreen(route.images)
        is Route.Translate -> TranslateScreen(route.image)
        is Route.Count -> CountScreen(route.image)
        is Route.IdPhoto -> IdPhotoScreen(route.image)
        is Route.Handwriting -> HandwritingScreen(route.images, route.exam)
        is Route.Erase -> EraseScreen(route.image, route.watermark, route.docId, route.index)
        is Route.Restore -> RestoreScreen(route.image, route.oldPhoto)
        is Route.Sign -> SignPageScreen(route.docId, route.index)
        Route.Signatures -> SignaturesScreen()
        is Route.PdfTool -> PdfToolScreen(route.tool, route.initial)
        is Route.PdfView -> PdfViewScreen(route.path)
        Route.Account -> AccountScreen()
        Route.Feedback -> FeedbackScreen()
        is Route.Policy -> PolicyScreen(route.kind)
        is Route.AiAssist -> AiAssistScreen(route.text, route.images)
        is Route.Solve -> SolveScreen(route.image)
    }
}
