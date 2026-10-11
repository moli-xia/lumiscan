package com.lumiscan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Brand = Color(0xFF2F6BFF)
val BrandDark = Color(0xFF1E4FE0)
val BrandSoft = Color(0xFFE8EFFF)
val Bg = Color(0xFFF4F6FA)
val Ink = Color(0xFF1A1D26)
val Sub = Color(0xFF8A8F99)
val Line = Color(0xFFE9ECF2)
val Danger = Color(0xFFF04438)
val Gold = Color(0xFFF5B83D)

private val scheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandDark,
    secondary = Color(0xFF00B386),
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F2F7),
    onSurfaceVariant = Sub,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHighest = Color(0xFFF0F2F7),
)

private val type = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 12.sp),
)

/** Largest system font scale the layouts honour; beyond it text stops growing. */
private const val MAX_FONT_SCALE = 1.3f

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = if (density.fontScale > MAX_FONT_SCALE) Density(density.density, MAX_FONT_SCALE) else density
    CompositionLocalProvider(LocalDensity provides capped) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
