package com.shopledger.pos.ui.screens.items

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import java.util.Locale

internal data class QuantityPreset(
    val deltaQty: Double,
    @StringRes val labelRes: Int,
    val arg1: String? = null,
    val arg2: String? = null,
)

internal fun quantityPresetsForUnit(unit: String): List<QuantityPreset> =
    when (unit.trim().lowercase(Locale.ROOT)) {
        "kg", "kgs", "kilogram", "kilograms" -> listOf(
            QuantityPreset(0.25, R.string.preset_qty_250g),
            QuantityPreset(0.5, R.string.preset_qty_500g),
            QuantityPreset(1.0, R.string.preset_qty_unit, "1", "kg"),
            QuantityPreset(2.0, R.string.preset_qty_unit, "2", "kg"),
            QuantityPreset(5.0, R.string.preset_qty_unit, "5", "kg"),
        )
        "l", "ltr", "ltrs", "litre", "litres", "liter", "liters" -> listOf(
            QuantityPreset(0.25, R.string.preset_qty_250ml),
            QuantityPreset(0.5, R.string.preset_qty_500ml),
            QuantityPreset(1.0, R.string.preset_qty_unit, "1", "ltr"),
            QuantityPreset(5.0, R.string.preset_qty_unit, "5", "ltr"),
        )
        else -> listOf(
            QuantityPreset(5.0, R.string.preset_qty_plus, "5"),
            QuantityPreset(10.0, R.string.preset_qty_plus, "10"),
            QuantityPreset(12.0, R.string.preset_qty_plus, "12"),
            QuantityPreset(50.0, R.string.preset_qty_plus, "50"),
        )
    }

@Composable
internal fun QuantityPreset.formatLabel(): String = when {
    arg1 != null && arg2 != null -> stringResource(labelRes, arg1, arg2)
    arg1 != null -> stringResource(labelRes, arg1)
    else -> stringResource(labelRes)
}
