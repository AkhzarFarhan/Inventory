package com.shopledger.pos.ui.screens.khata

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.MoneyField
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DistributorsScreen(
    distributors: List<PartyEntity>,
    purchaseBills: List<PurchaseBillEntity>,
    onAddDistributor: (name: String, phone: String, gstin: String, alreadyOwePaise: Long) -> Unit,
    onPayDistributor: (partyId: String, amountPaise: Long, mode: String, note: String) -> Unit,
    onOpenDistributor: (partyId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var payTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    val totalOwePaise = remember(distributors) {
        distributors.sumOf { (-it.currentBalancePaise).coerceAtLeast(0L) }
    }
    val lastBillBySupplier = remember(purchaseBills) {
        purchaseBills
            .filter { it.supplierPartyId != null }
            .groupBy { it.supplierPartyId!! }
            .mapValues { (_, list) -> list.maxByOrNull { it.createdAt } }
    }
    val filteredDistributors = remember(distributors, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        distributors
            .filter { p ->
                q.isEmpty() ||
                    p.name.lowercase(Locale.ROOT).contains(q) ||
                    p.phone.lowercase(Locale.ROOT).contains(q)
            }
            .sortedWith(
                compareByDescending<PartyEntity> { (-it.currentBalancePaise).coerceAtLeast(0L) }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }
    val dateFmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
        ) {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.distributors_total_owe),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.distributors_count, distributors.size, distributors.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = IndianCurrencyFormatter.formatPaise(totalOwePaise),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (totalOwePaise > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.khata_search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (filteredDistributors.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (distributors.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.distributors_empty_title),
                        message = stringResource(R.string.distributors_empty_message),
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.khata_no_match_title),
                        message = stringResource(R.string.khata_no_match_message),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
            ) {
                items(items = filteredDistributors, key = { it.id }) { party ->
                    val balance = party.currentBalancePaise
                    val owes = balance < 0L
                    val lastBill = lastBillBySupplier[party.id]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .clickable { onOpenDistributor(party.id) },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.dp,
                            if (owes) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingM),
                            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = ShopLedgerTokens.spacingS),
                                    verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                                ) {
                                    Text(
                                        text = party.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = party.phone.ifBlank { stringResource(R.string.distributor_no_phone) },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = if (lastBill != null) {
                                            stringResource(
                                                R.string.distributor_last_bill,
                                                dateFmt.format(Date(lastBill.createdAt)),
                                                IndianCurrencyFormatter.formatPaise(lastBill.totalPaise),
                                            )
                                        } else {
                                            stringResource(R.string.distributor_no_bills)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                                ) {
                                    when {
                                        balance < 0L -> {
                                            Text(
                                                text = stringResource(
                                                    R.string.distributor_you_owe,
                                                    IndianCurrencyFormatter.formatPaise(-balance),
                                                ),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                            StatusChip(
                                                text = stringResource(R.string.distributor_badge_owe),
                                                tone = ChipTone.WARNING,
                                            )
                                        }
                                        balance > 0L -> {
                                            StatusChip(
                                                text = stringResource(
                                                    R.string.distributor_they_owe,
                                                    IndianCurrencyFormatter.formatPaise(balance),
                                                ),
                                                tone = ChipTone.INFO,
                                            )
                                        }
                                        else -> {
                                            StatusChip(
                                                text = stringResource(R.string.khata_badge_settled),
                                                tone = ChipTone.SUCCESS,
                                            )
                                        }
                                    }
                                }
                            }

                            if (owes) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.distributor_pay),
                                    onClick = { payTargetId = party.id },
                                    icon = Icons.Filled.Payments,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        PrimaryActionButton(
            text = stringResource(R.string.distributor_add),
            onClick = { showAddDialog = true },
            icon = Icons.Filled.PersonAdd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
        )
    }

    if (showAddDialog) {
        AddDistributorDialog(
            onConfirm = { name, phone, gstin, owePaise ->
                onAddDistributor(name, phone, gstin, owePaise)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    val payTarget = remember(payTargetId, distributors) {
        payTargetId?.let { id -> distributors.firstOrNull { it.id == id } }
    }
    if (payTarget != null) {
        PayDistributorDialog(
            party = payTarget,
            onConfirm = { amount, mode, note ->
                onPayDistributor(payTarget.id, amount, mode, note)
                payTargetId = null
            },
            onDismiss = { payTargetId = null },
        )
    }
}

@Composable
private fun AddDistributorDialog(
    onConfirm: (name: String, phone: String, gstin: String, alreadyOwePaise: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var gstin by rememberSaveable { mutableStateOf("") }
    var owePaise by rememberSaveable { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.distributor_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.distributor_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                    label = { Text(stringResource(R.string.distributor_phone)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = gstin,
                    onValueChange = { gstin = it.uppercase(Locale.ROOT).take(15) },
                    label = { Text(stringResource(R.string.distributor_gstin)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                MoneyField(
                    valuePaise = owePaise,
                    onValueChange = { owePaise = it },
                    label = stringResource(R.string.distributor_already_owe),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), phone.trim(), gstin.trim(), (owePaise ?: 0L).coerceAtLeast(0L))
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) {
                Text(stringResource(R.string.khata_save_party), style = MaterialTheme.typography.labelLarge)
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
