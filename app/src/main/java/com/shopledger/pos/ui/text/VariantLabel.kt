package com.shopledger.pos.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R

/**
 * `ItemVariantEntity.buildVariantSummary()` stores "Standard" for a variant with no size, material or colour. The stored
 * text stays as it is (old sales keep it); screens and shared receipts show the translated label instead.
 */
private const val STORED_STANDARD_SUMMARY = "Standard"

/** [standardLabel] when this stored summary is blank or the stored "Standard" marker, otherwise the summary itself. */
fun String.orStandard(standardLabel: String): String =
    if (isBlank() || this == STORED_STANDARD_SUMMARY) standardLabel else this

/** This stored variant summary as shown to the shopkeeper, in the app language. */
@Composable
fun String.variantLabel(): String = orStandard(stringResource(R.string.item_form_standard))
