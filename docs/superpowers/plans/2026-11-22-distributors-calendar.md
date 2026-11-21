# ShopLedger POS — Distributor Profiles (Udhaar/Nagad accounts, items per distributor) + Business Calendar

- **Date:** 2026-11-22
- **Base:** `main` at `a64bb5b`
- **Branch to create:** `feat/distributors-calendar`
- **Audience:** an AI coding agent implementing this plan task by task. Every instruction is literal. Do not skip steps, do not invent extra features, do not change anything not named here. When the plan gives code, use it; when it says "follow the pattern of X", open X and copy its structure.

---

## 0. What the shop owner asked for

1. **Distributor / wholesaler profiles**, like customer profiles today, with **Udhaar** (credit) and **Nagad** (cash) payments:
   - see each distributor's account (what the shop owes them, every purchase bill and every payment),
   - record a payment to a distributor (Nagad or UPI),
   - know **which item was stocked in from which distributor** (per bill, and "items supplied" per distributor, and "last bought from" per item).
2. **A calendar view** that shows, per day, total sale, Udhaar, Nagad, stock-in, and similar details; tap a day for its full breakdown.

### Decisions (fixed — do not change)

| Topic | Decision |
|---|---|
| Name in the app | English **"Distributor"**, Hindi **"डिस्ट्रीब्यूटर"**. Stored party type stays `"SUPPLIER"` (already supported by `KhataCommands`, values `CUSTOMER`/`SUPPLIER`/`BOTH`). `BOTH` shows in both lists. |
| Sign convention (unchanged) | `parties.currentBalancePaise`: **+ = party owes the shop**, **− = shop owes the party**. A distributor you owe ₹500 has −50000. Show it as "You owe ₹500". |
| Purchase record | New immutable tables `purchase_bills` + `purchase_bill_lines` (schema **v4**), written in the **same transaction** as the stock movements by `StockInCommands.receiveScannedStock`. One bill per stock-in save (its id = the existing `purchaseId`). |
| Distributor ledger per stock-in | With a distributor: append `CREDIT_BILL` of **−total** and, if something was paid now, `PAYMENT_OUT` of **+paid** with mode `CASH`/`UPI`. Net effect is the same as today (−due) but the account now shows the bill and the payment separately. Without a distributor: no ledger rows (as today). |
| Paid-now mode | Stock-in review gets **Nagad / UPI** choice for the paid part, and an optional **bill number**. |
| Manual bill entry | Stock In sheet gets **"Enter bill by hand"**: opens the same review table empty, with an **Add item** button, so distributor bills can be recorded without a photo. |
| Khata tab | Top switch **Customers · Distributors**. Customers list shows only `CUSTOMER`/`BOTH` (today it also shows suppliers as "settled" — that is a bug this fixes). |
| Privacy | Distributor screens show purchase costs, so the **Distributors** side needs **seller mode unlocked** (same `requestToggleSeller` flow as Stock In). Calendar stock-in / paid-to-distributor numbers show only when seller mode is unlocked; sales and customer Udhaar always show. |
| Calendar place | Bills tab gets a top switch **List · Calendar**. The existing list screen is unchanged. |
| Calendar week | Starts on **Sunday** (Indian wall-calendar style). |
| Old stock-ins | Migration creates bills for past stock-ins from their movements (marked `isLegacy = 1`, cost unknown = 0, supplier taken from the old `CREDIT_BILL` entry when there was one). UI shows "Older entry — cost not recorded" for these. |

---

## 1. Global rules (apply to every task)

1. **Stack:** Kotlin 2.0.20, Compose (BOM 2024.09.03, Material3), Room 2.6.1 via KSP (`exportSchema = true`, schemas in `app/schemas/com.shopledger.pos.data.local.LpsDatabase/N.json`), minSdk 24. No new libraries.
2. **Paths:** `pos/` = `app/src/main/java/com/shopledger/pos/`. Tests in `app/src/test/java/com/shopledger/pos/`.
3. **Money is `Long` paise.** Quantities in the database are `Long` milli-units. Never use `Double` for money. Line total = `(qtyMilli * unitCostPaise + 500) / 1000` (round half up, `Long` math).
4. **All writes go through a `*Commands` class inside `LedgerWriter.runIdempotent(actionId, kind, replay) { … }`** (one transaction, idempotent by `actionId`). UI/ViewModel code never writes DAOs directly. Ledgers and movements are append-only.
5. **Strings:** every user-visible text in `res/values/strings.xml` **and** `res/values-hi/strings.xml` (same keys, same `%1$s`/`%2$d` args; counts as `<plurals>` with `one` + `other` in both). `StringParityTest` enforces this. Names of items/parties are never translated. Section 12 lists every new key.
6. **Design system:** `pos/ui/theme/Components.kt` (`PrimaryActionButton(text, onClick, modifier, icon, enabled, containerColor)`, `SecondaryActionButton`, `SectionCard(title, modifier, content)`, `EmptyState`, `ConfirmDialog`, `StatusChip(text, tone)` with `ChipTone`, `SegmentedChoice(options, selectedIndex, onSelect, modifier)`, `MoneyField`, `QtyStepper`), `MaterialTheme` colours/typography, `ShopLedgerTokens` spacing (`spacingXs/S/M/L/Xl`, `minTouch` = 48 dp). No hard-coded colours. Every tappable thing ≥ 48 dp. Icon-only buttons have a `contentDescription` string. Must work at **360 dp** width. No sideways scrolling lists.
7. **Seller lock:** cost prices / purchase amounts only when `isSellerUnlocked`. Unlock through the existing `requestToggleSeller` lambda in `MainActivity`.
8. **Line endings LF, UTF-8.** Stage with `git -c core.autocrlf=false add <files>`; `git ls-files --eol <files>` must show `i/lf`.
9. **One commit per task** with the given message, ending with a blank line and your tool's `Co-Authored-By:` trailer.
10. **Test-first:** write the listed tests, run (they fail), implement, run (green). Never weaken an existing test to make it pass; only update the assertions this plan says change.

### Commands (Windows PowerShell, from `C:\GitHub\Inventory`)

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\GitHub\Inventory\build\jtmp"
```
- One class: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.command.StockInCommandsTest"`
- Full suite: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
- Build: `.\gradlew.bat assembleDebug --console=plain > build\assemble.log 2>&1`
- Final: `.\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain > build\final.log 2>&1`

Always redirect Gradle output to a file under `build\` and read it; never pipe Gradle through `Select-Object` (it hangs). If a run hangs > 10 minutes: `.\gradlew.bat --stop` and rerun. Run `.\gradlew.bat --stop` when finished. Results: `app/build/test-results/testDebugUnitTest/*.xml`. Record the test count before Task 1; it must never go down and failures must stay 0.

---

## 2. Current code you will touch (read first)

| File | What is there |
|---|---|
| `pos/data/local/LpsDatabase.kt` | `@Database(entities = [...], views = [SaleRecordEntity::class], version = SCHEMA_VERSION)`, `const val SCHEMA_VERSION = 3`, DAO getters, `addMigrations(LpsDatabase.MIGRATION_1_2, Migration2To3(migrationContext))` (~line 401). |
| `pos/data/local/entity/ShopLedgerEntities.kt` | `PartyEntity`, `InvoiceHeaderEntity`, `InvoiceLineEntity`, `StockMovementEntity` (`movementType`, `refDocId`), `PartyTransactionEntity` (`entryType`, `amountDeltaPaise`, `paymentMode`, `refDocId`, `createdAt`). Constant `DEFAULT_SHOP_ID`. |
| `pos/data/local/dao/ShopPartyAndLedgerDao.kt` | Party/ledger/invoice/movement queries (`observeAllParties`, `observeAllLedgerEntries`, `getPartyById`, …). |
| `pos/data/local/migrations/Migration2To3.kt` | Pattern for a migration class; calls `IntegrityChecker.findViolations(db)` and `OutboxRebuilder.enqueueEverything(db, now)`. |
| `pos/data/local/IntegrityChecker.kt` | Invariants I1–I5 as SQL. |
| `pos/data/local/OutboxRebuilder.kt` | `enqueueEverything(db, now)`. |
| `pos/data/command/CommandSupport.kt` | `CommandEnvironment` (`shopId()`, `actor()`, `now()`, `newId()`), `CommandException`, `OutboxDocType`, `OutboxOp`, `MovementType`, `LedgerEntryType` (`OPENING`, `CREDIT_BILL`, `PAYMENT_IN`, `PAYMENT_OUT`, `CREDIT_NOTE`, `ADJUSTMENT`). |
| `pos/data/command/LedgerWriter.kt` | `runIdempotent`, `localDevice()`, `appendMovement(skuId, deltaMilli, movementType, refDocId, note, device, enqueue)`, `appendLedgerEntry(partyId, deltaPaise, entryType, paymentMode, refDocId, note, device, enqueue)`, `enqueue(docType, docId, op)`. |
| `pos/data/command/StockInCommands.kt` | `StockInRow`, `StockInRequest(actionId, rows, supplierPartyId, newSupplierName, billTotalPaise, amountPaidPaise)`, `StockInResult`, `StockInStep`, `receiveScannedStock`. |
| `pos/data/command/KhataCommands.kt` | `createParty(actionId, name, phone, partyType, gstin, openingBalancePaise)`, `recordPayment(actionId, partyId, amountPaise, paymentMode, direction: PaymentDirection, note)` — `PaymentDirection.OUT` = money paid **to** the party (balance goes **up**). |
| `pos/core/ledger/LedgerPrimitives.kt` | `CalendarParts.of(epochMs)` → `year`, `month` (1–12), `day`. |
| `pos/data/repository/LpsRepository.kt` | Flows (`partiesFlow`, `invoiceHeadersFlow`, `allLedgerEntriesFlow`, …), `receiveScannedStock`, `addOrUpdateParty`, `recordPartyPaymentReceived`. |
| `pos/ui/MainViewModel.kt` | Exposes repository flows as `StateFlow`s; `launchSafe(titleRes) { … }`. |
| `pos/ui/screens/stockin/StockInViewModel.kt`, `StockInReviewScreen.kt`, `StockInEntrySheet.kt` | Stock-in review state/events (`SetSupplier`, `SetNewSupplier`, `SetBillTotal`, `SetPaid`, `Save`, `PickVariant`, …), supplier dropdown, entry sheet (Scan / Pick photo / Add item manually). |
| `pos/ui/screens/khata/PartiesKhataScreen.kt` | Customer khata list, add-party dialog, receive dialog, statement dialog. |
| `pos/ui/screens/sales/SaleHistoryScreen.kt`, `GallaSummary.kt` | Bills list; `computeGallaBreakdown(...)` rules for Cash/UPI/Udhaar — the calendar must use the **same** rules. |
| `pos/MainActivity.kt` | Tab content (`AppTab.BILLS -> SaleHistoryScreen(...)`, `AppTab.KHATA -> PartiesKhataScreen(...)`), overlays (checkout, stock-in review), `requestToggleSeller`. |
| Tests | `data/command/StockInCommandsTest.kt`, `KhataCommandsTest.kt`, `data/local/IntegrityCheckerTest.kt`, `data/local/migrations/Migration2To3Test.kt` (pattern for `MigrationTestHelper`), `ui/*Test.kt` (Compose + Robolectric pattern, `@Config(qualifiers = "w360dp-h800dp")`). |

---

## Task 1 — Schema v4: purchase bills (entities, DAO, migration, invariants)

### 1.1 Entities — add to `pos/data/local/entity/ShopLedgerEntities.kt`

```kotlin
/** Immutable. One row per stock-in save; id = the stock-in purchaseId (= refDocId of its PURCHASE movements). */
@Immutable
@Entity(
    tableName = "purchase_bills",
    indices = [
        Index(value = ["shopId", "supplierPartyId"]),
        Index(value = ["shopId", "createdAt"]),
        Index(value = ["shopId", "yearInt", "monthInt"])
    ]
)
data class PurchaseBillEntity(
    @PrimaryKey val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    /** null = stock-in without a distributor. */
    val supplierPartyId: String? = null,
    val supplierNameSnapshot: String = "",
    val billNumber: String = "",
    /** Amount on the distributor's bill (may include tax/rounding). */
    val totalPaise: Long,
    /** Σ line totals (qty × cost). */
    val linesTotalPaise: Long,
    val paidPaise: Long,
    val duePaise: Long,
    /** "CASH" or "UPI" for the paid part; "CREDIT" when nothing was paid now. */
    val paymentMode: String = "CASH",
    val lineCount: Int,
    val createdAt: Long,
    val yearInt: Int,
    val monthInt: Int,
    val dayInt: Int,
    /** 1 = rebuilt by the v4 migration from an older stock-in (costs unknown). */
    val isLegacy: Boolean = false,
    val deviceId: String = "",
    val createdByUid: String = ""
)

@Immutable
@Entity(
    tableName = "purchase_bill_lines",
    foreignKeys = [
        ForeignKey(entity = PurchaseBillEntity::class, parentColumns = ["id"], childColumns = ["purchaseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["purchaseId"]), Index(value = ["shopId", "skuId"])]
)
data class PurchaseBillLineEntity(
    @PrimaryKey val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val purchaseId: String,
    val skuId: String,
    val productId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String = "",
    val unitSnapshot: String = "pcs",
    val qtyMilliUnits: Long,
    val unitCostPaise: Long,
    val lineTotalPaise: Long
)
```

### 1.2 DAO — create `pos/data/local/dao/PurchaseDao.kt`

```kotlin
data class SupplierItemSummary(
    val skuId: String,
    val productId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val unitSnapshot: String,
    val totalQtyMilli: Long,
    val lastUnitCostPaise: Long,
    val lastBoughtAt: Long,
    val billCount: Int
)

data class LastPurchaseInfo(
    val supplierPartyId: String?,
    val supplierNameSnapshot: String,
    val unitCostPaise: Long,
    val createdAt: Long,
    val isLegacy: Boolean
)

@Dao
interface PurchaseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBill(bill: PurchaseBillEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLines(lines: List<PurchaseBillLineEntity>)

    @Query("SELECT * FROM purchase_bills ORDER BY createdAt DESC")
    fun observeAllBills(): Flow<List<PurchaseBillEntity>>

    @Query("SELECT * FROM purchase_bills WHERE supplierPartyId = :partyId ORDER BY createdAt DESC")
    fun observeBillsForSupplier(partyId: String): Flow<List<PurchaseBillEntity>>

    @Query("SELECT * FROM purchase_bills WHERE id = :id LIMIT 1")
    suspend fun getBill(id: String): PurchaseBillEntity?

    @Query("SELECT * FROM purchase_bill_lines WHERE purchaseId = :purchaseId ORDER BY itemNameSnapshot")
    suspend fun getLines(purchaseId: String): List<PurchaseBillLineEntity>

    @Query(
        "SELECT l.skuId AS skuId, l.productId AS productId, l.itemNameSnapshot AS itemNameSnapshot, " +
            "l.variantSummarySnapshot AS variantSummarySnapshot, l.unitSnapshot AS unitSnapshot, " +
            "SUM(l.qtyMilliUnits) AS totalQtyMilli, " +
            "(SELECT l2.unitCostPaise FROM purchase_bill_lines l2 JOIN purchase_bills b2 ON b2.id = l2.purchaseId " +
            "  WHERE l2.skuId = l.skuId AND b2.supplierPartyId = :partyId ORDER BY b2.createdAt DESC LIMIT 1) AS lastUnitCostPaise, " +
            "MAX(b.createdAt) AS lastBoughtAt, COUNT(DISTINCT b.id) AS billCount " +
            "FROM purchase_bill_lines l JOIN purchase_bills b ON b.id = l.purchaseId " +
            "WHERE b.supplierPartyId = :partyId GROUP BY l.skuId ORDER BY lastBoughtAt DESC"
    )
    fun observeItemsForSupplier(partyId: String): Flow<List<SupplierItemSummary>>

    @Query(
        "SELECT b.supplierPartyId AS supplierPartyId, b.supplierNameSnapshot AS supplierNameSnapshot, " +
            "l.unitCostPaise AS unitCostPaise, b.createdAt AS createdAt, b.isLegacy AS isLegacy " +
            "FROM purchase_bill_lines l JOIN purchase_bills b ON b.id = l.purchaseId " +
            "WHERE l.skuId = :skuId ORDER BY b.createdAt DESC LIMIT 1"
    )
    suspend fun lastPurchaseForSku(skuId: String): LastPurchaseInfo?

    @Query("SELECT * FROM purchase_bills")
    suspend fun getAllBills(): List<PurchaseBillEntity>
}
```
Register in `LpsDatabase`: add both entities to `entities = [...]`, add `abstract fun purchaseDao(): PurchaseDao`, set `SCHEMA_VERSION = 4`.

### 1.3 Generate the v4 schema JSON

Run `.\gradlew.bat assembleDebug --console=plain > build\assemble.log 2>&1`. KSP writes `app/schemas/com.shopledger.pos.data.local.LpsDatabase/4.json`. Open it and find the `createSql` of `purchase_bills` and `purchase_bill_lines` and every index `createSql` for them. **Copy those exact strings** into the migration below (replace `` `${TABLE_NAME}` `` with the table name). Commit `4.json`.

### 1.4 Migration — create `pos/data/local/migrations/Migration3To4.kt`

```kotlin
class Migration3To4(private val clock: () -> Long = { System.currentTimeMillis() }) : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = clock()
        // 1. Tables + indexes: EXACT createSql strings copied from schemas/4.json.
        db.execSQL("CREATE TABLE IF NOT EXISTS `purchase_bills` (...copied...)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_bills_shopId_supplierPartyId` ON `purchase_bills` (`shopId`, `supplierPartyId`)")
        // ...every index from 4.json for both tables...
        db.execSQL("CREATE TABLE IF NOT EXISTS `purchase_bill_lines` (...copied...)")
        // ...its indexes...

        // 2. Rebuild bills for older stock-ins (one per distinct refDocId of PURCHASE movements).
        db.execSQL(
            "INSERT INTO purchase_bills (id, shopId, supplierPartyId, supplierNameSnapshot, billNumber, totalPaise, linesTotalPaise, " +
                "paidPaise, duePaise, paymentMode, lineCount, createdAt, yearInt, monthInt, dayInt, isLegacy, deviceId, createdByUid) " +
                "SELECT m.refDocId, MIN(m.shopId), " +
                "(SELECT e.partyId FROM party_ledger_entries e WHERE e.refDocId = m.refDocId AND e.entryType = 'CREDIT_BILL' LIMIT 1), " +
                "COALESCE((SELECT p.name FROM parties p WHERE p.id = (SELECT e2.partyId FROM party_ledger_entries e2 WHERE e2.refDocId = m.refDocId AND e2.entryType = 'CREDIT_BILL' LIMIT 1)), ''), " +
                "'', " +
                "COALESCE((SELECT -SUM(e3.amountDeltaPaise) FROM party_ledger_entries e3 WHERE e3.refDocId = m.refDocId AND e3.entryType = 'CREDIT_BILL'), 0), " +
                "0, 0, " +
                "COALESCE((SELECT -SUM(e4.amountDeltaPaise) FROM party_ledger_entries e4 WHERE e4.refDocId = m.refDocId AND e4.entryType = 'CREDIT_BILL'), 0), " +
                "'CREDIT', COUNT(*), MIN(m.createdAt), " +
                "CAST(strftime('%Y', MIN(m.createdAt) / 1000, 'unixepoch', 'localtime') AS INTEGER), " +
                "CAST(strftime('%m', MIN(m.createdAt) / 1000, 'unixepoch', 'localtime') AS INTEGER), " +
                "CAST(strftime('%d', MIN(m.createdAt) / 1000, 'unixepoch', 'localtime') AS INTEGER), " +
                "1, MIN(m.deviceId), MIN(m.createdByUid) " +
                "FROM stock_movements m WHERE m.movementType = 'PURCHASE' AND m.refDocId != '' GROUP BY m.refDocId"
        )
        db.execSQL(
            "INSERT INTO purchase_bill_lines (id, shopId, purchaseId, skuId, productId, itemNameSnapshot, variantSummarySnapshot, " +
                "unitSnapshot, qtyMilliUnits, unitCostPaise, lineTotalPaise) " +
                "SELECT m.movementId, m.shopId, m.refDocId, m.skuId, COALESCE(v.itemId, ''), COALESCE(i.displayName, ''), '', " +
                "COALESCE(v.unit, 'pcs'), m.deltaMilliUnits, 0, 0 " +
                "FROM stock_movements m LEFT JOIN item_variants v ON v.id = m.skuId LEFT JOIN inventory_items i ON i.id = v.itemId " +
                "WHERE m.movementType = 'PURCHASE' AND m.refDocId != ''"
        )
        // 3. Outbox for the rebuilt bills.
        OutboxRebuilder.enqueuePurchases(db, now)
        // 4. Verify.
        val violations = IntegrityChecker.findViolations(db, includePurchases = true)
        check(violations.isEmpty()) { "v4 upgrade check failed: ${violations.take(5)}" }
    }
}
```
Column names used above were checked against `3.json` (see 1.5).

Register: `addMigrations(LpsDatabase.MIGRATION_1_2, Migration2To3(migrationContext), Migration3To4())`.

### 1.5 IntegrityChecker and OutboxRebuilder — avoid the v2→v3 trap

`Migration2To3` calls `IntegrityChecker.findViolations(db)` and `OutboxRebuilder.enqueueEverything(db, now)` while the database is still **v3** (no purchase tables). If you add purchase queries to those unconditionally, upgrading a v2 install crashes. So:
- Change the signature to `fun findViolations(db: SupportSQLiteDatabase, includePurchases: Boolean = true): List<String>` and add, **only when `includePurchases`**:
```kotlin
collect(
    "I6 purchase lines differ from movements",
    "SELECT b.id FROM purchase_bills b WHERE " +
        "(SELECT COALESCE(SUM(l.qtyMilliUnits), 0) FROM purchase_bill_lines l WHERE l.purchaseId = b.id) != " +
        "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.refDocId = b.id AND m.movementType = 'PURCHASE')"
)
collect(
    "I7 purchase bill amounts inconsistent",
    "SELECT b.id FROM purchase_bills b WHERE b.paidPaise + b.duePaise != b.totalPaise OR b.duePaise < 0 OR b.paidPaise < 0"
)
```
- In `Migration2To3.kt` change the call to `IntegrityChecker.findViolations(db, includePurchases = false)`.
- Add to `OutboxDocType`: `const val PURCHASE = "PURCHASE"`.
- In `OutboxRebuilder` add `fun enqueuePurchases(db: SupportSQLiteDatabase, now: Long)` = `INSERT INTO sync_outbox (docType, docId, op, createdAt, attempts, lastError) SELECT 'PURCHASE', id, 'APPEND', ?, 0, '' FROM purchase_bills`. Do **not** call it from `enqueueEverything` (that runs inside the v2→v3 migration). Then `grep -rn "enqueueEverything" app/src/main/java`: for every caller that runs on a **v4** database (not `Migration2To3`), add a call to `enqueuePurchases` right after it.
- `grep -rn "findViolations(" app/src/main/java` — callers today: `Migration2To3` (→ `false`), `PendingRestore.kt` (~line 274) and `ShopLedgerApp.kt` (~line 117). `PendingRestore` can restore a backup taken **before** v4 (Room migrates it later), so there add a helper `private fun hasPurchaseTables(db) = db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='purchase_bills'").use { it.moveToFirst() }` and pass `includePurchases = hasPurchaseTables(sqlite)`; also call `OutboxRebuilder.enqueuePurchases(sqlite, now())` after `enqueueEverything` (~line 269) **only when** `hasPurchaseTables(sqlite)`. `ShopLedgerApp` runs on the opened v4 database: keep the default (`true`).
- Verified column names (from `3.json`): `inventory_items.displayName`, `item_variants.itemId`, `item_variants.unit`, `sync_outbox(seq, docType, docId, op, createdAt, attempts, lastError)` (`seq` is auto-generated, do not insert it).

### 1.6 Tests first

1. `data/local/migrations/Migration3To4Test.kt` (pattern: `Migration2To3Test`, `MigrationTestHelper`, `helper.createDatabase(TEST_DB, 3)`, insert raw rows with `execSQL`, `helper.runMigrationsAndValidate(TEST_DB, 4, true, Migration3To4(clock = { 9_000L }))`):
   - `oldStockInWithSupplierBecomesLegacyBill` — v3 rows: a party `s1` (SUPPLIER, balance −15000), item `i1`/variant `v1` (unit `pcs`), movements `m1` (+5000, PURCHASE, refDocId `p1`) and `m2` (+2000, PURCHASE, `p1`) on two variants, ledger `e1` (partyId `s1`, CREDIT_BILL, −15000, refDocId `p1`), plus the variants/items and movements that keep I1 true. After migration: one bill `p1` with `supplierPartyId = 's1'`, `supplierNameSnapshot` = the party name, `totalPaise = 15000`, `duePaise = 15000`, `paidPaise = 0`, `isLegacy = 1`, `lineCount = 2`; two lines with qty 5000 and 2000; `IntegrityChecker.findViolations(db)` empty.
   - `oldStockInWithoutSupplierBecomesBillWithNoSupplier` — PURCHASE movement `p2` with no ledger row → bill `p2`, `supplierPartyId IS NULL`, totals 0.
   - `nonPurchaseMovementsAreIgnored` — SALE/OPENING movements create no bills.
   - `outboxHasOneRowPerBill`.
2. `data/local/IntegrityCheckerTest.kt`: add `purchaseLineMismatchIsReported` (bill line qty 3000 vs movement 2000 → an "I6" violation) and `purchaseAmountsMustAddUp` (paid 100 + due 100 ≠ total 300 → "I7").
3. Existing `Migration2To3Test` must still pass unchanged.

Run → fail → implement → green. `assembleDebug` succeeds.

**Commit:** `feat(db): v4 purchase bills and lines with migration of older stock-ins`

---

## Task 2 — `StockInCommands` writes the purchase bill and the full distributor account

### 2.1 Request/steps changes (`pos/data/command/StockInCommands.kt`)

- `StockInRequest` gets two fields with defaults (so existing callers compile):
```kotlin
/** "CASH" or "UPI": how the paid-now part was paid. Ignored when amountPaidPaise == 0. */
val paidMode: String = "CASH",
/** Distributor's bill/invoice number, optional. */
val billNumber: String = ""
```
- `StockInStep`: add `const val PURCHASE_BILL = "PURCHASE_BILL"` and put it in `ALL` **after `MOVEMENTS` and before `LEDGER`**.
- In `validate`: `paidMode` trimmed upper-case must be `CASH` or `UPI` → else `CommandException.Validation(R.string.msg_paid_mode_unknown)`; `billNumber.trim().length <= 40` → else `Validation(R.string.msg_bill_number_too_long)`.

### 2.2 Writing the bill (inside the existing `runIdempotent` block)

Keep a list of the `ItemVariantEntity` per row while you append movements (re-read with `inventory.getVariantById(targets[index])!!` as the code already does) and the item name via `inventory.getItemById(variant.itemId)` (`InventoryDao.getItemById(itemId)` → `InventoryItemEntity.displayName`). After `faults.at(StockInStep.MOVEMENTS)`:

```kotlin
val parts = CalendarParts.of(env.now())
val lines = request.rows.mapIndexed { index, row ->
    val variant = inventory.getVariantById(targets[index])!!
    val itemName = /* item displayName for variant.itemId */
    PurchaseBillLineEntity(
        id = env.newId(),
        shopId = env.shopId(),
        purchaseId = purchaseId,
        skuId = variant.id,
        productId = variant.itemId,
        itemNameSnapshot = itemName,
        variantSummarySnapshot = variant.buildVariantSummary(),
        unitSnapshot = variant.unit,
        qtyMilliUnits = row.qtyMilli,
        unitCostPaise = row.costPaise,
        lineTotalPaise = (row.qtyMilli * row.costPaise + 500) / 1000
    )
}
val linesTotal = lines.sumOf { it.lineTotalPaise }
val total = if (request.billTotalPaise > 0L) request.billTotalPaise else linesTotal
val paid = request.amountPaidPaise.coerceAtMost(total)
val supplierName = supplierId?.let { parties.getPartyById(it)?.name }.orEmpty()
purchases.insertBill(
    PurchaseBillEntity(
        id = purchaseId, shopId = env.shopId(), supplierPartyId = supplierId, supplierNameSnapshot = supplierName,
        billNumber = request.billNumber.trim(), totalPaise = total, linesTotalPaise = linesTotal,
        paidPaise = paid, duePaise = total - paid,
        paymentMode = if (paid > 0L) request.paidMode.trim().uppercase(Locale.ROOT) else "CREDIT",
        lineCount = lines.size, createdAt = env.now(),
        yearInt = parts.year, monthInt = parts.month, dayInt = parts.day,
        isLegacy = false, deviceId = device.deviceId, createdByUid = env.actor().uid
    )
)
purchases.insertLines(lines)
writer.enqueue(OutboxDocType.PURCHASE, purchaseId, OutboxOp.APPEND)
faults.at(StockInStep.PURCHASE_BILL)
```
(`private val purchases = db.purchaseDao()` as a class field.) If `request.billTotalPaise == 0` and there is no distributor, `paid` = `request.amountPaidPaise` coerced to `total`; validation already guarantees `paid <= billTotal` when a total was given.

### 2.3 Ledger (replace the current LEDGER block)

```kotlin
var recordedDue = 0L
if (supplierId != null) {
    if (total > 0L) {
        writer.appendLedgerEntry(supplierId, -total, LedgerEntryType.CREDIT_BILL, "CREDIT", purchaseId,
            "Purchase bill", device, enqueue = true)
    }
    if (paid > 0L) {
        writer.appendLedgerEntry(supplierId, paid, LedgerEntryType.PAYMENT_OUT,
            request.paidMode.trim().uppercase(Locale.ROOT), purchaseId, "Paid with bill", device, enqueue = true)
    }
    recordedDue = total - paid
}
faults.at(StockInStep.LEDGER)
```
`StockInResult.supplierDuePaise = recordedDue` (unchanged meaning). Replay format unchanged.

### 2.4 Tests (`data/command/StockInCommandsTest.kt`)

Update / add:
1. `mixedRequest_savesEverythingAtomically` — keep all asserts; the supplier balance is still −15000. Add: one `purchase_bills` row with `id = result.purchaseId`, `supplierPartyId = supplier.id`, `totalPaise`, `paidPaise`, `duePaise = 15000`, `lineCount = 2`; two `purchase_bill_lines` with the right qty/cost/lineTotal. The existing assertion "1 CREDIT_BILL" stays true. If `mixed(...)` pays something now, also assert 1 `PAYMENT_OUT` row with that amount; if it pays nothing, assert 0.
2. `paidPartNowShowsAsSeparatePaymentOnTheAccount` — total 10000, paid 4000, `paidMode = "UPI"`, existing supplier → ledger rows: CREDIT_BILL −10000 and PAYMENT_OUT +4000 mode UPI (both `refDocId = purchaseId`); balance −6000; bill `paymentMode = "UPI"`.
3. `noSupplierStillRecordsBillWithoutLedger` — no supplier, total = paid → bill with `supplierPartyId == null`, no ledger rows.
4. `billTotalZeroUsesLinesTotal` — `billTotalPaise = 0`, rows 2.5 qty × ₹40 → `totalPaise = 10000`, `linesTotalPaise = 10000`.
5. `unknownPaidModeIsRejected` — `paidMode = "CHEQUE"` with paid > 0 → `CommandException.Validation`, nothing written.
6. The fault-injection test that loops over `StockInStep.ALL` must now also cover `PURCHASE_BILL` (it does automatically if it iterates `ALL`; check) — after a fault at any step, `purchase_bills` and `purchase_bill_lines` are empty.
7. The replay test: second call writes no extra bill/lines.
8. Every test ends with `IntegrityChecker.findViolations(...)` empty where the existing tests already do.

Also run `LedgerInvariantPropertyTest`; if it generates stock-ins, the new invariants must hold.

**Commit:** `feat(stockin): record purchase bills and show bill + payment on the distributor account`

---

## Task 3 — Repository + ViewModel access

### 3.1 `LpsRepository`

Add:
```kotlin
private val purchaseDao = database.purchaseDao()
val purchaseBillsFlow: Flow<List<PurchaseBillEntity>> = purchaseDao.observeAllBills()
fun billsForSupplier(partyId: String): Flow<List<PurchaseBillEntity>> = purchaseDao.observeBillsForSupplier(partyId)
fun itemsForSupplier(partyId: String): Flow<List<SupplierItemSummary>> = purchaseDao.observeItemsForSupplier(partyId)
fun ledgerForParty(partyId: String): Flow<List<PartyTransactionEntity>> = shopDao.observePartyLedger(partyId)
suspend fun purchaseLines(purchaseId: String): List<PurchaseBillLineEntity> = withContext(Dispatchers.IO) { purchaseDao.getLines(purchaseId) }
suspend fun lastPurchaseForSku(skuId: String): LastPurchaseInfo? = withContext(Dispatchers.IO) { purchaseDao.lastPurchaseForSku(skuId) }

/** Money paid to a distributor (balance goes up toward 0). */
suspend fun recordPaymentToDistributor(partyId: String, amountPaise: Long, paymentMode: String, note: String) {
    khata.recordPayment(newActionId(), partyId, amountPaise, paymentMode, PaymentDirection.OUT, note)
}
```
(`shopDao` is the existing `ShopPartyAndLedgerDao` field in `LpsRepository`.) Distributor creation reuses `addOrUpdateParty(name = …, phone = …, partyType = "SUPPLIER", gstin = …, openingBalancePaise = -oweAmountPaise)`.

### 3.2 `MainViewModel`

- `val purchaseBillsState: StateFlow<List<PurchaseBillEntity>> = repository.purchaseBillsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())` (follow how `partiesFlow` is exposed).
- `fun recordPaymentToDistributor(partyId: String, amountPaise: Long, mode: String, note: String)` → `launchSafe(R.string.err_payment_not_saved_title) { repository.recordPaymentToDistributor(...) }`.
- `fun addDistributor(name: String, phone: String, gstin: String, alreadyOwePaise: Long)` → `launchSafe(R.string.err_party_not_saved_title) { repository.addOrUpdateParty(name = name, phone = phone, partyType = "SUPPLIER", gstin = gstin, openingBalancePaise = -alreadyOwePaise) }`.
- `fun billsForSupplier(id)`, `itemsForSupplier(id)`, `ledgerForParty(id)` pass-throughs returning `Flow`s (screens collect them with `collectAsState(initial = emptyList())`).
- `suspend fun purchaseLines(id)` and `suspend fun lastPurchaseForSku(id)` pass-throughs.

### 3.3 Tests

`data/command/KhataCommandsTest.kt`: add `paymentToDistributorRaisesBalanceTowardZero` — create SUPPLIER with opening −10000, `recordPayment(..., direction = PaymentDirection.OUT, amount 4000, "CASH")` → balance −6000, entry type `PAYMENT_OUT`, delta +4000.

**Commit:** `feat(khata): repository and view-model access to distributor bills, items and payments`

---

## Task 4 — Stock-in review: Nagad/UPI, bill number, distributor wording, manual bill entry

### 4.1 State/events (`StockInViewModel.kt`)

- `StockInUiState` add `val paidMode: String = "CASH"` and `val billNumber: String = ""`; add both to `StockInSnapshot` (saved-state) and to its save/restore code.
- Events: `data class SetPaidMode(val mode: String) : StockInEvent` (`"CASH"`/`"UPI"`), `data class SetBillNumber(val text: String) : StockInEvent` (keep at most 40 chars), `data class AddRow(val itemId: String, val variantId: String) : StockInEvent`.
- `buildRequest` passes `paidMode = paidMode`, `billNumber = billNumber.trim()`.
- `AddRow`: append a new MATCHED `ReviewRow` for that variant: name from the inventory item, qty 1 unit (1000 milli), cost = the variant's `purchasePricePaise`, sell = its `sellingPricePaise`, unit = variant unit, new unique key from the existing `nextKey` counter **under the existing lock**. If the variant is already in a row, do not add a second row; increase that row's qty by 1 unit instead.
- Add `fun openManual()` that opens an empty review (same as how `open…` creates state today, with zero rows, a fresh actionId, no photo, `billTotalPaise = null`). The existing Save block reasons already block saving with zero rows (`msg_add_one_item` / the empty-rows reason) — check; if not, add a `BlockKind` for "no rows".

### 4.2 Review screen (`StockInReviewScreen.kt`)

- In the expanded header (supplier/total/paid form): rename visible labels to distributor strings (`review_supplier` etc. keep keys, change English text to "Distributor" and Hindi to "डिस्ट्रीब्यूटर" — see section 12).
- Under "Paid", when `amountPaidPaise > 0`: `SegmentedChoice(options = listOf(stringResource(R.string.pay_nagad), stringResource(R.string.pay_upi)), selectedIndex = if (state.paidMode == "UPI") 1 else 0, onSelect = { onEvent(StockInEvent.SetPaidMode(if (it == 1) "UPI" else "CASH")) })`.
- An `OutlinedTextField` "Bill number (optional)" bound to `billNumber` (singleLine).
- The one-line summary shows the bill number when present.
- Add an **"Add item"** button (`SecondaryActionButton`, icon `Icons.Filled.Add`) at the end of the rows list. It opens the **existing item picker** used by `PickVariant` (find it in this file; reuse it) in a mode that sends `StockInEvent.AddRow(itemId, variantId)` instead of `PickVariant`.
- When there are zero rows show `EmptyState(title = stringResource(R.string.review_empty_title), message = stringResource(R.string.review_empty_message))` above the Add item button.

### 4.3 Entry sheet (`StockInEntrySheet.kt`) + `MainActivity`

- Add a fourth option **"Enter bill by hand"** (`stock_in_manual_bill`), seller-gated exactly like Scan/Pick photo (`if (!isSellerUnlocked) onRequestUnlock() else …`), and with the same "Discard the unsaved review?" confirmation when `hasUnsavedReview`. New callback parameter `onManualBill: () -> Unit`. In `MainActivity`: `onManualBill = { stockInViewModel.openManual(); showStockInSheet = false }`.

### 4.4 Tests

- `StockInViewModelTest`: `paidModeReachesTheRequest` (UPI → request.paidMode "UPI"); `billNumberReachesTheRequest`; `addRowAddsMatchedRowWithVariantCost`; `addRowTwiceIncreasesQty`; `manualReviewStartsEmptyAndCannotSave`; `snapshotRestoresPaidModeAndBillNumber` (use the existing restore-test pattern).
- `StockInReviewScreenTest`: `nagadUpiChoiceAppearsWhenPaidIsAboveZero`; `addItemButtonOpensPicker`; `emptyReviewShowsEmptyState`.
- `StockInEntrySheetTest`: `manualBillNeedsSellerUnlock`; `manualBillCallsCallbackWhenUnlocked`.

**Commit:** `feat(stockin): Nagad/UPI and bill number on stock-in, enter a distributor bill by hand`

---

## Task 5 — Khata: Customers · Distributors

### 5.1 Split

Create `pos/ui/screens/khata/KhataTab.kt`:
```kotlin
@Composable
fun KhataTab(
    parties: List<PartyEntity>,
    ledgerEntries: List<PartyTransactionEntity>,
    purchaseBills: List<PurchaseBillEntity>,
    isSellerUnlocked: Boolean,
    onRequestUnlock: () -> Unit,
    onAddParty: (name: String, phone: String, partyType: String, openingBalancePaise: Long) -> Unit,
    onRecordPaymentReceived: (partyId: String, amountPaise: Long, paymentMode: String, note: String) -> Unit,
    onAddDistributor: (name: String, phone: String, gstin: String, alreadyOwePaise: Long) -> Unit,
    onPayDistributor: (partyId: String, amountPaise: Long, mode: String, note: String) -> Unit,
    onOpenDistributor: (partyId: String) -> Unit,
    modifier: Modifier = Modifier,
)
```
- `var side by rememberSaveable { mutableIntStateOf(0) }` (0 customers, 1 distributors).
- Top: `SegmentedChoice(options = listOf(stringResource(R.string.khata_side_customers), stringResource(R.string.khata_side_distributors)), selectedIndex = side, onSelect = { side = it })` with horizontal padding `spacingL`, vertical `spacingS`.
- `side == 0` → existing `PartiesKhataScreen(parties = parties.filter { it.partyType == "CUSTOMER" || it.partyType == "BOTH" }, ledgerEntries = ledgerEntries, onAddParty, onRecordPaymentReceived)`.
- `side == 1` → if `!isSellerUnlocked`: `EmptyState(title = stringResource(R.string.distributors_locked_title), message = stringResource(R.string.distributors_locked_message))` + `PrimaryActionButton(text = stringResource(R.string.distributors_unlock), onClick = onRequestUnlock, icon = Icons.Filled.LockOpen)`. Else → `DistributorsScreen(...)` (5.2).
- `MainActivity`: `AppTab.KHATA -> KhataTab(...)` wiring the new ViewModel functions; collect `purchaseBillsState`. Keep the KHATA tab badge counting **only customers** with balance > 0 (change `duePartyCount` to filter `partyType != "SUPPLIER"`).

### 5.2 `DistributorsScreen` — create `pos/ui/screens/khata/DistributorsScreen.kt`

Parameters: `distributors: List<PartyEntity>` (already filtered `SUPPLIER`/`BOTH`), `purchaseBills: List<PurchaseBillEntity>`, `onAddDistributor`, `onPayDistributor`, `onOpenDistributor`.

Layout (`Column`):
1. Summary `SectionCard`: "You owe in total" = Σ over distributors of `(-currentBalancePaise).coerceAtLeast(0)` (`titleLarge`, error colour when > 0); and "Distributors: N".
2. Search field (same style as the customer list).
3. `LazyColumn` of distributor cards sorted by amount owed descending, then name. Each card (whole card clickable → `onOpenDistributor(id)`, min height 72 dp):
   - name (`titleMedium`), phone or "No phone" (`bodyMedium`, onSurfaceVariant),
   - last purchase line: from `purchaseBills.filter { it.supplierPartyId == id }.maxByOrNull { it.createdAt }` → `stringResource(R.string.distributor_last_bill, date, amount)` or `distributor_no_bills`,
   - right side: owed amount: if `balance < 0` → text `stringResource(R.string.distributor_you_owe, formatPaise(-balance))` in error colour + `StatusChip(distributor_badge_owe, ChipTone.WARNING)`; if `balance > 0` → `distributor_they_owe` (advance) + `ChipTone.INFO`; else `StatusChip(khata_badge_settled, ChipTone.SUCCESS)`,
   - when `balance < 0`: `PrimaryActionButton(text = stringResource(R.string.distributor_pay), icon = Icons.Filled.Payments, onClick = { payTarget = party })`.
4. Floating/bottom `PrimaryActionButton(distributor_add, icon PersonAdd)` → add dialog.
5. `EmptyState(distributors_empty_title, distributors_empty_message)` when the list is empty.

**Add distributor dialog** (`AlertDialog`): name (required), phone (digits, ≤ 15), GSTIN (optional, ≤ 15, upper-case), "Amount you already owe (₹)" via `MoneyField` (optional, ≥ 0). Confirm disabled while the name is blank. Calls `onAddDistributor(name.trim(), phone.trim(), gstin.trim(), owePaise ?: 0L)`.

**Pay distributor dialog** (`PayDistributorDialog`, put it in its own file `PayDistributorDialog.kt` so Task 6 can reuse it): title `distributor_pay_title` with the name; amount `MoneyField` prefilled with what is owed; `SegmentedChoice` Nagad / UPI; note field (optional, ≤ 60); confirm disabled unless amount > 0; if amount > owed show a warning line `distributor_pay_more_than_owed` (still allowed — becomes an advance). Confirm calls `onPayDistributor(id, amount, "CASH"/"UPI", note.trim())`. Hold dialog state with `rememberSaveable`.

### 5.3 Tests — `ui/DistributorsScreenTest.kt` and `ui/KhataTabTest.kt`

- `customersSideHidesDistributors` — parties: customer A (+500), supplier S (−900) → customers side shows A only.
- `distributorsSideNeedsUnlock` — locked → unlock button shown; tap → `onRequestUnlock` called; no distributor names visible.
- `distributorCardShowsYouOwe` — S balance −90000 → text "You owe ₹900".
- `payDialogPrefillsOwedAndSendsUpi` — tap Pay on S → amount field shows "900"; choose UPI; confirm → `onPayDistributor("S", 90000, "UPI", "")`.
- `addDistributorSendsOweAmount` — fill name "Gupta Traders", owe 1500 → `onAddDistributor("Gupta Traders", "", "", 150000)`.
- `tappingCardOpensDistributor` — `onOpenDistributor("S")`.
- `emptyStateWhenNoDistributors`.

**Commit:** `feat(khata): Customers and Distributors sides with distributor list, add and pay`

---

## Task 6 — Distributor detail screen (account, bills, items)

Create `pos/ui/screens/khata/DistributorDetailScreen.kt` — a full-screen overlay opened from `MainActivity` like checkout: `var openDistributorId by rememberSaveable { mutableStateOf<String?>(null) }`, `BackHandler { openDistributorId = null }`, `Surface(Modifier.fillMaxSize().zIndex(9f))`. If seller mode becomes locked while it is open, close it (`LaunchedEffect(isSellerUnlocked) { if (!isSellerUnlocked) openDistributorId = null }`).

Stateless signature:
```kotlin
data class DistributorDetailState(
    val party: PartyEntity,
    val ledger: List<PartyTransactionEntity>,          // newest first
    val bills: List<PurchaseBillEntity>,               // newest first
    val items: List<SupplierItemSummary>,
)
sealed interface DistributorEvent {
    data object Close : DistributorEvent
    data object Pay : DistributorEvent
    data class OpenBill(val purchaseId: String) : DistributorEvent
    data object Call : DistributorEvent
    data object WhatsApp : DistributorEvent
}
@Composable fun DistributorDetailScreen(state: DistributorDetailState, onEvent: (DistributorEvent) -> Unit, modifier: Modifier = Modifier)
```
Layout:
1. `TopAppBar`: close icon (`cd_close`), title = party name, actions: Call (`Icons.Filled.Call`, only if phone not blank) and WhatsApp share (`Icons.Filled.Share`).
2. Header `SectionCard`: "You owe ₹X" / "Advance ₹X" / "Settled"; GSTIN if any; `PrimaryActionButton(distributor_pay)` (→ `Pay`) — shown always (an advance can be paid too).
3. `SegmentedChoice(listOf(distributor_tab_account, distributor_tab_bills, distributor_tab_items))`, `rememberSaveable` index.
4. **Account** tab: `LazyColumn` of ledger rows, newest first, each: date (dd MMM yyyy, `SimpleDateFormat("dd MMM yyyy", current locale)`), label by type — `CREDIT_BILL` → `distributor_entry_bill` (+ bill number if the linked bill has one: look up `bills.firstOrNull { it.id == entry.refDocId }`), `PAYMENT_OUT` → `distributor_entry_paid` + mode label (Nagad/UPI), `PAYMENT_IN` → `distributor_entry_refund`, `OPENING` → `khata_entry_opening` (existing key, check name), others → raw type; amount with sign shown as words, not ±: bills in error colour "Udhaar ₹X", payments in primary colour "Paid ₹X"; and a **running balance** column computed oldest→newest then displayed newest first (`balance after this entry`, shown as "Owe ₹X"/"Advance ₹X"/"₹0"). Tapping a bill row → `OpenBill(refDocId)`.
5. **Bills** tab: list of `PurchaseBillEntity`: date, bill number or `distributor_bill_no_number`, `pluralStringResource(R.plurals.distributor_bill_items, lineCount, lineCount)`, total, and a `StatusChip`: due > 0 → `distributor_bill_due` (WARNING) with amount; else `distributor_bill_paid` (SUCCESS). Legacy bills show `distributor_bill_legacy` small text. Tap → `OpenBill(id)`.
6. **Items** tab: list of `SupplierItemSummary`: item name + variant (`.variantLabel()` from `pos/ui/text/VariantLabel.kt`), total qty bought (`IndianCurrencyFormatter.formatQuantity(totalQtyMilli / 1000.0)` + unit), last cost (if `lastUnitCostPaise > 0`, else `distributor_cost_unknown`), last date, `billCount`. `EmptyState(distributor_items_empty_title, …)` when empty.

**Bill detail sheet** (`PurchaseBillSheet.kt`, `ModalBottomSheet`): header (distributor name, bill number, date), lines (name + variant, qty × cost = line total; legacy → `distributor_cost_unknown`), totals (lines total, bill total, paid now with mode, due). Lines are loaded in `MainActivity` with `LaunchedEffect(billId) { lines = viewModel.purchaseLines(billId) }`. This sheet is also used from the calendar (Task 9).

`MainActivity` wiring: state built from `viewModel.ledgerForParty(id).collectAsState(emptyList())`, `viewModel.billsForSupplier(id)…`, `viewModel.itemsForSupplier(id)…`, the party from `parties`. `Pay` opens `PayDistributorDialog` (from Task 5) → `viewModel.recordPaymentToDistributor`. `Call` → `Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone}"))`. `WhatsApp` → existing `ReceiptShareManager.shareTextToWhatsApp(context, text, phone)` with text `distributor_whatsapp_text` (shop name, amount owed).

Tests — `ui/DistributorDetailScreenTest.kt`:
- `accountShowsBillAndPaymentWithRunningBalance` — ledger: OPENING −10000 (day1), CREDIT_BILL −50000 (day2), PAYMENT_OUT +20000 UPI (day3) → rows show "Udhaar ₹500", "Paid ₹200 · UPI", balances "Owe ₹300" (newest), "Owe ₹500", "Owe ₹100".
- `billsTabShowsDueChip`.
- `itemsTabListsSuppliedItems` — 2 summaries → both names and qty shown.
- `legacyBillShowsCostUnknown`.
- `payButtonSendsPay`.
- `tappingBillRowOpensBill`.

**Commit:** `feat(khata): distributor detail with account, bills, items and bill sheet`

---

## Task 7 — "Last bought from" on the item editor

- `AddEditItemSheet` (manage mode only) gets a new optional parameter `lastPurchase: LastPurchaseInfo? = null`. When not null, show one line under the cost field: `stringResource(R.string.item_last_bought_from, supplierName ifBlank distributor_unknown, date, cost)`; when `isLegacy` or cost 0 use `item_last_bought_from_no_cost` (no cost). The editor already opens only when seller mode is unlocked.
- `MainActivity`: when `activeEditorMode` is `ManageItem(group, variantId)`, `val lastPurchase by produceState<LastPurchaseInfo?>(null, variantId) { value = viewModel.lastPurchaseForSku(variantId) }` and pass it.
- Test in `RestyledScreensTest` (or a new `ui/AddEditItemLastPurchaseTest.kt`): manage mode with `LastPurchaseInfo(supplierPartyId = "s", supplierNameSnapshot = "Gupta Traders", unitCostPaise = 4500, createdAt = <fixed>, isLegacy = false)` → text containing "Gupta Traders" and "₹45" is shown.

**Commit:** `feat(items): show the distributor an item was last bought from`

---

## Task 8 — Calendar numbers (pure Kotlin, fully tested)

Create `pos/ui/screens/sales/calendar/BusinessCalendar.kt`:

```kotlin
data class DaySummary(
    val day: Int,
    val saleCount: Int = 0,
    /** Net sales: SALE grand totals minus CREDIT_NOTE grand totals (credit notes are negative already: add them). */
    val salesPaise: Long = 0L,
    /** Nagad = cash received at sale time (CASH sales + the paid-now part of CREDIT sales + cash refunds negative). Same rules as computeGallaBreakdown. */
    val cashSalesPaise: Long = 0L,
    val upiSalesPaise: Long = 0L,
    /** Udhaar given to customers on this day (balanceDue of SALE and CREDIT_NOTE headers, same as Galla). */
    val udhaarGivenPaise: Long = 0L,
    /** Udhaar collected = PAYMENT_IN ledger entries (money received), split by mode. */
    val udhaarCollectedCashPaise: Long = 0L,
    val udhaarCollectedUpiPaise: Long = 0L,
    /** Stock-in: purchase bill totals created this day, and how many bills. */
    val stockInPaise: Long = 0L,
    val stockInBillCount: Int = 0,
    /** Stock-in left on distributor udhaar this day (Σ duePaise of bills). */
    val stockInOnUdhaarPaise: Long = 0L,
    /** Paid to distributors: PAYMENT_OUT ledger entries + paidPaise of bills with no distributor. */
    val paidToDistributorsCashPaise: Long = 0L,
    val paidToDistributorsUpiPaise: Long = 0L,
) {
    val udhaarCollectedPaise get() = udhaarCollectedCashPaise + udhaarCollectedUpiPaise
    val paidToDistributorsPaise get() = paidToDistributorsCashPaise + paidToDistributorsUpiPaise
    val nagadInPaise get() = cashSalesPaise + udhaarCollectedCashPaise
    val isEmpty get() = saleCount == 0 && salesPaise == 0L && udhaarGivenPaise == 0L && udhaarCollectedPaise == 0L &&
        stockInBillCount == 0 && paidToDistributorsPaise == 0L
}

data class MonthSummary(val year: Int, val month: Int, val days: Map<Int, DaySummary>, val total: DaySummary)

fun computeMonthSummary(
    year: Int,
    month: Int,
    invoices: List<InvoiceHeaderEntity>,
    ledgerEntries: List<PartyTransactionEntity>,
    purchaseBills: List<PurchaseBillEntity>,
    dayOf: (Long) -> CalendarParts = { CalendarParts.of(it) },
): MonthSummary
```
Rules (implement exactly; one pass over each list, accumulate into a `MutableMap<Int, DaySummary>` keyed by day):
1. **Invoices:** keep `yearInt == year && monthInt == month`. Mode = `paymentMode.trim().uppercase(Locale.ROOT)`.
   - `DocType.SALE`: `saleCount++`; `salesPaise += grandTotalPaise`; mode `UPI` → `upiSalesPaise += amountPaidPaise`; mode `CREDIT` → `udhaarGivenPaise += balanceDuePaise` and `cashSalesPaise += amountPaidPaise`; else → `cashSalesPaise += amountPaidPaise`.
   - `DocType.CREDIT_NOTE`: `salesPaise += grandTotalPaise` (negative); `udhaarGivenPaise += balanceDuePaise`; mode `UPI` → `upiSalesPaise += amountPaidPaise` else `cashSalesPaise += amountPaidPaise`.
   (These are the same rules as `computeGallaBreakdown` in `GallaSummary.kt` — re-read it and keep them identical.)
2. **Ledger entries:** day from `dayOf(createdAt)`; keep the month.
   - `PAYMENT_IN`: amount = `(-amountDeltaPaise).coerceAtLeast(0)`; UPI → `udhaarCollectedUpiPaise`, else cash.
   - `PAYMENT_OUT`: amount = `amountDeltaPaise.coerceAtLeast(0)`; UPI → `paidToDistributorsUpiPaise`, else cash.
   - others: ignored.
3. **Purchase bills:** keep `yearInt == year && monthInt == month`. `stockInPaise += totalPaise`, `stockInBillCount++`, `stockInOnUdhaarPaise += duePaise`; if `supplierPartyId == null && paidPaise > 0` add `paidPaise` to cash or UPI by `paymentMode` (bills **with** a distributor already have a `PAYMENT_OUT` ledger row — do not count twice).
4. `total` = field-wise sum of all days (`day = 0`).

Also in the same file:
```kotlin
/** "₹950", "₹12.4k", "₹1.2L", "₹3.1Cr" — for tiny calendar cells only. Negative keeps the minus sign. */
fun formatCompactRupees(paise: Long): String
/** Cells for a month grid starting on Sunday: leading nulls for blank cells, then 1..daysInMonth. */
fun monthGridCells(year: Int, month: Int): List<Int?>
```
`formatCompactRupees`: rupees = paise / 100 (integer); < 1,000 → `₹N`; < 1,00,000 → one decimal thousands `k` (drop `.0`); < 1,00,00,000 → one decimal lakhs `L`; else `Cr`. Use `Locale.ROOT` for number formatting. `monthGridCells`: `Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }`, leading = `get(DAY_OF_WEEK) - Calendar.SUNDAY`, days = `getActualMaximum(DAY_OF_MONTH)`; pad the end with nulls to a multiple of 7.

Tests — `ui/BusinessCalendarTest.kt` (plain JUnit, no Robolectric needed except if `Calendar` default TZ matters; pass a fixed `dayOf` lambda for ledger entries):
1. `cashUpiCreditSalesSplitLikeGalla` — 3 SALE headers on day 5 (CASH 1000, UPI 2000, CREDIT total 3000 paid 500 due 2500) → salesPaise 6000, cash 1500, upi 2000, udhaarGiven 2500, saleCount 3.
2. `creditNoteReducesSales` — SALE 5000 cash + CREDIT_NOTE −2000 cash same day → salesPaise 3000, cash 3000.
3. `jamaAndPaymentsToDistributors` — PAYMENT_IN −700 UPI and PAYMENT_OUT +4000 CASH on day 9 → collected UPI 700, paid to distributors cash 4000.
4. `stockInWithAndWithoutDistributor` — bill A (supplier, total 10000, paid 4000 UPI, due 6000) + ledger PAYMENT_OUT +4000 UPI (same day) and bill B (no supplier, total 2000 paid 2000 CASH) → stockIn 12000, count 2, onUdhaar 6000, paid UPI 4000 (counted once), paid cash 2000.
5. `otherMonthsIgnored`.
6. `monthTotalIsSumOfDays`.
7. `dayMatchesGallaForThatDay` — random-ish fixed data; for day d, `cashSalesPaise`, `upiSalesPaise`, `udhaarGivenPaise`, jama cash/upi equal `computeGallaBreakdown(..., periodIndex = 0, year, month, d)` fields.
8. `compactFormat` — 95000 → "₹950", 1240000 → "₹12.4k", 1200000 → "₹12k", 12000000 → "₹1.2L", 3100000000 → "₹3.1Cr", -50000 → "-₹500".
9. `gridStartsOnSunday` — 2026-11 (1 Nov 2026 is a Sunday) → first cell 1, size multiple of 7; 2026-12 (1 Dec 2026 is a Tuesday) → two leading nulls.

**Commit:** `feat(bills): month calendar numbers for sales, udhaar, nagad and stock-in`

---

## Task 9 — Calendar screen in the Bills tab

### 9.1 Bills tab wrapper — create `pos/ui/screens/sales/BillsTab.kt`

```kotlin
@Composable
fun BillsTab(
    // every parameter SaleHistoryScreen takes today, passed through unchanged
    ...,
    purchaseBills: List<PurchaseBillEntity>,
    isSellerUnlocked: Boolean,
    onOpenPurchaseBill: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```
`var view by rememberSaveable { mutableIntStateOf(0) }` → `SegmentedChoice(listOf(stringResource(R.string.bills_view_list), stringResource(R.string.bills_view_calendar)), view) { view = it }` at the top, then `SaleHistoryScreen(...)` (unchanged) or `BusinessCalendarScreen(...)`. `MainActivity`: `AppTab.BILLS -> BillsTab(...)`.

### 9.2 `BusinessCalendarScreen` — create `pos/ui/screens/sales/calendar/BusinessCalendarScreen.kt`

Parameters: `invoices`, `ledgerEntries`, `purchaseBills`, `isSellerUnlocked`, `onOpenPurchaseBill`.
- State: `var year`/`var month` with `rememberSaveable`, initialised from today (`CalendarParts.of(System.currentTimeMillis())`); `var selectedDay by rememberSaveable { mutableStateOf<Int?>(null) }`.
- `val summary = remember(year, month, invoices, ledgerEntries, purchaseBills) { computeMonthSummary(year, month, invoices, ledgerEntries, purchaseBills) }`.
- Layout (`LazyColumn` so it scrolls on small phones):
  1. Month header row: `IconButton` previous (`Icons.AutoMirrored.Filled.KeyboardArrowLeft`, cd `calendar_prev_month`), month title `SimpleDateFormat("MMMM yyyy", current locale)` (`titleLarge`), `IconButton` next (cd `calendar_next_month`, disabled when the month is the current month or later), and a "Today" `TextButton` (`calendar_today`) when not on the current month.
  2. Month totals `SectionCard(title = calendar_month_total)`: rows (label left, amount right, `AmountRow` style): Sale (`calendar_sale`), Nagad (`calendar_nagad` = `total.cashSalesPaise`), UPI (`calendar_upi`), Udhaar given (`calendar_udhaar_given`), Udhaar collected (`calendar_udhaar_collected`). If `isSellerUnlocked`: Stock-in (`calendar_stock_in`), On distributor udhaar (`calendar_stock_in_udhaar`), Paid to distributors (`calendar_paid_distributors`). Else one line `calendar_locked_hint`.
  3. Weekday header row: 7 equal-weight `Text`s from `DateFormatSymbols.getInstance(current locale).shortWeekdays` (indices 1..7, Sunday first), `labelMedium`, centred.
  4. Grid: `monthGridCells(year, month).chunked(7)` → `Row` of 7 `Box(Modifier.weight(1f).aspectRatio(0.8f).heightIn(min = 56.dp))`. A day cell (`clickable { selectedDay = day }`, `semantics { contentDescription = stringResource(R.string.calendar_day_cd, day, salesText, udhaarText) }`):
     - day number top-left (`labelLarge`); today's date gets a `primary` circle background; the selected day gets a `primaryContainer` cell background;
     - sale compact amount (`formatCompactRupees(day.salesPaise)`, `labelSmall`, `primary`), only when `salesPaise != 0`;
     - a row of small dots (6 dp circles, spacing 2 dp): `tertiary` when `udhaarGivenPaise > 0`, `secondary` when `udhaarCollectedPaise > 0`, `error` when `isSellerUnlocked && stockInBillCount > 0`.
     - Blank (null) cells are empty boxes, not clickable.
  5. Legend row (wrapping `FlowRow`): dot + `calendar_legend_udhaar`, dot + `calendar_legend_jama`, dot + `calendar_legend_stock` (last only when unlocked).
- Day details: when `selectedDay != null` show `DayDetailsSheet` (`ModalBottomSheet`) with:
  - title: full date (`SimpleDateFormat("EEEE, d MMMM yyyy", current locale)`),
  - if the day `isEmpty` → `EmptyState(calendar_day_empty_title, calendar_day_empty_message)`,
  - Sales `SectionCard`: bills count (`pluralStringResource(R.plurals.calendar_bills_count, …)`), Sale, Nagad, UPI, Udhaar given,
  - Udhaar collected `SectionCard`: Nagad, UPI,
  - When unlocked: Stock-in `SectionCard`: total, on udhaar, paid to distributors (Nagad/UPI), and the list of that day's purchase bills (distributor name or `distributor_unknown`, bill number, total) — tapping one calls `onOpenPurchaseBill(id)` (MainActivity opens the `PurchaseBillSheet` from Task 6),
  - Close button `SecondaryActionButton(action_close or the existing close key)`.

### 9.3 Tests — `ui/BusinessCalendarScreenTest.kt` (`w360dp-h800dp`, fixed data in **the current month** so the screen opens on it, or add an `initialYear/initialMonth` parameter with default = today and pass a fixed month in tests — prefer the parameter)
1. `gridShowsSaleAmountOnDay` — sale ₹12,400 on day 10 → cell text "₹12.4k".
2. `tappingDayOpensDetailsWithBreakdown` — tap day 10 → "Nagad", "UPI", "Udhaar given" rows with the right amounts.
3. `stockInHiddenWhenLocked` — purchase bill on day 10, locked → no "Stock-in" text, lock hint shown; unlocked → "Stock-in" with amount.
4. `prevMonthButtonChangesTitle`.
5. `emptyDayShowsEmptyState`.
6. `cellsAreAtLeast48dpTall` — `assertHeightIsAtLeast(48.dp)` on a day cell.
7. `billsTabSwitchesBetweenListAndCalendar` (in a `BillsTab` test).

**Commit:** `feat(bills): calendar view with daily sale, udhaar, nagad and stock-in`

---

## Task 10 — Strings, README, final verification

1. Make sure every key in section 12 exists in both files; `StringParityTest` green. Change the English/Hindi **text** of the existing stock-in supplier labels to Distributor wording (keys unchanged): grep `values/strings.xml` for `review_supplier` and every string whose English text contains "supplier"/"Supplier" in stock-in/khata context; replace "Supplier" → "Distributor" (Hindi "सप्लायर"/"सप्लायर" → "डिस्ट्रीब्यूटर").
2. `grep -rnE 'Text\("[A-Za-z]' app/src/main/java` → nothing. `grep -rn "horizontalScroll" app/src/main/java/com/shopledger/pos/ui/screens/khata app/src/main/java/com/shopledger/pos/ui/screens/sales/calendar` → nothing.
3. Full test suite → 0 failures, count ≥ baseline + new tests.
4. `.\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain > build\final.log 2>&1` → `BUILD SUCCESSFUL`, 0 lint errors; list new warnings.
5. `git ls-files --eol` on changed files → `i/lf`. `app/schemas/com.shopledger.pos.data.local.LpsDatabase/4.json` is committed.
6. README features: "Distributors: account per distributor (Udhaar and Nagad/UPI payments), every purchase bill, which items came from which distributor, and the last distributor on each item." and "Calendar in Bills: sale, Nagad, UPI, Udhaar and stock-in for every day of the month."
7. Manual check on a phone/emulator (write results in the final commit message body):
   - **Upgrade:** install the current release (v3 database) with a few stock-ins (one with a supplier due), then install this build → app opens, data intact, Khata → Distributors (after unlock) shows the old supplier with the old bill marked "Older entry".
   - Stock In → Enter bill by hand → Add item ×2 → distributor "Gupta Traders" (new) → total ₹1,000, paid ₹400, UPI, bill no. "GT-17" → Save → stock up, Gupta owes ₹600; detail: Account shows "Udhaar ₹1,000" and "Paid ₹400 · UPI", Bills shows GT-17 with Due ₹600, Items shows both items.
   - Pay Gupta ₹600 Nagad → settled.
   - Open one of those items in the editor → "Last bought from Gupta Traders …".
   - Khata → Customers no longer lists Gupta.
   - Make sales (Cash, UPI, Udhaar) and receive a Jama → Bills → Calendar: today's cell shows the sale; tap → numbers match the Galla card for Today.
   - Lock seller mode → calendar hides stock-in; Distributors side asks to unlock.
   - Switch to Hindi and repeat one round; dark mode; rotate on the calendar and distributor detail.

**Commit:** `docs: README for distributors and calendar`

---

## 12. All new strings (add to BOTH files)

| Key | English | Hindi |
|---|---|---|
| `khata_side_customers` | Customers | ग्राहक |
| `khata_side_distributors` | Distributors | डिस्ट्रीब्यूटर |
| `distributors_locked_title` | Distributor accounts are locked | डिस्ट्रीब्यूटर खाते लॉक हैं |
| `distributors_locked_message` | They show purchase prices. Unlock seller mode to see them. | इनमें खरीद दाम दिखते हैं। देखने के लिए सेलर मोड खोलें। |
| `distributors_unlock` | Unlock | अनलॉक करें |
| `distributors_empty_title` | No distributors yet | अभी कोई डिस्ट्रीब्यूटर नहीं |
| `distributors_empty_message` | Add one here, or choose a distributor when you do Stock In. | यहाँ जोड़ें, या स्टॉक इन करते समय डिस्ट्रीब्यूटर चुनें। |
| `distributors_total_owe` | You owe in total | कुल देना बाकी |
| `distributors_count` (plurals) | one `%1$d distributor` / other `%1$d distributors` | one `%1$d डिस्ट्रीब्यूटर` / other `%1$d डिस्ट्रीब्यूटर` |
| `distributor_add` | Add distributor | डिस्ट्रीब्यूटर जोड़ें |
| `distributor_name` | Distributor name | डिस्ट्रीब्यूटर का नाम |
| `distributor_phone` | Phone (optional) | फ़ोन (ज़रूरी नहीं) |
| `distributor_gstin` | GSTIN (optional) | GSTIN (ज़रूरी नहीं) |
| `distributor_already_owe` | Amount you already owe (₹) | पहले से देना बाकी (₹) |
| `distributor_you_owe` | You owe %1$s | देना है %1$s |
| `distributor_they_owe` | Advance %1$s | एडवांस %1$s |
| `distributor_badge_owe` | To pay | देना है |
| `distributor_last_bill` | Last bill %1$s · %2$s | पिछला बिल %1$s · %2$s |
| `distributor_no_bills` | No bills yet | अभी कोई बिल नहीं |
| `distributor_pay` | Pay | भुगतान करें |
| `distributor_pay_title` | Pay %1$s | %1$s को भुगतान |
| `distributor_pay_amount` | Amount (₹) | रकम (₹) |
| `distributor_pay_note` | Note (optional) | नोट (ज़रूरी नहीं) |
| `distributor_pay_more_than_owed` | This is more than you owe. The extra is kept as advance. | यह बाकी से ज़्यादा है। बाकी रकम एडवांस में रहेगी। |
| `distributor_pay_save` | Save payment | भुगतान सेव करें |
| `distributor_tab_account` | Account | खाता |
| `distributor_tab_bills` | Bills | बिल |
| `distributor_tab_items` | Items | सामान |
| `distributor_entry_bill` | Purchase bill | खरीद बिल |
| `distributor_entry_paid` | Paid | भुगतान किया |
| `distributor_entry_refund` | Money back | पैसे वापस |
| `distributor_udhaar_amount` | Udhaar %1$s | उधार %1$s |
| `distributor_paid_amount` | Paid %1$s | दिया %1$s |
| `distributor_balance_owe` | Owe %1$s | बाकी %1$s |
| `distributor_balance_advance` | Advance %1$s | एडवांस %1$s |
| `distributor_bill_no_number` | No bill number | बिल नंबर नहीं |
| `distributor_bill_items` (plurals) | one `%1$d item` / other `%1$d items` | one `%1$d सामान` / other `%1$d सामान` |
| `distributor_bill_due` | Due %1$s | बाकी %1$s |
| `distributor_bill_paid` | Paid | चुकता |
| `distributor_bill_legacy` | Older entry — cost not recorded | पुरानी एंट्री — दाम दर्ज नहीं |
| `distributor_cost_unknown` | Cost not recorded | दाम दर्ज नहीं |
| `distributor_unknown` | No distributor | बिना डिस्ट्रीब्यूटर |
| `distributor_items_empty_title` | No items yet | अभी कोई सामान नहीं |
| `distributor_items_empty_message` | Items appear here after a Stock In with this distributor. | इस डिस्ट्रीब्यूटर से स्टॉक इन करने पर सामान यहाँ दिखेगा। |
| `distributor_items_bought` | Bought %1$s %2$s in %3$d bills | %3$d बिलों में %1$s %2$s खरीदा |
| `distributor_last_cost` | Last cost %1$s on %2$s | आखिरी दाम %1$s, %2$s |
| `distributor_whatsapp_text` | Namaste %1$s, %2$s will pay the pending %3$s soon. | नमस्ते %1$s, %2$s बाकी %3$s जल्द भुगतान करेगा। |
| `distributor_cd_call` | Call distributor | डिस्ट्रीब्यूटर को कॉल करें |
| `distributor_cd_share` | Send on WhatsApp | व्हाट्सऐप पर भेजें |
| `purchase_bill_title` | Purchase bill | खरीद बिल |
| `purchase_bill_lines_total` | Items total | सामान का जोड़ |
| `purchase_bill_total` | Bill total | बिल कुल |
| `purchase_bill_paid_now` | Paid now (%1$s) | अभी दिया (%1$s) |
| `purchase_bill_due` | On udhaar | उधार पर |
| `pay_nagad` | Nagad | नकद |
| `pay_upi` | UPI | UPI |
| `review_paid_mode` | Paid by | भुगतान का तरीका |
| `review_bill_number` | Bill number (optional) | बिल नंबर (ज़रूरी नहीं) |
| `review_add_item` | Add item | सामान जोड़ें |
| `review_empty_title` | No items on this bill yet | इस बिल में अभी कोई सामान नहीं |
| `review_empty_message` | Tap Add item to add what you received. | जो सामान आया है उसे सामान जोड़ें दबाकर डालें। |
| `stock_in_manual_bill` | Enter bill by hand | बिल हाथ से भरें |
| `msg_paid_mode_unknown` | Choose Nagad or UPI for the amount paid. | दी गई रकम के लिए नकद या UPI चुनें। |
| `msg_bill_number_too_long` | Bill number is too long. Keep it under 40 letters. | बिल नंबर बहुत लंबा है। 40 अक्षरों से कम रखें। |
| `item_last_bought_from` | Last bought from %1$s on %2$s at %3$s | आखिरी बार %1$s से %2$s को %3$s में खरीदा |
| `item_last_bought_from_no_cost` | Last bought from %1$s on %2$s | आखिरी बार %1$s से %2$s को खरीदा |
| `bills_view_list` | List | सूची |
| `bills_view_calendar` | Calendar | कैलेंडर |
| `calendar_prev_month` | Previous month | पिछला महीना |
| `calendar_next_month` | Next month | अगला महीना |
| `calendar_today` | Today | आज |
| `calendar_month_total` | This month | इस महीने |
| `calendar_sale` | Sale | बिक्री |
| `calendar_nagad` | Nagad | नकद |
| `calendar_upi` | UPI | UPI |
| `calendar_udhaar_given` | Udhaar given | उधार दिया |
| `calendar_udhaar_collected` | Udhaar collected | उधार वसूला |
| `calendar_stock_in` | Stock-in | स्टॉक इन |
| `calendar_stock_in_udhaar` | Stock-in on distributor udhaar | डिस्ट्रीब्यूटर का उधार |
| `calendar_paid_distributors` | Paid to distributors | डिस्ट्रीब्यूटर को दिया |
| `calendar_locked_hint` | Unlock seller mode to see stock-in and distributor payments. | स्टॉक इन और डिस्ट्रीब्यूटर भुगतान देखने के लिए सेलर मोड खोलें। |
| `calendar_legend_udhaar` | Udhaar given | उधार दिया |
| `calendar_legend_jama` | Udhaar collected | उधार वसूला |
| `calendar_legend_stock` | Stock-in | स्टॉक इन |
| `calendar_day_cd` | Day %1$d, sale %2$s, udhaar %3$s | तारीख %1$d, बिक्री %2$s, उधार %3$s |
| `calendar_day_empty_title` | Nothing on this day | इस दिन कुछ नहीं |
| `calendar_day_empty_message` | No sales, payments or stock-in were recorded. | कोई बिक्री, भुगतान या स्टॉक इन दर्ज नहीं हुआ। |
| `calendar_bills_count` (plurals) | one `%1$d bill` / other `%1$d bills` | one `%1$d बिल` / other `%1$d बिल` |
| `calendar_section_sales` | Sales | बिक्री |
| `calendar_section_collected` | Udhaar collected | उधार वसूली |
| `calendar_section_stock` | Stock-in | स्टॉक इन |

Before adding a key, `grep` both files: if a key with the same meaning already exists (for example a "Close" button, `khata_badge_settled`, an opening-balance label), reuse it instead of adding a duplicate.

---

## 13. Things that must NOT change (reviewer checklist)

- Sales, returns, receipts, checkout, carts, customer Khata behaviour and amounts.
- Stock numbers: stock still comes only from `stock_movements`; purchase bills are an extra record written in the same transaction, never a second source of stock.
- Supplier balance after a stock-in is exactly what it was before this change (−due); only the entries that produce it are split into bill + payment.
- Idempotency: a repeated stock-in `actionId` writes nothing new (no second bill).
- Upgrading from v2 still works (`Migration2To3` uses `includePurchases = false`); upgrading from v3 creates bills for old stock-ins; `PreMigrationGuard` backup before migration is automatic because `SCHEMA_VERSION` changed — do not touch it.
- Seller lock: no purchase cost or distributor amount is visible while locked.
- English/Hindi parity, 48 dp targets, 360 dp layouts, no sideways-scrolling lists.
