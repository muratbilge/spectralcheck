package com.spectralcheck.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Deep-navy night sky with the launcher icon's amber as the accent —
// the same family as the spectrogram's inferno palette.
private val Amber = Color(0xFFFFC24B)
private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF251A00),
    primaryContainer = Color(0xFF3D2E0E),
    onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFF4DD0E1),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF12424B),
    onSecondaryContainer = Color(0xFFA8EDF7),
    background = Color(0xFF0D0F1A),
    onBackground = Color(0xFFE5E4EC),
    surface = Color(0xFF0D0F1A),
    onSurface = Color(0xFFE5E4EC),
    surfaceVariant = Color(0xFF20243A),
    onSurfaceVariant = Color(0xFF9BA0B8),
    surfaceContainerLowest = Color(0xFF0A0C14),
    surfaceContainerLow = Color(0xFF14172A),
    surfaceContainer = Color(0xFF171B30),
    surfaceContainerHigh = Color(0xFF1D2138),
    surfaceContainerHighest = Color(0xFF232842),
    outline = Color(0xFF3C415C),
    outlineVariant = Color(0xFF272C45),
    error = Color(0xFFEF5350),
    onError = Color(0xFF3B0908),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SpectralTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
