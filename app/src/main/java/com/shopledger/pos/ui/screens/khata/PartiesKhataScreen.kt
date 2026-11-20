package com.shopledger.pos.ui.screens.khata

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip

/**
 * Customer & Supplier Credit Ledger (*Udhar Khata*) Screen.
 * Tracks receivables (*Baaki*), records partial/full *Jama* payments in Cash or UPI,
 * and sends 1-tap WhatsApp payment reminders.
 */
@Composable
fun PartiesKhataScreen(
    parties: List<PartyEntity>,
    onAddParty: (name: String, phone: String, partyType: String, openingBalancePaise: Long) -> Unit,
    onRecordPaymentReceived: (partyId: String, amountPaise: Long, paymentMode: String, note: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var filterTab by remember { mutableIntStateOf(0) } // 0 = All, 1 = Due (Baaki), 2 = Settled
    var showAddPartyDialog by remember { mutableStateOf(false) }
    var paymentTargetParty by remember { mutableStateOf<PartyEntity?>(null) }

    val totalDuePaise = remember(parties) {
        parties.sumOf { it.currentBalancePaise.coerceAtLeast(0L) }
    }
    val duePartyCount = remember(parties) {
        parties.count { it.currentBalancePaise > 0L }
    }

    val filteredParties = remember(parties, searchQuery, filterTab) {
        val q = searchQuery.trim().lowercase()
        parties.filter { p ->
            val matchesFilter = when (filterTab) {
                1 -> p.currentBalancePaise > 0L
                2 -> p.currentBalancePaise <= 0L
                else -> true
            }
            val matchesQuery = q.isEmpty() ||
                p.name.lowercase().contains(q) ||
                p.phone.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Summary + search + filter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingM),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
        ) {
            val hasDueTotal = totalDuePaise > 0L
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (hasDueTotal) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (hasDueTotal) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(ShopLedgerTokens.spacingM),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.khata_total_due),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.khata_summary, duePartyCount, parties.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        text = IndianCurrencyFormatter.formatPaise(totalDuePaise),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.khata_search_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )

                FilledIconButton(
                    onClick = { showAddPartyDialog = true },
                    modifier = Modifier.size(ShopLedgerTokens.primaryActionHeight),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.khata_add_party))
                }
            }

            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.khata_filter_all, parties.size),
                    stringResource(R.string.khata_filter_due, duePartyCount),
                    stringResource(R.string.khata_filter_settled),
                ),
                selectedIndex = filterTab,
                onSelect = { filterTab = it }
            )
        }

        if (filteredParties.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (parties.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.khata_empty_title),
                        message = stringResource(R.string.khata_empty_message),
                        actionText = stringResource(R.string.khata_add_party),
                        onAction = { showAddPartyDialog = true },
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
                contentPadding = PaddingValues(ShopLedgerTokens.spacingL),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = filteredParties, key = { it.id }) { party ->
                    val hasDue = party.currentBalancePaise > 0L
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.dp,
                            if (hasDue) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(ShopLedgerTokens.spacingL),
                            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column {
                                        Text(
                                            text = party.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = party.phone.ifBlank { party.partyType },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = IndianCurrencyFormatter.formatPaise(if (hasDue) party.currentBalancePaise else 0L),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = if (hasDue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    StatusChip(
                                        text = stringResource(if (hasDue) R.string.khata_badge_due else R.string.khata_badge_settled),
                                        tone = if (hasDue) ChipTone.DANGER else ChipTone.SUCCESS,
                                    )
                                }
                            }

                            if (hasDue) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                                ) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.khata_remind),
                                        icon = Icons.Default.Share,
                                        onClick = {
                                            val shopName = SyncManager.getSellerName(context)
                                            val upiId = SyncManager.getUpiId(context)
                                            val amtStr = IndianCurrencyFormatter.formatPaise(party.currentBalancePaise)
                                            val msg = buildString {
                                                append(context.getString(R.string.khata_reminder_body, party.name, shopName, amtStr))
                                                if (upiId.isNotBlank()) {
                                                    append(context.getString(R.string.khata_reminder_upi, upiId))
                                                }
                                                append(context.getString(R.string.khata_reminder_thanks))
                                            }
                                            ReceiptShareManager.shareTextToWhatsApp(context, msg, party.phone)
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    PrimaryActionButton(
                                        text = stringResource(R.string.khata_receive),
                                        icon = Icons.Default.CheckCircle,
                                        onClick = { paymentTargetParty = party },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Party Dialog
    if (showAddPartyDialog) {
        var partyName by remember { mutableStateOf("") }
        var partyPhone by remember { mutableStateOf("") }
        var openingBalInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddPartyDialog = false },
            title = { Text(stringResource(R.string.khata_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
                    OutlinedTextField(
                        value = partyName,
                        onValueChange = { partyName = it },
                        label = { Text(stringResource(R.string.khata_field_name)) },
                        placeholder = { Text(stringResource(R.string.khata_field_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = partyPhone,
                        onValueChange = { partyPhone = it },
                        label = { Text(stringResource(R.string.khata_field_phone)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = openingBalInput,
                        onValueChange = { openingBalInput = it },
                        label = { Text(stringResource(R.string.khata_field_opening)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (partyName.isBlank()) {
                            Toast.makeText(context, R.string.msg_party_name_needed, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val openingPaise = IndianCurrencyFormatter.parseRupeesToPaise(openingBalInput)
                        onAddParty(partyName.trim(), partyPhone.trim(), "CUSTOMER", openingPaise)
                        showAddPartyDialog = false
                    },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.khata_save_party), style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddPartyDialog = false },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    // Receive Payment (Jama) Dialog
    paymentTargetParty?.let { target ->
        var amountInput by remember(target.id) {
            mutableStateOf(IndianCurrencyFormatter.paiseToEditableString(target.currentBalancePaise))
        }
        var modeIndex by remember(target.id) { mutableIntStateOf(0) } // 0 = CASH, 1 = UPI
        var noteInput by remember(target.id) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { paymentTargetParty = null },
            title = { Text(stringResource(R.string.khata_pay_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
                    Text(
                        text = stringResource(
                            R.string.khata_pay_party_due,
                            target.name,
                            IndianCurrencyFormatter.formatPaise(target.currentBalancePaise)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text(stringResource(R.string.khata_pay_amount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SegmentedChoice(
                        options = listOf(
                            stringResource(R.string.checkout_pay_cash),
                            stringResource(R.string.checkout_pay_upi),
                        ),
                        selectedIndex = modeIndex,
                        onSelect = { modeIndex = it }
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text(stringResource(R.string.khata_pay_note)) },
                        placeholder = { Text(stringResource(R.string.khata_pay_note_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amtPaise = IndianCurrencyFormatter.parseRupeesToPaise(amountInput)
                        if (amtPaise <= 0L) {
                            Toast.makeText(context, R.string.msg_amount_needed, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val mode = if (modeIndex == 1) "UPI" else "CASH"
                        onRecordPaymentReceived(target.id, amtPaise, mode, noteInput)
                        paymentTargetParty = null
                        Toast.makeText(context, R.string.msg_payment_recorded, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.khata_pay_confirm), style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { paymentTargetParty = null },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}
