package com.novelworkspace.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.novelworkspace.core.security.AppThemeMode

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFD4943A),
    onPrimary = Color(0xFF1A140B),
    primaryContainer = Color(0xFF3A2A14),
    onPrimaryContainer = Color(0xFFF6D9AC),
    secondary = Color(0xFF8CA4C8),
    onSecondary = Color(0xFF101926),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF191C22),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF232730),
    onSurfaceVariant = Color(0xFFA7AFBE),
    outline = Color(0xFF313744),
    error = Color(0xFFE06C75),
    onError = Color(0xFF24090B)
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF8C5310),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7E4C9),
    onPrimaryContainer = Color(0xFF2F1A02),
    secondary = Color(0xFF3E5678),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFECEEF3),
    onSurfaceVariant = Color(0xFF545C6B),
    outline = Color(0xFFD8DCE5),
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF)
)

val TabularNumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum"
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
)

@Composable
fun NovelWorkspaceTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkPalette else LightPalette,
        typography = AppTypography,
        content = content
    )
}
