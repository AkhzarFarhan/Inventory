package com.shopledger.pos.ui.screens.sales

import com.shopledger.pos.data.command.DocType
import com.shopledger.pos.data.command.LedgerEntryType
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import java.util.Calendar
import java.util.Locale

data class GallaBreakdown(
    val cashSalesPaise: Long = 0L,
    val upiSalesPaise: Long = 0L,
    val udharGivenPaise: Long = 0L,
    val jamaCashPaise: Long = 0L,
    val jamaUpiPaise: Long = 0L,
    val totalSalesCount: Int = 0,
) {
    val cashInDrawerPaise: Long get() = cashSalesPaise + jamaCashPaise
    val totalUpiPaise: Long get() = upiSalesPaise + jamaUpiPaise
    val totalJamaPaise: Long get() = jamaCashPaise + jamaUpiPaise
}

fun computeGallaBreakdown(
    invoices: List<InvoiceHeaderEntity>,
    salesFallback: List<SaleRecordEntity>,
    ledgerEntries: List<PartyTransactionEntity>,
    year: Int,
    month: Int,
    day: Int,
    periodIndex: Int,
): GallaBreakdown {
    fun matchesCalendar(y: Int, m: Int, d: Int): Boolean = when (periodIndex) {
        0 -> y == year && m == month && d == day
        1 -> y == year && m == month
        else -> true
    }

    val cal = Calendar.getInstance()
    fun matchesMillis(tsMillis: Long): Boolean {
        if (periodIndex != 0 && periodIndex != 1) return true
        cal.timeInMillis = tsMillis
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return matchesCalendar(y, m, d)
    }

    var cashSalesPaise = 0L
    var upiSalesPaise = 0L
    var udharGivenPaise = 0L
    var jamaCashPaise = 0L
    var jamaUpiPaise = 0L
    var totalSalesCount = 0

    if (invoices.isNotEmpty()) {
        invoices
            .asSequence()
            .filter { matchesCalendar(it.yearInt, it.monthInt, it.dayInt) }
            .forEach { inv ->
                val mode = inv.paymentMode.trim().uppercase(Locale.ROOT)
                when (inv.docType) {
                    DocType.SALE -> {
                        totalSalesCount++
                        when (mode) {
                            "UPI" -> upiSalesPaise += inv.amountPaidPaise
                            "CREDIT" -> {
                                udharGivenPaise += inv.balanceDuePaise
                                cashSalesPaise += inv.amountPaidPaise
                            }
                            else -> cashSalesPaise += inv.amountPaidPaise
                        }
                    }
                    DocType.CREDIT_NOTE -> {
                        udharGivenPaise += inv.balanceDuePaise
                        if (mode == "UPI") {
                            upiSalesPaise += inv.amountPaidPaise
                        } else {
                            cashSalesPaise += inv.amountPaidPaise
                        }
                    }
                }
            }
    } else if (salesFallback.isNotEmpty()) {
        salesFallback
            .asSequence()
            .filter { !it.isReturned && matchesCalendar(it.yearInt, it.monthInt, it.dayInt) }
            .distinctBy { it.invoiceId.ifBlank { it.receiptNumber } }
            .forEach { sale ->
                totalSalesCount++
                when (sale.paymentMode.trim().uppercase(Locale.ROOT)) {
                    "UPI" -> upiSalesPaise += sale.amountPaidPaise
                    "CREDIT" -> {
                        udharGivenPaise += sale.balanceDuePaise
                        cashSalesPaise += sale.amountPaidPaise
                    }
                    else -> cashSalesPaise += sale.amountPaidPaise
                }
            }
    }

    ledgerEntries
        .asSequence()
        .filter { it.entryType == LedgerEntryType.PAYMENT_IN && matchesMillis(it.createdAt) }
        .forEach { entry ->
            val amt = (-entry.amountDeltaPaise).coerceAtLeast(0L)
            if (entry.paymentMode.trim().equals("UPI", ignoreCase = true)) {
                jamaUpiPaise += amt
            } else {
                jamaCashPaise += amt
            }
        }

    return GallaBreakdown(
        cashSalesPaise = cashSalesPaise,
        upiSalesPaise = upiSalesPaise,
        udharGivenPaise = udharGivenPaise,
        jamaCashPaise = jamaCashPaise,
        jamaUpiPaise = jamaUpiPaise,
        totalSalesCount = totalSalesCount,
    )
}
