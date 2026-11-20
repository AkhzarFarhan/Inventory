package com.shopledger.pos.ui

import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.scanner.MatchStatus
import com.shopledger.pos.core.scanner.ParsedStockLine
import com.shopledger.pos.core.scanner.StockLineMatch
import com.shopledger.pos.data.command.CommandException
import com.shopledger.pos.data.command.Role
import com.shopledger.pos.data.command.StockInRequest
import com.shopledger.pos.data.command.StockInResult
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import com.shopledger.pos.ui.screens.stockin.BlockKind
import com.shopledger.pos.ui.screens.stockin.StockInEvent
import com.shopledger.pos.ui.screens.stockin.StockInSaver
import com.shopledger.pos.ui.screens.stockin.StockInViewModel
import com.shopledger.pos.ui.screens.stockin.parseQtyMilli
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockInViewModelTest {

    private fun inv(id: String, name: String, cost: Long, sell: Long) = InventoryItemWithVariants(
        item = InventoryItemEntity(id = id, displayName = name, sortKey = name.uppercase()),
        allVariants = listOf(
            ItemVariantEntity(id = "v-$id", itemId = id, unit = "pcs", purchasePricePaise = cost, sellingPricePaise = sell)
        )
    )

    private val inventory = listOf(inv("1", "Butt Hinge 4 SS", 4800, 6000), inv("2", "Fevicol SH 1kg", 20000, 25000))

    private fun line(name: String, qty: Long = 2000, rate: Long? = null, amount: Long? = null) =
        ParsedStockLine(name, qty, null, rate, amount, true, name)

    private fun matched(itemId: String, rate: Long?, name: String = "x", status: MatchStatus = MatchStatus.MATCHED) = StockLineMatch(
        line(name, rate = rate), status, itemId, "v-$itemId", if (itemId == "1") 4800 else 20000, if (itemId == "1") 6000 else 25000, null
    )

    private fun newLine(name: String, rate: Long?, suggested: Long?) =
        StockLineMatch(line(name, rate = rate), MatchStatus.NEW, null, null, null, null, suggested)

    private class FakeSaver(var failure: Throwable? = null) : StockInSaver {
        val requests = ArrayList<StockInRequest>()
        override suspend fun receive(request: StockInRequest): StockInResult {
            requests.add(request)
            failure?.let { throw it }
            return StockInResult("p1", 1, request.rows.size - 1, request.rows.sumOf { it.qtyMilli }, 0L, false)
        }
    }

    private fun vm(saver: StockInSaver = FakeSaver(), role: Role = Role.OWNER, ids: MutableList<String> = mutableListOf("act-1", "act-2")) =
        StockInViewModel(saver, { role }, { ids.removeAt(0) })

    private fun StockInViewModel.rows() = state.value!!.rows

    @Test fun matchedRowDefaultsCostToCurrentWhenBillHasNoRate() {
        val v = vm()
        v.openMatches(listOf(matched("1", null)), inventory, null, null)
        val r = v.rows().single()
        assertEquals(4800L, r.costPaise)
        assertEquals("Butt Hinge 4 SS", r.itemName)
        assertNull(v.state.value!!.blockReason)
    }

    @Test fun matchedRowUsesBillRateAsNewCost() {
        val v = vm()
        v.openMatches(listOf(matched("1", 5000)), inventory, null, null)
        assertEquals(5000L, v.rows().single().costPaise)
        assertEquals(4800L, v.rows().single().currentCostPaise)
    }

    @Test fun newRowWithoutCostIsInvalidUntilEntered() {
        val v = vm()
        v.openMatches(listOf(newLine("Door Stopper", null, null)), inventory, null, null)
        assertEquals(BlockKind.COST, v.state.value!!.blockReason!!.kind)
        val key = v.rows().single().key
        v.onEvent(StockInEvent.EditCost(key, 1000))
        assertEquals(BlockKind.SELL, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.EditSell(key, 1500))
        assertNull(v.state.value!!.blockReason)
    }

    @Test fun zeroQtyIsInvalid() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        v.onEvent(StockInEvent.EditQty(v.rows().single().key, 0))
        assertEquals(BlockKind.QTY, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.EditQty(v.rows().single().key, null))
        assertEquals(BlockKind.QTY, v.state.value!!.blockReason!!.kind)
    }

    @Test fun unresolvedCheckBlocksUntilPickedOrMadeNew() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800, status = MatchStatus.CHECK)), inventory, null, null)
        assertEquals(BlockKind.CHECK, v.state.value!!.blockReason!!.kind)
        val key = v.rows().single().key
        v.onEvent(StockInEvent.PickVariant(key, "2", "v-2"))
        val r = v.rows().single()
        assertEquals(MatchStatus.MATCHED, r.status)
        assertEquals("Fevicol SH 1kg", r.itemName)
        assertNull(v.state.value!!.blockReason)
    }

    @Test fun makeNewTurnsCheckRowIntoNewNeedingSell() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800, name = "Hnge 4", status = MatchStatus.CHECK)), inventory, null, null)
        v.onEvent(StockInEvent.MakeNew(v.rows().single().key))
        val r = v.rows().single()
        assertEquals(MatchStatus.NEW, r.status)
        assertNull(r.variantId)
        assertEquals("Hnge 4", r.itemName)
        assertEquals(BlockKind.SELL, v.state.value!!.blockReason!!.kind)
    }

    @Test fun sameVariantTwiceIsInvalid() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800), matched("2", 20000)), inventory, null, null)
        v.onEvent(StockInEvent.PickVariant(v.rows()[1].key, "1", "v-1"))
        assertEquals(BlockKind.DUPLICATE, v.state.value!!.blockReason!!.kind)
    }

    @Test fun deleteRowRemovesIt() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800), matched("2", 20000)), inventory, null, null)
        v.onEvent(StockInEvent.DeleteRow(v.rows()[0].key))
        assertEquals(1, v.rows().size)
    }

    @Test fun staffCannotEditCostOfExistingItems() {
        val v = vm(role = Role.STAFF)
        v.openMatches(listOf(matched("1", 5000)), inventory, null, null)
        // The scanned rate must not leak into the cost for staff: it stays at the current cost.
        assertEquals(4800L, v.rows().single().costPaise)
        v.onEvent(StockInEvent.EditCost(v.rows().single().key, 9999))
        assertEquals(4800L, v.rows().single().costPaise)
        assertFalse(v.state.value!!.canEditCost(v.rows().single()))
    }

    @Test fun billTotalAndSupplierRules() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800)), inventory, 9600L, null)
        assertEquals(9600L, v.state.value!!.billTotalPaise)
        assertEquals(BlockKind.SUPPLIER, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.SetNewSupplier("Sharma Traders"))
        assertNull(v.state.value!!.blockReason)
        v.onEvent(StockInEvent.SetPaid(20000))
        assertEquals(BlockKind.PAID, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.SetPaid(9600))
        assertNull(v.state.value!!.blockReason)
    }

    @Test fun onlySupplierAndBothPartiesAreOffered() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        v.setSuppliers(
            listOf(
                PartyEntity(id = "s1", partyType = "SUPPLIER", name = "Sharma"),
                PartyEntity(id = "c1", partyType = "CUSTOMER", name = "Ravi"),
                PartyEntity(id = "b1", partyType = "BOTH", name = "Gupta"),
                PartyEntity(id = "d1", partyType = "SUPPLIER", name = "Gone", isDeleted = true),
            )
        )
        assertEquals(listOf("Sharma", "Gupta"), v.state.value!!.suppliers.map { it.name })
    }

    @Test fun buildsRequestWithActionIdSupplierAndRows() = runBlocking {
        val saver = FakeSaver()
        val v = vm(saver)
        v.openMatches(listOf(matched("1", 5000), newLine("Door Stopper", 1000, 1500)), inventory, 12000L, null)
        v.onEvent(StockInEvent.SetSupplier("s1"))
        v.onEvent(StockInEvent.SetPaid(2000))
        v.save()
        val req = saver.requests.single()
        assertEquals("act-1", req.actionId)
        assertEquals("s1", req.supplierPartyId)
        assertEquals(12000L, req.billTotalPaise)
        assertEquals(2000L, req.amountPaidPaise)
        assertEquals("v-1", req.rows[0].existingVariantId)
        assertEquals(5000L, req.rows[0].costPaise)
        assertNull(req.rows[1].existingVariantId)
        assertEquals("Door Stopper", req.rows[1].newItemName)
        assertEquals(1500L, req.rows[1].sellPaise)
        assertNotNull(v.state.value!!.saved)
    }

    @Test fun failureKeepsStateShowsMessageAndRetryReusesActionId() = runBlocking {
        val saver = FakeSaver(CommandException.Validation("Quantity must be more than 0"))
        val v = vm(saver)
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        v.save()
        val s = v.state.value!!
        assertEquals("Quantity must be more than 0", s.error)
        assertFalse(s.saving)
        assertNull(s.saved)
        assertEquals(1, s.rows.size)
        saver.failure = null
        v.save()
        assertEquals(listOf("act-1", "act-1"), saver.requests.map { it.actionId })
        assertNotNull(v.state.value!!.saved)
    }

    @Test fun savingAfterSuccessDoesNotSendAgain() = runBlocking {
        val saver = FakeSaver()
        val v = vm(saver)
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        v.save()
        v.save()
        assertEquals(1, saver.requests.size)
    }

    @Test fun invalidStateNeverCallsTheSaver() = runBlocking {
        val saver = FakeSaver()
        val v = vm(saver)
        v.openMatches(listOf(newLine("Door Stopper", 1000, null)), inventory, null, null)
        v.save()
        assertTrue(saver.requests.isEmpty())
    }

    @Test fun openingAgainUsesAFreshActionId() {
        val v = vm(ids = mutableListOf("a", "b"))
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        assertEquals("a", v.state.value!!.actionId)
        v.close()
        assertNull(v.state.value)
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        assertEquals("b", v.state.value!!.actionId)
    }

    @Test fun openParsesAndMatchesText() {
        val v = vm()
        v.open("Fevicol SH 1kg 5 pkt 235 1175\nTotal 1175", inventory, null, ShopVertical.HARDWARE)
        val s = v.state.value!!
        assertEquals(1, s.rows.size)
        assertEquals(MatchStatus.MATCHED, s.rows.single().status)
        assertEquals(117500L, s.billTotalPaise)
    }

    @Test fun emptyTextOpensEmptyReview() {
        val v = vm()
        v.open("Thank you visit again", inventory, null, ShopVertical.HARDWARE)
        assertTrue(v.state.value!!.rows.isEmpty())
    }

    @Test fun qtyParsing() {
        assertEquals(12000L, parseQtyMilli("12"))
        assertEquals(2500L, parseQtyMilli("2.5"))
        assertNull(parseQtyMilli(""))
        assertNull(parseQtyMilli("abc"))
    }
}
