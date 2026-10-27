package com.example.rawcurveeditor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MonoBackground = Color(0xFF0A0A0A)
val MonoSurface = Color(0xFF141414)
val MonoSurfaceElevated = Color(0xFF1E1E1E)
val MonoBorder = Color(0xFF2C2C2C)
val MonoTextPrimary = Color(0xFFF0F0F0)
val MonoTextSecondary = Color(0xFF8E8E8E)
val MonoTextMuted = Color(0xFF5A5A5A)

// Subtle channel indicator colors tuned for darkroom aesthetics
val AccentMaster = Color(0xFFF2F2F2)
val AccentRed = Color(0xFFE05A5A)
val AccentGreen = Color(0xFF5AC878)
val AccentBlue = Color(0xFF5A9BE0)

private val MonotoneColorScheme = darkColorScheme(
    primary = MonoTextPrimary,
    onPrimary = MonoBackground,
    secondary = MonoTextSecondary,
    onSecondary = MonoBackground,
    background = MonoBackground,
    onBackground = MonoTextPrimary,
    surface = MonoSurface,
    onSurface = MonoTextPrimary,
    surfaceVariant = MonoSurfaceElevated,
    onSurfaceVariant = MonoTextSecondary,
    outline = MonoBorder
)

@Composable
fun RawCurveMonotoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonotoneColorScheme,
        content = content
    )
}
