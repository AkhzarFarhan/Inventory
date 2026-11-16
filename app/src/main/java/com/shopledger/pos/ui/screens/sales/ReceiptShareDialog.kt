package com.shopledger.pos.ui.screens.sales

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.print.ThermalPrinterManager
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.ui.theme.CupertinoColors
import com.shopledger.pos.ui.theme.CupertinoTextField
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

    val bannerBgColor = if (receipt.isEstimate) CupertinoColors.CipherIndigo else CupertinoColors.SystemBlue
    val docTypeLabel = if (receipt.isEstimate) "Estimate" else "Receipt"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CupertinoColors.SystemGroupedBackground,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FloatingAppMessageHost()
                // Visual Preview of Receipt / Estimate Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CupertinoColors.CardSurface)
                        .border(1.dp, CupertinoColors.Separator, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bannerBgColor)
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = sellerNameInput.ifBlank { "SHOPLEDGER POS" }.uppercase(Locale.ENGLISH),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = if (receipt.isEstimate) "ESTIMATE / QUOTATION"
                            else if (activeGstin.isNotBlank()) "TAX / SALE RECEIPT"
                            else "CASH / SALE RECEIPT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoColors.SystemBlueTint
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$docTypeLabel: #${receipt.receiptNumber}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.LabelPrimary
                            )
                            Text(
                                text = dateFormatted,
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }

                        if (activeGstin.isNotBlank()) {
                            Text(
                                text = "GSTIN: $activeGstin",
                                fontSize = 11.sp,
                                color = CupertinoColors.LabelSecondary
                            )
                        }

                        if (sellerWhatsAppInput.isNotBlank()) {
                            Text(
                                text = "Seller WhatsApp: +91 ${sellerWhatsAppInput.removePrefix("+91").trim()}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CupertinoColors.WhatsAppGreen
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(CupertinoColors.Separator)
                        )

                        receipt.items.forEachIndexed { idx, line ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${idx + 1}. ${line.itemName}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CupertinoColors.LabelPrimary
                                    )
                                    Text(
                                        text = "${line.variantSummary} • ${
                                            IndianCurrencyFormatter.formatQuantity(line.quantity)
                                        } ${line.unit} × ${IndianCurrencyFormatter.formatPaise(line.unitSellingPricePaise)}",
                                        fontSize = 11.sp,
                                        color = CupertinoColors.LabelSecondary
                                    )
                                }
                                Text(
                                    text = IndianCurrencyFormatter.formatPaise(line.lineTotalPaise),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CupertinoColors.LabelPrimary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(CupertinoColors.Separator)
                        )

                        if (receipt.discountPaise > 0L) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Discount", fontSize = 11.sp, color = CupertinoColors.LabelSecondary)
                                Text(
                                    text = "-${IndianCurrencyFormatter.formatPaise(receipt.discountPaise)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CupertinoColors.EmeraldGreen
                                )
                            }
                        }

                        if (receipt.taxPaise > 0L) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("GST Tax", fontSize = 11.sp, color = CupertinoColors.LabelSecondary)
                                Text(
                                    text = IndianCurrencyFormatter.formatPaise(receipt.taxPaise),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CupertinoColors.LabelPrimary
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CupertinoColors.SystemBlueTint)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (receipt.isEstimate) "ESTIMATE TOTAL" else "GRAND TOTAL",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = bannerBgColor
                            )
                            Text(
                                text = IndianCurrencyFormatter.formatPaise(receipt.grandTotalPaise),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = bannerBgColor
                            )
                        }

                        if (!receipt.isEstimate) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Mode: ${receipt.paymentMode} • Paid: ${IndianCurrencyFormatter.formatPaise(receipt.amountPaidPaise)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CupertinoColors.LabelSecondary
                                )
                                if (receipt.balanceDuePaise > 0L) {
                                    Text(
                                        text = "Due: ${IndianCurrencyFormatter.formatPaise(receipt.balanceDuePaise)}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CupertinoColors.DestructiveRed
                                    )
                                }
                            }
                        }

                        if (activeUpiId.isNotBlank()) {
                            Text(
                                text = "✓ Scannable UPI QR ($activeUpiId) embedded on shared PNG",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.EmeraldGreen
                            )
                        }
                    }
                }

                CupertinoTextField(
                    value = buyerPhoneInput,
                    onValueChange = { buyerPhoneInput = it },
                    label = "Buyer's WhatsApp Number (+91)",
                    placeholder = "Enter buyer's 10-digit number",
                    keyboardType = KeyboardType.Phone
                )

                CupertinoTextField(
                    value = buyerNameInput,
                    onValueChange = { buyerNameInput = it },
                    label = "Buyer Name on $docTypeLabel (Optional)",
                    placeholder = "Customer Name",
                    capitalization = KeyboardCapitalization.Words
                )

                CupertinoTextField(
                    value = sellerNameInput,
                    onValueChange = {
                        sellerNameInput = it
                        SyncManager.saveSellerName(context, it)
                    },
                    label = "Seller / Shop Name (Printed on Receipt)",
                    placeholder = "Shop Name / Owner Name",
                    capitalization = KeyboardCapitalization.Words
                )

                CupertinoTextField(
                    value = sellerWhatsAppInput,
                    onValueChange = {
                        sellerWhatsAppInput = it
                        SyncManager.saveSellerWhatsAppNumber(context, it)
                    },
                    label = "Seller's WhatsApp Number (For 1-Tap Buyer Reply)",
                    placeholder = "Your shop WhatsApp number (saved automatically)",
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
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

                Button(
                    onClick = {
                        runCatching {
                            val updated = buildUpdatedReceipt()
                            ReceiptShareManager.shareReceiptImageToWhatsApp(
                                context = context,
                                receipt = updated,
                                rawBuyerPhone = buyerPhoneInput
                            )
                        }.onFailure { err ->
                            AppErrorManager.reportError("Receipt Share Failed", err)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CupertinoColors.WhatsAppGreen,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (buyerPhoneInput.isNotBlank()) {
                            "Send $docTypeLabel Photo to ${buyerPhoneInput.trim()}"
                        } else {
                            "Share $docTypeLabel Photo on WhatsApp"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // 58mm Bluetooth ESC/POS Thermal Print Button
                OutlinedButton(
                    onClick = {
                        if (isPrinting) return@OutlinedButton
                        isPrinting = true
                        val updated = buildUpdatedReceipt()
                        coroutineScope.launch {
                            val result = ThermalPrinterManager.printReceiptViaBluetooth(context, updated, 58)
                            isPrinting = false
                            result.onSuccess { printerName ->
                                AppErrorManager.reportSuccess("Thermal Print Sent", "Printed via $printerName")
                            }.onFailure { err ->
                                AppErrorManager.reportError("Bluetooth Print Failed", err)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = CupertinoColors.SystemBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPrinting) "Printing..." else "Print Thermal Receipt (58mm Bluetooth)",
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.SystemBlue,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done / Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
