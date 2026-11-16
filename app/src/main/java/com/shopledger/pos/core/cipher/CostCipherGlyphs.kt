package com.shopledger.pos.core.cipher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.ui.theme.CupertinoColors

/**
 * Displays the purchase price encoded using the shopkeeper's user-defined custom cipher:
 * - [CipherMode.CUSTOM_CODE]: User-configured digit-to-code mapping (letters, symbols, or 10-char key).
 * - [CipherMode.HIDDEN_PIN]: Masked `••••` until Owner PIN / Seller Mode is unlocked.
 */
@Composable
fun CodedPurchasePriceBadge(
    purchasePricePaise: Long,
    modifier: Modifier = Modifier,
    isSellerUnlocked: Boolean = false,
    cipherMode: CipherMode? = null,
    cipherWord10: String? = null,
    @Suppress("UNUSED_PARAMETER") glyphSize: Dp = 17.dp,
    onClick: (() -> Unit)? = null
) {
    val activeModeFlow by CostCipherEngine.activeModeFlow.collectAsState()
    val activeDigitMapFlow by CostCipherEngine.activeCustomDigitMapFlow.collectAsState()
    val resolvedMode = cipherMode ?: activeModeFlow
    val resolvedMap = remember(cipherWord10, activeDigitMapFlow) {
        if (!cipherWord10.isNullOrBlank()) {
            CostCipherEngine.word10ToDigitMap(cipherWord10)
        } else {
            activeDigitMapFlow
        }
    }

    val codedText = remember(purchasePricePaise, resolvedMap) {
        CostCipherEngine.encodePaiseToCustomCode(purchasePricePaise, resolvedMap)
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
        when (resolvedMode) {
            CipherMode.CUSTOM_CODE -> {
                Text(
                    text = codedText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupertinoColors.CipherIndigo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            CipherMode.HIDDEN_PIN -> {
                if (!isSellerUnlocked) {
                    Text(
                        text = "••••",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.CipherIndigo
                    )
                }
            }
        }
        if (isSellerUnlocked) {
            val formattedCost = IndianCurrencyFormatter.formatPaise(purchasePricePaise)
            Text(
                text = if (resolvedMode == CipherMode.HIDDEN_PIN) formattedCost else " ($formattedCost)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = CupertinoColors.CipherIndigo
            )
        }
    }
}

/**
 * Interactive reference legend card adapting to the shop's user-defined custom cipher key.
 */
@Composable
fun CipherReferenceStrip(
    modifier: Modifier = Modifier,
    cipherMode: CipherMode? = null,
    cipherWord10: String? = null
) {
    val activeModeFlow by CostCipherEngine.activeModeFlow.collectAsState()
    val activeDigitMapFlow by CostCipherEngine.activeCustomDigitMapFlow.collectAsState()
    val resolvedMode = cipherMode ?: activeModeFlow
    val resolvedMap = remember(cipherWord10, activeDigitMapFlow) {
        if (!cipherWord10.isNullOrBlank()) {
            CostCipherEngine.word10ToDigitMap(cipherWord10)
        } else {
            activeDigitMapFlow
        }
    }

    when (resolvedMode) {
        CipherMode.CUSTOM_CODE -> {
            Row(
                modifier = modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CupertinoColors.CipherContainer)
                    .border(1.dp, CupertinoColors.Separator, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CostCipherEngine.ORDERED_DIGITS.forEach { digit ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = resolvedMap[digit].orEmpty(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CupertinoColors.CipherIndigo
                        )
                        Text(
                            text = digit.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.LabelSecondary
                        )
                    }
                }
            }
        }
        CipherMode.HIDDEN_PIN -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CupertinoColors.CipherContainer)
                    .border(1.dp, CupertinoColors.Separator, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PIN-Protected Mode: Purchase cost is hidden behind •••• until Seller Mode is unlocked.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CupertinoColors.CipherIndigo
                )
            }
        }
    }
}
