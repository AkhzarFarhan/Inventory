package com.shopledger.pos.core.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.share.SaleReceiptData
import com.shopledger.pos.core.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Pure Kotlin ESC/POS Thermal Receipt Formatter & Bluetooth SPP Socket Client
 * compatible with generic 58mm (32-col) and 80mm (48-col) Bluetooth POS printers.
 */
object ThermalPrinterManager {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val ESC_INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val FEED_3_LINES = byteArrayOf(0x1B, 0x64, 0x03)
    private val PARTIAL_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    private fun asciiRupees(paise: Long): String {
        val raw = IndianCurrencyFormatter.formatPaise(paise, includeSymbol = false)
        return "Rs.$raw"
    }

    private fun formatTwoColumns(left: String, right: String, width: Int): String {
        val l = left.trim()
        val r = right.trim()
        if (l.length + r.length + 1 <= width) {
            return l + " ".repeat(width - l.length - r.length) + r
        }
        val maxLeft = (width - r.length - 1).coerceAtLeast(1)
        return l.take(maxLeft) + " " + r
    }

    /**
     * Generates deterministic ESC/POS byte commands ready for 58mm or 80mm thermal printers.
     */
    fun buildEscPosBytes(
        receipt: SaleReceiptData,
        paperWidthMm: Int = 58
    ): ByteArray {
        val cols = if (paperWidthMm >= 80) 48 else 32
        val divider = "-".repeat(cols)
        val shopName = receipt.sellerName.ifBlank { "ShopLedger POS" }.uppercase(Locale.ENGLISH)
        val dateStr = SimpleDateFormat("dd MMM yy, hh:mm a", Locale.ENGLISH)
            .format(Date(receipt.timestampMillis))

        val out = ByteArrayOutputStream(1024)
        fun writeLine(text: String = "") {
            out.write((text + "\n").toByteArray(Charsets.US_ASCII))
        }

        out.write(ESC_INIT)
        out.write(ALIGN_CENTER)
        out.write(BOLD_ON)
        writeLine(shopName.take(cols))
        out.write(BOLD_OFF)
        writeLine(if (receipt.isEstimate) "ESTIMATE / QUOTATION" else "SALE RECEIPT")
        if (receipt.gstin.isNotBlank()) {
            writeLine("GSTIN: ${receipt.gstin}")
        }
        if (receipt.sellerWhatsAppPhone.isNotBlank()) {
            writeLine("Ph: ${receipt.sellerWhatsAppPhone}")
        }

        out.write(ALIGN_LEFT)
        writeLine(divider)
        val billTag = "#${receipt.receiptNumber}"
        if (billTag.length + dateStr.length + 1 <= cols) {
            writeLine(formatTwoColumns(billTag, dateStr, cols))
        } else {
            writeLine("Bill: $billTag".take(cols))
            writeLine("Date: $dateStr".take(cols))
        }
        if (receipt.buyerName.isNotBlank() || receipt.buyerPhone.isNotBlank()) {
            val cust = listOf(receipt.buyerName, receipt.buyerPhone).filter { it.isNotBlank() }.joinToString(" ")
            writeLine("Customer: ${cust.take(cols - 10)}")
        }
        writeLine(divider)

        receipt.items.forEachIndexed { idx, line ->
            writeLine("${idx + 1}. ${line.itemName.take(cols - 4)}")
            val qtyPart = "${IndianCurrencyFormatter.formatQuantity(line.quantity)} ${line.unit} x ${asciiRupees(line.unitSellingPricePaise)}"
            val amtPart = asciiRupees(line.lineTotalPaise)
            writeLine(formatTwoColumns("   $qtyPart", amtPart, cols))
        }

        writeLine(divider)
        if (receipt.discountPaise > 0L) {
            writeLine(formatTwoColumns("Subtotal", asciiRupees(receipt.subtotalPaise), cols))
            writeLine(formatTwoColumns("Discount", "-${asciiRupees(receipt.discountPaise)}", cols))
        }
        if (receipt.taxPaise > 0L) {
            writeLine(formatTwoColumns("GST Tax", asciiRupees(receipt.taxPaise), cols))
        }

        out.write(BOLD_ON)
        val totalLabel = if (receipt.isEstimate) "ESTIMATE TOTAL" else "GRAND TOTAL"
        writeLine(formatTwoColumns(totalLabel, asciiRupees(receipt.grandTotalPaise), cols))
        out.write(BOLD_OFF)

        if (!receipt.isEstimate) {
            writeLine(formatTwoColumns("Mode: ${receipt.paymentMode}", "Paid: ${asciiRupees(receipt.amountPaidPaise)}", cols))
            if (receipt.balanceDuePaise > 0L) {
                out.write(BOLD_ON)
                writeLine(formatTwoColumns("BALANCE DUE (UDHAR)", asciiRupees(receipt.balanceDuePaise), cols))
                out.write(BOLD_OFF)
            }
        }

        writeLine(divider)
        out.write(ALIGN_CENTER)
        writeLine("Thank you! Visit again.")
        out.write(FEED_3_LINES)
        out.write(PARTIAL_CUT)
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    suspend fun printReceiptViaBluetooth(
        context: Context,
        receipt: SaleReceiptData,
        paperWidthMm: Int = 58
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    return@withContext Result.failure(
                        SecurityException("Allow Nearby Devices (Bluetooth) permission in App Settings to print.")
                    )
                }
            }

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
                ?: return@withContext Result.failure(IllegalStateException("Bluetooth is not supported on this device."))
            if (!adapter.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Please turn ON Bluetooth and pair your printer."))
            }

            val bonded = adapter.bondedDevices.orEmpty()
            if (bonded.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No paired Bluetooth thermal printer found."))
            }

            val printer = bonded.firstOrNull { dev ->
                val n = dev.name.orEmpty().lowercase(Locale.ROOT)
                n.contains("print") || n.contains("pos") || n.contains("mpt") || n.contains("rp") || n.contains("bt")
            } ?: bonded.first()

            val filledReceipt = if (receipt.sellerName.isBlank()) {
                receipt.copy(sellerName = SyncManager.getSellerName(context))
            } else {
                receipt
            }
            val payload = buildEscPosBytes(filledReceipt, paperWidthMm)

            adapter.cancelDiscovery()
            val socket = printer.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.use { s ->
                s.connect()
                s.outputStream.use { os ->
                    os.write(payload)
                    os.flush()
                }
            }
            Result.success(printer.name ?: "Bluetooth Printer")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
