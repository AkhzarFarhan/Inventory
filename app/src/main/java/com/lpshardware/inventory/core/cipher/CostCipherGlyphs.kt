package com.lpshardware.inventory.core.cipher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.ui.theme.CupertinoColors

/**
 * Renders the exact geometric stroke symbols from `IMG_20261031_222339.jpg` using hardware-accelerated
 * Compose Canvas vector paths (zero bitmap allocation, crisp at any DPI).
 */
@Composable
fun CipherSymbolGlyph(
    symbol: CipherSymbol,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 18.dp,
    tint: Color = CupertinoColors.CipherIndigo,
    strokeWidthDp: Dp = 1.8.dp
) {
    val actualWidth = if (symbol == CipherSymbol.DECIMAL) glyphSize * 0.4f else glyphSize
    Canvas(modifier = modifier.size(width = actualWidth, height = glyphSize)) {
        val strokePx = strokeWidthDp.toPx()
        val stroke = Stroke(
            width = strokePx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        drawCipherSymbol(symbol = symbol, color = tint, stroke = stroke)
    }
}

fun DrawScope.drawCipherSymbol(
    symbol: CipherSymbol,
    color: Color,
    stroke: Stroke
) {
    val w = size.width
    val h = size.height
    val padX = w * 0.16f
    val padY = h * 0.12f
    val left = padX
    val right = w - padX
    val top = padY
    val bottom = h - padY
    val centerX = w * 0.5f

    when (symbol) {
        CipherSymbol.ONE -> {
            // 1 = Single vertical stroke |
            drawLine(
                color = color,
                start = Offset(centerX, top),
                end = Offset(centerX, bottom),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
        }

        CipherSymbol.TWO -> {
            // 2 = Diagonal cross X
            drawLine(
                color = color,
                start = Offset(left, top),
                end = Offset(right, bottom),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
            drawLine(
                color = color,
                start = Offset(right, top),
                end = Offset(left, bottom),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
        }

        CipherSymbol.THREE -> {
            // 3 = Triangle △
            val path = Path().apply {
                moveTo(centerX, top)
                lineTo(right, bottom)
                lineTo(left, bottom)
                close()
            }
            drawPath(path = path, color = color, style = stroke)
        }

        CipherSymbol.FOUR -> {
            // 4 = Square □
            val sqTop = h * 0.18f
            val sqBottom = h * 0.82f
            drawRect(
                color = color,
                topLeft = Offset(left, sqTop),
                size = Size(right - left, sqBottom - sqTop),
                style = stroke
            )
        }

        CipherSymbol.FIVE -> {
            // 5 = Square □ + 1 diagonal (bottom-left to top-right, as in photo)
            val sqTop = h * 0.18f
            val sqBottom = h * 0.82f
            drawRect(
                color = color,
                topLeft = Offset(left, sqTop),
                size = Size(right - left, sqBottom - sqTop),
                style = stroke
            )
            drawLine(
                color = color,
                start = Offset(left, sqBottom),
                end = Offset(right, sqTop),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
        }

        CipherSymbol.SIX -> {
            // 6 = Square □ + 2 crossing diagonals ☒
            val sqTop = h * 0.18f
            val sqBottom = h * 0.82f
            drawRect(
                color = color,
                topLeft = Offset(left, sqTop),
                size = Size(right - left, sqBottom - sqTop),
                style = stroke
            )
            drawLine(
                color = color,
                start = Offset(left, sqBottom),
                end = Offset(right, sqTop),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
            drawLine(
                color = color,
                start = Offset(left, sqTop),
                end = Offset(right, sqBottom),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
        }

        CipherSymbol.SEVEN -> {
            // 7 = House shape: Square body + Triangle roof on top
            val roofApexY = h * 0.10f
            val eaveY = h * 0.42f
            val baseBottomY = h * 0.86f

            // Square body
            drawRect(
                color = color,
                topLeft = Offset(left, eaveY),
                size = Size(right - left, baseBottomY - eaveY),
                style = stroke
            )
            // Roof triangle
            val roofPath = Path().apply {
                moveTo(left, eaveY)
                lineTo(centerX, roofApexY)
                lineTo(right, eaveY)
            }
            drawPath(path = roofPath, color = color, style = stroke)
        }

        CipherSymbol.EIGHT -> {
            // 8 = House shape (7) + vertical stem extending down from bottom center
            val roofApexY = h * 0.07f
            val eaveY = h * 0.34f
            val boxBottomY = h * 0.66f
            val stemBottomY = h * 0.93f

            // Square body
            drawRect(
                color = color,
                topLeft = Offset(left, eaveY),
                size = Size(right - left, boxBottomY - eaveY),
                style = stroke
            )
            // Roof
            val roofPath = Path().apply {
                moveTo(left, eaveY)
                lineTo(centerX, roofApexY)
                lineTo(right, eaveY)
            }
            drawPath(path = roofPath, color = color, style = stroke)
            // Bottom vertical stem
            drawLine(
                color = color,
                start = Offset(centerX, boxBottomY),
                end = Offset(centerX, stemBottomY),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
        }

        CipherSymbol.NINE -> {
            // 9 = House shape + bottom vertical stem (8) + horizontal crossbar on the stem
            val roofApexY = h * 0.06f
            val eaveY = h * 0.31f
            val boxBottomY = h * 0.60f
            val stemBottomY = h * 0.95f
            val crossBarY = h * 0.79f
            val crossHalfWidth = (right - left) * 0.38f

            // Square body
            drawRect(
                color = color,
                topLeft = Offset(left, eaveY),
                size = Size(right - left, boxBottomY - eaveY),
                style = stroke
            )
            // Roof
            val roofPath = Path().apply {
                moveTo(left, eaveY)
                lineTo(centerX, roofApexY)
                lineTo(right, eaveY)
            }
            drawPath(path = roofPath, color = color, style = stroke)
            // Bottom vertical stem
            drawLine(
                color = color,
                start = Offset(centerX, boxBottomY),
                end = Offset(centerX, stemBottomY),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
            // Horizontal crossbar on the stem
            drawLine(
                color = color,
                start = Offset(centerX - crossHalfWidth, crossBarY),
                end = Offset(centerX + crossHalfWidth, crossBarY),
                strokeWidth = stroke.width,
                cap = stroke.cap
            )
        }

        CipherSymbol.ZERO -> {
            // 0 = Oval / Circle ○
            val ovalTop = h * 0.18f
            val ovalBottom = h * 0.82f
            drawOval(
                color = color,
                topLeft = Offset(left, ovalTop),
                size = Size(right - left, ovalBottom - ovalTop),
                style = stroke
            )
        }

        CipherSymbol.DECIMAL -> {
            drawCircle(
                color = color,
                radius = stroke.width * 0.85f,
                center = Offset(w * 0.5f, bottom - stroke.width)
            )
        }
    }
}

/**
 * Displays the purchase price encoded in the shop's secret geometric symbols (`IMG_20261031_222339.jpg`).
 * When `isSellerUnlocked` is explicitly enabled by the seller, also shows the decoded ₹ value.
 */
@Composable
fun CodedPurchasePriceBadge(
    purchasePricePaise: Long,
    modifier: Modifier = Modifier,
    isSellerUnlocked: Boolean = false,
    glyphSize: Dp = 17.dp,
    onClick: (() -> Unit)? = null
) {
    val symbols = remember(purchasePricePaise) {
        CostCipherEngine.encodePaiseToSymbols(purchasePricePaise)
    }

    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(CupertinoColors.CipherContainer)
            .border(1.dp, CupertinoColors.CipherIndigo.copy(alpha = 0.22f), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        symbols.forEach { symbol ->
            CipherSymbolGlyph(
                symbol = symbol,
                glyphSize = glyphSize,
                tint = CupertinoColors.CipherIndigo
            )
        }
        if (isSellerUnlocked) {
            Text(
                text = " (${IndianCurrencyFormatter.formatPaise(purchasePricePaise)})",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = CupertinoColors.CipherIndigo
            )
        }
    }
}

/**
 * Interactive reference legend card (only visible inside the Seller-unlocked Cipher Reference modal
 * or live preview while editing purchase prices).
 */
@Composable
fun CipherReferenceStrip(
    modifier: Modifier = Modifier
) {
    val allDigits = remember {
        listOf(
            CipherSymbol.ONE,
            CipherSymbol.TWO,
            CipherSymbol.THREE,
            CipherSymbol.FOUR,
            CipherSymbol.FIVE,
            CipherSymbol.SIX,
            CipherSymbol.SEVEN,
            CipherSymbol.EIGHT,
            CipherSymbol.NINE,
            CipherSymbol.ZERO
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CupertinoColors.CipherContainer)
            .border(1.dp, CupertinoColors.Separator, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        allDigits.forEach { sym ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CipherSymbolGlyph(symbol = sym, glyphSize = 18.dp)
                Text(
                    text = sym.digitChar.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelSecondary
                )
            }
        }
    }
}
