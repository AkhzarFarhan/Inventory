package com.shopledger.pos.core.ledger

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Calendar date of an instant in a time zone. `dayKey` is `yyMMdd`, used in receipt numbers. */
data class CalendarParts(val year: Int, val month: Int, val day: Int) {
    val dayKey: String
        get() = String.format(Locale.US, "%02d%02d%02d", year % 100, month, day)

    companion object {
        fun of(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): CalendarParts {
            val cal = Calendar.getInstance(tz).apply { timeInMillis = epochMs }
            return CalendarParts(
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                day = cal.get(Calendar.DAY_OF_MONTH)
            )
        }
    }
}

/** Receipt numbers are `{deviceCode}-{yyMMdd}-{seq:04d}`; unique per shop because device codes are. */
object ReceiptNumbers {
    fun format(deviceCode: String, dayKey: String, seq: Long): String {
        require(seq >= 1) { "Receipt sequence must start at 1" }
        val code = deviceCode.trim().uppercase(Locale.US)
        return "$code-$dayKey-${seq.toString().padStart(4, '0')}"
    }
}

/** Exact integer money maths: paise × milli-units, rounded half-up to whole paise. */
object InvoiceMath {
    fun lineTotalPaise(unitPaise: Long, qtyMilli: Long): Long {
        require(unitPaise >= 0) { "Unit price cannot be negative" }
        if (qtyMilli < 0) return -lineTotalPaise(unitPaise, -qtyMilli)
        return Math.addExact(Math.multiplyExact(unitPaise, qtyMilli), 500L) / 1000L
    }
}
