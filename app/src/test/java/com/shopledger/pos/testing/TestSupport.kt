package com.shopledger.pos.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopledger.pos.core.backup.GcmStreams
import com.shopledger.pos.core.backup.SnapshotCipher
import com.shopledger.pos.data.command.Actor
import com.shopledger.pos.data.command.CatalogCommands
import com.shopledger.pos.data.command.CommandEnvironment
import com.shopledger.pos.data.command.Role
import com.shopledger.pos.data.command.VariantEdit
import com.shopledger.pos.data.command.VariantSpec
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.withShopLedgerDefaults
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec

/** Deterministic environment: fixed clock, sequential ids, configurable role. Thread-safe. */
class TestEnv(
    @Volatile var clock: Long = BASE_TIME,
    @Volatile var role: Role = Role.OWNER,
    @Volatile var gst: Boolean = false,
    private val shop: String = DEFAULT_SHOP_ID
) : CommandEnvironment {
    private val ids = AtomicLong(0)
    val commits = AtomicInteger(0)
    override fun shopId() = shop
    override fun actor() = Actor(uid = "uid-test", role = role)
    override fun gstEnabled() = gst
    override fun now() = clock
    override fun newId() = "id-${ids.incrementAndGet()}"
    override fun afterCommit() {
        commits.incrementAndGet()
    }

    companion object {
        /** 2026-11-18T19:00:00Z */
        const val BASE_TIME = 1_795_028_400_000L
    }
}

fun newTestDatabase(): LpsDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LpsDatabase::class.java)
        .withShopLedgerDefaults()
        .allowMainThreadQueries()
        .build()

/** First column of the first row as Long (COUNT/SUM queries). */
fun LpsDatabase.scalar(sql: String): Long =
    openHelper.readableDatabase.query(sql).use { c ->
        check(c.moveToFirst()) { "No rows for: $sql" }
        c.getLong(0)
    }

/** Like assertThrows, but usable from coroutines (the inlined block may suspend). */
inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("Expected ${T::class.java.simpleName} but got $t", t)
    }
    throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
}

fun ItemVariantEntity.toEdit(targetStockMilli: Long? = null) = VariantEdit(
    sizeLabel = sizeLabel,
    materialLabel = materialLabel,
    colorFinishLabel = colorFinishLabel,
    unit = unit,
    purchasePricePaise = purchasePricePaise,
    sellingPricePaise = sellingPricePaise,
    lowStockMilli = lowStockMilliUnits,
    barcode = barcode,
    skuCode = skuCode,
    gstRateBps = gstRateBps,
    targetStockMilli = targetStockMilli
)

/** Creates "Butt Hinge" (cost Rs 48, price Rs 75) with the given opening stock; returns the variant id. */
suspend fun seedHinge(
    db: LpsDatabase,
    env: CommandEnvironment,
    openingMilli: Long = 10_000L,
    actionId: String = "seed-hinge"
): String {
    val itemId = CatalogCommands(db, env).createItem(
        actionId, "Butt Hinge", "Fittings",
        listOf(VariantSpec(sizeLabel = "4 inch", purchasePricePaise = 4_800L, sellingPricePaise = 7_500L, openingStockMilli = openingMilli))
    )
    return db.inventoryDao().getVariantsForItem(itemId).single().id
}

/** Fixed-key AES-GCM (Robolectric has no AndroidKeyStore). Output = 12-byte IV + ciphertext. */
class AesTestCipher : SnapshotCipher {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    override fun encrypt(out: OutputStream): OutputStream = GcmStreams.encrypt(key, out)

    override fun decrypt(input: InputStream): InputStream = GcmStreams.decrypt(key, input)
}
