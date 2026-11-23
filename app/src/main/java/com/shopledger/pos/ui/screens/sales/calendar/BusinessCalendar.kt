package com.shopledger.pos.ui.screens.sales.calendar

import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.data.command.DocType
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

data class DaySummary(
    val day: Int,
    val saleCount: Int = 0,
    /** Net sales: SALE grand totals minus CREDIT_NOTE grand totals (credit notes are negative already: add them). */
    val salesPaise: Long = 0L,
    /** Nagad = cash received at sale time (CASH sales + the paid-now part of CREDIT sales + cash refunds negative). Same rules as computeGallaBreakdown. */
    val cashSalesPaise: Long = 0L,
    val upiSalesPaise: Long = 0L,
    /** Udhaar given to customers on this day (balanceDue of SALE and CREDIT_NOTE headers, same as Galla). */
    val udhaarGivenPaise: Long = 0L,
    /** Udhaar collected = PAYMENT_IN ledger entries (money received), split by mode. */
    val udhaarCollectedCashPaise: Long = 0L,
    val udhaarCollectedUpiPaise: Long = 0L,
    /** Stock-in: purchase bill totals created this day, and how many bills. */
    val stockInPaise: Long = 0L,
    val stockInBillCount: Int = 0,
    /** Stock-in left on distributor udhaar this day (Σ duePaise of bills). */
    val stockInOnUdhaarPaise: Long = 0L,
    /** Paid to distributors: PAYMENT_OUT ledger entries + paidPaise of bills with no distributor. */
    val paidToDistributorsCashPaise: Long = 0L,
    val paidToDistributorsUpiPaise: Long = 0L,
) {
    val udhaarCollectedPaise: Long get() = udhaarCollectedCashPaise + udhaarCollectedUpiPaise
    val paidToDistributorsPaise: Long get() = paidToDistributorsCashPaise + paidToDistributorsUpiPaise
    val nagadInPaise: Long get() = cashSalesPaise + udhaarCollectedCashPaise
    val isEmpty: Boolean
        get() = saleCount == 0 && salesPaise == 0L && udhaarGivenPaise == 0L && udhaarCollectedPaise == 0L &&
            stockInBillCount == 0 && paidToDistributorsPaise == 0L
}

data class MonthSummary(
    val year: Int,
    val month: Int,
    val days: Map<Int, DaySummary>,
    val total: DaySummary,
)

fun computeMonthSummary(
    year: Int,
    month: Int,
    invoices: List<InvoiceHeaderEntity>,
    ledgerEntries: List<PartyTransactionEntity>,
    purchaseBills: List<PurchaseBillEntity>,
    dayOf: (Long) -> CalendarParts = { CalendarParts.of(it) },
): MonthSummary {
    val byDay = mutableMapOf<Int, DaySummary>()
    fun updateDay(day: Int, transform: (DaySummary) -> DaySummary) {
        val current = byDay[day] ?: DaySummary(day = day)
        byDay[day] = transform(current)
    }

    for (inv in invoices) {
        if (inv.yearInt != year || inv.monthInt != month) continue
        val mode = inv.paymentMode.trim().uppercase(Locale.ROOT)
        when (inv.docType) {
            DocType.SALE -> updateDay(inv.dayInt) { d ->
                when (mode) {
                    "UPI" -> d.copy(
                        saleCount = d.saleCount + 1,
                        salesPaise = d.salesPaise + inv.grandTotalPaise,
                        upiSalesPaise = d.upiSalesPaise + inv.amountPaidPaise,
                    )
                    "CREDIT" -> d.copy(
                        saleCount = d.saleCount + 1,
                        salesPaise = d.salesPaise + inv.grandTotalPaise,
                        udhaarGivenPaise = d.udhaarGivenPaise + inv.balanceDuePaise,
                        cashSalesPaise = d.cashSalesPaise + inv.amountPaidPaise,
                    )
                    else -> d.copy(
                        saleCount = d.saleCount + 1,
                        salesPaise = d.salesPaise + inv.grandTotalPaise,
                        cashSalesPaise = d.cashSalesPaise + inv.amountPaidPaise,
                    )
                }
            }
            DocType.CREDIT_NOTE -> updateDay(inv.dayInt) { d ->
                if (mode == "UPI") {
                    d.copy(
                        salesPaise = d.salesPaise + inv.grandTotalPaise,
                        udhaarGivenPaise = d.udhaarGivenPaise + inv.balanceDuePaise,
                        upiSalesPaise = d.upiSalesPaise + inv.amountPaidPaise,
                    )
                } else {
                    d.copy(
                        salesPaise = d.salesPaise + inv.grandTotalPaise,
                        udhaarGivenPaise = d.udhaarGivenPaise + inv.balanceDuePaise,
                        cashSalesPaise = d.cashSalesPaise + inv.amountPaidPaise,
                    )
                }
            }
        }
    }

    for (entry in ledgerEntries) {
        val parts = dayOf(entry.createdAt)
        if (parts.year != year || parts.month != month) continue
        val isUpi = entry.paymentMode.trim().equals("UPI", ignoreCase = true)
        when (entry.entryType) {
            LedgerEntryType.PAYMENT_IN -> {
                val amt = (-entry.amountDeltaPaise).coerceAtLeast(0L)
                if (amt > 0L) {
                    updateDay(parts.day) { d ->
                        if (isUpi) d.copy(udhaarCollectedUpiPaise = d.udhaarCollectedUpiPaise + amt)
                        else d.copy(udhaarCollectedCashPaise = d.udhaarCollectedCashPaise + amt)
                    }
                }
            }
            LedgerEntryType.PAYMENT_OUT -> {
                val amt = entry.amountDeltaPaise.coerceAtLeast(0L)
                if (amt > 0L) {
                    updateDay(parts.day) { d ->
                        if (isUpi) d.copy(paidToDistributorsUpiPaise = d.paidToDistributorsUpiPaise + amt)
                        else d.copy(paidToDistributorsCashPaise = d.paidToDistributorsCashPaise + amt)
                    }
                }
            }
        }
    }

    for (bill in purchaseBills) {
        if (bill.yearInt != year || bill.monthInt != month) continue
        val isUpi = bill.paymentMode.trim().equals("UPI", ignoreCase = true)
        updateDay(bill.dayInt) { d ->
            val extraCash = if (bill.supplierPartyId == null && bill.paidPaise > 0L && !isUpi) bill.paidPaise else 0L
            val extraUpi = if (bill.supplierPartyId == null && bill.paidPaise > 0L && isUpi) bill.paidPaise else 0L
            d.copy(
                stockInPaise = d.stockInPaise + bill.totalPaise,
                stockInBillCount = d.stockInBillCount + 1,
                stockInOnUdhaarPaise = d.stockInOnUdhaarPaise + bill.duePaise,
                paidToDistributorsCashPaise = d.paidToDistributorsCashPaise + extraCash,
                paidToDistributorsUpiPaise = d.paidToDistributorsUpiPaise + extraUpi,
            )
        }
    }

    var total = DaySummary(day = 0)
    for (d in byDay.values) {
        total = DaySummary(
            day = 0,
            saleCount = total.saleCount + d.saleCount,
            salesPaise = total.salesPaise + d.salesPaise,
            cashSalesPaise = total.cashSalesPaise + d.cashSalesPaise,
            upiSalesPaise = total.upiSalesPaise + d.upiSalesPaise,
            udhaarGivenPaise = total.udhaarGivenPaise + d.udhaarGivenPaise,
            udhaarCollectedCashPaise = total.udhaarCollectedCashPaise + d.udhaarCollectedCashPaise,
            udhaarCollectedUpiPaise = total.udhaarCollectedUpiPaise + d.udhaarCollectedUpiPaise,
            stockInPaise = total.stockInPaise + d.stockInPaise,
            stockInBillCount = total.stockInBillCount + d.stockInBillCount,
            stockInOnUdhaarPaise = total.stockInOnUdhaarPaise + d.stockInOnUdhaarPaise,
            paidToDistributorsCashPaise = total.paidToDistributorsCashPaise + d.paidToDistributorsCashPaise,
            paidToDistributorsUpiPaise = total.paidToDistributorsUpiPaise + d.paidToDistributorsUpiPaise,
        )
    }

    return MonthSummary(year = year, month = month, days = byDay.toMap(), total = total)
}

/** "₹950", "₹12.4k", "₹1.2L", "₹3.1Cr" — for tiny calendar cells only. Negative keeps the minus sign. */
fun formatCompactRupees(paise: Long): String {
    val rupees = paise / 100L
    val negative = rupees < 0L
    val absRupees = abs(rupees)
    val body = when {
        absRupees < 1_000L -> "₹$absRupees"
        absRupees < 100_000L -> "₹" + formatOneDecimal(absRupees / 1_000.0) + "k"
        absRupees < 10_000_000L -> "₹" + formatOneDecimal(absRupees / 100_000.0) + "L"
        else -> "₹" + formatOneDecimal(absRupees / 10_000_000.0) + "Cr"
    }
    return if (negative) "-$body" else body
}

private fun formatOneDecimal(value: Double): String =
    String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

/** Cells for a month grid starting on Sunday: leading nulls for blank cells, then 1..daysInMonth, padded to a multiple of 7. */
fun monthGridCells(year: Int, month: Int): List<Int?> {
    val cal = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }
    val leading = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val result = ArrayList<Int?>(leading + daysInMonth + 6)
    repeat(leading) { result.add(null) }
    for (day in 1..daysInMonth) result.add(day)
    val rem = result.size % 7
    if (rem != 0) repeat(7 - rem) { result.add(null) }
    return result
}
