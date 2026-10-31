package com.lpshardware.inventory.core.share

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
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.core.sync.SyncManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
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
    /** Smart date-based Receipt ID in format YYYYMMDDCCCC (e.g. 202611010001) */
    val receiptNumber: String,
    val timestampMillis: Long,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val sellerName: String = "",
    val sellerWhatsAppPhone: String = "",
    val isEstimate: Boolean = false,
    val items: List<ReceiptLineItem>,
    val grandTotalPaise: Long
)

object ReceiptShareManager {

    /**
     * Renders a crisp, modern iOS-styled Sale Receipt OR Estimate (Kaccha Parcha) Bitmap
     * using low-memory `RGB_565` (2 bytes/pixel) and saves it to `cacheDir/receipts/` for sharing via WhatsApp.
     *
     * Strictly customer-safe: NEVER includes purchase price or profit margins.
     * Includes Seller's WhatsApp Number so the buyer can contact the shop owner easily.
     */
    fun generateReceiptImageUri(context: Context, receipt: SaleReceiptData): Uri? {
        return try {
            val sellerName = receipt.sellerName.ifBlank {
                SyncManager.getSellerName(context)
            }.ifBlank { "LPS Hardware" }
            val sellerPhone = receipt.sellerWhatsAppPhone.ifBlank {
                SyncManager.getSellerWhatsAppNumber(context)
            }
            val width = 760
            val hasBuyerRow = receipt.buyerPhone.isNotBlank() || receipt.buyerName.isNotBlank()
            val hasSellerRow = sellerPhone.isNotBlank() || sellerName.isNotBlank()
            val headerHeight = 205 + (if (hasBuyerRow) 36 else 0) + (if (hasSellerRow) 34 else 0)
            val tableHeaderHeight = 56
            val perItemHeight = 76
            val footerHeight = 200
            val totalHeight = headerHeight + tableHeaderHeight + (receipt.items.size * perItemHeight) + footerHeight

            // RGB_565 uses 50% less RAM than ARGB_8888 on low-RAM devices
            val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)

            // Background canvas (#F2F2F7 iOS grouped background)
            canvas.drawColor(Color.parseColor("#F2F2F7"))

            // White Receipt Card container
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

            // Top Brand Accent Banner (Blue for Sale Receipt, Teal/Indigo for Estimate)
            val bannerColor = if (receipt.isEstimate) Color.parseColor("#3634A3") else Color.parseColor("#007AFF")
            val bannerRect = RectF(24f, 24f, (width - 24).toFloat(), 118f)
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bannerColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(bannerRect, 24f, 24f, bannerPaint)
            canvas.drawRect(24f, 90f, (width - 24).toFloat(), 118f, bannerPaint)

            // Shop / Seller Name & Subtitle
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
                if (receipt.isEstimate) "ESTIMATE / QUOTATION (KACCHA PARCHA)" else "CASH / SALE RECEIPT",
                width / 2f,
                98f,
                subTitlePaint
            )

            // Receipt Metadata (Date, Smart Receipt ID YYYYMMDDCCCC, Buyer Info, Seller WhatsApp)
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

            y += 24f
            val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D1D1D6")
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
            }
            canvas.drawLine(48f, y, (width - 48).toFloat(), y, dashedPaint)

            // Table Header
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

            // Line Items
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

            // Footer
            y += 108f
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8E8E93")
                textSize = 18f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Thank you for shopping at LPS Hardware!", width / 2f, y, footerPaint)

            // Write PNG to cacheDir/receipts
            val receiptsDir = File(context.cacheDir, "receipts")
            if (!receiptsDir.exists()) receiptsDir.mkdirs()

            receiptsDir.listFiles()?.let { files ->
                if (files.size > 12) {
                    files.sortedBy { it.lastModified() }.take(files.size - 12).forEach { it.delete() }
                }
            }

            val filePrefix = if (receipt.isEstimate) "LPS_Estimate_" else "LPS_Receipt_"
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
     * Includes a 1-tap `https://wa.me/91<SellerNumber>` link in the message caption so the buyer can
     * message or call the seller back in a single tap!
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
        }.ifBlank { "LPS Hardware" }
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
            if (normalizedSellerPhone.isNotEmpty()) {
                // Single-tap clickable wa.me link so buyer can reach out to seller in 1 tap!
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

        // Fallback: system share chooser with image + caption
        val chooserIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share LPS Hardware $docTitle"
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    }

    /**
     * Shares plain text (e.g. Low-Stock Supplier Reorder List or JSON Backup) to WhatsApp.
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

    /**
     * Normalizes an Indian mobile number (`9876543210` or `+91 98765-43210`) into `919876543210`.
     */
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
