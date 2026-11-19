package com.shopledger.pos.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1A7F4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1CF),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBE6FF),
    onSecondaryContainer = Color(0xFF001A44),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F9F7),
    onBackground = Color(0xFF181C19),
    surface = Color.White,
    onSurface = Color(0xFF181C19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C1),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7DDBA3),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF005230),
    onPrimaryContainer = Color(0xFFB7F1CF),
    secondary = Color(0xFFB0C6FF),
    onSecondary = Color(0xFF002D6F),
    secondaryContainer = Color(0xFF0F449D),
    onSecondaryContainer = Color(0xFFDBE6FF),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF101411),
    onBackground = Color(0xFFE0E3DE),
    surface = Color(0xFF1A1F1B),
    onSurface = Color(0xFFE0E3DE),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF404943),
)

/** Semantic colours that Material 3's scheme has no slot for. */
data class ExtraColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val onSuccess: Color,
    val onWarning: Color,
    val onDanger: Color,
)

internal val LightExtraColors = ExtraColors(
    success = Color(0xFF1A7F4B),
    warning = Color(0xFFB45309),
    danger = Color(0xFFB3261E),
    onSuccess = Color.White,
    onWarning = Color.White,
    onDanger = Color.White,
)

internal val DarkExtraColors = ExtraColors(
    success = Color(0xFF7DDBA3),
    warning = Color(0xFFFFB86B),
    danger = Color(0xFFF2B8B5),
    onSuccess = Color(0xFF00391E),
    onWarning = Color(0xFF4A2800),
    onDanger = Color(0xFF601410),
)
