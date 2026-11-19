package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.newTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LedgerInvariantPropertyTest {

    @Test
    fun randomCommandSequences_keepAllInvariants() = runBlocking<Unit> {
        repeat(50) { seed -> runSequence(seed) }
    }

    private suspend fun runSequence(seed: Int) {
        val rnd = Random(seed)
        val db = newTestDatabase()
        val env = TestEnv()
        val catalog = CatalogCommands(db, env)
        val khata = KhataCommands(db, env)
        val sales = SalesCommands(db, env)
        val variants = mutableListOf<String>()
        val parties = mutableListOf<String>()
        val soldLines = mutableListOf<Pair<String, String>>() // invoiceId to lineId
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
                            val result = sales.checkout(
                                CheckoutRequest(
                                    actionId = actionId,
                                    lines = chosen.map { CheckoutLine(it, rnd.nextLong(1L, 5_000L), rnd.nextLong(0L, 90_000L)) },
                                    buyerName = if (rnd.nextBoolean()) "Buyer ${rnd.nextInt(3)}" else "",
                                    paymentMode = listOf("CASH", "UPI", "CREDIT").random(rnd),
                                    amountPaidPaise = if (rnd.nextBoolean()) null else rnd.nextLong(0L, 100_000L)
                                )
                            )
                            result.lines.forEach { soldLines += result.header.id to it.id }
                        }
                        5 -> if (soldLines.isNotEmpty()) {
                            val (invoiceId, lineId) = soldLines.random(rnd)
                            sales.returnItems(actionId, invoiceId, listOf(ReturnLine(lineId, rnd.nextLong(1L, 4_000L))))
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
