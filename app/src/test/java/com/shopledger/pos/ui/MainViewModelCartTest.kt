package com.shopledger.pos.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.repository.LpsRepository
import com.shopledger.pos.testing.newTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(application = android.app.Application::class)
@RunWith(AndroidJUnit4::class)
class MainViewModelCartTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: LpsDatabase

    private val itemA = InventoryItemEntity(id = "item-a", displayName = "Hinge", sortKey = "HINGE")
    private val variantA = ItemVariantEntity(
        id = "var-a",
        itemId = "item-a",
        purchasePricePaise = 4_000L,
        sellingPricePaise = 5_000L,
        stockMilliUnits = 20_000L,
        lowStockMilliUnits = 5_000L,
    )
    private val itemB = InventoryItemEntity(id = "item-b", displayName = "Bolt", sortKey = "BOLT")
    private val variantB = ItemVariantEntity(
        id = "var-b",
        itemId = "item-b",
        purchasePricePaise = 2_000L,
        sellingPricePaise = 3_000L,
        stockMilliUnits = 15_000L,
        lowStockMilliUnits = 5_000L,
    )

    @Before
    fun setUp() {
        context.getDatabasePath(LpsDatabase.DB_NAME).delete()
        listOf("shopledger_sync_prefs", "lps_hardware_sync_prefs", "lps_seed_prefs", "shopledger_cart_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        db = newTestDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun newViewModel() = MainViewModel(LpsRepository(context, db))

    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun newCartUsesSmallestFreeNumber() {
        val vm = newViewModel()
        vm.createNewCart()
        vm.createNewCart()
        assertEquals(listOf(1, 2, 3), vm.cartsState.value.map { it.cartNumber })

        val cart2 = vm.cartsState.value.first { it.cartNumber == 2 }
        vm.closeCart(cart2.cartId)
        assertEquals(listOf(1, 3), vm.cartsState.value.map { it.cartNumber })

        val newId = vm.createNewCart()
        assertNotNull(newId)
        val created = vm.cartsState.value.first { it.cartId == newId }
        assertEquals(2, created.cartNumber)
    }

    @Test
    fun cannotOpenMoreThanEightCarts() {
        val vm = newViewModel()
        val results = (1..10).map { vm.createNewCart() }
        assertEquals(8, vm.cartsState.value.size)
        results.take(7).forEach { assertNotNull(it) }
        results.drop(7).forEach { assertNull(it) }
    }

    @Test
    fun displayLabelPrefersTypedNameThenCustomerThenNumber() {
        val vm = newViewModel()
        val activeId = vm.activeCartIdState.value
        assertEquals("Cart 1", vm.cartsState.value.first().displayLabel(context))

        vm.updateActiveCartBuyer("Ramesh", "")
        assertEquals("Ramesh", vm.cartsState.value.first().displayLabel(context))

        vm.renameCart(activeId, "Site order")
        assertEquals("Site order", vm.cartsState.value.first().displayLabel(context))

        vm.renameCart(activeId, "  ")
        assertEquals("Ramesh", vm.cartsState.value.first().displayLabel(context))
    }

    @Test
    fun addingAgainKeepsTypedPrice() {
        val vm = newViewModel()
        vm.addOrUpdateCartItem(itemA, variantA, 1.0)
        vm.updateCartLineQuantityAndPrice(variantA.id, 1.0, 4_500L)
        vm.addOrUpdateCartItem(itemA, variantA, 1.0)

        val line = vm.cartsState.value.first().items.single()
        assertEquals(2.0, line.quantity, 0.0001)
        assertEquals(4_500L, line.customSellingPricePaise)
    }

    @Test
    fun itemsGoOnlyIntoTheActiveCart() {
        val vm = newViewModel()
        val cart1Id = vm.activeCartIdState.value
        vm.addOrUpdateCartItem(itemA, variantA, 1.0)

        val cart2Id = vm.createNewCart()
        assertNotNull(cart2Id)
        vm.addOrUpdateCartItem(itemB, variantB, 1.0)

        val cart1 = vm.cartsState.value.first { it.cartId == cart1Id }
        val cart2 = vm.cartsState.value.first { it.cartId == cart2Id }
        assertEquals(listOf(variantA.id), cart1.items.map { it.variantId })
        assertEquals(listOf(variantB.id), cart2.items.map { it.variantId })
    }

    @Test
    fun switchingCartsKeepsEachCartsItems() {
        val vm = newViewModel()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch { vm.cartItems.collect {} }
        try {
            val cart1Id = vm.activeCartIdState.value
            vm.addOrUpdateCartItem(itemA, variantA, 1.0)
            vm.createNewCart()
            vm.addOrUpdateCartItem(itemB, variantB, 1.0)
            awaitUntil("active cart items switched to cart 2") {
                vm.cartItems.value.map { it.variantId } == listOf(variantB.id)
            }

            vm.switchActiveCart(cart1Id)
            awaitUntil("active cart items switched to cart 1") {
                vm.cartItems.value.map { it.variantId } == listOf(variantA.id)
            }
            assertEquals(listOf(variantA.id), vm.cartItems.value.map { it.variantId })
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun closingLastCartLeavesFreshCartOne() {
        val vm = newViewModel()
        val cart1Id = vm.activeCartIdState.value
        vm.addOrUpdateCartItem(itemA, variantA, 1.0)
        assertEquals(1, vm.cartsState.value.single().items.size)

        vm.closeCart(cart1Id)
        val remaining = vm.cartsState.value.single()
        assertTrue(remaining.items.isEmpty())
        assertEquals(1, remaining.cartNumber)
    }
}
