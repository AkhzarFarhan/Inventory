package com.shopledger.pos.ui.screens.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.ui.theme.StockStatus

internal fun statusOf(v: ItemVariantEntity): StockStatus = when {
    v.isOversold -> StockStatus.OVERSOLD
    v.isOutOfStock -> StockStatus.OUT
    v.isLowStock -> StockStatus.LOW
    else -> StockStatus.OK
}

internal fun qty(v: ItemVariantEntity): String = IndianCurrencyFormatter.formatQuantity(v.stockQuantity)

@Composable
internal fun variantStockText(v: ItemVariantEntity): String = when (statusOf(v)) {
    StockStatus.OVERSOLD -> stringResource(R.string.stock_oversold, qty(v), v.unit)
    StockStatus.OUT -> stringResource(R.string.stock_out)
    StockStatus.LOW -> stringResource(R.string.stock_low, qty(v), v.unit)
    StockStatus.OK -> stringResource(R.string.stock_in_stock, qty(v), v.unit)
}
