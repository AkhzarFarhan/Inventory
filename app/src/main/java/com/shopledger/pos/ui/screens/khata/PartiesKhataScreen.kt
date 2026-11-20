package com.shopledger.pos.ui.screens.khata

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.ui.theme.CupertinoCard
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.CupertinoColors
import com.shopledger.pos.ui.theme.CupertinoSegmentedControl
import com.shopledger.pos.ui.theme.CupertinoTextField
import com.shopledger.pos.ui.theme.SemanticPillBadge

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CupertinoColors.SystemGroupedBackground)
    ) {
        // Header + Search + KPI Summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CupertinoColors.CardSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (totalDuePaise > 0L) CupertinoColors.AmberTint else CupertinoColors.EmeraldTint)
                    .border(
                        1.dp,
                        if (totalDuePaise > 0L) CupertinoColors.AmberWarning.copy(alpha = 0.35f)
                        else CupertinoColors.EmeraldGreen.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = if (totalDuePaise > 0L) CupertinoColors.AmberWarning else CupertinoColors.EmeraldGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "TOTAL KHATA RECEIVABLE (BAAKI)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.LabelSecondary
                        )
                        Text(
                            text = "$duePartyCount pending parties • ${parties.size} total",
                            fontSize = 11.sp,
                            color = CupertinoColors.LabelSecondary
                        )
                    }
                }

                Text(
                    text = IndianCurrencyFormatter.formatPaise(totalDuePaise),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (totalDuePaise > 0L) CupertinoColors.DestructiveRed else CupertinoColors.EmeraldGreen
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search customer / phone...",
                            fontSize = 13.sp,
                            color = CupertinoColors.LabelMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Parties",
                            tint = CupertinoColors.LabelSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CupertinoColors.SearchFieldFill,
                        unfocusedContainerColor = CupertinoColors.SearchFieldFill,
                        focusedBorderColor = CupertinoColors.SystemBlue,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = { showAddPartyDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.SystemBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Party", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Party", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            CupertinoSegmentedControl(
                options = listOf("All (${parties.size})", "Due ($duePartyCount)", "Settled"),
                selectedIndex = filterTab,
                onSelect = { filterTab = it }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CupertinoColors.Separator)
        )

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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = filteredParties, key = { it.id }) { party ->
                    val hasDue = party.currentBalancePaise > 0L
                    CupertinoCard(
                        borderColor = if (hasDue) CupertinoColors.DestructiveRed.copy(alpha = 0.25f) else CupertinoColors.LightBorder
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = CupertinoColors.SystemBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = party.name,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CupertinoColors.LabelPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = party.phone.ifBlank { party.partyType },
                                        fontSize = 12.sp,
                                        color = CupertinoColors.LabelSecondary
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (hasDue) IndianCurrencyFormatter.formatPaise(party.currentBalancePaise) else "₹0",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (hasDue) CupertinoColors.DestructiveRed else CupertinoColors.EmeraldGreen
                                )
                                SemanticPillBadge(
                                    text = if (hasDue) "BAAKI DUE" else "SETTLED",
                                    containerColor = if (hasDue) CupertinoColors.DestructiveTint else CupertinoColors.EmeraldTint,
                                    contentColor = if (hasDue) CupertinoColors.DestructiveRed else CupertinoColors.EmeraldGreen
                                )
                            }
                        }

                        if (hasDue) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val shopName = SyncManager.getSellerName(context)
                                        val upiId = SyncManager.getUpiId(context)
                                        val amtStr = IndianCurrencyFormatter.formatPaise(party.currentBalancePaise)
                                        val msg = buildString {
                                            append("Namaste ${party.name},\n")
                                            append("Gentle payment reminder from *$shopName*: Your pending Khata balance is *$amtStr*.\n")
                                            if (upiId.isNotBlank()) {
                                                append("Pay via UPI: `$upiId`\n")
                                            }
                                            append("Thank you!")
                                        }
                                        ReceiptShareManager.shareTextToWhatsApp(context, msg, party.phone)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, CupertinoColors.WhatsAppGreen.copy(alpha = 0.45f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = CupertinoColors.WhatsAppGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Remind",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CupertinoColors.WhatsAppGreen
                                    )
                                }

                                Button(
                                    onClick = { paymentTargetParty = party },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CupertinoColors.EmeraldGreen,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1.25f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "+ Receive (Jama)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
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
            title = { Text("Add Customer / Supplier", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CupertinoTextField(
                        value = partyName,
                        onValueChange = { partyName = it },
                        label = "Party Name",
                        placeholder = "e.g. Ramesh Traders",
                        capitalization = KeyboardCapitalization.Words
                    )
                    CupertinoTextField(
                        value = partyPhone,
                        onValueChange = { partyPhone = it },
                        label = "WhatsApp / Mobile",
                        placeholder = "9876543210",
                        keyboardType = KeyboardType.Phone
                    )
                    CupertinoTextField(
                        value = openingBalInput,
                        onValueChange = { openingBalInput = it },
                        label = "Opening Due Balance (₹, Optional)",
                        placeholder = "0",
                        keyboardType = KeyboardType.Decimal
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
                    }
                ) {
                    Text("Save Party", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPartyDialog = false }) {
                    Text("Cancel")
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
            title = { Text("Receive Payment (Jama)", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${target.name} • Pending Due: ${IndianCurrencyFormatter.formatPaise(target.currentBalancePaise)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CupertinoColors.LabelSecondary
                    )
                    CupertinoTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = "Amount Received (₹)",
                        keyboardType = KeyboardType.Decimal
                    )
                    CupertinoSegmentedControl(
                        options = listOf("Cash", "UPI"),
                        selectedIndex = modeIndex,
                        onSelect = { modeIndex = it }
                    )
                    CupertinoTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = "Note (Optional)",
                        placeholder = "e.g. Part payment"
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
                    colors = ButtonDefaults.buttonColors(containerColor = CupertinoColors.EmeraldGreen)
                ) {
                    Text("Confirm Jama", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { paymentTargetParty = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
