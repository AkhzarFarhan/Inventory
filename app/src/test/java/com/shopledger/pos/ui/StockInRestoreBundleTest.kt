package com.shopledger.pos.ui

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.scanner.MatchStatus
import com.shopledger.pos.core.scanner.ParsedStockLine
import com.shopledger.pos.core.scanner.StockLineMatch
import com.shopledger.pos.data.command.Role
import com.shopledger.pos.data.command.StockInResult
import com.shopledger.pos.ui.screens.stockin.StockInEvent
import com.shopledger.pos.ui.screens.stockin.StockInSaver
import com.shopledger.pos.ui.screens.stockin.StockInViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StockInRestoreBundleTest {
    private val saver = StockInSaver { StockInResult("p", 0, 0, 0L, 0L, false) }

    @Test fun reviewSurvivesARealBundleRoundTrip() {
        val handle = SavedStateHandle()
        val first = StockInViewModel(saver, { Role.OWNER }, { "act-bundle" }, handle)
        val line = ParsedStockLine("Door Stopper", 2000L, null, 1000L, null, true, "Door Stopper")
        first.openMatches(
            listOf(StockLineMatch(line, MatchStatus.NEW, null, null, null, null, 1300L)),
            emptyList(), 2000L, null,
        )
        first.onEvent(StockInEvent.SetNewSupplier("Sharma"))
        first.onEvent(StockInEvent.SetPaid(500L))

        // What Android does on process death: write the saved state to a Parcel and read it back.
        val saved: Bundle = handle.savedStateProvider().saveState()
        val parcel = Parcel.obtain()
        parcel.writeBundle(saved)
        parcel.setDataPosition(0)
        val restored = parcel.readBundle(javaClass.classLoader)
        parcel.recycle()

        val second = StockInViewModel(saver, { Role.OWNER }, { "other" }, SavedStateHandle.createHandle(restored, null))
        val s = second.state.value!!
        assertEquals("act-bundle", s.actionId)
        assertEquals("Door Stopper", s.rows.single().itemName)
        assertEquals(1300L, s.rows.single().sellPaise)
        assertEquals("Sharma", s.newSupplierName)
        assertEquals(500L, s.amountPaidPaise)
        assertEquals(true, s.paidEdited)
    }
}
