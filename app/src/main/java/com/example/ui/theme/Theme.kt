package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = CrispWhite,
    onPrimary = ObsidianBlack,
    primaryContainer = SlateElevated,
    onPrimaryContainer = CrispWhite,
    secondary = AccentSilver,
    onSecondary = ObsidianBlack,
    secondaryContainer = GraphiteCard,
    onSecondaryContainer = CrispWhite,
    tertiary = StatusAvailableGreen,
    onTertiary = ObsidianBlack,
    background = ObsidianBlack,
    onBackground = CrispWhite,
    surface = CarbonSurface,
    onSurface = CrispWhite,
    surfaceVariant = GraphiteCard,
    onSurfaceVariant = MutedTextDark,
    outline = SubtleBorderDark,
    outlineVariant = Color(0xFF1F222A),
    error = StatusErrorRed,
    onError = CrispWhite
)

private val LightColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = CrispWhite,
    primaryContainer = SoftGrayElevated,
    onPrimaryContainer = InkBlack,
    secondary = AccentCharcoal,
    onSecondary = CrispWhite,
    secondaryContainer = PorcelainSurface,
    onSecondaryContainer = InkBlack,
    tertiary = StatusAvailableGreen,
    onTertiary = CrispWhite,
    background = AlabasterWhite,
    onBackground = InkBlack,
    surface = PorcelainSurface,
    onSurface = InkBlack,
    surfaceVariant = PearlCard,
    onSurfaceVariant = MutedTextLight,
    outline = SubtleBorderLight,
    outlineVariant = Color(0xFFECEEF2),
    error = StatusErrorRed,
    onError = CrispWhite
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
private fun animateColor(targetValue: Color, reducedAnimations: Boolean): Color {
    if (reducedAnimations) return targetValue
    val animated by animateColorAsState(
        targetValue = targetValue,
        animationSpec = tween(durationMillis = 220),
        label = "theme_color_transition"
    )
    return animated
}

@Composable
fun NexusAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedAnimations: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetScheme: ColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val animatedScheme = targetScheme.copy(
        primary = animateColor(targetScheme.primary, reducedAnimations),
        onPrimary = animateColor(targetScheme.onPrimary, reducedAnimations),
        primaryContainer = animateColor(targetScheme.primaryContainer, reducedAnimations),
        onPrimaryContainer = animateColor(targetScheme.onPrimaryContainer, reducedAnimations),
        secondary = animateColor(targetScheme.secondary, reducedAnimations),
        onSecondary = animateColor(targetScheme.onSecondary, reducedAnimations),
        secondaryContainer = animateColor(targetScheme.secondaryContainer, reducedAnimations),
        onSecondaryContainer = animateColor(targetScheme.onSecondaryContainer, reducedAnimations),
        background = animateColor(targetScheme.background, reducedAnimations),
        onBackground = animateColor(targetScheme.onBackground, reducedAnimations),
        surface = animateColor(targetScheme.surface, reducedAnimations),
        onSurface = animateColor(targetScheme.onSurface, reducedAnimations),
        surfaceVariant = animateColor(targetScheme.surfaceVariant, reducedAnimations),
        onSurfaceVariant = animateColor(targetScheme.onSurfaceVariant, reducedAnimations),
        outline = animateColor(targetScheme.outline, reducedAnimations)
    )

    MaterialTheme(
        colorScheme = animatedScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
