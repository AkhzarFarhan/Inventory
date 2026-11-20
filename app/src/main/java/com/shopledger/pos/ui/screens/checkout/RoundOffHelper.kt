package com.shopledger.pos.ui.screens.checkout

internal data class RoundOffOption(
    val targetTotalPaise: Long,
    val discountPaise: Long,
)

internal fun roundOffSuggestions(
    subtotalPaise: Long,
    currentDiscountPaise: Long,
): List<RoundOffOption> {
    if (subtotalPaise <= 1_000L) return emptyList()
    val steps = listOf(1_000L, 5_000L, 10_000L)
    return steps
        .mapNotNull { step ->
            val target = (subtotalPaise / step) * step
            if (target in 1L until subtotalPaise) {
                val discount = subtotalPaise - target
                val acceptable = step == 1_000L || discount * 100L <= subtotalPaise * 15L
                if (acceptable && discount != currentDiscountPaise) {
                    RoundOffOption(targetTotalPaise = target, discountPaise = discount)
                } else {
                    null
                }
            } else {
                null
            }
        }
        .distinctBy { it.targetTotalPaise }
        .sortedBy { it.discountPaise }
        .take(2)
}
