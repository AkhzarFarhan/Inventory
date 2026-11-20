package com.shopledger.pos.ui.screens.khata

import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity

fun computeDaysOverdue(
    party: PartyEntity,
    entries: List<PartyTransactionEntity>,
    nowMillis: Long,
): Int? {
    if (party.currentBalancePaise <= 0L) return null
    var bal = 0L
    var dueSince: Long? = null
    entries
        .sortedBy { it.createdAt }
        .forEach { entry ->
            val prev = bal
            bal += entry.amountDeltaPaise
            if (prev <= 0L && bal > 0L) {
                dueSince = entry.createdAt
            } else if (bal <= 0L) {
                dueSince = null
            }
        }
    val since = dueSince ?: party.updatedAt
    return ((nowMillis - since).coerceAtLeast(0L) / 86_400_000L).toInt()
}
