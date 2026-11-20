package com.shopledger.pos.ui.screens.cart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.ui.MAX_OPEN_CARTS
import com.shopledger.pos.ui.theme.ShopLedgerTokens

data class CartChip(
    val cartId: String,
    val label: String,
    val lineCount: Int,
    val subtotalPaise: Long = 0L,
)

/**
 * All open carts as wrapping chips (never sideways scroll) + a "New cart" chip.
 * Used on the Items home screen and at the top of checkout.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CartSwitcher(
    carts: List<CartChip>,
    activeCartId: String,
    onSwitch: (String) -> Unit,
    onNewCart: () -> Unit,
    modifier: Modifier = Modifier,
    maxCarts: Int = MAX_OPEN_CARTS,
    onLongPressCart: ((CartChip) -> Unit)? = null,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ShopLedgerTokens.spacingL,
                vertical = ShopLedgerTokens.spacingS,
            ),
        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
    ) {
        carts.forEach { cart ->
            val selected = cart.cartId == activeCartId
            val chipLabel = if (selected && cart.lineCount > 0 && cart.subtotalPaise > 0L) {
                pluralStringResource(
                    R.plurals.cart_chip_label_with_total,
                    cart.lineCount,
                    cart.label,
                    cart.lineCount,
                    IndianCurrencyFormatter.formatPaise(cart.subtotalPaise),
                )
            } else {
                pluralStringResource(
                    R.plurals.cart_chip_label,
                    cart.lineCount,
                    cart.label,
                    cart.lineCount,
                )
            }
            if (onLongPressCart == null) {
                FilterChip(
                    selected = selected,
                    onClick = { onSwitch(cart.cartId) },
                    label = {
                        Text(
                            text = chipLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.ShoppingCart,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .heightIn(min = ShopLedgerTokens.minTouch)
                        .widthIn(max = 240.dp),
                )
            } else {
                val scheme = MaterialTheme.colorScheme
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) scheme.secondaryContainer else scheme.surface,
                    contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    border = if (selected) null else BorderStroke(1.dp, scheme.outline),
                    modifier = Modifier
                        .heightIn(min = ShopLedgerTokens.minTouch)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .semantics {
                            this.selected = selected
                            this.role = Role.Checkbox
                        }
                        .combinedClickable(
                            onClick = { onSwitch(cart.cartId) },
                            onLongClick = { onLongPressCart(cart) },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.ShoppingCart,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            text = chipLabel,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (carts.size < maxCarts) {
            AssistChip(
                onClick = onNewCart,
                label = { Text(stringResource(R.string.cart_new)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            )
        }
    }
}
