package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KhataCommandsTest {
    private lateinit var db: LpsDatabase
    private lateinit var khata: KhataCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        khata = KhataCommands(db, TestEnv())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun balance(id: String) = db.shopPartyAndLedgerDao().getPartyById(id)!!.currentBalancePaise

    @Test
    fun createParty_withOpeningBalance_writesOpeningEntry() = runBlocking {
        val id = khata.createParty("p1", "Ramesh", "98765 43210", "customer", openingBalancePaise = 50_000L)
        assertEquals(50_000L, balance(id))
        val entry = db.shopPartyAndLedgerDao().getAllLedgerEntries().single()
        assertEquals(LedgerEntryType.OPENING, entry.entryType)
        assertEquals(listOf("PARTY", "LEDGER"), db.syncOutboxDao().all().map { it.docType })
    }

    @Test
    fun recordPayment_overpayment_becomesAdvance() = runBlocking {
        val id = khata.createParty("p1", "Ramesh", "", openingBalancePaise = 50_000L)
        khata.recordPayment("pay1", id, 80_000L, "UPI")
        assertEquals(-30_000L, balance(id))
        val payment = db.shopPartyAndLedgerDao().getAllLedgerEntries().single { it.entryType == LedgerEntryType.PAYMENT_IN }
        assertEquals(-80_000L, payment.amountDeltaPaise)
        assertEquals("UPI", payment.paymentMode)
    }

    @Test
    fun recordPayment_sameActionIdTwice_appliesOnce() = runBlocking {
        val id = khata.createParty("p1", "Ramesh", "", openingBalancePaise = 50_000L)
        val first = khata.recordPayment("pay1", id, 10_000L)
        val second = khata.recordPayment("pay1", id, 10_000L)
        assertEquals(first, second)
        assertEquals(40_000L, balance(id))
    }

    @Test
    fun recordPayment_zeroAmount_isRejected() = runBlocking {
        val id = khata.createParty("p1", "Ramesh", "")
        expectFailure<CommandException.Validation> { khata.recordPayment("pay1", id, 0L) }
        assertEquals(0L, balance(id))
    }

    @Test
    fun updateParty_withStaleUpdatedAt_throws() = runBlocking {
        val id = khata.createParty("p1", "Ramesh", "")
        val party = db.shopPartyAndLedgerDao().getPartyById(id)!!
        expectFailure<CommandException.StaleEdit> {
            khata.updateParty("u1", id, party.updatedAt - 1, "Ramesh Sharma", "", "CUSTOMER", "")
        }
        assertEquals("Ramesh", db.shopPartyAndLedgerDao().getPartyById(id)!!.name)
    }

    @Test
    fun paymentToDistributorRaisesBalanceTowardZero() = runBlocking {
        val id = khata.createParty("sup-1", "Gupta Traders", "", partyType = "SUPPLIER", openingBalancePaise = -10_000L)
        khata.recordPayment("pay-sup-1", id, 4_000L, "CASH", direction = PaymentDirection.OUT)
        assertEquals(-6_000L, balance(id))
        val entry = db.shopPartyAndLedgerDao().getAllLedgerEntries().single { it.entryType == LedgerEntryType.PAYMENT_OUT }
        assertEquals(4_000L, entry.amountDeltaPaise)
        assertEquals("CASH", entry.paymentMode)
    }
}
