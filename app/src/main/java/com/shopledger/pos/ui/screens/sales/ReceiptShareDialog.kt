package com.shopledger.pos.ui.screens.sales

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shopledger.pos.R
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.print.PrinterProblem
import com.shopledger.pos.core.print.ThermalPrinterManager
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interactive Sale Receipt OR Estimate Photo Preview, UPI QR & WhatsApp Share,
 * plus 58mm / 80mm Bluetooth ESC/POS Thermal Printing Modal.
 */
@Composable
fun ReceiptShareDialog(
    receipt: SaleReceiptData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var buyerPhoneInput by remember(receipt) { mutableStateOf(receipt.buyerPhone) }
    var buyerNameInput by remember(receipt) { mutableStateOf(receipt.buyerName) }
    var sellerNameInput by remember(receipt) {
        mutableStateOf(
            receipt.sellerName.ifBlank { SyncManager.getSellerName(context) }
        )
    }
    var sellerWhatsAppInput by remember(receipt) {
        mutableStateOf(
            receipt.sellerWhatsAppPhone.ifBlank { SyncManager.getSellerWhatsAppNumber(context) }
        )
    }
    val activeUpiId = remember(receipt) {
        receipt.upiId.ifBlank { SyncManager.getUpiId(context) }
    }
    val activeGstin = remember(receipt) {
        receipt.gstin.ifBlank { SyncManager.getGstin(context) }
    }
    var isPrinting by remember { mutableStateOf(false) }

    val dateFormatted = remember(receipt.timestampMillis) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(receipt.timestampMillis))
    }

    val scheme = MaterialTheme.colorScheme
    val bannerBgColor = if (receipt.isEstimate) scheme.tertiary else scheme.primary
    val bannerTextColor = if (receipt.isEstimate) scheme.onTertiary else scheme.onPrimary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(ShopLedgerTokens.spacingL),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM)
            ) {
                FloatingAppMessageHost()
                // Visual preview of the receipt / estimate card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bannerBgColor)
                                .padding(vertical = ShopLedgerTokens.spacingM, horizontal = ShopLedgerTokens.spacingL),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = sellerNameInput.ifBlank { stringResource(R.string.app_name) }.uppercase(Locale.ENGLISH),
                                style = MaterialTheme.typography.titleLarge,
                                color = bannerTextColor
                            )
                            Text(
                                text = stringResource(
                                    if (receipt.isEstimate) R.string.receipt_banner_estimate
                                    else if (activeGstin.isNotBlank()) R.string.receipt_banner_tax
                                    else R.string.receipt_banner_cash
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = bannerTextColor
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(ShopLedgerTokens.spacingL),
                            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(
                                        if (receipt.isEstimate) R.string.receipt_doc_estimate else R.string.receipt_doc_receipt,
                                        receipt.receiptNumber
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = dateFormatted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (activeGstin.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.receipt_gstin, activeGstin),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (sellerWhatsAppInput.isNotBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.receipt_seller_whatsapp,
                                        sellerWhatsAppInput.removePrefix("+91").trim()
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider()

                            receipt.items.forEachIndexed { idx, line ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = ShopLedgerTokens.spacingXs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${idx + 1}. ${line.itemName}",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = "${line.variantSummary} • ${
                                                IndianCurrencyFormatter.formatQuantity(line.quantity)
                                            } ${line.unit} × ${IndianCurrencyFormatter.formatPaise(line.unitSellingPricePaise)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = IndianCurrencyFormatter.formatPaise(line.lineTotalPaise),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            HorizontalDivider()

                            if (receipt.discountPaise > 0L) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        stringResource(R.string.checkout_discount),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "-${IndianCurrencyFormatter.formatPaise(receipt.discountPaise)}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (receipt.taxPaise > 0L) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        stringResource(R.string.checkout_gst),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = IndianCurrencyFormatter.formatPaise(receipt.taxPaise),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (receipt.isEstimate) R.string.receipt_total_estimate else R.string.receipt_total_grand
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = IndianCurrencyFormatter.formatPaise(receipt.grandTotalPaise),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                }
                            }

                            if (!receipt.isEstimate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.receipt_mode_paid,
                                            paymentModeLabel(receipt.paymentMode),
                                            IndianCurrencyFormatter.formatPaise(receipt.amountPaidPaise)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (receipt.balanceDuePaise > 0L) {
                                        Text(
                                            text = stringResource(
                                                R.string.receipt_due,
                                                IndianCurrencyFormatter.formatPaise(receipt.balanceDuePaise)
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            if (activeUpiId.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.receipt_upi_qr, activeUpiId),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                val docIsEstimate = receipt.isEstimate

                OutlinedTextField(
                    value = buyerPhoneInput,
                    onValueChange = { buyerPhoneInput = it },
                    label = { Text(stringResource(R.string.receipt_field_buyer_phone)) },
                    placeholder = { Text(stringResource(R.string.receipt_field_buyer_phone_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = buyerNameInput,
                    onValueChange = { buyerNameInput = it },
                    label = {
                        Text(
                            stringResource(
                                if (docIsEstimate) R.string.receipt_field_buyer_name_estimate
                                else R.string.receipt_field_buyer_name_receipt
                            )
                        )
                    },
                    placeholder = { Text(stringResource(R.string.receipt_field_buyer_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = sellerNameInput,
                    onValueChange = {
                        sellerNameInput = it
                        SyncManager.saveSellerName(context, it)
                    },
                    label = { Text(stringResource(R.string.receipt_field_seller_name)) },
                    placeholder = { Text(stringResource(R.string.receipt_field_seller_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = sellerWhatsAppInput,
                    onValueChange = {
                        sellerWhatsAppInput = it
                        SyncManager.saveSellerWhatsAppNumber(context, it)
                    },
                    label = { Text(stringResource(R.string.receipt_field_seller_whatsapp)) },
                    placeholder = { Text(stringResource(R.string.receipt_field_seller_whatsapp_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                fun buildUpdatedReceipt(): SaleReceiptData {
                    SyncManager.saveSellerName(context, sellerNameInput)
                    SyncManager.saveSellerWhatsAppNumber(context, sellerWhatsAppInput)
                    return receipt.copy(
                        buyerName = buyerNameInput.trim(),
                        buyerPhone = buyerPhoneInput.trim(),
                        sellerName = sellerNameInput.trim(),
                        sellerWhatsAppPhone = sellerWhatsAppInput.trim(),
                        upiId = activeUpiId,
                        gstin = activeGstin
                    )
                }

                PrimaryActionButton(
                    text = if (buyerPhoneInput.isNotBlank()) {
                        stringResource(
                            if (docIsEstimate) R.string.receipt_send_estimate_to else R.string.receipt_send_receipt_to,
                            buyerPhoneInput.trim()
                        )
                    } else {
                        stringResource(if (docIsEstimate) R.string.receipt_share_estimate else R.string.receipt_share_receipt)
                    },
                    icon = Icons.Default.Share,
                    onClick = {
                        runCatching {
                            val updated = buildUpdatedReceipt()
                            ReceiptShareManager.shareReceiptImageToWhatsApp(
                                context = context,
                                receipt = updated,
                                rawBuyerPhone = buyerPhoneInput
                            )
                        }.onFailure { err ->
                            AppErrorManager.reportError(UiText.Res(R.string.err_receipt_share_title), UiText.Res(R.string.err_receipt_share_detail), err)
                        }
                    },
                )

                // 58mm Bluetooth ESC/POS thermal print button
                SecondaryActionButton(
                    text = stringResource(if (isPrinting) R.string.receipt_printing else R.string.receipt_print),
                    icon = Icons.Default.Print,
                    onClick = {
                        if (isPrinting) return@SecondaryActionButton
                        isPrinting = true
                        val updated = buildUpdatedReceipt()
                        coroutineScope.launch {
                            val result = ThermalPrinterManager.printReceiptViaBluetooth(context, updated, 58)
                            isPrinting = false
                            result.onSuccess { printerName ->
                                AppErrorManager.reportSuccess(UiText.Res(R.string.msg_print_sent_title), UiText.Res(R.string.msg_print_sent, listOf(printerName)))
                            }.onFailure { err ->
                                AppErrorManager.reportError(
                                    UiText.Res(R.string.err_print_title),
                                    (err as? PrinterProblem)?.let { UiText.Res(it.userMessage) } ?: UiText.Res(R.string.err_print_detail),
                                    err
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                SecondaryActionButton(
                    text = stringResource(R.string.action_done),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun paymentModeLabel(mode: String): String = when (mode.trim().uppercase(Locale.ENGLISH)) {
    "CASH" -> stringResource(R.string.checkout_pay_cash)
    "UPI" -> stringResource(R.string.checkout_pay_upi)
    "CREDIT", "UDHAR" -> stringResource(R.string.checkout_pay_udhar)
    else -> mode
}
