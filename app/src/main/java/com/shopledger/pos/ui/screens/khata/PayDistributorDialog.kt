package com.shopledger.pos.ui.screens.khata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.ui.theme.MoneyField
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens

@Composable
fun PayDistributorDialog(
    party: PartyEntity,
    onConfirm: (amountPaise: Long, mode: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val owedPaise = (-party.currentBalancePaise).coerceAtLeast(0L)
    var amountPaise by rememberSaveable(party.id) { mutableStateOf(owedPaise.takeIf { it > 0L }) }
    var modeIndex by rememberSaveable(party.id) { mutableIntStateOf(0) } // 0 = CASH (Nagad), 1 = UPI
    var note by rememberSaveable(party.id) { mutableStateOf("") }
    val entered = amountPaise ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.distributor_pay_title, party.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
            ) {
                MoneyField(
                    valuePaise = amountPaise,
                    onValueChange = { amountPaise = it },
                    label = stringResource(R.string.distributor_pay_amount),
                    modifier = Modifier.fillMaxWidth(),
                )
                SegmentedChoice(
                    options = listOf(
                        stringResource(R.string.pay_nagad),
                        stringResource(R.string.pay_upi),
                    ),
                    selectedIndex = modeIndex,
                    onSelect = { modeIndex = it },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(60) },
                    label = { Text(stringResource(R.string.distributor_pay_note)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (entered > owedPaise) {
                    Text(
                        text = stringResource(R.string.distributor_pay_more_than_owed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (entered > 0L) {
                        onConfirm(entered, if (modeIndex == 1) "UPI" else "CASH", note.trim())
                    }
                },
                enabled = entered > 0L,
                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
            ) {
                Text(stringResource(R.string.distributor_pay_save), style = MaterialTheme.typography.labelLarge)
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
