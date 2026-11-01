package com.lpshardware.inventory.ui.screens.inventory

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpshardware.inventory.core.cipher.CipherReferenceStrip
import com.lpshardware.inventory.core.cipher.CodedPurchasePriceBadge
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.core.nlp.DuplicateCheckResult
import com.lpshardware.inventory.core.nlp.DuplicateDecisionType
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
import com.lpshardware.inventory.ui.theme.CupertinoCard
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.CupertinoTextField

/**
 * Unified editor modes:
 * - [AddNewItem]: Create a brand new item from scratch.
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
    onCheckDuplicate: (name: String, size: String, material: String, color: String, excludeItemId: String?) -> DuplicateCheckResult,
    onSaveNewItem: (
        displayName: String,
        category: String,
        size: String,
        material: String,
        color: String,
        unit: String,
        purchasePaise: Long,
        sellingPaise: Long,
        stockQty: Double,
        lowStockAlert: Double
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
        lowStockAlert: Double
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
        newLowStockAlert: Double
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val managedItemWithVariants = (mode as? ItemEditorMode.ManageItem)?.itemWithVariants
    val variants = managedItemWithVariants?.variants.orEmpty()
    val initialStartInAdd = (mode as? ItemEditorMode.ManageItem)?.startInAddVariantMode ?: false

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
                is ItemEditorMode.AddNewItem -> "Door & Window Fittings"
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

    var unit by remember(mode) {
        mutableStateOf(activeVariant?.unit ?: variants.firstOrNull()?.unit ?: "pcs")
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

    fun selectVariant(v: ItemVariantEntity) {
        isAddingNewVariant = false
        selectedVariantId = v.id
        sizeLabel = v.sizeLabel
        materialLabel = v.materialLabel
        colorFinishLabel = v.colorFinishLabel
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
        unit = unit.ifBlank { variants.firstOrNull()?.unit ?: "pcs" }
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

    val categories = remember {
        listOf(
            "Door & Window Fittings",
            "Fasteners & Screws",
            "Plumbing & Sanitary",
            "Tools & Blades",
            "Paints & Adhesives",
            "Electrical & General"
        )
    }
    val units = remember { listOf("pcs", "box", "kg", "mtr", "pkt", "set", "pair", "dzn") }

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
                        is ItemEditorMode.AddNewItem -> "Add Hardware Item"
                        is ItemEditorMode.ManageItem -> "Edit Item & Variants"
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

            // CARD 1: Item Name & Category
            CupertinoCard(contentPadding = PaddingValues(12.dp)) {
                CupertinoTextField(
                    value = itemName,
                    onValueChange = {
                        itemName = it
                        validationError = ""
                    },
                    label = "Item Name",
                    placeholder = "e.g. SS Butt Hinge, Kabza, Godrej Lock",
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        val selected = category == cat
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

            // UNIFIED VARIANTS SELECTOR: Only in ManageItem mode
            // Allows seamless switching between editing existing variants and adding a new variant
            if (mode is ItemEditorMode.ManageItem) {
                CupertinoCard(contentPadding = PaddingValues(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "VARIANTS (${variants.size})",
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
                                    text = summary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else CupertinoColors.LabelPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Unified [+ Add Variant] pill
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
                            "● Adding new variant under \"$itemName\""
                        } else {
                            "Editing: ${activeVariant?.buildVariantSummary() ?: "Standard"}"
                        },
                        fontSize = 11.sp,
                        fontWeight = if (isAddingNewVariant) FontWeight.Bold else FontWeight.Normal,
                        color = if (isAddingNewVariant) CupertinoColors.SystemBlue else CupertinoColors.LabelSecondary
                    )
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

                // ROW 1 (Two small named text boxes): Size / Dimension + Material
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CupertinoTextField(
                        value = sizeLabel,
                        onValueChange = { sizeLabel = it },
                        label = "Size / Dimension",
                        placeholder = "e.g. 4 inch, 100mm",
                        capitalization = KeyboardCapitalization.None,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = materialLabel,
                        onValueChange = { materialLabel = it },
                        label = "Material",
                        placeholder = "e.g. SS 304, Brass",
                        capitalization = KeyboardCapitalization.Words,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ROW 2 (Two small named text boxes): Color / Finish + Unit of Measure
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CupertinoTextField(
                        value = colorFinishLabel,
                        onValueChange = { colorFinishLabel = it },
                        label = "Color / Finish",
                        placeholder = "e.g. Matt Black, Chrome",
                        capitalization = KeyboardCapitalization.Words,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = "Unit of Measure",
                        placeholder = "e.g. pcs, box, kg",
                        capitalization = KeyboardCapitalization.None,
                        modifier = Modifier.weight(1f)
                    )
                }

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
                            modifier = Modifier.clickable { unit = u }
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
                        onValueChange = { stockQtyInput = it },
                        label = "Current Stock",
                        placeholder = "10",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )

                    CupertinoTextField(
                        value = lowStockInput,
                        onValueChange = { lowStockInput = it },
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

                        val finalPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
                        val finalSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
                        val finalStock = stockQtyInput.toDoubleOrNull() ?: 0.0
                        val finalLowAlert = lowStockInput.toDoubleOrNull() ?: 5.0

                        if (itemName.isBlank()) {
                            validationError = "Please enter an item name."
                            return@Button
                        }
                        if (finalSellingPaise <= 0L) {
                            validationError = "Please enter a valid selling price (₹)."
                            return@Button
                        }
                        if (mode is ItemEditorMode.AddNewItem &&
                            duplicateCheckResult?.decision == DuplicateDecisionType.EXACT_DUPLICATE
                        ) {
                            validationError = "This exact item & variant already exists! Tap 'Edit Existing' above or change its Size/Material/Color."
                            return@Button
                        }

                        when (mode) {
                            is ItemEditorMode.AddNewItem -> {
                                onSaveNewItem(
                                    itemName,
                                    category,
                                    sizeLabel,
                                    materialLabel,
                                    colorFinishLabel,
                                    unit,
                                    finalPurchasePaise,
                                    finalSellingPaise,
                                    finalStock,
                                    finalLowAlert
                                )
                            }
                            is ItemEditorMode.ManageItem -> {
                                if (isAddingNewVariant) {
                                    onSaveVariantToParent(
                                        mode.itemWithVariants.item,
                                        itemName,
                                        category,
                                        sizeLabel,
                                        materialLabel,
                                        colorFinishLabel,
                                        unit,
                                        finalPurchasePaise,
                                        finalSellingPaise,
                                        finalStock,
                                        finalLowAlert
                                    )
                                } else {
                                    val targetVar = activeVariant ?: mode.itemWithVariants.variants.first()
                                    onUpdateExisting(
                                        mode.itemWithVariants.item,
                                        itemName,
                                        category,
                                        targetVar,
                                        sizeLabel,
                                        materialLabel,
                                        colorFinishLabel,
                                        unit,
                                        finalPurchasePaise,
                                        finalSellingPaise,
                                        finalStock,
                                        finalLowAlert
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
                            mode is ItemEditorMode.AddNewItem -> "Save Item"
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
            title = { Text("Delete Hardware Item?", fontWeight = FontWeight.Bold) },
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
