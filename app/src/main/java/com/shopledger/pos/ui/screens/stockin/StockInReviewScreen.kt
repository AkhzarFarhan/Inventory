package com.shopledger.pos.ui.screens.stockin

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.scanner.MatchStatus
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.MoneyField
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.sanitiseMoneyInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Widths of the fixed columns. On a 360 dp screen the item column still gets about 146 dp.
private val StatusWidth = 18.dp
private val QtyWidth = 52.dp
private val MoneyWidth = 66.dp
private val RowPadding = 2.dp
private val CellPadding = 4.dp

/** Stateless review table for a scanned bill or stock list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInReviewScreen(
    state: StockInUiState,
    onEvent: (StockInEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPhoto by remember { mutableStateOf(false) }
    var pickerKey by remember { mutableStateOf<Long?>(null) }
    // Whether the user chose "New supplier…" (it must survive the header being collapsed).
    var newSupplierMode by rememberSaveable { mutableStateOf(state.newSupplierName.isNotBlank()) }
    // Open at first only when the supplier or the amounts are what stops the save.
    var headerExpanded by rememberSaveable {
        mutableStateOf(state.blockReason?.kind.let { it == BlockKind.SUPPLIER || it == BlockKind.PAID })
    }

    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.review_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { onEvent(StockInEvent.Close) }, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.review_cd_close))
                }
            },
            actions = {
                if (state.imageUri != null) {
                    IconButton(onClick = { showPhoto = true }, modifier = Modifier.size(ShopLedgerTokens.minTouch)) {
                        Icon(Icons.Filled.Image, contentDescription = stringResource(R.string.review_cd_photo))
                    }
                }
            },
        )

        if (state.rows.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                EmptyState(
                    title = stringResource(R.string.review_empty_title),
                    message = stringResource(R.string.review_empty_message),
                    actionText = stringResource(R.string.action_retry),
                    onAction = { onEvent(StockInEvent.Rescan) },
                )
                SecondaryActionButton(
                    text = stringResource(R.string.stock_in_add_manually),
                    onClick = { onEvent(StockInEvent.AddManually) },
                    modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingXl),
                )
            }
        } else {
            // Supplier and amounts, the filter and the column titles stay above the scrolling rows.
            SummaryHeader(
                state = state,
                expanded = headerExpanded,
                onToggle = { headerExpanded = !headerExpanded },
                newMode = newSupplierMode,
                onNewModeChange = { newSupplierMode = it },
                onEvent = onEvent,
            )
            FilterTabs(state, onEvent)
            ColumnHeaders()
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.visibleRows, key = { it.key }) { row ->
                    ReviewRowView(
                        row = row,
                        canEditCost = state.canEditCost(row),
                        onEvent = onEvent,
                        onPickItem = { pickerKey = row.key },
                    )
                    HorizontalDivider()
                }
                item(key = "hint") {
                    Text(
                        stringResource(R.string.review_hint_remove),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(ShopLedgerTokens.spacingL),
                    )
                }
            }
            BottomBar(state, onEvent)
        }
    }

    if (showPhoto && state.imageUri != null) PhotoDialog(state.imageUri, onDismiss = { showPhoto = false })

    // The row being picked for can disappear (deleted, list replaced): close the picker then, outside composition.
    LaunchedEffect(pickerKey, state.rows) {
        val key = pickerKey
        if (key != null && state.rows.none { it.key == key }) pickerKey = null
    }
    pickerKey?.let { key ->
        state.rows.firstOrNull { it.key == key }?.let { row ->
            ItemPickerDialog(
                state = state,
                row = row,
                onPick = { itemId, variantId ->
                    onEvent(StockInEvent.PickVariant(key, itemId, variantId))
                    pickerKey = null
                },
                onMakeNew = {
                    onEvent(StockInEvent.MakeNew(key))
                    pickerKey = null
                },
                onDismiss = { pickerKey = null },
            )
        }
    }
}

/** One line (supplier, total, paid) that opens into the full supplier and amounts form when tapped. */
@Composable
private fun SummaryHeader(
    state: StockInUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    newMode: Boolean,
    onNewModeChange: (Boolean) -> Unit,
    onEvent: (StockInEvent) -> Unit,
) {
    val supplierName = state.suppliers.firstOrNull { it.id == state.supplierId }?.name
        ?: state.newSupplierName.trim().ifBlank { null }
        ?: if (newMode) stringResource(R.string.review_supplier_new) else stringResource(R.string.review_supplier_none)
    val total = state.billTotalPaise?.let { IndianCurrencyFormatter.formatPaise(it) }
        ?: stringResource(R.string.review_summary_total_unset)
    val paid = IndianCurrencyFormatter.formatPaise(state.amountPaidPaise ?: 0L)
    val summary = stringResource(R.string.review_summary, supplierName, total, paid)
    val problem = state.blockReason?.kind.let { it == BlockKind.SUPPLIER || it == BlockKind.PAID }
    val toggleDesc = stringResource(if (expanded) R.string.review_cd_summary_collapse else R.string.review_cd_summary_expand)

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ShopLedgerTokens.minTouch)
                .clickable(onClick = onToggle)
                .semantics(mergeDescendants = true) { contentDescription = "$toggleDesc: $summary" }
                .padding(horizontal = ShopLedgerTokens.spacingL)
                .testTag("header_summary"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyLarge,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        if (expanded) HeaderSection(state, newMode, onNewModeChange, onEvent)
        HorizontalDivider()
    }
}

@Composable
private fun HeaderSection(
    state: StockInUiState,
    newMode: Boolean,
    onNewModeChange: (Boolean) -> Unit,
    onEvent: (StockInEvent) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val supplierName = state.suppliers.firstOrNull { it.id == state.supplierId }?.name
        ?: if (newMode) stringResource(R.string.review_supplier_new) else stringResource(R.string.review_supplier_none)

    Column(
        Modifier.padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
    ) {
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.minTouch),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    stringResource(R.string.review_supplier) + ": " + supplierName,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.review_supplier_none)) },
                    onClick = {
                        menuOpen = false
                        onNewModeChange(false)
                        onEvent(StockInEvent.SetSupplier(null))
                        onEvent(StockInEvent.SetNewSupplier(""))
                    },
                )
                state.suppliers.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        onClick = { menuOpen = false; onNewModeChange(false); onEvent(StockInEvent.SetSupplier(s.id)) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.review_supplier_new)) },
                    onClick = { menuOpen = false; onNewModeChange(true); onEvent(StockInEvent.SetSupplier(null)) },
                )
            }
        }
        if (newMode) {
            OutlinedTextField(
                value = state.newSupplierName,
                onValueChange = { onEvent(StockInEvent.SetNewSupplier(it)) },
                label = { Text(stringResource(R.string.review_supplier_new_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
            MoneyField(
                valuePaise = state.billTotalPaise,
                onValueChange = { onEvent(StockInEvent.SetBillTotal(it)) },
                label = stringResource(R.string.review_bill_total),
                modifier = Modifier.weight(1f),
            )
            MoneyField(
                valuePaise = state.amountPaidPaise,
                onValueChange = { onEvent(StockInEvent.SetPaid(it)) },
                label = stringResource(R.string.review_paid),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterTabs(state: StockInUiState, onEvent: (StockInEvent) -> Unit) {
    Row(
        Modifier.padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingXs),
        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
    ) {
        listOf(
            ReviewFilter.ALL to stringResource(R.string.review_tab_all, state.rows.size),
            ReviewFilter.NEW to stringResource(R.string.review_tab_new, state.newCount),
            ReviewFilter.CHECK to stringResource(R.string.review_tab_check, state.checkCount),
        ).forEach { (filter, label) ->
            FilterChip(
                selected = state.filter == filter,
                onClick = { onEvent(StockInEvent.SetFilter(filter)) },
                label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            )
        }
    }
}

@Composable
private fun ColumnHeaders() {
    val style = MaterialTheme.typography.labelLarge
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = ShopLedgerTokens.spacingXs)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StatusWidth))
        Text(
            stringResource(R.string.review_col_item), style = style, color = color,
            modifier = Modifier.weight(1f).padding(horizontal = CellPadding),
        )
        Text(stringResource(R.string.review_col_qty), style = style, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(QtyWidth).padding(horizontal = CellPadding))
        Text(stringResource(R.string.review_col_cost), style = style, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(MoneyWidth).padding(horizontal = CellPadding))
        Text(stringResource(R.string.review_col_sell), style = style, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(MoneyWidth).padding(horizontal = CellPadding))
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewRowView(
    row: ReviewRow,
    canEditCost: Boolean,
    onEvent: (StockInEvent) -> Unit,
    onPickItem: () -> Unit,
) {
    val key = row.key
    val itemLabel = row.itemName.ifBlank { row.scannedName }
    var menuOpen by remember { mutableStateOf(false) }
    val removeText = stringResource(R.string.review_cd_remove, itemLabel)
    val onRemove = { onEvent(StockInEvent.DeleteRow(key)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(row.status)
        // Item: tap to rename (new) or pick another item (matched); press and hold to remove the row.
        Box(Modifier.weight(1f)) {
            if (row.status == MatchStatus.NEW) {
                EditableCell(
                    tag = "cell_name_$key",
                    columnLabel = stringResource(R.string.review_col_item),
                    itemLabel = itemLabel,
                    display = row.itemName.ifBlank { null },
                    placeholder = stringResource(R.string.review_add_name),
                    initialText = row.itemName,
                    keyboardType = KeyboardType.Text,
                    sanitize = { it },
                    onCommit = { onEvent(StockInEvent.EditName(key, it)) },
                    align = TextAlign.Start,
                    onLongClick = { menuOpen = true },
                    removeLabel = removeText,
                    onRemove = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ShopLedgerTokens.minTouch)
                        .semantics { customActions = listOf(CustomAccessibilityAction(removeText) { onRemove(); true }) }
                        .combinedClickable(onClick = onPickItem, onLongClick = { menuOpen = true })
                        .padding(horizontal = CellPadding)
                        .testTag("cell_item_$key"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        row.itemName, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                    )
                    if (row.variantSummary.isNotBlank()) {
                        Text(row.variantSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (row.status == MatchStatus.CHECK && !row.scannedName.equals(row.itemName, ignoreCase = true)) {
                        Text(
                            stringResource(R.string.review_pick_on_bill, row.scannedName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(removeText) },
                    onClick = { menuOpen = false; onRemove() },
                    modifier = Modifier.testTag("remove_$key"),
                )
            }
        }
        // Qty
        EditableCell(
            tag = "cell_qty_$key",
            columnLabel = stringResource(R.string.review_col_qty),
            itemLabel = itemLabel,
            display = row.qtyMilli?.let { IndianCurrencyFormatter.formatMilliUnits(it) },
            placeholder = "–",
            initialText = row.qtyMilli?.let { IndianCurrencyFormatter.formatMilliUnits(it).replace(",", "") } ?: "",
            keyboardType = KeyboardType.Decimal,
            sanitize = ::sanitiseQtyInput,
            onCommit = { onEvent(StockInEvent.EditQty(key, parseQtyMilli(it))) },
            subText = row.unit,
            modifier = Modifier.width(QtyWidth),
        )
        // Cost
        EditableCell(
            tag = "cell_cost_$key",
            columnLabel = stringResource(R.string.review_col_cost),
            itemLabel = itemLabel,
            display = row.costPaise?.let { IndianCurrencyFormatter.formatPaise(it) },
            placeholder = stringResource(R.string.review_add_price),
            initialText = row.costPaise?.let { IndianCurrencyFormatter.paiseToEditableString(it) } ?: "",
            keyboardType = KeyboardType.Decimal,
            sanitize = ::sanitiseMoneyInput,
            onCommit = { onEvent(StockInEvent.EditCost(key, moneyToPaise(it))) },
            readOnly = !canEditCost,
            subText = if (row.currentCostPaise != null && row.costPaise != null && row.costPaise != row.currentCostPaise) {
                stringResource(R.string.review_was, IndianCurrencyFormatter.formatPaise(row.currentCostPaise))
            } else null,
            modifier = Modifier.width(MoneyWidth),
        )
        // Sell
        EditableCell(
            tag = "cell_sell_$key",
            columnLabel = stringResource(R.string.review_col_sell),
            itemLabel = itemLabel,
            display = row.sellPaise?.let { IndianCurrencyFormatter.formatPaise(it) },
            placeholder = stringResource(R.string.review_add_price),
            initialText = row.sellPaise?.let { IndianCurrencyFormatter.paiseToEditableString(it) } ?: "",
            keyboardType = KeyboardType.Decimal,
            sanitize = ::sanitiseMoneyInput,
            onCommit = { onEvent(StockInEvent.EditSell(key, moneyToPaise(it))) },
            readOnly = row.variantId != null,
            modifier = Modifier.width(MoneyWidth),
        )
    }
}

@Composable
private fun StatusIcon(status: MatchStatus) {
    val (icon, desc, tint) = when (status) {
        MatchStatus.MATCHED -> Triple(Icons.Filled.CheckCircle, stringResource(R.string.review_status_matched), MaterialTheme.colorScheme.primary)
        MatchStatus.CHECK -> Triple(Icons.Filled.Warning, stringResource(R.string.review_status_check), MaterialTheme.colorScheme.tertiary)
        MatchStatus.NEW -> Triple(Icons.Filled.FiberNew, stringResource(R.string.review_status_new), MaterialTheme.colorScheme.secondary)
    }
    Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(StatusWidth))
}

/** Quantity text: digits and at most one '.', up to 3 decimals. */
internal fun sanitiseQtyInput(input: String): String? {
    if (!input.all { it.isDigit() || it == '.' }) return null
    if (input.count { it == '.' } > 1) return null
    if (input.substringBefore('.').length > 9) return null
    if (input.substringAfter('.', "").length > 3) return null
    return input
}

/** "" and "." mean no value; otherwise rupees to paise exactly (no floating point). */
private fun moneyToPaise(text: String): Long? =
    if (text.isBlank() || text == ".") null else parseMoneyPaise(text)

/**
 * A table cell that shows its value and turns into a text field when tapped. The whole cell (at least 48 dp tall)
 * is the tap target, and it is announced as "column for item: value".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableCell(
    tag: String,
    columnLabel: String,
    itemLabel: String,
    display: String?,
    placeholder: String,
    initialText: String,
    keyboardType: KeyboardType,
    sanitize: (String) -> String?,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    subText: String? = null,
    align: TextAlign = TextAlign.End,
    onLongClick: (() -> Unit)? = null,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val description = stringResource(R.string.review_cell_cd, columnLabel, itemLabel, display ?: placeholder)
    val isEditing = editing && !readOnly
    // While editing, only the text field carries the tag, so tests and services see a single node.
    val cellModifier = if (isEditing) {
        modifier.heightIn(min = ShopLedgerTokens.minTouch)
    } else {
        modifier
            .heightIn(min = ShopLedgerTokens.minTouch)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (removeLabel != null && onRemove != null) {
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                }
            }
            .then(
                if (readOnly) Modifier
                else Modifier.combinedClickable(onClick = { editing = true }, onLongClick = onLongClick)
            )
            .testTag(tag)
    }
    Column(
        cellModifier.padding(horizontal = CellPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start,
    ) {
        if (isEditing) {
            var text by remember { mutableStateOf(initialText) }
            var hadFocus by remember { mutableStateOf(false) }
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
            BasicTextField(
                value = text,
                onValueChange = { v -> sanitize(v)?.let { text = it; onCommit(it) } },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface, textAlign = align,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType, imeAction = ImeAction.Done,
                    capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
                    }
                    .testTag(tag),
            )
        } else {
            val empty = display == null
            Text(
                text = display ?: placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = if (empty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = align,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (subText != null) {
                Text(
                    subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = align,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(state: StockInUiState, onEvent: (StockInEvent) -> Unit) {
    val reason = state.blockReason
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
        ) {
            state.error?.let { err ->
                val text = if (state.errorIsGeneric) {
                    stringResource(R.string.review_error_generic) + if (err.isNotBlank()) "\n$err" else ""
                } else err
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { onEvent(StockInEvent.DismissError) },
                )
            }
            if (reason != null) {
                Text(
                    blockText(reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryActionButton(
                text = if (state.saving) stringResource(R.string.review_saving)
                else pluralStringResource(R.plurals.review_save, state.rows.size, state.rows.size),
                onClick = { onEvent(StockInEvent.Save) },
                enabled = reason == null && !state.saving,
            )
        }
    }
}

@Composable
private fun blockText(reason: BlockReason): String {
    val label = reason.rowLabel.orEmpty()
    return when (reason.kind) {
        BlockKind.EMPTY -> stringResource(R.string.review_block_empty)
        BlockKind.QTY -> stringResource(R.string.review_block_qty, label)
        BlockKind.NAME -> stringResource(R.string.review_block_name)
        BlockKind.COST -> stringResource(R.string.review_block_cost, label)
        BlockKind.SELL -> stringResource(R.string.review_block_sell, label)
        BlockKind.CHECK -> stringResource(R.string.review_block_check, label)
        BlockKind.DUPLICATE -> stringResource(R.string.review_block_duplicate, label)
        BlockKind.SUPPLIER -> stringResource(R.string.review_block_supplier)
        BlockKind.PAID -> stringResource(R.string.review_block_paid)
    }
}

@Composable
private fun PhotoDialog(uri: android.net.Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.review_cd_close))
            }
        },
        text = {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = stringResource(R.string.review_cd_photo), modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun ItemPickerDialog(
    state: StockInUiState,
    row: ReviewRow,
    onPick: (itemId: String, variantId: String) -> Unit,
    onMakeNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val options = remember(state.inventory, query) {
        val q = query.trim().lowercase()
        state.inventory.flatMap { g -> g.variants.map { v -> Triple(g, v, v.buildVariantSummary()) } }
            .filter { (g, _, summary) -> q.isEmpty() || g.item.displayName.lowercase().contains(q) || summary.lowercase().contains(q) }
            .take(60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
                Text(
                    stringResource(R.string.review_pick_on_bill, row.scannedName),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.review_pick_search)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("picker_search"),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(options, key = { it.second.id }) { (g, v, summary) ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(g.item.id, v.id) }
                                .heightIn(min = ShopLedgerTokens.minTouch)
                                .padding(vertical = ShopLedgerTokens.spacingXs),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(g.item.displayName, style = MaterialTheme.typography.bodyLarge)
                            if (v.hasLabels()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onMakeNew, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.review_make_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** True when the variant has a size, material or colour label (so the summary is worth showing). */
private fun ItemVariantEntity.hasLabels(): Boolean =
    listOf(sizeLabel, materialLabel, colorFinishLabel).any { it.isNotBlank() }
