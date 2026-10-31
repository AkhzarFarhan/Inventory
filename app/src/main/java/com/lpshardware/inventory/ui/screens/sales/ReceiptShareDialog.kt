package com.lpshardware.inventory.ui.screens.sales

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
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.core.share.ReceiptShareManager
import com.lpshardware.inventory.core.share.SaleReceiptData
import com.lpshardware.inventory.core.sync.SyncManager
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.CupertinoTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interactive Sale Receipt OR Estimate Photo Preview & Direct WhatsApp Buyer Phone Number Share Modal.
 * Generates a high-contrast PNG image of the receipt/estimate and opens WhatsApp directly to the buyer's phone number,
 * including the Seller's WhatsApp Number so the buyer can reach out to the seller in a single tap.
 */
@Composable
fun ReceiptShareDialog(
    receipt: SaleReceiptData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                // Visual Preview of the Receipt / Estimate Photo Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CupertinoColors.CardSurface)
                        .border(1.dp, CupertinoColors.Separator, RoundedCornerShape(14.dp))
                ) {
                    // Header Banner
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bannerBgColor)
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = sellerNameInput.ifBlank { "LPS HARDWARE" }.uppercase(Locale.ENGLISH),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = if (receipt.isEstimate) "ESTIMATE / QUOTATION (KACCHA PARCHA)"
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
                    }
                }

                // Buyer's WhatsApp Number Input
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

                // Seller's Name & WhatsApp Number (Reflected on photo & 1-tap wa.me link for the buyer)
                CupertinoTextField(
                    value = sellerNameInput,
                    onValueChange = {
                        sellerNameInput = it
                        SyncManager.saveSellerName(context, it)
                    },
                    label = "Seller / Shop Name (Printed on Receipt)",
                    placeholder = "LPS Hardware / Owner Name",
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

                // Share Receipt / Estimate Photo over WhatsApp Button
                Button(
                    onClick = {
                        SyncManager.saveSellerName(context, sellerNameInput)
                        SyncManager.saveSellerWhatsAppNumber(context, sellerWhatsAppInput)
                        val updatedReceipt = receipt.copy(
                            buyerName = buyerNameInput.trim(),
                            buyerPhone = buyerPhoneInput.trim(),
                            sellerName = sellerNameInput.trim(),
                            sellerWhatsAppPhone = sellerWhatsAppInput.trim()
                        )
                        ReceiptShareManager.shareReceiptImageToWhatsApp(
                            context = context,
                            receipt = updatedReceipt,
                            rawBuyerPhone = buyerPhoneInput
                        )
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
