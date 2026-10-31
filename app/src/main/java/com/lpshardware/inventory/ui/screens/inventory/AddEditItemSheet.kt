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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

sealed class ItemEditorMode {
    data object AddNewItem : ItemEditorMode()
    data class AddVariantToParent(val parent: InventoryItemWithVariants) : ItemEditorMode()
    data class EditExisting(
        val item: InventoryItemEntity,
        val variant: ItemVariantEntity,
        val totalVariantsCount: Int
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
        itemId: String,
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
    onSwitchToVariantMode: (InventoryItemWithVariants, prefillSize: String, prefillMaterial: String, prefillColor: String) -> Unit,
    onSwitchToEditExistingVariant: (InventoryItemEntity, ItemVariantEntity, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val initialName = when (mode) {
        is ItemEditorMode.AddNewItem -> ""
        is ItemEditorMode.AddVariantToParent -> mode.parent.item.displayName
        is ItemEditorMode.EditExisting -> mode.item.displayName
    }
    val initialCategory = when (mode) {
        is ItemEditorMode.AddNewItem -> "Door & Window Fittings"
        is ItemEditorMode.AddVariantToParent -> mode.parent.item.category
        is ItemEditorMode.EditExisting -> mode.item.category
    }
    val initialSize = (mode as? ItemEditorMode.EditExisting)?.variant?.sizeLabel.orEmpty()
    val initialMaterial = (mode as? ItemEditorMode.EditExisting)?.variant?.materialLabel.orEmpty()
    val initialColor = (mode as? ItemEditorMode.EditExisting)?.variant?.colorFinishLabel.orEmpty()
    val initialUnit = (mode as? ItemEditorMode.EditExisting)?.variant?.unit ?: "pcs"
    val initialPurchaseStr = (mode as? ItemEditorMode.EditExisting)?.variant?.let {
        IndianCurrencyFormatter.paiseToEditableString(it.purchasePricePaise)
    }.orEmpty()
    val initialSellingStr = (mode as? ItemEditorMode.EditExisting)?.variant?.let {
        IndianCurrencyFormatter.paiseToEditableString(it.sellingPricePaise)
    }.orEmpty()
    val initialStockStr = (mode as? ItemEditorMode.EditExisting)?.variant?.let {
        IndianCurrencyFormatter.formatQuantity(it.stockQuantity)
    } ?: "10"
    val initialLowStockStr = (mode as? ItemEditorMode.EditExisting)?.variant?.let {
        IndianCurrencyFormatter.formatQuantity(it.lowStockThreshold)
    } ?: "5"

    var itemName by remember(mode) { mutableStateOf(initialName) }
    var category by remember(mode) { mutableStateOf(initialCategory) }
    var sizeLabel by remember(mode) { mutableStateOf(initialSize) }
    var materialLabel by remember(mode) { mutableStateOf(initialMaterial) }
    var colorFinishLabel by remember(mode) { mutableStateOf(initialColor) }
    var unit by remember(mode) { mutableStateOf(initialUnit) }
    var purchasePriceInput by remember(mode) { mutableStateOf(initialPurchaseStr) }
    var sellingPriceInput by remember(mode) { mutableStateOf(initialSellingStr) }
    var stockQtyInput by remember(mode) { mutableStateOf(initialStockStr) }
    var lowStockInput by remember(mode) { mutableStateOf(initialLowStockStr) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }

    // Live Smart Duplicate & Variant Understanding (Requirement #4)
    val excludeItemId = when (mode) {
        is ItemEditorMode.EditExisting -> mode.item.id
        is ItemEditorMode.AddVariantToParent -> mode.parent.item.id
        else -> null
    }
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
    val units = remember { listOf("pcs", "box", "kg", "mtr", "pkt", "pair", "set", "dzn") }
    val quickMaterials = remember { listOf("SS 304", "SS", "MS (Iron)", "Brass", "Aluminium", "GI", "PVC") }
    val quickColors = remember { listOf("Silver / Chrome", "Matt Black", "Antique Brass", "Rose Gold", "White", "Black") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CupertinoColors.SystemGroupedBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sheet Title Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (mode) {
                        is ItemEditorMode.AddNewItem -> "Add Hardware Item"
                        is ItemEditorMode.AddVariantToParent -> "Add Variant: ${mode.parent.item.displayName}"
                        is ItemEditorMode.EditExisting -> "Update Item & Variant"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CupertinoColors.LabelPrimary
                )

                if (mode is ItemEditorMode.EditExisting) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = CupertinoColors.DestructiveRed)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Item Name & Category Section
            if (mode !is ItemEditorMode.AddVariantToParent) {
                CupertinoCard {
                    CupertinoTextField(
                        value = itemName,
                        onValueChange = {
                            itemName = it
                            validationError = ""
                        },
                        label = "Item Name (English or Hinglish)",
                        placeholder = "e.g. SS Butt Hinge, 4 inch Kabza, Drywall Screw",
                        capitalization = KeyboardCapitalization.Words
                    )

                    // Auto-extracted attributes helper pill if user typed size/material/color inside the name
                    val sig = duplicateCheckResult?.candidateSignature
                    if (sig != null && (sig.canonicalSize.isNotEmpty() || sig.canonicalMaterial.isNotEmpty() || sig.canonicalColorFinish.isNotEmpty())) {
                        Spacer(modifier = Modifier.height(8.dp))
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
                                tint = CupertinoColors.SystemBlue
                            )
                            Text(
                                text = "Smart Extract: Tap to auto-fill Variant (${
                                    listOf(sig.canonicalSize, sig.canonicalMaterial, sig.canonicalColorFinish)
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" · ")
                                }) & clean name to \"${sig.suggestedCleanBaseName}\"",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.SystemBlue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Category",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.LabelSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) Color.White else CupertinoColors.LabelPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Smart Duplicate / Variant Detection Alert Banner
            if (duplicateCheckResult != null && duplicateCheckResult.decision != DuplicateDecisionType.UNIQUE_NEW_ITEM) {
                val matched = duplicateCheckResult.matchedItem
                if (matched != null) {
                    val isExactDup = duplicateCheckResult.decision == DuplicateDecisionType.EXACT_DUPLICATE
                    CupertinoCard(
                        containerColor = if (isExactDup) CupertinoColors.DestructiveTint else CupertinoColors.AmberTint,
                        borderColor = if (isExactDup) CupertinoColors.DestructiveRed else CupertinoColors.AmberWarning
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (isExactDup) CupertinoColors.DestructiveRed else CupertinoColors.AmberWarning
                            )
                            Text(
                                text = if (isExactDup) "Duplicate Hardware Item Detected!"
                                else "Existing Parent Item Recognised!",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isExactDup) CupertinoColors.DestructiveRed else CupertinoColors.AmberWarning
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = duplicateCheckResult.explanationMessage,
                            fontSize = 12.sp,
                            color = CupertinoColors.LabelPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isExactDup) {
                                val targetVar = matched.variants.firstOrNull {
                                    it.id == duplicateCheckResult.matchedVariantId
                                } ?: matched.variants.firstOrNull()
                                if (targetVar != null) {
                                    Button(
                                        onClick = {
                                            onSwitchToEditExistingVariant(
                                                matched.item,
                                                targetVar,
                                                matched.variants.size
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
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val sig = duplicateCheckResult.candidateSignature
                                        onSwitchToVariantMode(
                                            matched,
                                            sizeLabel.ifBlank { sig.canonicalSize },
                                            materialLabel.ifBlank { sig.canonicalMaterial },
                                            colorFinishLabel.ifBlank { sig.canonicalColorFinish }
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

            // Variant Attributes Card (Size, Material, Color/Finish)
            CupertinoCard {
                Text(
                    text = "Variant Specifications (Size · Material · Color)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                CupertinoTextField(
                    value = sizeLabel,
                    onValueChange = { sizeLabel = it },
                    label = "Size / Dimension (Optional)",
                    placeholder = "e.g. 4 inch, 100mm, 1/2 inch, 1 kg",
                    capitalization = KeyboardCapitalization.None
                )

                Spacer(modifier = Modifier.height(8.dp))

                CupertinoTextField(
                    value = materialLabel,
                    onValueChange = { materialLabel = it },
                    label = "Material (Optional)",
                    placeholder = "e.g. SS 304, Brass, MS (Iron), Aluminium, PVC",
                    capitalization = KeyboardCapitalization.Words
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickMaterials.forEach { mat ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (materialLabel == mat) CupertinoColors.SystemBlueTint else CupertinoColors.SecondarySurface,
                            border = BorderStroke(1.dp, CupertinoColors.Separator),
                            modifier = Modifier.clickable { materialLabel = mat }
                        ) {
                            Text(
                                text = mat,
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                CupertinoTextField(
                    value = colorFinishLabel,
                    onValueChange = { colorFinishLabel = it },
                    label = "Color / Finish (Optional)",
                    placeholder = "e.g. Matt Black, Antique Brass, Rose Gold, Chrome",
                    capitalization = KeyboardCapitalization.Words
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickColors.forEach { col ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (colorFinishLabel == col) CupertinoColors.SystemBlueTint else CupertinoColors.SecondarySurface,
                            border = BorderStroke(1.dp, CupertinoColors.Separator),
                            modifier = Modifier.clickable { colorFinishLabel = col }
                        ) {
                            Text(
                                text = col,
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Pricing & Coded Purchase Price Preview Card
            val parsedPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
            val parsedSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)

            CupertinoCard {
                Text(
                    text = "Pricing & Stock Count",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

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

                // Live Coded Symbol Preview Box (IMG_20261031_222339.jpg)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CupertinoColors.SecondarySurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LIVE CODED PURCHASE SYMBOL",
                            fontSize = 10.sp,
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
                        glyphSize = 19.dp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                CipherReferenceStrip(modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(10.dp))

                // Stock Quantity, Low Stock Alert, and Unit
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

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unit of Measure",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    units.forEach { u ->
                        val selected = unit == u
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                            border = BorderStroke(1.dp, if (selected) CupertinoColors.SystemBlue else CupertinoColors.Separator),
                            modifier = Modifier.clickable { unit = u }
                        ) {
                            Text(
                                text = u,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else CupertinoColors.LabelPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            if (validationError.isNotEmpty()) {
                Text(
                    text = validationError,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.DestructiveRed,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Bottom Save / Cancel Row
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
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()

                        val finalPurchasePaise = IndianCurrencyFormatter.parseRupeesToPaise(purchasePriceInput)
                        val finalSellingPaise = IndianCurrencyFormatter.parseRupeesToPaise(sellingPriceInput)
                        val finalStock = stockQtyInput.toDoubleOrNull() ?: 0.0
                        val finalLowAlert = lowStockInput.toDoubleOrNull() ?: 5.0

                        if (mode !is ItemEditorMode.AddVariantToParent && itemName.isBlank()) {
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
                            is ItemEditorMode.AddVariantToParent -> {
                                onSaveVariantToParent(
                                    mode.parent.item.id,
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
                            is ItemEditorMode.EditExisting -> {
                                onUpdateExisting(
                                    mode.item,
                                    itemName,
                                    category,
                                    mode.variant,
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
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.SystemBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1.4f),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text(
                        text = when (mode) {
                            is ItemEditorMode.AddNewItem -> "Save Item"
                            is ItemEditorMode.AddVariantToParent -> "Add Variant"
                            is ItemEditorMode.EditExisting -> "Update Changes"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && mode is ItemEditorMode.EditExisting) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Hardware Item?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (mode.totalVariantsCount > 1) {
                        "Delete only this variant (${mode.variant.buildVariantSummary()}) or delete the entire item \"${mode.item.displayName}\"?"
                    } else {
                        "Are you sure you want to delete \"${mode.item.displayName}\" from inventory?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteItemOrVariant(mode.item.id, mode.variant.id, mode.totalVariantsCount)
                        onDismiss()
                    }
                ) {
                    Text(
                        text = if (mode.totalVariantsCount > 1) "Delete This Variant" else "Delete Item",
                        color = CupertinoColors.DestructiveRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Row {
                    if (mode.totalVariantsCount > 1) {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                onDeleteItemOrVariant(mode.item.id, null, 1)
                                onDismiss()
                            }
                        ) {
                            Text("Delete All Variants", color = CupertinoColors.DestructiveRed)
                        }
                    }
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}
