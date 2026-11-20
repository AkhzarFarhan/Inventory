package com.shopledger.pos.ui.screens.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.ui.theme.HapticHelper
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.QtyStepper
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StockStatus

data class AddToCartVariantRow(
    val variantId: String,
    val label: String,
    val pricePaise: Long,
    val purchasePricePaise: Long,
    val stockText: String,
    val status: StockStatus,
    val qtyInCart: Double,
    val unit: String = "pcs",
)

data class AddToCartState(
    val itemName: String,
    val cartLabel: String,
    val variants: List<AddToCartVariantRow>,
    val cartLineCount: Int,
    val cartTotalPaise: Long,
    val isSellerUnlocked: Boolean,
)

sealed interface AddToCartEvent {
    data class Add(val variantId: String) : AddToCartEvent
    data class SetQty(val variantId: String, val qty: Double) : AddToCartEvent
    data object GoToCart : AddToCartEvent
    data object Done : AddToCartEvent
}

@Composable
fun AddToCartContent(
    state: AddToCartState,
    onEvent: (AddToCartEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cipherMode by CostCipherEngine.activeModeFlow.collectAsState()
    var editingRow by remember { mutableStateOf<AddToCartVariantRow?>(null) }
    var variantFilter by remember { mutableStateOf("") }
    val visibleVariants = remember(state.variants, variantFilter) {
        val needle = variantFilter.trim()
        if (needle.isEmpty()) state.variants else state.variants.filter { it.label.contains(needle, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(ShopLedgerTokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs)) {
            Text(
                text = state.itemName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.add_sheet_adding_to, state.cartLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.variants.size > 6) {
            OutlinedTextField(
                value = variantFilter,
                onValueChange = { variantFilter = it },
                label = { Text(stringResource(R.string.add_sheet_filter_sizes)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (variantFilter.isNotEmpty()) {
                        IconButton(onClick = { variantFilter = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 420.dp),
        ) {
            itemsIndexed(visibleVariants, key = { _, row -> row.variantId }) { index, row ->
                val priceText = IndianCurrencyFormatter.formatPaise(row.pricePaise)
                val rowCd = stringResource(R.string.add_sheet_row_cd, row.label, priceText, row.stockText)
                val stockColor = when (row.status) {
                    StockStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
                    StockStatus.LOW -> ShopLedgerTheme.extra.warning
                    StockStatus.OUT, StockStatus.OVERSOLD -> ShopLedgerTheme.extra.danger
                }
                val inCart = row.qtyInCart > 0.0
                Surface(
                    color = if (inCart) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ShopLedgerTokens.spacingXs, vertical = ShopLedgerTokens.spacingS),
                        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .semantics(mergeDescendants = false) { contentDescription = rowCd },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
                        ) {
                            if (inCart) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            shape = MaterialTheme.shapes.extraSmall,
                                        ),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                            ) {
                                Text(
                                    text = row.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = row.stockText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = stockColor,
                                )
                                if (state.isSellerUnlocked || cipherMode == CipherMode.CUSTOM_CODE) {
                                    CodedPurchasePriceBadge(
                                        purchasePricePaise = row.purchasePricePaise,
                                        isSellerUnlocked = state.isSellerUnlocked,
                                    )
                                }
                            }

                            Text(
                                text = priceText,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            if (!inCart) {
                                FilledTonalButton(
                                    onClick = {
                                        HapticHelper.tick(context)
                                        onEvent(AddToCartEvent.Add(row.variantId))
                                    },
                                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                                ) {
                                    Text(stringResource(R.string.add_sheet_add))
                                }
                            } else {
                                QtyStepper(
                                    valueText = IndianCurrencyFormatter.formatQuantity(row.qtyInCart),
                                    onMinus = {
                                        HapticHelper.tick(context)
                                        onEvent(
                                            AddToCartEvent.SetQty(
                                                row.variantId,
                                                (row.qtyInCart - 1.0).coerceAtLeast(0.0),
                                            ),
                                        )
                                    },
                                    onPlus = {
                                        HapticHelper.tick(context)
                                        onEvent(AddToCartEvent.SetQty(row.variantId, row.qtyInCart + 1.0))
                                    },
                                    onValueClick = { editingRow = row },
                                )
                            }
                        }

                        if (inCart) {
                            val presets = remember(row.unit) { quantityPresetsForUnit(row.unit) }
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                            ) {
                                presets.forEach { preset ->
                                    SuggestionChip(
                                        onClick = {
                                            HapticHelper.tick(context)
                                            onEvent(
                                                AddToCartEvent.SetQty(
                                                    row.variantId,
                                                    row.qtyInCart + preset.deltaQty,
                                                ),
                                            )
                                        },
                                        label = { Text(preset.formatLabel(), style = MaterialTheme.typography.labelLarge) },
                                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                                    )
                                }
                            }
                        }
                    }
                }
                if (index < visibleVariants.lastIndex) {
                    HorizontalDivider()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ShopLedgerTokens.spacingXl),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
        ) {
            PrimaryActionButton(
                text = if (state.cartLineCount == 0) {
                    stringResource(R.string.add_sheet_go_to_cart_empty)
                } else {
                    pluralStringResource(
                        R.plurals.add_sheet_go_to_cart,
                        state.cartLineCount,
                        state.cartLineCount,
                        IndianCurrencyFormatter.formatPaise(state.cartTotalPaise),
                    )
                },
                icon = Icons.Filled.ShoppingCart,
                enabled = state.cartLineCount > 0,
                onClick = { onEvent(AddToCartEvent.GoToCart) },
            )
            SecondaryActionButton(
                text = stringResource(R.string.add_sheet_done),
                onClick = { onEvent(AddToCartEvent.Done) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    editingRow?.let { target ->
        QuantityDialog(
            initial = target.qtyInCart,
            unit = target.unit,
            onConfirm = { newQty ->
                editingRow = null
                onEvent(AddToCartEvent.SetQty(target.variantId, newQty))
            },
            onDismiss = { editingRow = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCartSheet(
    state: AddToCartState,
    onEvent: (AddToCartEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AddToCartContent(state = state, onEvent = onEvent)
    }
}

private fun sanitiseQuantityInput(input: String): String? {
    val cleaned = input.replace(',', '.').filter { it in '0'..'9' || it == '.' }
    if (cleaned.count { it == '.' } > 1) return null
    if (cleaned.substringBefore('.').length > 7) return null
    if (cleaned.substringAfter('.', "").length > 3) return null
    return cleaned
}

@Composable
private fun QuantityDialog(
    initial: Double,
    unit: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(IndianCurrencyFormatter.formatQuantity(initial)) }
    val parsed = text.toBigDecimalOrNull()?.toDouble()
    val valid = parsed != null && parsed > 0.0
    val presets = remember(unit) { quantityPresetsForUnit(unit) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_sheet_qty_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { sanitiseQuantityInput(it)?.let { ok -> text = ok } },
                    label = { Text(stringResource(R.string.checkout_qty_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                    verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                ) {
                    presets.forEach { preset ->
                        SuggestionChip(
                            onClick = {
                                HapticHelper.tick(context)
                                val base = parsed ?: 0.0
                                onConfirm((base + preset.deltaQty).coerceAtLeast(0.001))
                            },
                            label = { Text(preset.formatLabel(), style = MaterialTheme.typography.labelLarge) },
                            modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid,
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) {
                Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
