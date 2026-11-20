package com.shopledger.pos.ui.screens.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.ui.MAX_OPEN_CARTS
import com.shopledger.pos.ui.theme.ShopLedgerTokens

data class CartChip(val cartId: String, val label: String, val lineCount: Int)

/**
 * All open carts as wrapping chips (never sideways scroll) + a "New cart" chip.
 * Used on the Items home screen and at the top of checkout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CartSwitcher(
    carts: List<CartChip>,
    activeCartId: String,
    onSwitch: (String) -> Unit,
    onNewCart: () -> Unit,
    modifier: Modifier = Modifier,
    maxCarts: Int = MAX_OPEN_CARTS,
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
            FilterChip(
                selected = selected,
                onClick = { onSwitch(cart.cartId) },
                label = {
                    Text(
                        text = pluralStringResource(
                            R.plurals.cart_chip_label,
                            cart.lineCount,
                            cart.label,
                            cart.lineCount,
                        ),
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
                    .widthIn(max = 220.dp),
            )
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
