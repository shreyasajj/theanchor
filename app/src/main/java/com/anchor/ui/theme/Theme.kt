package com.anchor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AnchorColors = darkColorScheme(
    primary = AnchorAmber,
    onPrimary = AnchorBlack,
    primaryContainer = AnchorAmberDim,
    onPrimaryContainer = AnchorAmberBright,
    secondary = AnchorMuted,
    onSecondary = AnchorBlack,
    tertiary = AnchorSage,
    onTertiary = AnchorBlack,
    tertiaryContainer = AnchorSageDim,
    onTertiaryContainer = AnchorSage,
    background = AnchorBlack,
    onBackground = AnchorText,
    surface = AnchorBlack,
    onSurface = AnchorText,
    surfaceVariant = AnchorSurfaceMid,
    onSurfaceVariant = AnchorMuted,
    surfaceContainerLowest = AnchorSurface,
    surfaceContainerLow = AnchorSurfaceLow,
    surfaceContainer = AnchorSurfaceMid,
    surfaceContainerHigh = AnchorSurfaceHigh,
    surfaceContainerHighest = AnchorSurfaceHigh,
    outline = AnchorOutline,
    outlineVariant = AnchorOutlineSoft,
    error = AnchorError,
    onError = AnchorBlack,
    errorContainer = AnchorErrorDim,
    onErrorContainer = AnchorError,
)

private val AnchorShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Always dark, regardless of system setting: these screens are shown at 5 AM
 * and at midnight, and a white flash is the opposite of the intent.
 */
@Composable
fun AnchorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AnchorColors,
        typography = AnchorTypography,
        shapes = AnchorShapes,
        content = content,
    )
}
