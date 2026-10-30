package com.maysk.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF2E63)
val AccentDark = Color(0xFFC81D4C)
val AccentSoft = Color(0x33FF2E63)

enum class AppTheme { LIGHT, DARK, OLED }

@Immutable
data class MayskColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val isLight: Boolean
)

private val DarkColors = MayskColors(
    bg = Color(0xFF0E0B10), surface = Color(0xFF1A1520), surface2 = Color(0xFF261E2C),
    text = Color.White, muted = Color(0xFF9C93A3), border = Color(0xFF332A3A), isLight = false
)
private val OledColors = MayskColors(
    bg = Color.Black, surface = Color(0xFF0D0B0F), surface2 = Color(0xFF17131A),
    text = Color.White, muted = Color(0xFF8E8694), border = Color(0xFF241E28), isLight = false
)
private val LightColors = MayskColors(
    bg = Color(0xFFF7F4F8), surface = Color.White, surface2 = Color(0xFFEDE7F0),
    text = Color(0xFF16121A), muted = Color(0xFF6E6574), border = Color(0xFFDDD5E2), isLight = true
)

val LocalMayskColors = staticCompositionLocalOf { DarkColors }

object Maysk {
    val colors: MayskColors
        @Composable get() = LocalMayskColors.current
}

@Composable
fun MayskTheme(theme: AppTheme = AppTheme.DARK, content: @Composable () -> Unit) {
    val c = when (theme) {
        AppTheme.LIGHT -> LightColors
        AppTheme.DARK -> DarkColors
        AppTheme.OLED -> OledColors
    }
    val scheme = if (c.isLight) lightColorScheme(
        primary = Accent, onPrimary = Color.White, background = c.bg, surface = c.surface,
        onBackground = c.text, onSurface = c.text, secondary = Accent
    ) else darkColorScheme(
        primary = Accent, onPrimary = Color.White, background = c.bg, surface = c.surface,
        onBackground = c.text, onSurface = c.text, secondary = Accent, surfaceVariant = c.surface2
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalMayskColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
