package com.shopledger.pos.ui

import androidx.lifecycle.SavedStateHandle
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
import com.shopledger.pos.ui.screens.stockin.parseMoneyPaise
import com.shopledger.pos.ui.screens.stockin.parseQtyMilli
import com.shopledger.pos.ui.screens.stockin.sumOfLinesPaise
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
        // Entering the cost also suggests a sell price (usual markup 25%: Rs 12.50 -> Rs 13), so the row is complete.
        assertEquals(1300L, v.rows().single().sellPaise)
        assertNull(v.state.value!!.blockReason)
        v.onEvent(StockInEvent.EditSell(key, 1500))
        assertEquals(1500L, v.rows().single().sellPaise)
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
        // Cost 48.00 plus the median markup of the shop (25%) = 60.00, already filled in.
        assertEquals(4800L, r.costPaise)
        assertEquals(6000L, r.sellPaise)
        assertNull(v.state.value!!.blockReason)
    }

    @Test fun makeNewWithoutABillRateNeedsCostThenSuggestsSell() {
        val v = vm()
        v.openMatches(listOf(matched("1", null, name = "Hnge 4", status = MatchStatus.CHECK)), inventory, null, null)
        val key = v.rows().single().key
        v.onEvent(StockInEvent.MakeNew(key))
        assertNull(v.rows().single().sellPaise)
        assertEquals(BlockKind.COST, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.EditCost(key, 2000))
        assertEquals(2500L, v.rows().single().sellPaise)
        // The suggestion follows the cost while the user has not typed a sell price ...
        v.onEvent(StockInEvent.EditCost(key, 4000))
        assertEquals(5000L, v.rows().single().sellPaise)
        // ... and never overwrites one they typed.
        v.onEvent(StockInEvent.EditSell(key, 9900))
        v.onEvent(StockInEvent.EditCost(key, 6000))
        assertEquals(9900L, v.rows().single().sellPaise)
    }

    @Test fun costOfZeroIsNeverSentAsUnknown() = runBlocking {
        val saver = FakeSaver()
        val v = vm(saver)
        v.openMatches(listOf(newLine("Door Stopper", 0, 1500)), inventory, null, null)
        assertEquals(BlockKind.COST, v.state.value!!.blockReason!!.kind)
        v.save()
        assertTrue(saver.requests.isEmpty())
        // A matched item whose stored cost is 0 and whose bill has no rate is blocked too.
        val free = listOf(inv("3", "Free Sample", 0, 100))
        val w = vm(saver)
        w.openMatches(listOf(StockLineMatch(line("Free Sample"), MatchStatus.MATCHED, "3", "v-3", 0, 100, null)), free, null, null)
        assertEquals(0L, w.rows().single().costPaise)
        assertEquals(BlockKind.COST, w.state.value!!.blockReason!!.kind)
        w.onEvent(StockInEvent.EditCost(w.rows().single().key, 50))
        assertNull(w.state.value!!.blockReason)
    }

    @Test fun sameNewItemNameTwiceIsInvalid() {
        val v = vm()
        v.openMatches(listOf(newLine("Plug Point", 500, 700), newLine("  plug point ", 500, 700)), inventory, null, null)
        assertEquals(BlockKind.DUPLICATE, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.EditName(v.rows()[1].key, "Plug Point 6A"))
        assertNull(v.state.value!!.blockReason)
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
        // No supplier: the bill is assumed paid in full, so saving is allowed.
        assertEquals(9600L, v.state.value!!.amountPaidPaise)
        assertNull(v.state.value!!.blockReason)
        // Paying less than the total needs a supplier to owe the rest to.
        v.onEvent(StockInEvent.SetPaid(0))
        assertEquals(BlockKind.SUPPLIER, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.SetNewSupplier("Sharma Traders"))
        assertNull(v.state.value!!.blockReason)
        v.onEvent(StockInEvent.SetPaid(20000))
        assertEquals(BlockKind.PAID, v.state.value!!.blockReason!!.kind)
        v.onEvent(StockInEvent.SetPaid(9600))
        assertNull(v.state.value!!.blockReason)
    }

    @Test fun paidFollowsTotalUntilEditedOrSupplierChosen() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        v.onEvent(StockInEvent.SetBillTotal(5000))
        assertEquals(5000L, v.state.value!!.amountPaidPaise)
        assertNull(v.state.value!!.blockReason)
        v.onEvent(StockInEvent.SetBillTotal(7000))
        assertEquals(7000L, v.state.value!!.amountPaidPaise)
        v.onEvent(StockInEvent.SetBillTotal(null))
        assertEquals(0L, v.state.value!!.amountPaidPaise)
        // Once the user edits paid, the total no longer drags it along.
        v.onEvent(StockInEvent.SetBillTotal(7000))
        v.onEvent(StockInEvent.SetPaid(2000))
        v.onEvent(StockInEvent.SetBillTotal(8000))
        assertEquals(2000L, v.state.value!!.amountPaidPaise)
        assertEquals(BlockKind.SUPPLIER, v.state.value!!.blockReason!!.kind)
        // With a supplier chosen it does not follow either.
        val w = vm()
        w.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        w.onEvent(StockInEvent.SetSupplier("s1"))
        w.onEvent(StockInEvent.SetBillTotal(5000))
        assertEquals(0L, w.state.value!!.amountPaidPaise)
        assertNull(w.state.value!!.blockReason)
    }

    @Test fun billTotalComesFromRawTextWhenTheModelTidiedTheLines() {
        val tidied = "Fevicol SH 1kg 5 pkt 235 1175"
        val raw = "SHARMA TRADERS\nFevicol SH 1kg 5 pkt 235 1175\nGrand Total 1250"
        val v = vm()
        v.open(tidied, inventory, null, ShopVertical.HARDWARE, rawText = raw)
        assertEquals(125000L, v.state.value!!.billTotalPaise)
        assertEquals(1, v.rows().size)
    }

    @Test fun billTotalFallsBackToTheSumOfTheLines() {
        val v = vm()
        v.open("Fevicol SH 1kg 5 pkt 235 1175\nButt Hinge 4 SS 2 pc 48 96", inventory, null, ShopVertical.HARDWARE, rawText = "no total line here")
        assertEquals(2, v.rows().size)
        assertEquals(117500L + 9600L, v.state.value!!.billTotalPaise)
    }

    @Test fun billTotalFallsBackToQtyTimesRateWhenAmountIsMissing() {
        val lines = listOf(line("A", qty = 2500, rate = 1000, amount = null), line("B", qty = 1000, rate = null, amount = 700))
        assertEquals(2500L + 700L, sumOfLinesPaise(lines))
        assertNull(sumOfLinesPaise(listOf(line("C", rate = null, amount = null))))
    }

    @Test fun reviewIsRestoredFromSavedStateWithTheSameActionId() {
        val handle = SavedStateHandle()
        val first = StockInViewModel(FakeSaver(), { Role.OWNER }, { "act-keep" }, handle)
        first.openMatches(listOf(matched("1", 5000), newLine("Door Stopper", 1000, null)), inventory, 12000L, null)
        first.onEvent(StockInEvent.SetNewSupplier("Sharma"))
        // The process dies and comes back: a new view model gets the same saved state.
        val second = StockInViewModel(FakeSaver(), { Role.OWNER }, { "act-other" }, handle)
        second.setInventory(inventory)
        val s = second.state.value!!
        assertEquals("act-keep", s.actionId)
        assertEquals(2, s.rows.size)
        assertEquals("Sharma", s.newSupplierName)
        assertEquals(12000L, s.billTotalPaise)
        assertEquals(inventory, s.inventory)
        second.close()
        assertNull(StockInViewModel(FakeSaver(), { Role.OWNER }, { "x" }, handle).state.value)
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
        val saver = FakeSaver(CommandException.Validation(com.shopledger.pos.R.string.msg_qty_positive))
        val v = vm(saver)
        v.openMatches(listOf(matched("1", 4800)), inventory, null, null)
        v.save()
        val s = v.state.value!!
        assertEquals(com.shopledger.pos.ui.text.UiText.Res(com.shopledger.pos.R.string.msg_qty_positive), s.error)
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

    @Test fun moneyParsingIsExact() {
        assertEquals(1250L, parseMoneyPaise("12.5"))
        assertEquals(1005L, parseMoneyPaise("10.05"))
        assertEquals(113L, parseMoneyPaise("1.13"))
        assertNull(parseMoneyPaise("."))
    }

    @Test fun clearingTheSupplierSyncsPaidBackToTheTotalWhenNotEdited() {
        val v = vm()
        v.openMatches(listOf(matched("1", 4800)), inventory, 9600L, null)
        v.onEvent(StockInEvent.SetSupplier("s1"))
        v.onEvent(StockInEvent.SetSupplier(null))
        assertEquals(9600L, v.state.value!!.amountPaidPaise)
        assertNull(v.state.value!!.blockReason)
        // A typed supplier name that is cleared again, with the total changed meanwhile.
        val w = vm()
        w.openMatches(listOf(matched("1", 4800)), inventory, 9600L, null)
        w.onEvent(StockInEvent.SetNewSupplier("Sharma"))
        w.onEvent(StockInEvent.SetBillTotal(7000L))
        assertEquals(9600L, w.state.value!!.amountPaidPaise)
        w.onEvent(StockInEvent.SetNewSupplier(""))
        assertEquals(7000L, w.state.value!!.amountPaidPaise)
        assertNull(w.state.value!!.blockReason)
        // An edited paid amount is left alone.
        val x = vm()
        x.openMatches(listOf(matched("1", 4800)), inventory, 9600L, null)
        x.onEvent(StockInEvent.SetPaid(100L))
        x.onEvent(StockInEvent.SetSupplier("s1"))
        x.onEvent(StockInEvent.SetSupplier(null))
        assertEquals(100L, x.state.value!!.amountPaidPaise)
    }

    @Test fun clearingTheSellPriceOfANewRowBlocksOnSell() {
        val v = vm()
        v.openMatches(listOf(newLine("Door Stopper", 1000, 1300)), inventory, null, null)
        assertNull(v.state.value!!.blockReason)
        v.onEvent(StockInEvent.EditSell(v.rows().single().key, null))
        assertEquals(BlockKind.SELL, v.state.value!!.blockReason!!.kind)
    }

    @Test fun staffWithAMatchedRowOfZeroCostIsToldToAskTheOwner() {
        val zeroCost = listOf(inv("1", "Butt Hinge 4 SS", 0, 6000))
        val staff = vm(role = Role.STAFF)
        staff.openMatches(listOf(matched("1", 5000)), zeroCost, null, null)
        assertFalse(staff.state.value!!.canEditCost(staff.rows().single()))
        assertEquals(BlockKind.COST_OWNER, staff.state.value!!.blockReason!!.kind)
        // The owner can simply type it.
        val owner = vm()
        owner.openMatches(listOf(matched("1", null)), zeroCost, null, null)
        assertEquals(BlockKind.COST, owner.state.value!!.blockReason!!.kind)
    }

    @Test fun paidModeReachesTheRequest() = runBlocking {
        val saver = FakeSaver()
        val v = vm(saver)
        v.openMatches(listOf(matched("1", 4800)), inventory, 10000L, null)
        v.onEvent(StockInEvent.SetSupplier("s1"))
        v.onEvent(StockInEvent.SetPaid(4000L))
        v.onEvent(StockInEvent.SetPaidMode("UPI"))
        v.save()
        assertEquals("UPI", saver.requests.single().paidMode)
    }

    @Test fun billNumberReachesTheRequest() = runBlocking {
        val saver = FakeSaver()
        val v = vm(saver)
        v.openMatches(listOf(matched("1", 4800)), inventory, 9600L, null)
        v.onEvent(StockInEvent.SetBillNumber("  GT-17  "))
        v.save()
        assertEquals("GT-17", saver.requests.single().billNumber)
    }

    @Test fun addRowAddsMatchedRowWithVariantCost() {
        val v = vm()
        v.setInventory(inventory)
        v.openManual()
        v.onEvent(StockInEvent.AddRow("1", "v-1"))
        val r = v.rows().single()
        assertEquals(MatchStatus.MATCHED, r.status)
        assertEquals("v-1", r.variantId)
        assertEquals(1000L, r.qtyMilli)
        assertEquals(4800L, r.costPaise)
        assertEquals(6000L, r.sellPaise)
    }

    @Test fun addRowTwiceIncreasesQty() {
        val v = vm()
        v.setInventory(inventory)
        v.openManual()
        v.onEvent(StockInEvent.AddRow("1", "v-1"))
        v.onEvent(StockInEvent.AddRow("1", "v-1"))
        val r = v.rows().single()
        assertEquals(2000L, r.qtyMilli)
    }

    @Test fun manualReviewStartsEmptyAndCannotSave() {
        val v = vm()
        v.openManual()
        val s = v.state.value!!
        assertTrue(s.rows.isEmpty())
        assertNull(s.billTotalPaise)
        assertEquals(BlockKind.EMPTY, s.blockReason?.kind)
    }

    @Test fun snapshotRestoresPaidModeAndBillNumber() {
        val handle = SavedStateHandle()
        val first = StockInViewModel(FakeSaver(), { Role.OWNER }, { "act-snap" }, handle)
        first.openMatches(listOf(matched("1", 4800)), inventory, 10000L, null)
        first.onEvent(StockInEvent.SetSupplier("s1"))
        first.onEvent(StockInEvent.SetPaid(4000L))
        first.onEvent(StockInEvent.SetPaidMode("UPI"))
        first.onEvent(StockInEvent.SetBillNumber("GT-99"))

        val restored = StockInViewModel(FakeSaver(), { Role.OWNER }, { "other" }, handle)
        val s = restored.state.value!!
        assertEquals("UPI", s.paidMode)
        assertEquals("GT-99", s.billNumber)
    }
}
