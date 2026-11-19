package com.shopledger.pos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ShopLedgerTokens {
    val spacingXs: Dp = 4.dp
    val spacingS: Dp = 8.dp
    val spacingM: Dp = 12.dp
    val spacingL: Dp = 16.dp
    val spacingXl: Dp = 24.dp
    val minTouch: Dp = 48.dp
    val primaryActionHeight: Dp = 56.dp
}

internal val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

private val ShopLedgerShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun ShopLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ShopLedgerTypography,
            shapes = ShopLedgerShapes,
            content = content,
        )
    }
}

/** Access to [ExtraColors] as `ShopLedgerTheme.extra`. */
object ShopLedgerTheme {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}
