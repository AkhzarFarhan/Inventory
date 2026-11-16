package com.shopledger.pos.core.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.sync.SyncManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale

@Immutable
data class ReceiptLineItem(
    val itemName: String,
    val variantSummary: String,
    val quantity: Double,
    val unit: String,
    val unitSellingPricePaise: Long,
    val lineTotalPaise: Long
)

@Immutable
data class SaleReceiptData(
    /** Smart Receipt ID (`YYYYMMDDCCCC` or `D1-YYYYMMDD-CCCC`) */
    val receiptNumber: String,
    val timestampMillis: Long,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val sellerName: String = "",
    val sellerWhatsAppPhone: String = "",
    val isEstimate: Boolean = false,
    val items: List<ReceiptLineItem>,
    val grandTotalPaise: Long,
    val paymentMode: String = "CASH",
    val subtotalPaise: Long = grandTotalPaise,
    val discountPaise: Long = 0L,
    val taxPaise: Long = 0L,
    val amountPaidPaise: Long = grandTotalPaise,
    val balanceDuePaise: Long = 0L,
    val upiId: String = "",
    val gstin: String = ""
)

object ReceiptShareManager {

    /**
     * Generates a scannable monochrome QR Bitmap for UPI payments (`upi://pay?...`) using ZXing.
     */
    fun generateUpiQrBitmap(
        upiId: String,
        payeeName: String,
        amountPaise: Long,
        sizePx: Int = 180
    ): Bitmap? {
        return try {
            val amountStr = IndianCurrencyFormatter.paiseToEditableString(amountPaise).ifBlank { "0" }
            val uri = "upi://pay?pa=${Uri.encode(upiId.trim())}&pn=${Uri.encode(payeeName.ifBlank { "ShopLedger POS" })}&am=$amountStr&cu=INR"
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.MARGIN, 1)
            }
            val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Renders a crisp, modern Sale Receipt OR Estimate PNG image (`RGB_565` low-memory config)
     * with Seller WhatsApp link, Payment Mode / Balance Due (Udhar), optional GSTIN, and UPI QR code.
     * Strictly customer-safe: NEVER includes purchase cost or profit margins.
     */
    fun generateReceiptImageUri(context: Context, receipt: SaleReceiptData): Uri? {
        return try {
            val sellerName = receipt.sellerName.ifBlank {
                SyncManager.getSellerName(context)
            }.ifBlank { "ShopLedger POS" }
            val sellerPhone = receipt.sellerWhatsAppPhone.ifBlank {
                SyncManager.getSellerWhatsAppNumber(context)
            }
            val upiId = receipt.upiId.ifBlank { SyncManager.getUpiId(context) }
            val gstin = receipt.gstin.ifBlank { SyncManager.getGstin(context) }

            val width = 760
            val hasBuyerRow = receipt.buyerPhone.isNotBlank() || receipt.buyerName.isNotBlank()
            val hasSellerRow = sellerPhone.isNotBlank() || sellerName.isNotBlank()
            val hasGstin = gstin.isNotBlank()

            val qrAmountPaise = if (receipt.balanceDuePaise > 0L && !receipt.isEstimate) {
                receipt.balanceDuePaise
            } else {
                receipt.grandTotalPaise
            }
            val qrBitmap = if (upiId.isNotBlank() && qrAmountPaise > 0L) {
                generateUpiQrBitmap(upiId, sellerName, qrAmountPaise, 176)
            } else {
                null
            }

            val headerHeight = 205 +
                (if (hasBuyerRow) 36 else 0) +
                (if (hasSellerRow) 34 else 0) +
                (if (hasGstin) 28 else 0)
            val tableHeaderHeight = 56
            val perItemHeight = 76
            val extrasHeight =
                (if (receipt.discountPaise > 0L) 30 else 0) +
                    (if (receipt.taxPaise > 0L) 30 else 0) +
                    (if (!receipt.isEstimate) 34 else 0) +
                    (if (!receipt.isEstimate && receipt.balanceDuePaise > 0L) 32 else 0) +
                    (if (qrBitmap != null) 230 else 0)
            val footerHeight = 195 + extrasHeight
            val totalHeight = headerHeight + tableHeaderHeight + (receipt.items.size * perItemHeight) + footerHeight

            val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)

            canvas.drawColor(Color.parseColor("#F2F2F7"))

            val cardRect = RectF(24f, 24f, (width - 24).toFloat(), (totalHeight - 24).toFloat())
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E5E5EA")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRoundRect(cardRect, 24f, 24f, cardPaint)
            canvas.drawRoundRect(cardRect, 24f, 24f, borderPaint)

            val bannerColor = if (receipt.isEstimate) Color.parseColor("#3634A3") else Color.parseColor("#007AFF")
            val bannerRect = RectF(24f, 24f, (width - 24).toFloat(), 118f)
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bannerColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(bannerRect, 24f, 24f, bannerPaint)
            canvas.drawRect(24f, 90f, (width - 24).toFloat(), 118f, bannerPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = if (sellerName.length > 18) 28f else 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E5F1FF")
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(sellerName.uppercase(Locale.ENGLISH), width / 2f, 68f, titlePaint)
            canvas.drawText(
                if (receipt.isEstimate) "ESTIMATE / QUOTATION" else if (hasGstin) "TAX / SALE RECEIPT" else "CASH / SALE RECEIPT",
                width / 2f,
                98f,
                subTitlePaint
            )

            val metaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#6C6C70")
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val metaValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1C1C1E")
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
                .format(Date(receipt.timestampMillis))

            var y = 156f
            val idPrefix = if (receipt.isEstimate) "Estimate No: #" else "Receipt No: #"
            canvas.drawText("$idPrefix${receipt.receiptNumber}", 52f, y, metaValuePaint)
            metaLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(dateStr, (width - 52).toFloat(), y, metaLabelPaint)
            metaLabelPaint.textAlign = Paint.Align.LEFT

            if (hasBuyerRow) {
                y += 34f
                val buyerDisplay = buildString {
                    append("Buyer: ")
                    if (receipt.buyerName.isNotBlank()) append(receipt.buyerName)
                    if (receipt.buyerPhone.isNotBlank()) {
                        if (receipt.buyerName.isNotBlank()) append(" • ")
                        append(receipt.buyerPhone)
                    }
                }
                canvas.drawText(buyerDisplay, 52f, y, metaLabelPaint)
            }

            if (hasSellerRow) {
                y += 32f
                val sellerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#128C7E")
                    textSize = 19f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val sellerLine = buildString {
                    append(sellerName)
                    if (sellerPhone.isNotBlank()) {
                        append(" • WhatsApp: +91 ")
                        append(sellerPhone.removePrefix("+91").trim())
                    }
                }
                canvas.drawText(sellerLine, 52f, y, sellerPaint)
            }

            if (hasGstin) {
                y += 28f
                canvas.drawText("GSTIN: $gstin", 52f, y, metaLabelPaint)
            }

            y += 24f
            val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D1D1D6")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
            }
            canvas.drawLine(48f, y, (width - 48).toFloat(), y, dashedPaint)

            y += 38f
            val colHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8E8E93")
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("ITEM DESCRIPTION", 52f, y, colHeaderPaint)
            colHeaderPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("QTY × RATE", (width - 195).toFloat(), y, colHeaderPaint)
            canvas.drawText("AMOUNT", (width - 52).toFloat(), y, colHeaderPaint)

            y += 16f
            canvas.drawLine(48f, y, (width - 48).toFloat(), y, borderPaint)

            val itemTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1C1C1E")
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val itemSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#6C6C70")
                textSize = 18f
            }
            val itemQtyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3A3A3C")
                textSize = 19f
                textAlign = Paint.Align.RIGHT
            }
            val itemAmtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1C1C1E")
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }

            receipt.items.forEachIndexed { idx, item ->
                y += 36f
                val maxTitleChars = 24
                val truncatedTitle = if (item.itemName.length > maxTitleChars) {
                    item.itemName.take(maxTitleChars - 1) + "…"
                } else {
                    item.itemName
                }
                canvas.drawText("${idx + 1}. $truncatedTitle", 52f, y, itemTitlePaint)

                val qtyRateStr = "${IndianCurrencyFormatter.formatQuantity(item.quantity)} ${item.unit} × ${
                    IndianCurrencyFormatter.formatPaise(item.unitSellingPricePaise)
                }"
                canvas.drawText(qtyRateStr, (width - 195).toFloat(), y, itemQtyPaint)
                canvas.drawText(
                    IndianCurrencyFormatter.formatPaise(item.lineTotalPaise),
                    (width - 52).toFloat(),
                    y,
                    itemAmtPaint
                )

                y += 26f
                val subText = item.variantSummary.ifBlank { "Standard" }
                canvas.drawText("    $subText", 52f, y, itemSubPaint)

                y += 14f
                canvas.drawLine(52f, y, (width - 52).toFloat(), y, borderPaint)
            }

            if (receipt.discountPaise > 0L) {
                y += 28f
                canvas.drawText("Subtotal (${IndianCurrencyFormatter.formatPaise(receipt.subtotalPaise)})  •  Discount: -${IndianCurrencyFormatter.formatPaise(receipt.discountPaise)}", 52f, y, metaLabelPaint)
            }
            if (receipt.taxPaise > 0L) {
                y += 28f
                canvas.drawText("GST Tax Included / Added: ${IndianCurrencyFormatter.formatPaise(receipt.taxPaise)}", 52f, y, metaLabelPaint)
            }

            // Grand Total Box
            y += 24f
            val totalBoxRect = RectF(48f, y, (width - 48).toFloat(), y + 68f)
            val totalBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E5F1FF")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(totalBoxRect, 14f, 14f, totalBoxPaint)

            val totalLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bannerColor
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val totalValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bannerColor
                textSize = 30f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val totalTitle = if (receipt.isEstimate) {
                "ESTIMATE TOTAL (${receipt.items.size} items)"
            } else {
                "GRAND TOTAL (${receipt.items.size} items)"
            }
            canvas.drawText(totalTitle, 68f, y + 43f, totalLabelPaint)
            canvas.drawText(
                IndianCurrencyFormatter.formatPaise(receipt.grandTotalPaise),
                (width - 68).toFloat(),
                y + 45f,
                totalValPaint
            )

            y += 96f
            if (!receipt.isEstimate) {
                canvas.drawText(
                    "Payment Mode: ${receipt.paymentMode}   •   Paid: ${IndianCurrencyFormatter.formatPaise(receipt.amountPaidPaise)}",
                    52f,
                    y,
                    metaValuePaint
                )
                y += 30f
                if (receipt.balanceDuePaise > 0L) {
                    val duePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#D70015")
                        textSize = 21f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    canvas.drawText(
                        "BALANCE DUE (UDHAR): ${IndianCurrencyFormatter.formatPaise(receipt.balanceDuePaise)}",
                        52f,
                        y,
                        duePaint
                    )
                    y += 32f
                }
            }

            if (qrBitmap != null) {
                val qrLeft = (width - qrBitmap.width) / 2f
                canvas.drawBitmap(qrBitmap, qrLeft, y, null)
                y += qrBitmap.height + 24f
                val qrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1C1C1E")
                    textSize = 17f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("Scan UPI QR to Pay ($upiId)", width / 2f, y, qrLabelPaint)
                y += 34f
                qrBitmap.recycle()
            }

            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8E8E93")
                textSize = 18f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Thank you for shopping at $sellerName!", width / 2f, y + 10f, footerPaint)

            val receiptsDir = File(context.cacheDir, "receipts")
            if (!receiptsDir.exists()) receiptsDir.mkdirs()

            receiptsDir.listFiles()?.let { files ->
                if (files.size > 12) {
                    files.sortedBy { it.lastModified() }.take(files.size - 12).forEach { it.delete() }
                }
            }

            val filePrefix = if (receipt.isEstimate) "Shop_Estimate_" else "Shop_Receipt_"
            val outFile = File(receiptsDir, "$filePrefix${receipt.receiptNumber}.png")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
                fos.flush()
            }
            bitmap.recycle()

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates the Sale Receipt OR Estimate image and shares it over WhatsApp directly to the buyer's phone number.
     */
    fun shareReceiptImageToWhatsApp(
        context: Context,
        receipt: SaleReceiptData,
        rawBuyerPhone: String
    ) {
        val imageUri = generateReceiptImageUri(context, receipt)
        if (imageUri == null) {
            Toast.makeText(context, "Could not generate image", Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedBuyerPhone = normalizeIndianPhoneNumber(rawBuyerPhone)
        val sellerName = receipt.sellerName.ifBlank {
            SyncManager.getSellerName(context)
        }.ifBlank { "ShopLedger POS" }
        val rawSellerPhone = receipt.sellerWhatsAppPhone.ifBlank {
            SyncManager.getSellerWhatsAppNumber(context)
        }
        val normalizedSellerPhone = normalizeIndianPhoneNumber(rawSellerPhone)

        val docTitle = if (receipt.isEstimate) "Estimate" else "Sale Receipt"
        val caption = buildString {
            append("*$sellerName — $docTitle #${receipt.receiptNumber}*\n")
            if (receipt.buyerName.isNotBlank()) {
                append("Customer: ${receipt.buyerName}\n")
            }
            append("Total Amount: *${IndianCurrencyFormatter.formatPaise(receipt.grandTotalPaise)}*\n")
            if (!receipt.isEstimate && receipt.balanceDuePaise > 0L) {
                append("Balance Due (Udhar): *${IndianCurrencyFormatter.formatPaise(receipt.balanceDuePaise)}*\n")
            }
            if (normalizedSellerPhone.isNotEmpty()) {
                append("\n📲 *Contact $sellerName (1-Tap):*\nhttps://wa.me/$normalizedSellerPhone\n")
            }
            append("Thank you for shopping with $sellerName!")
        }

        val whatsAppPkg = findInstalledWhatsAppPackage(context)

        if (whatsAppPkg != null) {
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    `package` = whatsAppPkg
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    putExtra(Intent.EXTRA_TEXT, caption)
                    if (normalizedBuyerPhone.isNotEmpty()) {
                        putExtra("jid", "$normalizedBuyerPhone@s.whatsapp.net")
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(sendIntent)
                return
            } catch (_: Exception) {
            }
        }

        val chooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share $sellerName $docTitle"
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    }

    /**
     * Shares plain text (e.g. Low-Stock Reorder List, Khata Reminder, or JSON Backup) to WhatsApp.
     */
    fun shareTextToWhatsApp(
        context: Context,
        message: String,
        rawPhone: String = ""
    ) {
        val normalizedPhone = normalizeIndianPhoneNumber(rawPhone)
        val whatsAppPkg = findInstalledWhatsAppPackage(context)

        if (normalizedPhone.isNotEmpty()) {
            try {
                val url = "https://api.whatsapp.com/send?phone=$normalizedPhone&text=${Uri.encode(message)}"
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    if (whatsAppPkg != null) `package` = whatsAppPkg
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
                return
            } catch (_: Exception) {
            }
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (whatsAppPkg != null) `package` = whatsAppPkg
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(sendIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                },
                "Share via"
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
        }
    }

    fun normalizeIndianPhoneNumber(rawPhone: String): String {
        val digitsOnly = rawPhone.filter { it.isDigit() }
        return when {
            digitsOnly.length == 10 -> "91$digitsOnly"
            digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "91${digitsOnly.drop(1)}"
            digitsOnly.length == 12 && digitsOnly.startsWith("91") -> digitsOnly
            digitsOnly.length >= 10 -> digitsOnly
            else -> ""
        }
    }

    private fun findInstalledWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }
}
