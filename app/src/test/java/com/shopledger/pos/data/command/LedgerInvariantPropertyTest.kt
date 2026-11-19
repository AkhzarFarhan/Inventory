package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.ledger.InvoiceMath
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.newTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LedgerInvariantPropertyTest {

    private var successfulCheckouts = 0
    private var successfulReturns = 0
    private var khataCreditReturns = 0

    @Test
    fun randomCommandSequences_keepAllInvariants() = runBlocking<Unit> {
        successfulCheckouts = 0
        successfulReturns = 0
        khataCreditReturns = 0
        repeat(50) { seed -> runSequence(seed) }
        println("PROPERTY-PATHS checkouts=$successfulCheckouts returns=$successfulReturns khataCreditReturns=$khataCreditReturns")
        assertTrue("no checkout ever succeeded", successfulCheckouts > 0)
        assertTrue("no return ever succeeded", successfulReturns > 0)
        assertTrue("no return ever credited a Khata balance", khataCreditReturns > 0)
    }

    private suspend fun runSequence(seed: Int) {
        val rnd = Random(seed)
        val db = newTestDatabase()
        val env = TestEnv().apply { gst = rnd.nextBoolean() }
        val catalog = CatalogCommands(db, env)
        val khata = KhataCommands(db, env)
        val sales = SalesCommands(db, env)
        val variants = mutableListOf<String>()
        val parties = mutableListOf<String>()
        val soldLines = mutableListOf<Pair<String, String>>() // invoiceId to lineId
        val khataSoldLines = mutableListOf<Pair<String, String>>() // lines of bills that left a balance due
        try {
            repeat(40) { step ->
                val actionId = "s$seed-$step"
                env.clock += rnd.nextLong(1L, 3_600_000L)
                try {
                    when (if (variants.isEmpty()) 0 else rnd.nextInt(8)) {
                        0 -> {
                            val itemId = catalog.createItem(
                                actionId, "Item $step", "Cat",
                                listOf(VariantSpec(purchasePricePaise = rnd.nextLong(0L, 50_000L), sellingPricePaise = rnd.nextLong(0L, 80_000L), openingStockMilli = rnd.nextLong(0L, 50_000L)))
                            )
                            variants += db.inventoryDao().getVariantsForItem(itemId).single().id
                        }
                        1 -> catalog.receivePurchase(actionId, listOf(PurchaseLine(variants.random(rnd), rnd.nextLong(1L, 20_000L))))
                        2 -> catalog.adjustStock(actionId, variants.random(rnd), deltaMilli = rnd.nextLong(-10_000L, 10_000L).takeIf { it != 0L } ?: 1L, reason = "random")
                        3, 4 -> {
                            val chosen = variants.shuffled(rnd).take(rnd.nextInt(1, minOf(3, variants.size) + 1))
                            val checkoutLines = chosen.map { CheckoutLine(it, rnd.nextLong(1L, 5_000L), rnd.nextLong(0L, 90_000L)) }
                            val subtotal = checkoutLines.sumOf { InvoiceMath.lineTotalPaise(it.unitSellPaise, it.qtyMilli) }
                            val result = sales.checkout(
                                CheckoutRequest(
                                    actionId = actionId,
                                    lines = checkoutLines,
                                    buyerName = if (rnd.nextBoolean()) "Buyer ${rnd.nextInt(3)}" else "",
                                    paymentMode = listOf("CASH", "UPI", "CREDIT").random(rnd),
                                    discountPaise = rnd.nextLong(0L, subtotal + 1),
                                    amountPaidPaise = if (rnd.nextBoolean()) null else rnd.nextLong(0L, 100_000L),
                                    gstRateBps = listOf(0, 500, 1200, 1800).random(rnd)
                                )
                            )
                            successfulCheckouts++
                            result.lines.forEach { soldLines += result.header.id to it.id }
                            if (result.header.balanceDuePaise > 0L) {
                                result.lines.forEach { khataSoldLines += result.header.id to it.id }
                            }
                        }
                        5 -> if (soldLines.isNotEmpty()) {
                            val pool = if (khataSoldLines.isNotEmpty() && rnd.nextBoolean()) khataSoldLines else soldLines
                            val (invoiceId, lineId) = pool.random(rnd)
                            val result = sales.returnItems(actionId, invoiceId, listOf(ReturnLine(lineId, rnd.nextLong(1L, 4_000L))))
                            successfulReturns++
                            if (result.header.balanceDuePaise < 0L) khataCreditReturns++
                        }
                        6 -> parties += khata.createParty(actionId, "Party $step", "", openingBalancePaise = rnd.nextLong(-5_000L, 50_000L))
                        7 -> if (parties.isNotEmpty()) khata.recordPayment(actionId, parties.random(rnd), rnd.nextLong(1L, 30_000L))
                    }
                } catch (rejected: CommandException) {
                    // Over-returns etc. are legitimate rejections; invariants must still hold afterwards.
                }
            }
            assertEquals("seed $seed", emptyList<String>(), IntegrityChecker.findViolations(db.openHelper.writableDatabase))

            val cachedStock = db.inventoryDao().getAllRawVariants().associate { it.id to it.stockMilliUnits }
            val cachedBalances = db.shopPartyAndLedgerDao().getAllRawParties().associate { it.id to it.currentBalancePaise }
            db.inventoryDao().rebuildAllStockProjections()
            db.shopPartyAndLedgerDao().rebuildAllPartyBalances()
            assertEquals("seed $seed stock", cachedStock, db.inventoryDao().getAllRawVariants().associate { it.id to it.stockMilliUnits })
            assertEquals("seed $seed balances", cachedBalances, db.shopPartyAndLedgerDao().getAllRawParties().associate { it.id to it.currentBalancePaise })
        } finally {
            db.close()
        }
    }
}
