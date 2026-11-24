package com.niriun.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand purple used for the primary action; text on it is white (contrast > 7:1). */
val BrandPurple = Color(0xFF820AD1)

/**
 * Purple palette in the style of Nubank. Every Material 3 slot is set so nothing falls back to the baseline
 * palette. Interactive accents use a lighter purple for contrast on the dark purple surfaces.
 */
private val NiriunColors = darkColorScheme(
    primary = Color(0xFFC58BFF),
    onPrimary = Color(0xFF2E0057),
    primaryContainer = Color(0xFF5A0F96),
    onPrimaryContainer = Color(0xFFF0DBFF),
    inversePrimary = BrandPurple,
    secondary = Color(0xFFD3BCE8),
    onSecondary = Color(0xFF38274A),
    secondaryContainer = Color(0xFF4A3A5E),
    onSecondaryContainer = Color(0xFFEEDBFF),
    tertiary = Color(0xFFFF8FB8),
    onTertiary = Color(0xFF540028),
    tertiaryContainer = Color(0xFF7A1F47),
    onTertiaryContainer = Color(0xFFFFD9E5),
    background = Color(0xFF12041D),
    onBackground = Color(0xFFF1E8F8),
    surface = Color(0xFF190A27),
    onSurface = Color(0xFFF1E8F8),
    surfaceVariant = Color(0xFF3D2B50),
    onSurfaceVariant = Color(0xFFCDBBDD),
    surfaceTint = Color(0xFFC58BFF),
    inverseSurface = Color(0xFFEBDDF5),
    inverseOnSurface = Color(0xFF2B1A3B),
    error = Color(0xFFFF6B8A),
    onError = Color(0xFF4A0018),
    errorContainer = Color(0xFF6B1230),
    onErrorContainer = Color(0xFFFFD9E0),
    outline = Color(0xFF8B759F),
    outlineVariant = Color(0xFF45305A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A2650),
    surfaceDim = Color(0xFF12041D),
    surfaceContainerLowest = Color(0xFF0D0215),
    surfaceContainerLow = Color(0xFF190A27),
    surfaceContainer = Color(0xFF221233),
    surfaceContainerHigh = Color(0xFF2C1A40),
    surfaceContainerHighest = Color(0xFF37234E),
)

@Composable
fun NiriunTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NiriunColors, content = content)
}

/** Status indicator colors; each state also has its own shape so color is never the only cue. */
object StatusColors {
    val stopped = Color(0xFF9A8CAB)
    val waiting = Color(0xFFCDB4FF)
    val connected = Color(0xFF4DD8E6)
    val streaming = Color(0xFF4DD8E6)

    /** Tally: magenta for ON AIR, amber for PREVIEW; both stay readable on the purple surfaces. */
    val program = Color(0xFFFF3D8B)
    val preview = Color(0xFFFFC247)
    val programEdge = Color(0xFFFF6FAE)
    val previewEdge = Color(0xFFFFD36E)

    /** Stop is a dark plum tonal button with soft pink text: clearly different from the vivid purple Start. */
    val stop = Color(0xFF4A1D5E)
    val onStop = Color(0xFFFFB3D1)
}
