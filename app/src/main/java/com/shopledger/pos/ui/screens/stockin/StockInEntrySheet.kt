package com.shopledger.pos.ui.screens.stockin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens

/**
 * Entry point for adding stock. Today it only offers manual entry; scanning a bill or picking a
 * photo are added here later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInEntrySheet(
    onAddManually: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            Text(stringResource(R.string.stock_in_title), style = MaterialTheme.typography.titleLarge)
            PrimaryActionButton(
                text = stringResource(R.string.stock_in_add_manually),
                onClick = onAddManually,
                icon = Icons.Filled.Edit,
            )
        }
    }
}
