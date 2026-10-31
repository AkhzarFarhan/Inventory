package com.lpshardware.inventory.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Modern iOS (Cupertino) Light Palette engineered for 60-30-10 visual harmony
 * and high outdoor/counter legibility without GPU-heavy shadow blurs.
 */
object CupertinoColors {
    // 60% Dominant Base
    val SystemGroupedBackground = Color(0xFFF2F2F7)
    val CardSurface = Color(0xFFFFFFFF)
    val SecondarySurface = Color(0xFFF8F9FA)

    // 30% Structural Borders & Fills
    val Separator = Color(0xFFE5E5EA)
    val LightBorder = Color(0xFFE9ECEF)
    val SegmentedTrackFill = Color(0xFFE5E5EA)
    val SearchFieldFill = Color(0xFFE9E9EB)

    // Typography Ink
    val LabelPrimary = Color(0xFF1C1C1E)
    val LabelSecondary = Color(0xFF6C6C70)
    val LabelMuted = Color(0xFF8E8E93)

    // 10% Semantic Accents & Soft Tinted Containers
    val SystemBlue = Color(0xFF007AFF)
    val SystemBlueTint = Color(0xFFE5F1FF)

    val EmeraldGreen = Color(0xFF1B7F37)
    val EmeraldTint = Color(0xFFD1F2D9)

    val AmberWarning = Color(0xFFB25000)
    val AmberTint = Color(0xFFFFF3CD)

    val DestructiveRed = Color(0xFFD70015)
    val DestructiveTint = Color(0xFFFFD8D8)

    val WhatsAppGreen = Color(0xFF128C7E)
    val WhatsAppTint = Color(0xFFE0F6F2)

    // Secret Purchase Price Cipher Accent
    val CipherIndigo = Color(0xFF3634A3)
    val CipherContainer = Color(0xFFEFEFF6)
}

private val CupertinoLightColorScheme = lightColorScheme(
    primary = CupertinoColors.SystemBlue,
    onPrimary = Color.White,
    primaryContainer = CupertinoColors.SystemBlueTint,
    onPrimaryContainer = CupertinoColors.SystemBlue,
    secondary = CupertinoColors.CipherIndigo,
    onSecondary = Color.White,
    secondaryContainer = CupertinoColors.CipherContainer,
    onSecondaryContainer = CupertinoColors.CipherIndigo,
    background = CupertinoColors.SystemGroupedBackground,
    onBackground = CupertinoColors.LabelPrimary,
    surface = CupertinoColors.CardSurface,
    onSurface = CupertinoColors.LabelPrimary,
    surfaceVariant = CupertinoColors.SecondarySurface,
    onSurfaceVariant = CupertinoColors.LabelSecondary,
    outline = CupertinoColors.Separator,
    error = CupertinoColors.DestructiveRed,
    onError = Color.White
)

private val CupertinoTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        color = CupertinoColors.LabelPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        color = CupertinoColors.LabelPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        color = CupertinoColors.LabelPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = CupertinoColors.LabelSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = CupertinoColors.LabelMuted
    )
)

@Composable
fun LpsCupertinoTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CupertinoLightColorScheme,
        typography = CupertinoTypography,
        content = content
    )
}

object HapticHelper {
    fun tick(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(14L, 60))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(14L)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun successPulse(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(32L, 120))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(32L)
                }
            }
        } catch (_: Exception) {
        }
    }
}
