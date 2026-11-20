package com.shopledger.pos.ui.screens.inventory

import com.shopledger.pos.ui.text.categoryLabel
import com.shopledger.pos.ui.text.isUncategorizedMarker
import com.shopledger.pos.ui.text.variantLabel
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.shopledger.pos.R
import com.shopledger.pos.core.cipher.CipherReferenceStrip
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.draft.ItemDraftManager
import com.shopledger.pos.core.draft.SavedItemDraft
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.nlp.DuplicateDecisionType
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.nlp.TradeTaxonomyRegistry
import com.shopledger.pos.core.scanner.BarcodeScannerHelper
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.data.repository.LpsRepository
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.text.asString
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import java.util.UUID

/**
 * Temporary draft model for in-memory variant composition during brand-new item creation.
 */
data class VariantDraft(
    val id: String = UUID.randomUUID().toString(),
    var sizeLabel: String = "",
    var materialLabel: String = "",
    var colorFinishLabel: String = "",
    var barcode: String = "",
    var unit: String = "pcs",
    var purchasePriceInput: String = "",
    var sellingPriceInput: String = "",
    var stockQtyInput: String = "10",
    var lowStockInput: String = "5"
) {
    /** The size / material / colour text, or "" when none is filled in (the screen shows a translated "Standard"). */
    fun buildSummary(): String {
        val parts = listOf(sizeLabel, materialLabel, colorFinishLabel).filter { it.isNotBlank() }
        return parts.joinToString(" · ")
    }
}

/**
 * Unified editor modes:
 * - [AddNewItem]: Create a brand new item from scratch, with support for multiple distinct variants with their own prices.
 * - [ManageItem]: Unified management for an existing item and all of its variants (edit existing or add new variant).
 */
sealed class ItemEditorMode {
    data object AddNewItem : ItemEditorMode()
    data class ManageItem(
        val itemWithVariants: InventoryItemWithVariants,
        val initialVariantId: String? = null,
        val startInAddVariantMode: Boolean = false,
        val prefillSize: String = "",
        val prefillMaterial: String = "",
        val prefillColor: String = ""
    ) : ItemEditorMode()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditItemSheet(
    mode: ItemEditorMode,
    existingCategories: List<String> = emptyList(),
    isSellerUnlocked: Boolean = false,
    onCheckDuplicate: (name: String, size: String, material: String, color: String, excludeItemId: String?) -> DuplicateCheckResult,
    onSaveNewItemWithVariants: (
        displayName: String,
        category: String,
        variants: List<LpsRepository.NewVariantSpec>
    ) -> Unit,
    onSaveVariantToParent: (
        item: InventoryItemEntity,
        newDisplayName: String,
        newCategory: String,
        size: String,
        material: String,
        color: String,
        unit: String,
        purchasePaise: Long,
        sellingPaise: Long,
        stockQty: Double,
        lowStockAlert: Double,
        barcode: String
    ) -> Unit,
    onUpdateExisting: (
        item: InventoryItemEntity,
        newName: String,
        newCategory: String,
        variant: ItemVariantEntity,
        newSize: String,
        newMaterial: String,
        newColor: String,
        newUnit: String,
        newPurchasePaise: Long,
        newSellingPaise: Long,
        newStockQty: Double,
        newLowStockAlert: Double,
        newBarcode: String
    ) -> Unit,
    onDeleteItemOrVariant: (itemId: String, variantId: String?, totalVariantsCount: Int) -> Unit,
    onSwitchToManageItem: (
        itemWithVariants: InventoryItemWithVariants,
        initialVariantId: String?,
        startInAddVariantMode: Boolean,
        prefillSize: String,
        prefillMaterial: String,
        prefillColor: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val activeVertical by SyncManager.shopVerticalFlow.collectAsState()
    val activePack = remember(activeVertical) { TradeTaxonomyRegistry.getPack(activeVertical) }

    val managedItemWithVariants = (mode as? ItemEditorMode.ManageItem)?.itemWithVariants
    val variants = managedItemWithVariants?.variants.orEmpty()
    val initialStartInAdd = (mode as? ItemEditorMode.ManageItem)?.startInAddVariantMode ?: false

    val existingSavedDraft = remember(mode) {
        if (mode is ItemEditorMode.AddNewItem) ItemDraftManager.draftFlow.value else null
    }
    var isDraftClearedOrSaved by remember(mode) { mutableStateOf(false) }

    val draftVariants = remember(mode) {
        if (mode is ItemEditorMode.AddNewItem && existingSavedDraft != null && existingSavedDraft.variants.isNotEmpty()) {
            mutableStateListOf<VariantDraft>().apply {
                addAll(existingSavedDraft.variants.map { it.copy() })
            }
        } else {
            mutableStateListOf(
                VariantDraft(
                    unit = activeVertical.defaultUnit
                )
            )
        }
    }
    var activeDraftIndex by remember(mode) {
        mutableIntStateOf(
            if (mode is ItemEditorMode.AddNewItem && existingSavedDraft != null) {
                existingSavedDraft.activeDraftIndex.coerceIn(0, (draftVariants.size - 1).coerceAtLeast(0))
            } else 0
        )
    }
    val initialDraftVariant = remember(mode) {
        if (mode is ItemEditorMode.AddNewItem) draftVariants.getOrNull(activeDraftIndex) else null
    }

    var isAddingNewVariant by remember(mode) { mutableStateOf(initialStartInAdd) }

    var selectedVariantId by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && !initialStartInAdd) {
                mode.initialVariantId ?: variants.firstOrNull()?.id
            } else null
        )
    }

    val activeVariant = remember(selectedVariantId, variants, isAddingNewVariant) {
        if (!isAddingNewVariant && variants.isNotEmpty()) {
            variants.firstOrNull { it.id == selectedVariantId } ?: variants.first()
        } else null
    }

    var itemName by remember(mode) {
        mutableStateOf(
            when (mode) {
                is ItemEditorMode.AddNewItem -> existingSavedDraft?.itemName.orEmpty()
                is ItemEditorMode.ManageItem -> mode.itemWithVariants.item.displayName
            }
        )
    }

    var category by remember(mode) {
        mutableStateOf(
            when (mode) {
                is ItemEditorMode.AddNewItem -> existingSavedDraft?.category?.takeIf { it.isNotBlank() }
                    ?: activePack.defaultCategories.firstOrNull()
                    ?: "Uncategorized"
                is ItemEditorMode.ManageItem -> mode.itemWithVariants.item.category
            }
        )
    }

    var sizeLabel by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.sizeLabel.orEmpty()
                mode is ItemEditorMode.ManageItem && mode.startInAddVariantMode -> mode.prefillSize
                else -> activeVariant?.sizeLabel.orEmpty()
            }
        )
    }

    var materialLabel by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.materialLabel.orEmpty()
                mode is ItemEditorMode.ManageItem && mode.startInAddVariantMode -> mode.prefillMaterial
                else -> activeVariant?.materialLabel.orEmpty()
            }
        )
    }

    var colorFinishLabel by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.colorFinishLabel.orEmpty()
                mode is ItemEditorMode.ManageItem && mode.startInAddVariantMode -> mode.prefillColor
                else -> activeVariant?.colorFinishLabel.orEmpty()
            }
        )
    }

    var barcodeInput by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.barcode.orEmpty()
                mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode -> activeVariant?.barcode.orEmpty()
                else -> ""
            }
        )
    }

    var unit by remember(mode) {
        mutableStateOf(
            when (mode) {
                is ItemEditorMode.AddNewItem -> initialDraftVariant?.unit?.takeIf { it.isNotBlank() } ?: activeVertical.defaultUnit
                is ItemEditorMode.ManageItem -> activeVariant?.unit ?: variants.firstOrNull()?.unit ?: activeVertical.defaultUnit
            }
        )
    }

    var purchasePriceInput by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.purchasePriceInput.orEmpty()
                mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode ->
                    activeVariant?.let { IndianCurrencyFormatter.paiseToEditableString(it.purchasePricePaise) }.orEmpty()
                else -> ""
            }
        )
    }

    var sellingPriceInput by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.sellingPriceInput.orEmpty()
                mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode ->
                    activeVariant?.let { IndianCurrencyFormatter.paiseToEditableString(it.sellingPricePaise) }.orEmpty()
                else -> ""
            }
        )
    }

    var stockQtyInput by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.stockQtyInput?.takeIf { it.isNotBlank() } ?: "10"
                mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode ->
                    activeVariant?.let { IndianCurrencyFormatter.formatQuantity(it.stockQuantity) } ?: "10"
                else -> "10"
            }
        )
    }

    var lowStockInput by remember(mode) {
        mutableStateOf(
            when {
                mode is ItemEditorMode.AddNewItem -> initialDraftVariant?.lowStockInput?.takeIf { it.isNotBlank() } ?: "5"
                mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode ->
                    activeVariant?.let { IndianCurrencyFormatter.formatQuantity(it.lowStockThreshold) } ?: "5"
                else -> "5"
            }
        )
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteVariantConfirm by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<UiText?>(null) }
    fun failValidation(@StringRes title: Int, message: UiText) {
        validationError = message
        AppErrorManager.reportWarning(UiText.Res(title), message)
    }

    // Synchronize current inputs into draft when creating brand new item
    fun saveCurrentIntoDraft(draft: VariantDraft) {
        draft.sizeLabel = sizeLabel
        draft.materialLabel = materialLabel
        draft.colorFinishLabel = colorFinishLabel
        draft.barcode = barcodeInput
        draft.unit = unit
        draft.purchasePriceInput = purchasePriceInput
        draft.sellingPriceInput = sellingPriceInput
        draft.stockQtyInput = stockQtyInput
        draft.lowStockInput = lowStockInput
    }

    fun loadDraft(draft: VariantDraft) {
        sizeLabel = draft.sizeLabel
        materialLabel = draft.materialLabel
        colorFinishLabel = draft.colorFinishLabel
        barcodeInput = draft.barcode
        unit = draft.unit
        purchasePriceInput = draft.purchasePriceInput
        sellingPriceInput = draft.sellingPriceInput
        stockQtyInput = draft.stockQtyInput
        lowStockInput = draft.lowStockInput
        validationError = null
    }

    fun persistAddDraft(): Boolean {
        if (mode !is ItemEditorMode.AddNewItem || isDraftClearedOrSaved) return false
        draftVariants.getOrNull(activeDraftIndex)?.let { saveCurrentIntoDraft(it) }
        val draftSnapshot = SavedItemDraft(
            itemName = itemName,
            category = category,
            variants = draftVariants.map { it.copy() },
            activeDraftIndex = activeDraftIndex
        )
        ItemDraftManager.saveDraft(context, draftSnapshot)
        return draftSnapshot.hasContent
    }

    fun clearAddDraftAndReset() {
        isDraftClearedOrSaved = true
        ItemDraftManager.clearDraft(context)
        val freshVariant = VariantDraft(unit = activeVertical.defaultUnit)
        draftVariants.clear()
        draftVariants.add(freshVariant)
        activeDraftIndex = 0
        itemName = ""
        category = activePack.defaultCategories.firstOrNull() ?: "Uncategorized"
        loadDraft(freshVariant)
        AppErrorManager.reportInfo(UiText.Res(R.string.msg_draft_cleared_title), UiText.Res(R.string.msg_draft_cleared))
    }

    LaunchedEffect(
        mode,
        itemName,
        category,
        sizeLabel,
        materialLabel,
        colorFinishLabel,
        barcodeInput,
        unit,
        purchasePriceInput,
        sellingPriceInput,
        stockQtyInput,
        lowStockInput,
        activeDraftIndex,
        draftVariants.size
    ) {
        if (mode is ItemEditorMode.AddNewItem && !isDraftClearedOrSaved) {
            persistAddDraft()
        }
    }

    fun selectVariant(v: ItemVariantEntity) {
        isAddingNewVariant = false
        selectedVariantId = v.id
        sizeLabel = v.sizeLabel
        materialLabel = v.materialLabel
        colorFinishLabel = v.colorFinishLabel
        barcodeInput = v.barcode
        unit = v.unit
        purchasePriceInput = IndianCurrencyFormatter.paiseToEditableString(v.purchasePricePaise)
        sellingPriceInput = IndianCurrencyFormatter.paiseToEditableString(v.sellingPricePaise)
        stockQtyInput = IndianCurrencyFormatter.formatQuantity(v.stockQuantity)
        lowStockInput = IndianCurrencyFormatter.formatQuantity(v.lowStockThreshold)
        validationError = null
    }

    fun startAddNewVariant() {
        isAddingNewVariant = true
        selectedVariantId = null
        sizeLabel = ""
        materialLabel = ""
        colorFinishLabel = ""
        barcodeInput = ""
        unit = unit.ifBlank { variants.firstOrNull()?.unit ?: activeVertical.defaultUnit }
        purchasePriceInput = ""
        sellingPriceInput = ""
        stockQtyInput = "10"
        lowStockInput = "5"
        validationError = null
    }

    // Live Smart Duplicate & Variant Understanding
    val excludeItemId = (mode as? ItemEditorMode.ManageItem)?.itemWithVariants?.item?.id
    val duplicateCheckResult = remember(itemName, sizeLabel, materialLabel, colorFinishLabel, mode) {
        if (mode is ItemEditorMode.AddNewItem && itemName.trim().length >= 2) {
            onCheckDuplicate(itemName, sizeLabel, materialLabel, colorFinishLabel, excludeItemId)
        } else {
            null
        }
    }

    val categories = remember(existingCategories, activePack) {
        (activePack.defaultCategories + existingCategories).filter { it.isNotBlank() }.distinct()
    }
    val units = remember(activePack) { activePack.defaultUnits }

    ModalBottomSheet(
        onDismissRequest = {
            if (mode is ItemEditorMode.AddNewItem && !isDraftClearedOrSaved) {
                if (persistAddDraft()) {
                    AppErrorManager.reportInfo(UiText.Res(R.string.msg_draft_saved_title), UiText.Res(R.string.msg_draft_saved))
                }
            }
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            FloatingAppMessageHost(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
            ) {
                // SHEET HEADER: title and optional clear-draft / delete action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            when (mode) {
                                is ItemEditorMode.AddNewItem ->
                                    if (existingSavedDraft?.hasContent == true && !isDraftClearedOrSaved) R.string.item_form_title_add_draft
                                    else R.string.item_form_title_add
                                is ItemEditorMode.ManageItem -> R.string.item_form_title_edit
                            }
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )

                    if (mode is ItemEditorMode.AddNewItem) {
                        val hasAnyDraftInput = itemName.isNotBlank() ||
                            sizeLabel.isNotBlank() ||
                            materialLabel.isNotBlank() ||
                            colorFinishLabel.isNotBlank() ||
                            barcodeInput.isNotBlank() ||
                            purchasePriceInput.isNotBlank() ||
                            sellingPriceInput.isNotBlank() ||
                            draftVariants.size > 1
                        if (hasAnyDraftInput) {
                            TextButton(
                                onClick = { clearAddDraftAndReset() },
                                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    stringResource(R.string.item_form_clear_draft),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = ShopLedgerTokens.spacingXs)
                                )
                            }
                        }
                    } else if (mode is ItemEditorMode.ManageItem) {
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                stringResource(R.string.action_delete),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = ShopLedgerTokens.spacingXs)
                            )
                        }
                    }
                }

                // CARD 1: item name and user-writable category
                SectionCard {
                    OutlinedTextField(
                        value = itemName,
                        onValueChange = {
                            itemName = it
                            validationError = null
                        },
                        label = { Text(stringResource(R.string.item_form_name)) },
                        placeholder = { Text(stringResource(R.string.item_form_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Helper if the user typed a size / material / colour inside the name
                    val sig = duplicateCheckResult?.candidateSignature
                    if (sig != null && (sig.canonicalSize.isNotEmpty() || sig.canonicalMaterial.isNotEmpty() || sig.canonicalColorFinish.isNotEmpty())) {
                        Surface(
                            onClick = {
                                if (sizeLabel.isBlank() && sig.canonicalSize.isNotEmpty()) {
                                    sizeLabel = sig.canonicalSize
                                }
                                if (materialLabel.isBlank() && sig.canonicalMaterial.isNotEmpty()) {
                                    materialLabel = sig.canonicalMaterial
                                }
                                if (colorFinishLabel.isBlank() && sig.canonicalColorFinish.isNotEmpty()) {
                                    colorFinishLabel = sig.canonicalColorFinish
                                }
                                itemName = sig.suggestedCleanBaseName
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.minTouch),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.item_form_smart_extract,
                                        listOf(sig.canonicalSize, sig.canonicalMaterial, sig.canonicalColorFinish)
                                            .filter { it.isNotEmpty() }
                                            .joinToString(" · ")
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    // Editable category text field
                    OutlinedTextField(
                        // The stored "Uncategorized" marker is shown as the translated placeholder, not as typed text.
                        value = if (category.isUncategorizedMarker()) "" else category,
                        onValueChange = {
                            category = it
                            validationError = null
                        },
                        label = { Text(stringResource(R.string.item_form_category)) },
                        placeholder = {
                            Text(
                                if (category.isUncategorizedMarker()) stringResource(R.string.category_uncategorized)
                                else stringResource(R.string.item_form_category_hint)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick category suggestions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = category.equals(cat, ignoreCase = true) || (category.isBlank() && cat.isUncategorizedMarker()),
                                onClick = { category = cat },
                                label = { Text(cat.categoryLabel(), style = MaterialTheme.typography.bodyMedium) },
                            )
                        }
                    }
                }

                // Smart duplicate / existing parent item banner (new item flow)
                if (duplicateCheckResult != null && duplicateCheckResult.decision != DuplicateDecisionType.UNIQUE_NEW_ITEM) {
                    val matched = duplicateCheckResult.matchedItem
                    if (matched != null) {
                        val isExactDup = duplicateCheckResult.decision == DuplicateDecisionType.EXACT_DUPLICATE
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = if (isExactDup) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = if (isExactDup) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Column(
                                modifier = Modifier.padding(ShopLedgerTokens.spacingM),
                                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = stringResource(if (isExactDup) R.string.item_form_dup_exact_title else R.string.item_form_dup_similar_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        if (isExactDup) R.string.item_form_dup_exact_msg else R.string.item_form_dup_similar_msg,
                                        matched.item.displayName
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (isExactDup) {
                                    PrimaryActionButton(
                                        text = stringResource(R.string.item_form_edit_existing),
                                        onClick = {
                                            onSwitchToManageItem(
                                                matched,
                                                duplicateCheckResult.matchedVariantId,
                                                false,
                                                "", "", ""
                                            )
                                        },
                                        containerColor = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    PrimaryActionButton(
                                        text = stringResource(R.string.item_form_add_as_variant, matched.item.displayName),
                                        onClick = {
                                            val cSig = duplicateCheckResult.candidateSignature
                                            onSwitchToManageItem(
                                                matched,
                                                null,
                                                true,
                                                sizeLabel.ifBlank { cSig.canonicalSize },
                                                materialLabel.ifBlank { cSig.canonicalMaterial },
                                                colorFinishLabel.ifBlank { cSig.canonicalColorFinish }
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // VARIANTS SELECTOR (different variants have different prices)
                // Available both when adding brand-new items (multiple drafts) and managing existing items
                val standardLabel = stringResource(R.string.item_form_standard)
                when (mode) {
                    is ItemEditorMode.AddNewItem -> {
                        SectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.item_form_variants_count, draftVariants.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                if (draftVariants.size > 1) {
                                    TextButton(
                                        onClick = {
                                            if (draftVariants.size > 1) {
                                                draftVariants.removeAt(activeDraftIndex)
                                                activeDraftIndex = activeDraftIndex.coerceIn(0, draftVariants.lastIndex)
                                                loadDraft(draftVariants[activeDraftIndex])
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.item_form_remove_variant),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                draftVariants.forEachIndexed { idx, draft ->
                                    val summary = draft.buildSummary().ifEmpty { standardLabel }
                                    val pricePart = if (draft.sellingPriceInput.isNotBlank()) " • ₹${draft.sellingPriceInput}" else ""
                                    FilterChip(
                                        selected = idx == activeDraftIndex,
                                        onClick = {
                                            if (idx != activeDraftIndex) {
                                                saveCurrentIntoDraft(draftVariants[activeDraftIndex])
                                                activeDraftIndex = idx
                                                loadDraft(draftVariants[idx])
                                            }
                                        },
                                        label = { Text("$summary$pricePart", style = MaterialTheme.typography.bodyMedium) },
                                    )
                                }

                                // [+ Add variant] chip for another variant with its own price
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        saveCurrentIntoDraft(draftVariants[activeDraftIndex])
                                        val nextDraft = VariantDraft(unit = unit.ifBlank { "pcs" })
                                        draftVariants.add(nextDraft)
                                        activeDraftIndex = draftVariants.lastIndex
                                        loadDraft(nextDraft)
                                    },
                                    label = { Text(stringResource(R.string.item_form_add_variant), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                            }

                            Text(
                                text = stringResource(
                                    R.string.item_form_configuring,
                                    activeDraftIndex + 1,
                                    draftVariants[activeDraftIndex].buildSummary().ifEmpty { standardLabel }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    is ItemEditorMode.ManageItem -> {
                        SectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.item_form_variants_count, variants.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                if (!isAddingNewVariant && variants.size > 1 && activeVariant != null) {
                                    TextButton(
                                        onClick = { showDeleteVariantConfirm = true },
                                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.item_form_delete_variant),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                variants.forEach { v ->
                                    val isSelected = !isAddingNewVariant && v.id == (activeVariant?.id ?: selectedVariantId)
                                    val summary = v.buildVariantSummary().variantLabel()
                                    val priceLabel = IndianCurrencyFormatter.formatPaise(v.sellingPricePaise)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectVariant(v) },
                                        label = { Text("$summary • $priceLabel", style = MaterialTheme.typography.bodyMedium) },
                                    )
                                }

                                // [+ Add variant] chip
                                FilterChip(
                                    selected = isAddingNewVariant,
                                    onClick = { startAddNewVariant() },
                                    label = { Text(stringResource(R.string.item_form_add_variant), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                            }

                            Text(
                                text = if (isAddingNewVariant) {
                                    stringResource(R.string.item_form_adding_under, itemName)
                                } else {
                                    if (isSellerUnlocked) {
                                        stringResource(
                                            R.string.item_form_editing_summary,
                                            activeVariant?.buildVariantSummary()?.variantLabel() ?: standardLabel,
                                            IndianCurrencyFormatter.formatPaise(activeVariant?.purchasePricePaise ?: 0),
                                            IndianCurrencyFormatter.formatPaise(activeVariant?.sellingPricePaise ?: 0)
                                        )
                                    } else {
                                        stringResource(
                                            R.string.item_form_editing_summary_locked,
                                            activeVariant?.buildVariantSummary()?.variantLabel() ?: standardLabel,
                                            IndianCurrencyFormatter.formatPaise(activeVariant?.sellingPricePaise ?: 0)
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isAddingNewVariant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // CARD 2: specifications and pricing form
                val parsedPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
                val parsedSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
                val attr1 = verticalAttr(activeVertical, 1)
                val attr2 = verticalAttr(activeVertical, 2)
                val attr3 = verticalAttr(activeVertical, 3)

                SectionCard(
                    title = stringResource(if (isAddingNewVariant) R.string.item_form_details_new else R.string.item_form_details)
                ) {
                    OutlinedTextField(
                        value = sizeLabel,
                        onValueChange = {
                            sizeLabel = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].sizeLabel = it
                            }
                        },
                        label = { Text(stringResource(attr1.label)) },
                        placeholder = { Text(stringResource(attr1.hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = materialLabel,
                        onValueChange = {
                            materialLabel = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].materialLabel = it
                            }
                        },
                        label = { Text(stringResource(attr2.label)) },
                        placeholder = { Text(stringResource(attr2.hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = colorFinishLabel,
                        onValueChange = {
                            colorFinishLabel = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].colorFinishLabel = it
                            }
                        },
                        label = { Text(stringResource(attr3.label)) },
                        placeholder = { Text(stringResource(attr3.hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = unit,
                        onValueChange = {
                            unit = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].unit = it
                            }
                        },
                        label = { Text(stringResource(R.string.item_form_unit)) },
                        placeholder = { Text(stringResource(R.string.item_form_unit_hint, activeVertical.defaultUnit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Unit quick-chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                    ) {
                        units.forEach { u ->
                            FilterChip(
                                selected = unit.equals(u, ignoreCase = true),
                                onClick = {
                                    unit = u
                                    if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                        draftVariants[activeDraftIndex].unit = u
                                    }
                                },
                                label = { Text(u, style = MaterialTheme.typography.bodyMedium) },
                            )
                        }
                    }

                    // Barcode / SKU with 1-tap camera scan button
                    val scanBarcodeDesc = stringResource(R.string.cd_scan_barcode)
                    OutlinedTextField(
                        value = barcodeInput,
                        onValueChange = {
                            barcodeInput = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].barcode = it
                            }
                        },
                        label = { Text(stringResource(R.string.item_form_barcode)) },
                        placeholder = { Text(stringResource(R.string.item_form_barcode_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    BarcodeScannerHelper.scanBarcode(
                                        context = context,
                                        onScanned = { scanned ->
                                            barcodeInput = scanned
                                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                                draftVariants[activeDraftIndex].barcode = scanned
                                            }
                                        },
                                        onError = { _ ->
                                            Toast.makeText(context, R.string.err_scanner_detail, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = scanBarcodeDesc,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )

                    // Purchase price + selling price
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
                    ) {
                        OutlinedTextField(
                            value = purchasePriceInput,
                            onValueChange = {
                                purchasePriceInput = it
                                if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                    draftVariants[activeDraftIndex].purchasePriceInput = it
                                }
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.item_form_purchase)) },
                            placeholder = { Text(stringResource(R.string.item_form_purchase_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = sellingPriceInput,
                            onValueChange = {
                                sellingPriceInput = it
                                if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                    draftVariants[activeDraftIndex].sellingPriceInput = it
                                }
                                validationError = null
                            },
                            label = { Text(stringResource(R.string.item_form_selling)) },
                            placeholder = { Text(stringResource(R.string.item_form_selling_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Live coded symbol preview + profit / gain
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.item_form_coded_preview),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                if (isSellerUnlocked && parsedPurchasePaise > 0 && parsedSellingPaise > 0) {
                                    val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
                                        parsedPurchasePaise,
                                        parsedSellingPaise,
                                        1.0
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.item_form_profit_preview,
                                            IndianCurrencyFormatter.formatPaise(analysis.totalProfitPaise, showPlusSign = true),
                                            IndianCurrencyFormatter.formatPercentage(analysis.gainMarkupPercent)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            CodedPurchasePriceBadge(
                                purchasePricePaise = parsedPurchasePaise,
                                isSellerUnlocked = false,
                                glyphSize = 18.dp
                            )
                        }
                    }

                    if (isSellerUnlocked) CipherReferenceStrip(modifier = Modifier.fillMaxWidth())

                    // Current stock + low stock alert
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
                    ) {
                        OutlinedTextField(
                            value = stockQtyInput,
                            onValueChange = {
                                stockQtyInput = it
                                if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                    draftVariants[activeDraftIndex].stockQtyInput = it
                                }
                            },
                            label = { Text(stringResource(R.string.item_form_stock)) },
                            placeholder = { Text(stringResource(R.string.item_form_stock_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = lowStockInput,
                            onValueChange = {
                                lowStockInput = it
                                if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                    draftVariants[activeDraftIndex].lowStockInput = it
                                }
                            },
                            label = { Text(stringResource(R.string.item_form_low_stock)) },
                            placeholder = { Text(stringResource(R.string.item_form_low_stock_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                validationError?.let { vErr ->
                    Text(
                        text = vErr.asString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingXs)
                    )
                }

                // BOTTOM ACTIONS: save, then save draft / close
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = ShopLedgerTokens.spacingXl),
                    verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                ) {
                    PrimaryActionButton(
                        text = when {
                            mode is ItemEditorMode.AddNewItem ->
                                if (draftVariants.size > 1) stringResource(R.string.item_form_save_n, draftVariants.size)
                                else stringResource(R.string.item_form_save)
                            mode is ItemEditorMode.ManageItem && isAddingNewVariant -> stringResource(R.string.item_form_add_variant_btn)
                            else -> stringResource(R.string.item_form_save_changes)
                        },
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()

                            if (itemName.isBlank()) {
                                failValidation(R.string.err_missing_name_title, UiText.Res(R.string.msg_item_name_required))
                                return@PrimaryActionButton
                            }
                            val cleanCategory = category.trim().ifEmpty { "Uncategorized" }

                            when (mode) {
                                is ItemEditorMode.AddNewItem -> {
                                    if (duplicateCheckResult?.decision == DuplicateDecisionType.EXACT_DUPLICATE) {
                                        failValidation(R.string.err_duplicate_item_title, UiText.Res(R.string.msg_item_exists))
                                        return@PrimaryActionButton
                                    }

                                    saveCurrentIntoDraft(draftVariants[activeDraftIndex])

                                    val specs = mutableListOf<LpsRepository.NewVariantSpec>()
                                    draftVariants.forEachIndexed { index, draft ->
                                        val finalSelling = IndianCurrencyFormatter.parseRupeesToPaise(draft.sellingPriceInput)
                                        val finalPurchase = IndianCurrencyFormatter.parseRupeesToPaise(draft.purchasePriceInput)
                                        if (finalSelling <= 0L) {
                                            val hint = if (draft.buildSummary().isEmpty()) context.getString(R.string.msg_variant_number, index + 1) else draft.buildSummary()
                                            failValidation(R.string.err_price_missing_title, UiText.Res(R.string.msg_variant_price, listOf(hint)))
                                            activeDraftIndex = index
                                            loadDraft(draft)
                                            return@PrimaryActionButton
                                        }
                                        specs.add(
                                            LpsRepository.NewVariantSpec(
                                                sizeLabel = draft.sizeLabel,
                                                materialLabel = draft.materialLabel,
                                                colorFinishLabel = draft.colorFinishLabel,
                                                unit = draft.unit.ifBlank { activeVertical.defaultUnit },
                                                purchasePricePaise = finalPurchase,
                                                sellingPricePaise = finalSelling,
                                                stockQuantity = draft.stockQtyInput.toDoubleOrNull() ?: 10.0,
                                                lowStockThreshold = draft.lowStockInput.toDoubleOrNull() ?: 5.0,
                                                barcode = draft.barcode
                                            )
                                        )
                                    }

                                    isDraftClearedOrSaved = true
                                    ItemDraftManager.clearDraft(context)
                                    onSaveNewItemWithVariants(itemName, cleanCategory, specs)
                                    AppErrorManager.reportSuccess(UiText.Res(R.string.msg_item_added_title), UiText.Res(R.string.msg_item_added, listOf(itemName)))
                                }
                                is ItemEditorMode.ManageItem -> {
                                    val finalPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
                                    val finalSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
                                    val finalStock = stockQtyInput.toDoubleOrNull() ?: 0.0
                                    val finalLowAlert = lowStockInput.toDoubleOrNull() ?: 5.0

                                    if (finalSellingPaise <= 0L) {
                                        failValidation(R.string.err_price_missing_title, UiText.Res(R.string.msg_price_required))
                                        return@PrimaryActionButton
                                    }

                                    if (isAddingNewVariant) {
                                        onSaveVariantToParent(
                                            mode.itemWithVariants.item,
                                            itemName,
                                            cleanCategory,
                                            sizeLabel,
                                            materialLabel,
                                            colorFinishLabel,
                                            unit,
                                            finalPurchasePaise,
                                            finalSellingPaise,
                                            finalStock,
                                            finalLowAlert,
                                            barcodeInput
                                        )
                                    } else {
                                        val targetVar = activeVariant ?: mode.itemWithVariants.variants.first()
                                        onUpdateExisting(
                                            mode.itemWithVariants.item,
                                            itemName,
                                            cleanCategory,
                                            targetVar,
                                            sizeLabel,
                                            materialLabel,
                                            colorFinishLabel,
                                            unit,
                                            finalPurchasePaise,
                                            finalSellingPaise,
                                            finalStock,
                                            finalLowAlert,
                                            barcodeInput
                                        )
                                    }
                                }
                            }
                            onDismiss()
                        },
                    )

                    SecondaryActionButton(
                        text = stringResource(R.string.item_form_save_draft_close),
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            if (mode is ItemEditorMode.AddNewItem && !isDraftClearedOrSaved) {
                                if (persistAddDraft()) {
                                    AppErrorManager.reportInfo(UiText.Res(R.string.msg_draft_saved_title), UiText.Res(R.string.msg_draft_saved))
                                }
                            }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // Confirm dialog for deleting the entire item
    if (showDeleteConfirm && mode is ItemEditorMode.ManageItem) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.item_form_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.item_form_delete_message,
                        mode.itemWithVariants.variants.size,
                        mode.itemWithVariants.item.displayName,
                        mode.itemWithVariants.variants.size
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteItemOrVariant(mode.itemWithVariants.item.id, null, 1)
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(
                        stringResource(R.string.item_form_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    // Confirm dialog for deleting a specific variant
    if (showDeleteVariantConfirm && mode is ItemEditorMode.ManageItem && activeVariant != null) {
        AlertDialog(
            onDismissRequest = { showDeleteVariantConfirm = false },
            title = { Text(stringResource(R.string.item_form_delete_variant_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.item_form_delete_variant_message,
                        activeVariant.buildVariantSummary().variantLabel(),
                        mode.itemWithVariants.item.displayName
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteVariantConfirm = false
                        onDeleteItemOrVariant(mode.itemWithVariants.item.id, activeVariant.id, mode.itemWithVariants.variants.size)
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(
                        stringResource(R.string.item_form_delete_variant_confirm),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteVariantConfirm = false },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

private class AttrRes(@StringRes val label: Int, @StringRes val hint: Int)

/** Field label and example for the 1st, 2nd or 3rd variant attribute of a shop type, in the app language. */
private fun verticalAttr(vertical: ShopVertical, index: Int): AttrRes = when (vertical) {
    ShopVertical.HARDWARE -> when (index) {
        1 -> AttrRes(R.string.attr_hardware_1, R.string.attr_hardware_1_hint)
        2 -> AttrRes(R.string.attr_hardware_2, R.string.attr_hardware_2_hint)
        else -> AttrRes(R.string.attr_hardware_3, R.string.attr_hardware_3_hint)
    }
    ShopVertical.KIRANA -> when (index) {
        1 -> AttrRes(R.string.attr_kirana_1, R.string.attr_kirana_1_hint)
        2 -> AttrRes(R.string.attr_kirana_2, R.string.attr_kirana_2_hint)
        else -> AttrRes(R.string.attr_kirana_3, R.string.attr_kirana_3_hint)
    }
    ShopVertical.PHARMA -> when (index) {
        1 -> AttrRes(R.string.attr_pharma_1, R.string.attr_pharma_1_hint)
        2 -> AttrRes(R.string.attr_pharma_2, R.string.attr_pharma_2_hint)
        else -> AttrRes(R.string.attr_pharma_3, R.string.attr_pharma_3_hint)
    }
    ShopVertical.ELECTRONICS -> when (index) {
        1 -> AttrRes(R.string.attr_electronics_1, R.string.attr_electronics_1_hint)
        2 -> AttrRes(R.string.attr_electronics_2, R.string.attr_electronics_2_hint)
        else -> AttrRes(R.string.attr_electronics_3, R.string.attr_electronics_3_hint)
    }
    ShopVertical.GENERAL -> when (index) {
        1 -> AttrRes(R.string.attr_general_1, R.string.attr_general_1_hint)
        2 -> AttrRes(R.string.attr_general_2, R.string.attr_general_2_hint)
        else -> AttrRes(R.string.attr_general_3, R.string.attr_general_3_hint)
    }
}
