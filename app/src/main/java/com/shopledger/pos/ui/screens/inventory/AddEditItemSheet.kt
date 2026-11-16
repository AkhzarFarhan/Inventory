package com.shopledger.pos.ui.screens.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.core.cipher.CipherReferenceStrip
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.nlp.DuplicateCheckResult
import com.shopledger.pos.core.nlp.DuplicateDecisionType
import com.shopledger.pos.core.nlp.TradeTaxonomyRegistry
import com.shopledger.pos.core.scanner.BarcodeScannerHelper
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.data.repository.LpsRepository
import com.shopledger.pos.ui.theme.CupertinoCard
import com.shopledger.pos.ui.theme.CupertinoColors
import com.shopledger.pos.ui.theme.CupertinoTextField
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
    fun buildSummary(): String {
        val parts = listOf(sizeLabel, materialLabel, colorFinishLabel).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "Standard" else parts.joinToString(" · ")
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

    val draftVariants = remember(mode) {
        mutableStateListOf(
            VariantDraft(
                unit = activeVertical.defaultUnit
            )
        )
    }
    var activeDraftIndex by remember(mode) { mutableIntStateOf(0) }

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
                is ItemEditorMode.AddNewItem -> ""
                is ItemEditorMode.ManageItem -> mode.itemWithVariants.item.displayName
            }
        )
    }

    var category by remember(mode) {
        mutableStateOf(
            when (mode) {
                is ItemEditorMode.AddNewItem -> activePack.defaultCategories.firstOrNull() ?: "Uncategorized"
                is ItemEditorMode.ManageItem -> mode.itemWithVariants.item.category
            }
        )
    }

    var sizeLabel by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && mode.startInAddVariantMode) {
                mode.prefillSize
            } else {
                activeVariant?.sizeLabel.orEmpty()
            }
        )
    }

    var materialLabel by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && mode.startInAddVariantMode) {
                mode.prefillMaterial
            } else {
                activeVariant?.materialLabel.orEmpty()
            }
        )
    }

    var colorFinishLabel by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && mode.startInAddVariantMode) {
                mode.prefillColor
            } else {
                activeVariant?.colorFinishLabel.orEmpty()
            }
        )
    }

    var barcodeInput by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode) {
                activeVariant?.barcode.orEmpty()
            } else ""
        )
    }

    var unit by remember(mode) {
        mutableStateOf(activeVariant?.unit ?: variants.firstOrNull()?.unit ?: activeVertical.defaultUnit)
    }

    var purchasePriceInput by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode) {
                activeVariant?.let { IndianCurrencyFormatter.paiseToEditableString(it.purchasePricePaise) }.orEmpty()
            } else ""
        )
    }

    var sellingPriceInput by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode) {
                activeVariant?.let { IndianCurrencyFormatter.paiseToEditableString(it.sellingPricePaise) }.orEmpty()
            } else ""
        )
    }

    var stockQtyInput by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode) {
                activeVariant?.let { IndianCurrencyFormatter.formatQuantity(it.stockQuantity) } ?: "10"
            } else "10"
        )
    }

    var lowStockInput by remember(mode) {
        mutableStateOf(
            if (mode is ItemEditorMode.ManageItem && !mode.startInAddVariantMode) {
                activeVariant?.let { IndianCurrencyFormatter.formatQuantity(it.lowStockThreshold) } ?: "5"
            } else "5"
        )
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteVariantConfirm by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }

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
        validationError = ""
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
        validationError = ""
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
        validationError = ""
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
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // SHEET HEADER: Clean title and optional Delete action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (mode) {
                        is ItemEditorMode.AddNewItem -> "Add Product"
                        is ItemEditorMode.ManageItem -> "Edit Product & Variants"
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupertinoColors.LabelPrimary
                )

                if (mode is ItemEditorMode.ManageItem) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = CupertinoColors.DestructiveRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Item",
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // CARD 1: Item Name & User-Writable Category (Requirement #2)
            CupertinoCard(contentPadding = PaddingValues(12.dp)) {
                CupertinoTextField(
                    value = itemName,
                    onValueChange = {
                        itemName = it
                        validationError = ""
                    },
                    label = "Product Name",
                    placeholder = "e.g. Product Name, Brand, or Trade Name",
                    capitalization = KeyboardCapitalization.Words
                )

                // Auto-extracted attributes helper pill if user typed size/material/color in the name
                val sig = duplicateCheckResult?.candidateSignature
                if (sig != null && (sig.canonicalSize.isNotEmpty() || sig.canonicalMaterial.isNotEmpty() || sig.canonicalColorFinish.isNotEmpty())) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CupertinoColors.SystemBlueTint)
                            .clickable {
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
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CupertinoColors.SystemBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Smart Extract: Fill Variant (${
                                listOf(sig.canonicalSize, sig.canonicalMaterial, sig.canonicalColorFinish)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" · ")
                            }) & clean name",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupertinoColors.SystemBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Editable User-Writable Category Text Field (Requirement #2)
                CupertinoTextField(
                    value = category,
                    onValueChange = {
                        category = it
                        validationError = ""
                    },
                    label = "Category (Type custom or select below)",
                    placeholder = "e.g. Fittings, Staples, Tablets, Cables...",
                    capitalization = KeyboardCapitalization.Words
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Quick Category Suggestions Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        val selected = category.equals(cat, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                            border = BorderStroke(1.dp, if (selected) CupertinoColors.SystemBlue else CupertinoColors.Separator),
                            modifier = Modifier.clickable { category = cat }
                        ) {
                            Text(
                                text = cat,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else CupertinoColors.LabelPrimary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // Smart Duplicate / Existing Parent Item Detected Banner (New Item flow)
            if (duplicateCheckResult != null && duplicateCheckResult.decision != DuplicateDecisionType.UNIQUE_NEW_ITEM) {
                val matched = duplicateCheckResult.matchedItem
                if (matched != null) {
                    val isExactDup = duplicateCheckResult.decision == DuplicateDecisionType.EXACT_DUPLICATE
                    CupertinoCard(
                        containerColor = if (isExactDup) CupertinoColors.DestructiveTint else CupertinoColors.AmberTint,
                        borderColor = if (isExactDup) CupertinoColors.DestructiveRed else CupertinoColors.AmberWarning,
                        contentPadding = PaddingValues(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (isExactDup) CupertinoColors.DestructiveRed else CupertinoColors.AmberWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isExactDup) "Duplicate Item Detected!" else "Existing Parent Item Found!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isExactDup) CupertinoColors.DestructiveRed else CupertinoColors.AmberWarning
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = duplicateCheckResult.explanationMessage,
                            fontSize = 11.sp,
                            color = CupertinoColors.LabelPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isExactDup) {
                                Button(
                                    onClick = {
                                        onSwitchToManageItem(
                                            matched,
                                            duplicateCheckResult.matchedVariantId,
                                            false,
                                            "", "", ""
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CupertinoColors.DestructiveRed,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Edit Existing \"${matched.item.displayName}\" Instead",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Button(
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
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CupertinoColors.SystemBlue,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Add as Variant under \"${matched.item.displayName}\"",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // VARIANTS SELECTOR BAR (Requirement #1: Different variants have different prices)
            // Available both when adding brand-new items (multiple drafts) and managing existing items
            when (mode) {
                is ItemEditorMode.AddNewItem -> {
                    CupertinoCard(contentPadding = PaddingValues(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ITEM VARIANTS (${draftVariants.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.LabelMuted
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
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Remove Variant",
                                        color = CupertinoColors.DestructiveRed,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            draftVariants.forEachIndexed { idx, draft ->
                                val isSelected = idx == activeDraftIndex
                                val summary = draft.buildSummary()
                                val pricePart = if (draft.sellingPriceInput.isNotBlank()) " • ₹${draft.sellingPriceInput}" else ""
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.Separator
                                    ),
                                    modifier = Modifier.clickable {
                                        if (idx != activeDraftIndex) {
                                            saveCurrentIntoDraft(draftVariants[activeDraftIndex])
                                            activeDraftIndex = idx
                                            loadDraft(draftVariants[idx])
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "$summary$pricePart",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else CupertinoColors.LabelPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            // Unified [+ Add Variant] chip for adding another variant with distinct price
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = CupertinoColors.SystemBlueTint,
                                border = BorderStroke(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.4f)),
                                modifier = Modifier.clickable {
                                    saveCurrentIntoDraft(draftVariants[activeDraftIndex])
                                    val nextDraft = VariantDraft(unit = unit.ifBlank { "pcs" })
                                    draftVariants.add(nextDraft)
                                    activeDraftIndex = draftVariants.lastIndex
                                    loadDraft(nextDraft)
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Variant",
                                        tint = CupertinoColors.SystemBlue,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Add Variant",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CupertinoColors.SystemBlue
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "● Configuring Variant #${activeDraftIndex + 1} (${draftVariants[activeDraftIndex].buildSummary()})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CupertinoColors.SystemBlue
                        )
                    }
                }

                is ItemEditorMode.ManageItem -> {
                    CupertinoCard(contentPadding = PaddingValues(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ITEM VARIANTS (${variants.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.LabelMuted
                            )

                            if (!isAddingNewVariant && variants.size > 1 && activeVariant != null) {
                                TextButton(
                                    onClick = { showDeleteVariantConfirm = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Delete This Variant",
                                        color = CupertinoColors.DestructiveRed,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            variants.forEach { v ->
                                val isSelected = !isAddingNewVariant && v.id == (activeVariant?.id ?: selectedVariantId)
                                val summary = v.buildVariantSummary()
                                val priceLabel = IndianCurrencyFormatter.formatPaise(v.sellingPricePaise)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) CupertinoColors.SystemBlue else CupertinoColors.Separator
                                    ),
                                    modifier = Modifier.clickable { selectVariant(v) }
                                ) {
                                    Text(
                                        text = "$summary • $priceLabel",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else CupertinoColors.LabelPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            // Unified [+ Add Variant] chip
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAddingNewVariant) CupertinoColors.SystemBlue else CupertinoColors.SystemBlueTint,
                                border = BorderStroke(
                                    1.dp,
                                    if (isAddingNewVariant) CupertinoColors.SystemBlue else CupertinoColors.SystemBlue.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier.clickable { startAddNewVariant() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Variant",
                                        tint = if (isAddingNewVariant) Color.White else CupertinoColors.SystemBlue,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Add Variant",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAddingNewVariant) Color.White else CupertinoColors.SystemBlue
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAddingNewVariant) {
                                "● Adding new variant under \"$itemName\" (with distinct purchase & selling price)"
                            } else {
                                "Editing: ${activeVariant?.buildVariantSummary() ?: "Standard"} • Purchase: ${IndianCurrencyFormatter.formatPaise(activeVariant?.purchasePricePaise ?: 0)} • Sale: ${IndianCurrencyFormatter.formatPaise(activeVariant?.sellingPricePaise ?: 0)}"
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isAddingNewVariant) FontWeight.Bold else FontWeight.Normal,
                            color = if (isAddingNewVariant) CupertinoColors.SystemBlue else CupertinoColors.LabelSecondary
                        )
                    }
                }
            }

            // CARD 2: COMPACT 2-IN-A-ROW SPECIFICATIONS & PRICING FORM
            val parsedPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
            val parsedSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)

            CupertinoCard(contentPadding = PaddingValues(12.dp)) {
                Text(
                    text = if (isAddingNewVariant) "New Variant Details" else "Specifications & Pricing",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // ROW 1 (Two small named text boxes): Vertical Attr1 + Vertical Attr2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CupertinoTextField(
                        value = sizeLabel,
                        onValueChange = {
                            sizeLabel = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].sizeLabel = it
                            }
                        },
                        label = activeVertical.attr1Label,
                        placeholder = activeVertical.attr1Placeholder,
                        capitalization = KeyboardCapitalization.None,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = materialLabel,
                        onValueChange = {
                            materialLabel = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].materialLabel = it
                            }
                        },
                        label = activeVertical.attr2Label,
                        placeholder = activeVertical.attr2Placeholder,
                        capitalization = KeyboardCapitalization.Words,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ROW 2 (Two small named text boxes): Vertical Attr3 + Unit of Measure
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CupertinoTextField(
                        value = colorFinishLabel,
                        onValueChange = {
                            colorFinishLabel = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].colorFinishLabel = it
                            }
                        },
                        label = activeVertical.attr3Label,
                        placeholder = activeVertical.attr3Placeholder,
                        capitalization = KeyboardCapitalization.Words,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = unit,
                        onValueChange = {
                            unit = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].unit = it
                            }
                        },
                        label = "Unit of Measure",
                        placeholder = "e.g. ${activeVertical.defaultUnit}",
                        capitalization = KeyboardCapitalization.None,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Barcode / SKU Code with 1-Tap Camera Scan button
                CupertinoTextField(
                    value = barcodeInput,
                    onValueChange = {
                        barcodeInput = it
                        if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                            draftVariants[activeDraftIndex].barcode = it
                        }
                    },
                    label = "Barcode / SKU Code (Optional)",
                    placeholder = "Scan EAN-13 / QR or type SKU code",
                    capitalization = KeyboardCapitalization.None,
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
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan Product Barcode",
                                tint = CupertinoColors.SystemBlue
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Compact Unit quick-chips (single row, 22dp height)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    units.forEach { u ->
                        val selected = unit.equals(u, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                            border = BorderStroke(1.dp, if (selected) CupertinoColors.SystemBlue else CupertinoColors.Separator),
                            modifier = Modifier.clickable {
                                unit = u
                                if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                    draftVariants[activeDraftIndex].unit = u
                                }
                            }
                        ) {
                            Text(
                                text = u,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else CupertinoColors.LabelPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ROW 3 (Two small named text boxes): Purchase Price (₹) + Selling Price (₹)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CupertinoTextField(
                        value = purchasePriceInput,
                        onValueChange = {
                            purchasePriceInput = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].purchasePriceInput = it
                            }
                            validationError = ""
                        },
                        label = "Purchase Price (₹)",
                        placeholder = "e.g. 140",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = sellingPriceInput,
                        onValueChange = {
                            sellingPriceInput = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].sellingPriceInput = it
                            }
                            validationError = ""
                        },
                        label = "Selling Price (₹)",
                        placeholder = "e.g. 210",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Live Coded Symbol Preview Box (IMG_20261031_222339.jpg cipher + profit/gain)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CupertinoColors.SecondarySurface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LIVE CODED PURCHASE SYMBOL",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.CipherIndigo
                        )
                        if (parsedPurchasePaise > 0 && parsedSellingPaise > 0) {
                            val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
                                parsedPurchasePaise,
                                parsedSellingPaise,
                                1.0
                            )
                            Text(
                                text = "Profit: ${IndianCurrencyFormatter.formatPaise(analysis.totalProfitPaise, showPlusSign = true)} (${
                                    IndianCurrencyFormatter.formatPercentage(analysis.gainMarkupPercent)
                                } gain)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.EmeraldGreen
                            )
                        }
                    }

                    CodedPurchasePriceBadge(
                        purchasePricePaise = parsedPurchasePaise,
                        isSellerUnlocked = false,
                        glyphSize = 18.dp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                CipherReferenceStrip(modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))

                // ROW 4 (Two small named text boxes): Current Stock + Low Stock Alert
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CupertinoTextField(
                        value = stockQtyInput,
                        onValueChange = {
                            stockQtyInput = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].stockQtyInput = it
                            }
                        },
                        label = "Current Stock",
                        placeholder = "10",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = lowStockInput,
                        onValueChange = {
                            lowStockInput = it
                            if (mode is ItemEditorMode.AddNewItem && draftVariants.indices.contains(activeDraftIndex)) {
                                draftVariants[activeDraftIndex].lowStockInput = it
                            }
                        },
                        label = "Low Stock Alert At",
                        placeholder = "5",
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (validationError.isNotEmpty()) {
                Text(
                    text = validationError,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.DestructiveRed,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // BOTTOM ACTION ROW: Cancel + Save / Add Variant
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()

                        if (itemName.isBlank()) {
                            validationError = "Please enter an item name."
                            return@Button
                        }
                        val cleanCategory = category.trim().ifEmpty { "Uncategorized" }

                        when (mode) {
                            is ItemEditorMode.AddNewItem -> {
                                if (duplicateCheckResult?.decision == DuplicateDecisionType.EXACT_DUPLICATE) {
                                    validationError = "This exact item already exists! Tap 'Edit Existing' above."
                                    return@Button
                                }

                                saveCurrentIntoDraft(draftVariants[activeDraftIndex])

                                val specs = mutableListOf<LpsRepository.NewVariantSpec>()
                                draftVariants.forEachIndexed { index, draft ->
                                    val finalSelling = IndianCurrencyFormatter.parseRupeesToPaise(draft.sellingPriceInput)
                                    val finalPurchase = IndianCurrencyFormatter.parseRupeesToPaise(draft.purchasePriceInput)
                                    if (finalSelling <= 0L) {
                                        val hint = if (draft.buildSummary() == "Standard") "Variant #${index + 1}" else draft.buildSummary()
                                        validationError = "Please enter a valid selling price (₹) for $hint."
                                        activeDraftIndex = index
                                        loadDraft(draft)
                                        return@Button
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

                                onSaveNewItemWithVariants(itemName, cleanCategory, specs)
                            }
                            is ItemEditorMode.ManageItem -> {
                                val finalPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
                                val finalSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
                                val finalStock = stockQtyInput.toDoubleOrNull() ?: 0.0
                                val finalLowAlert = lowStockInput.toDoubleOrNull() ?: 5.0

                                if (finalSellingPaise <= 0L) {
                                    validationError = "Please enter a valid selling price (₹)."
                                    return@Button
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
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.SystemBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Text(
                        text = when {
                            mode is ItemEditorMode.AddNewItem -> if (draftVariants.size > 1) "Save (${draftVariants.size} Variants)" else "Save Item"
                            mode is ItemEditorMode.ManageItem && isAddingNewVariant -> "+ Add Variant"
                            else -> "Save Changes"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // Confirm dialog for deleting entire item
    if (showDeleteConfirm && mode is ItemEditorMode.ManageItem) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Product?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to permanently delete \"${mode.itemWithVariants.item.displayName}\" and all ${mode.itemWithVariants.variants.size} variant(s) from inventory?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteItemOrVariant(mode.itemWithVariants.item.id, null, 1)
                        onDismiss()
                    }
                ) {
                    Text("Delete Item", color = CupertinoColors.DestructiveRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm dialog for deleting a specific variant
    if (showDeleteVariantConfirm && mode is ItemEditorMode.ManageItem && activeVariant != null) {
        AlertDialog(
            onDismissRequest = { showDeleteVariantConfirm = false },
            title = { Text("Delete Variant?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Delete only the \"${activeVariant.buildVariantSummary()}\" variant from \"${mode.itemWithVariants.item.displayName}\"?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteVariantConfirm = false
                        onDeleteItemOrVariant(mode.itemWithVariants.item.id, activeVariant.id, mode.itemWithVariants.variants.size)
                        onDismiss()
                    }
                ) {
                    Text("Delete Variant", color = CupertinoColors.DestructiveRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVariantConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
