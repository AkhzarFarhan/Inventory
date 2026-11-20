package com.shopledger.pos.core.cart

import android.content.Context
import com.shopledger.pos.data.model.CartLineItem
import com.shopledger.pos.ui.CustomerCartSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Saves the open counter carts so several customers' carts survive the app being killed. */
object CartDraftStore {
    private const val PREFS = "shopledger_cart_prefs"
    private const val KEY = "open_carts_v1"

    data class Snapshot(val carts: List<CustomerCartSession>, val activeCartId: String)

    fun save(context: Context, carts: List<CustomerCartSession>, activeCartId: String) {
        val cartsArray = JSONArray()
        for (cart in carts) {
            val itemsArray = JSONArray()
            for (item in cart.items) {
                val itemObj = JSONObject()
                    .put("itemId", item.itemId)
                    .put("variantId", item.variantId)
                    .put("itemName", item.itemName)
                    .put("variantSummary", item.variantSummary)
                    .put("unit", item.unit)
                    .put("availableStock", item.availableStock)
                    .put("unitPurchasePricePaise", item.unitPurchasePricePaise)
                    .put("defaultSellingPricePaise", item.defaultSellingPricePaise)
                    .put("customSellingPricePaise", item.customSellingPricePaise)
                    .put("quantity", item.quantity)
                    .put("skuCode", item.skuCode)
                    .put("barcode", item.barcode)
                    .put("gstRateBps", item.gstRateBps)
                itemsArray.put(itemObj)
            }
            val cartObj = JSONObject()
                .put("cartId", cart.cartId)
                .put("label", cart.label)
                .put("cartNumber", cart.cartNumber)
                .put("buyerName", cart.buyerName)
                .put("buyerPhone", cart.buyerPhone)
                .put("createdAt", cart.createdAt)
                .put("checkoutActionId", cart.checkoutActionId)
                .put("items", itemsArray)
            cartsArray.put(cartObj)
        }
        val root = JSONObject()
            .put("active", activeCartId)
            .put("carts", cartsArray)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, root.toString())
            .apply()
    }

    fun load(context: Context): Snapshot? {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val root = JSONObject(raw)
            val active = root.optString("active", "")
            val cartsArray = root.optJSONArray("carts") ?: return null
            val carts = ArrayList<CustomerCartSession>(cartsArray.length())
            for (i in 0 until cartsArray.length()) {
                val c = cartsArray.getJSONObject(i)
                val cartId = c.optString("cartId", "").takeIf { it.isNotBlank() } ?: continue
                val itemsArray = c.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<CartLineItem>(itemsArray.length())
                for (j in 0 until itemsArray.length()) {
                    val it = itemsArray.getJSONObject(j)
                    items.add(
                        CartLineItem(
                            itemId = it.optString("itemId", ""),
                            variantId = it.optString("variantId", ""),
                            itemName = it.optString("itemName", ""),
                            variantSummary = it.optString("variantSummary", ""),
                            unit = it.optString("unit", "pcs"),
                            availableStock = it.optDouble("availableStock", 0.0),
                            unitPurchasePricePaise = it.optLong("unitPurchasePricePaise", 0L),
                            defaultSellingPricePaise = it.optLong("defaultSellingPricePaise", 0L),
                            customSellingPricePaise = it.optLong("customSellingPricePaise", 0L),
                            quantity = it.optDouble("quantity", 1.0),
                            skuCode = it.optString("skuCode", ""),
                            barcode = it.optString("barcode", ""),
                            gstRateBps = it.optInt("gstRateBps", 0),
                        )
                    )
                }
                carts.add(
                    CustomerCartSession(
                        cartId = cartId,
                        label = c.optString("label", ""),
                        cartNumber = c.optInt("cartNumber", 1),
                        buyerName = c.optString("buyerName", ""),
                        buyerPhone = c.optString("buyerPhone", ""),
                        items = items,
                        createdAt = c.optLong("createdAt", System.currentTimeMillis()),
                        checkoutActionId = c.optString("checkoutActionId", "").ifBlank { UUID.randomUUID().toString() },
                    )
                )
            }
            if (carts.isEmpty()) null else Snapshot(carts = carts, activeCartId = active)
        } catch (_: Exception) {
            null
        }
    }
}
