package com.shopledger.pos.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

internal val ShopLedgerTypography = base.copy(
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)
