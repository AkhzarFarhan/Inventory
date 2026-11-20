package com.shopledger.pos.core.cart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.CustomerCartSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(application = android.app.Application::class)
@RunWith(AndroidJUnit4::class)
class CartDraftStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("shopledger_cart_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun roundTripKeepsEverything() {
        val line1 = CartLineItem(
            itemId = "item-1",
            variantId = "var-1",
            itemName = "Hinge",
            variantSummary = "4 inch",
            unit = "pcs",
            availableStock = 20.0,
            unitPurchasePricePaise = 4_000L,
            defaultSellingPricePaise = 5_000L,
            customSellingPricePaise = 4_800L,
            skuCode = "SKU-1",
            barcode = "890111",
            gstRateBps = 1800,
            quantity = 2.5,
        )
        val line2 = CartLineItem(
            itemId = "item-2",
            variantId = "var-2",
            itemName = "Bolt",
            variantSummary = "6 inch",
            unit = "kg",
            availableStock = 10.5,
            unitPurchasePricePaise = 2_000L,
            defaultSellingPricePaise = 3_000L,
            customSellingPricePaise = 2_750L,
            skuCode = "SKU-2",
            barcode = "890222",
            gstRateBps = 1800,
            quantity = 2.5,
        )
        val cart1 = CustomerCartSession(
            cartId = "cart-aaa",
            label = "Site",
            cartNumber = 1,
            buyerName = "Ramesh",
            buyerPhone = "9876543210",
            items = listOf(line1, line2),
            createdAt = 1_700_000_000_000L,
            checkoutActionId = "act-111",
        )
        val cart2 = CustomerCartSession(
            cartId = "cart-bbb",
            label = "",
            cartNumber = 2,
            buyerName = "",
            buyerPhone = "",
            items = emptyList(),
            createdAt = 1_700_000_005_000L,
            checkoutActionId = "act-222",
        )

        CartDraftStore.save(context, listOf(cart1, cart2), "cart-bbb")
        val loaded = CartDraftStore.load(context)
        assertNotNull(loaded)
        assertEquals("cart-bbb", loaded!!.activeCartId)
        assertEquals(listOf(cart1, cart2), loaded.carts)
    }

    @Test
    fun corruptJsonLoadsNothing() {
        context.getSharedPreferences("shopledger_cart_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("open_carts_v1", "{not json")
            .commit()
        assertNull(CartDraftStore.load(context))
    }

    @Test
    fun emptyPrefsLoadsNothing() {
        assertNull(CartDraftStore.load(context))
    }
}
