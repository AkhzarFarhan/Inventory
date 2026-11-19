# ShopLedger POS Data Core (Ledger, Atomicity, Outbox) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every stock and money action in ShopLedger POS atomic and conflict-free, and ready for multi-device sync. That means append-only stock and Khata ledgers with cached totals, immutable invoices with credit-note returns, a transactional sync outbox, a safe v2→v3 database upgrade, and encrypted local backups.

**Architecture:** Room schema v3 makes `stock_movements`, `party_ledger_entries` and `invoice_headers`/`invoice_lines` append-only sources of truth. `item_variants.stockMilliUnits` and `parties.currentBalancePaise` become cached projections, updated in the same transaction. All writes go through command classes (`CatalogCommands`, `KhataCommands`, `SalesCommands`). Each one runs exactly one `withTransaction` through `LedgerWriter.runIdempotent`, which checks an `applied_actions` idempotency table and writes `sync_outbox` rows. `LpsRepository` becomes a thin facade. The old Firestore worker is stubbed until sub-project 2.

**Tech Stack:** Kotlin 2.0.20, Room 2.6.1 (KSP), WorkManager 2.9.1, Jetpack Compose (Material3), JUnit 4.13 + Robolectric 4.13 + `androidx.room:room-testing`.

**Spec:** `docs/superpowers/specs/2026-11-19-data-core-ledger-design.md`

## Global Constraints

- minSdk 24, compileSdk 34, JVM target 17. Don't use `java.time` (no core-library desugaring) or `VACUUM INTO` (needs SQLite 3.27, API 30+).
- Money is `Long` paise. Quantities are `Long` milli-units (1 unit = 1000). No `Double` in any persisted table column. Room views may expose computed `Double`s.
- No `OnConflictStrategy.REPLACE` on write paths for items, variants, parties, invoices, invoice lines, stock movements, ledger entries, outbox or applied actions. Use `ABORT` inserts, `@Update`, `@Upsert` (shop profile only), or relative `UPDATE … SET x = x + :delta`. Task 3 leaves two interim REPLACE invoice inserts, and Task 4 removes them.
- Every business write is exactly one `database.withTransaction { }`, run through `LedgerWriter.runIdempotent(actionId, kind, …)`.
- Stock and balance projections are never clamped. Negative stock means oversold.
- Never call `fallbackToDestructiveMigration`.
- Receipt number format: `{deviceCode}-{yyMMdd}-{seq:04d}`, for example `L-261119-0007`.
- Tasks 3–5 change the schema to version 3 before the 2→3 migration exists (Task 6). Those builds are **dev-only**: uninstall the app on a dev phone before installing them, and never install any build from this plan on a shop phone until Task 8 is complete.
- Run tests with `powershell -ExecutionPolicy Bypass -File scripts/test.ps1` (created in Task 1). Pass Gradle filters after it, for example `... scripts/test.ps1 --tests "com.shopledger.pos.data.command.SalesCommandsTest"`. In Claude Code, run this command **outside the sandbox** (Gradle needs local sockets).
- Work on branch `feat/data-core-ledger`. Commit after every task using Conventional Commits. End every commit message with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Deliberate deviations from the spec, already decided:
  - The idempotency key lives only in `applied_actions` (PK = `actionId`). There's no `actionId` column on `invoice_headers`. This still satisfies I5.
  - The device counter column is called `lastInvoiceSeq` (it holds the last number handed out).
  - Commands throw typed `CommandException`s instead of returning `Result<T>`, so the existing `launchSafe` error path shows them.
  - Shareable backups are gzip only, without a passphrase. The UI warns that the file contains business data.

## File Structure

```
app/schemas/com.shopledger.pos.data.local.LpsDatabase/{2,3}.json   Room schema history (generated, committed)
scripts/test.ps1                                                   Local test runner (JDK + Windows socket fix)

app/src/main/java/com/shopledger/pos/
  core/ledger/LedgerPrimitives.kt        CalendarParts, ReceiptNumbers, InvoiceMath (pure Kotlin)
  core/backup/SnapshotCipher.kt          SnapshotCipher + Android Keystore AES-GCM implementation
  core/backup/BackupManager.kt           Consistent DB copy, encrypted snapshots, shareable export, restore
  core/backup/LocalSnapshotWorker.kt     Daily snapshot WorkManager job
  core/backup/BackupShare.kt             FileProvider share helper
  core/backup/AppRestarter.kt            Process restart after restore/recovery
  data/command/CommandSupport.kt         Role, Actor, CommandEnvironment, CommandException, FaultInjector, constants
  data/command/AppCommandEnvironment.kt  Production CommandEnvironment (SyncManager / GoogleAuthManager)
  data/command/LedgerWriter.kt           runIdempotent, appendMovement, appendLedgerEntry, enqueue
  data/command/CatalogCommands.kt        create/add-variant/edit/delete item, adjust stock, receive purchase
  data/command/KhataCommands.kt          create/update party, record payment
  data/command/SalesCommands.kt          checkout, returns (credit notes)
  data/command/LegacyJsonImporter.kt     v1/v2 JSON backup import through the ledger
  data/local/LpsDatabase.kt              v3 database, builder defaults, close/reopen
  data/local/DatabaseSeed.kt             Default shop + local device rows
  data/local/IntegrityChecker.kt         Invariants I1–I5 as SQL
  data/local/OutboxRebuilder.kt          Enqueue every syncable document (migration + restore)
  data/local/PreMigrationGuard.kt        Safety copy + integrity check before upgrades
  data/local/DatabaseHealth.kt           App-wide "database usable?" state
  data/local/QuarantiningOpenHelperFactory.kt  Never auto-delete a corrupt database
  data/local/migrations/Migration2To3.kt v2 → v3 upgrade
  data/local/entity/InventoryItemEntity.kt, ItemVariantEntity.kt, ShopLedgerEntities.kt,
              LedgerSupportEntities.kt (devices, sync_outbox, applied_actions), SaleRecordEntity.kt (view)
  data/local/dao/InventoryDao.kt, ShopPartyAndLedgerDao.kt, SaleDao.kt, LedgerSupportDaos.kt
  data/repository/LpsRepository.kt       Read flows + facade over commands
  ui/screens/recovery/DatabaseRecoveryScreen.kt

app/src/test/java/com/shopledger/pos/
  testing/TestSupport.kt                 TestEnv, newTestDatabase(), scalar(), expectFailure(), AesTestCipher
  core/ledger/LedgerPrimitivesTest.kt
  data/local/DatabaseSmokeTest.kt
  data/command/CatalogCommandsTest.kt, KhataCommandsTest.kt, SalesCommandsTest.kt, ReturnsTest.kt,
               LegacyJsonImporterTest.kt, LedgerInvariantPropertyTest.kt
  data/local/migrations/Migration2To3Test.kt, data/local/PreMigrationGuardTest.kt
  core/backup/BackupManagerTest.kt
```

---

### Task 1: Test harness, Room schema export, CI test gate

**Files:**
- Create: `scripts/test.ps1`
- Create: `app/src/test/resources/robolectric.properties`
- Create: `app/src/test/java/com/shopledger/pos/data/local/DatabaseSmokeTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/shopledger/pos/data/local/LpsDatabase.kt` (`exportSchema`)
- Modify: `.github/workflows/build-release.yml`
- Generated and committed: `app/schemas/com.shopledger.pos.data.local.LpsDatabase/2.json`

**Interfaces:**
- Produces: `scripts/test.ps1` (every later task runs it); test dependencies Robolectric, `androidx.test:core-ktx`, `androidx.test.ext:junit-ktx`, `androidx.room:room-testing`; the v2 schema JSON that Task 6's migration test loads.

- [ ] **Step 1: Create the branch**

```bash
git checkout -b feat/data-core-ledger
```

- [ ] **Step 2: Create `scripts/test.ps1`**

```powershell
# Runs the app's JVM unit tests (Robolectric included).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/test.ps1 [--tests "fully.qualified.TestClass"]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
# Java's Windows selector uses a Unix-domain socket in the temp dir; a short, writable dir avoids
# "Unable to establish loopback connection".
$tmp = Join-Path $root "build\jtmp"
New-Item -ItemType Directory -Force $tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$tmp"
Push-Location $root
try {
    & .\gradlew.bat ":app:testDebugUnitTest" --console=plain @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
```

- [ ] **Step 3: Write the failing smoke test**

Create `app/src/test/java/com/shopledger/pos/data/local/DatabaseSmokeTest.kt`:

```kotlin
package com.shopledger.pos.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    @Test
    fun inMemoryDatabaseOpensAndStoresAnItem() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LpsDatabase::class.java
        ).allowMainThreadQueries().build()
        db.inventoryDao().upsertItem(InventoryItemEntity(id = "i1", displayName = "Hinge", sortKey = "HINGE"))
        assertEquals(1, db.inventoryDao().getAllRawItems().size)
        db.close()
    }
}
```

Create `app/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

- [ ] **Step 4: Run it and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.local.DatabaseSmokeTest"`
Expected: FAIL with compilation errors such as `Unresolved reference: ApplicationProvider` / `AndroidJUnit4`.

- [ ] **Step 5: Add the test dependencies**

In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
robolectric = "4.13"
androidxTestCore = "1.6.1"
androidxTestExtJunit = "1.2.1"
coroutinesTest = "1.8.1"
```

Add under `[libraries]`:

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit-ktx", version.ref = "androidxTestExtJunit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
```

In `app/build.gradle.kts`, add this block after the `plugins { }` block:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Inside `android { }`, after `packaging { … }`, add:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
```

Replace the two test lines at the end of `dependencies { }`:

```kotlin
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

with:

```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
```

In `LpsDatabase.kt`, change `exportSchema = false` to `exportSchema = true`.

- [ ] **Step 6: Run all tests and confirm they pass and the v2 schema was exported**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`. All 19 existing tests in `LpsHardwareDomainTest` plus `DatabaseSmokeTest` pass. The first run downloads the Robolectric android-all jar.

Run: `ls app/schemas/com.shopledger.pos.data.local.LpsDatabase/`
Expected: `2.json`. Open it and confirm `"version": 2` and that it contains a `sale_records` entity with a `syncStatus` column.

- [ ] **Step 7: Gate CI releases on tests**

In `.github/workflows/build-release.yml`, insert this step immediately before the `- name: Fast Build Debug APK` step:

```yaml
      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest --console=plain
```

- [ ] **Step 8: Commit**

```bash
git add scripts/test.ps1 gradle/libs.versions.toml app/build.gradle.kts app/src/test app/schemas app/src/main/java/com/shopledger/pos/data/local/LpsDatabase.kt .github/workflows/build-release.yml
git commit -m "test: add Robolectric/Room test harness, export Room v2 schema, gate releases on unit tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Ledger primitives and command support types (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/shopledger/pos/core/ledger/LedgerPrimitives.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/command/CommandSupport.kt`
- Test: `app/src/test/java/com/shopledger/pos/core/ledger/LedgerPrimitivesTest.kt`

**Interfaces:**
- Produces:
  - `data class CalendarParts(year: Int, month: Int, day: Int)` with `val dayKey: String` (`yyMMdd`) and `CalendarParts.of(epochMs: Long, tz: TimeZone = TimeZone.getDefault())`
  - `ReceiptNumbers.format(deviceCode: String, dayKey: String, seq: Long): String`
  - `InvoiceMath.lineTotalPaise(unitPaise: Long, qtyMilli: Long): Long` (half-up; sign follows qty)
  - `enum class Role { OWNER, STAFF }`, `data class Actor(uid: String = "", role: Role = Role.OWNER)`
  - `interface CommandEnvironment { shopId(); actor(); gstEnabled(); now(); newId(); afterCommit() }`
  - `sealed class CommandException` with `Validation`, `StaleEdit`, `PermissionDenied`, `ReturnExceedsSold`, `NotFound`
  - `fun interface FaultInjector { fun at(step: String) }` with `FaultInjector.NONE`
  - Constants `OutboxDocType`, `OutboxOp`, `MovementType`, `LedgerEntryType`, `CheckoutStep`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shopledger/pos/core/ledger/LedgerPrimitivesTest.kt`:

```kotlin
package com.shopledger.pos.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.TimeZone

class LedgerPrimitivesTest {

    @Test
    fun lineTotal_roundsHalfUpOnMilliUnits() {
        assertEquals(15_000L, InvoiceMath.lineTotalPaise(7_500L, 2_000L))
        assertEquals(3_750L, InvoiceMath.lineTotalPaise(2_500L, 1_500L))
        assertEquals(1L, InvoiceMath.lineTotalPaise(1L, 500L))
        assertEquals(0L, InvoiceMath.lineTotalPaise(1L, 499L))
        assertEquals(-3_750L, InvoiceMath.lineTotalPaise(2_500L, -1_500L))
        assertEquals(0L, InvoiceMath.lineTotalPaise(7_500L, 0L))
    }

    @Test
    fun lineTotal_rejectsNegativeUnitPrice() {
        assertThrows(IllegalArgumentException::class.java) { InvoiceMath.lineTotalPaise(-1L, 1_000L) }
    }

    @Test
    fun receiptNumber_hasDeviceDayAndPaddedSequence() {
        assertEquals("A-261119-0007", ReceiptNumbers.format("a", "261119", 7))
        assertEquals("D1-261119-12345", ReceiptNumbers.format(" d1 ", "261119", 12_345))
    }

    @Test
    fun receiptNumber_rejectsNonPositiveSequence() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptNumbers.format("A", "261119", 0) }
    }

    @Test
    fun calendarParts_followTheGivenTimeZone() {
        // 2026-11-18T19:00:00Z is 2026-11-19 00:30 in India.
        val instant = 1_795_028_400_000L
        val ist = CalendarParts.of(instant, TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals(CalendarParts(2026, 11, 19), ist)
        assertEquals("261119", ist.dayKey)
        assertEquals(CalendarParts(2026, 11, 18), CalendarParts.of(instant, TimeZone.getTimeZone("UTC")))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.core.ledger.LedgerPrimitivesTest"`
Expected: FAIL. `Unresolved reference: InvoiceMath`.

- [ ] **Step 3: Implement `LedgerPrimitives.kt`**

```kotlin
package com.shopledger.pos.core.ledger

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Calendar date of an instant in a time zone. `dayKey` is `yyMMdd`, used in receipt numbers. */
data class CalendarParts(val year: Int, val month: Int, val day: Int) {
    val dayKey: String
        get() = String.format(Locale.US, "%02d%02d%02d", year % 100, month, day)

    companion object {
        fun of(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): CalendarParts {
            val cal = Calendar.getInstance(tz).apply { timeInMillis = epochMs }
            return CalendarParts(
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                day = cal.get(Calendar.DAY_OF_MONTH)
            )
        }
    }
}

/** Receipt numbers are `{deviceCode}-{yyMMdd}-{seq:04d}`; unique per shop because device codes are. */
object ReceiptNumbers {
    fun format(deviceCode: String, dayKey: String, seq: Long): String {
        require(seq >= 1) { "Receipt sequence must start at 1" }
        val code = deviceCode.trim().uppercase(Locale.US)
        return "$code-$dayKey-${seq.toString().padStart(4, '0')}"
    }
}

/** Exact integer money maths: paise × milli-units, rounded half-up to whole paise. */
object InvoiceMath {
    fun lineTotalPaise(unitPaise: Long, qtyMilli: Long): Long {
        require(unitPaise >= 0) { "Unit price cannot be negative" }
        if (qtyMilli < 0) return -lineTotalPaise(unitPaise, -qtyMilli)
        return Math.addExact(Math.multiplyExact(unitPaise, qtyMilli), 500L) / 1000L
    }
}
```

- [ ] **Step 4: Implement `CommandSupport.kt`**

```kotlin
package com.shopledger.pos.data.command

enum class Role { OWNER, STAFF }

data class Actor(val uid: String = "", val role: Role = Role.OWNER)

/** Everything a command needs from the outside world; tests supply a deterministic fake. */
interface CommandEnvironment {
    fun shopId(): String
    fun actor(): Actor
    fun gstEnabled(): Boolean
    fun now(): Long
    fun newId(): String
    /** Called once after a command's transaction commits (or replays). */
    fun afterCommit()
}

/** Typed, user-presentable failures. Throwing one inside a command rolls the transaction back. */
sealed class CommandException(message: String) : Exception(message) {
    class Validation(message: String) : CommandException(message)
    class StaleEdit(what: String) :
        CommandException("$what was changed on another screen or phone. Reload and try again.")
    class PermissionDenied(message: String) : CommandException(message)
    class ReturnExceedsSold(itemName: String, remainingMilli: Long) :
        CommandException("Cannot return more $itemName than was sold (still returnable: ${remainingMilli / 1000.0}).")
    class NotFound(what: String) : CommandException("$what not found")
}

/** Test hook: lets tests throw at named points inside a command to prove rollback. */
fun interface FaultInjector {
    fun at(step: String)

    companion object {
        val NONE = FaultInjector { }
    }
}

object OutboxDocType {
    const val INVOICE = "INVOICE"
    const val MOVEMENT = "MOVEMENT"
    const val LEDGER = "LEDGER"
    const val ITEM = "ITEM"
    const val VARIANT = "VARIANT"
    const val PARTY = "PARTY"
    const val SHOP = "SHOP"
}

object OutboxOp {
    const val UPSERT = "UPSERT"
    const val APPEND = "APPEND"
}

object MovementType {
    const val OPENING = "OPENING"
    const val SALE = "SALE"
    const val SALE_RETURN = "SALE_RETURN"
    const val PURCHASE = "PURCHASE"
    const val ADJUSTMENT = "ADJUSTMENT"
    const val CORRECTION = "CORRECTION"
}

object LedgerEntryType {
    const val OPENING = "OPENING"
    const val CREDIT_BILL = "CREDIT_BILL"
    const val PAYMENT_IN = "PAYMENT_IN"
    const val PAYMENT_OUT = "PAYMENT_OUT"
    const val CREDIT_NOTE = "CREDIT_NOTE"
    const val ADJUSTMENT = "ADJUSTMENT"
}

object CheckoutStep {
    const val NUMBER = "NUMBER"
    const val HEADER = "HEADER"
    const val LINES = "LINES"
    const val MOVEMENTS = "MOVEMENTS"
    const val LEDGER = "LEDGER"
    const val OUTBOX = "OUTBOX"
    val ALL = listOf(NUMBER, HEADER, LINES, MOVEMENTS, LEDGER, OUTBOX)
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.core.ledger.LedgerPrimitivesTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shopledger/pos/core/ledger app/src/main/java/com/shopledger/pos/data/command/CommandSupport.kt app/src/test/java/com/shopledger/pos/core/ledger
git commit -m "feat(ledger): add receipt numbering, exact line maths and command support types

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Schema v3 for catalog, stock and Khata, plus catalog and Khata commands

This task makes stock and Khata ledger-based and routes every catalog and Khata write through commands. Invoice tables and `sale_records` keep their v2 shape until Task 4. Checkout and return get a short **interim** rewrite here so the app keeps working, and Task 4 replaces both.

**Files:**
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/entity/InventoryItemEntity.kt`
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/entity/ItemVariantEntity.kt`
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/entity/ShopLedgerEntities.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/entity/LedgerSupportEntities.kt`
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/dao/InventoryDao.kt`
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/dao/ShopPartyAndLedgerDao.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/dao/LedgerSupportDaos.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/DatabaseSeed.kt`
- Modify: `app/src/main/java/com/shopledger/pos/data/local/LpsDatabase.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/command/LedgerWriter.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/command/CatalogCommands.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/command/KhataCommands.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/command/AppCommandEnvironment.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/command/LegacyJsonImporter.kt`
- Modify: `app/src/main/java/com/shopledger/pos/data/repository/LpsRepository.kt`
- Modify (full replace): `app/src/main/java/com/shopledger/pos/core/sync/FirestoreDeltaSyncWorker.kt`
- Modify: `app/src/main/java/com/shopledger/pos/core/sync/SyncManager.kt` (`scheduleDeltaSync`)
- Create: `app/src/test/java/com/shopledger/pos/testing/TestSupport.kt`
- Create: `app/src/test/java/com/shopledger/pos/data/command/CatalogCommandsTest.kt`
- Create: `app/src/test/java/com/shopledger/pos/data/command/KhataCommandsTest.kt`
- Modify: `app/src/test/java/com/shopledger/pos/data/local/DatabaseSmokeTest.kt`
- Modify: `app/src/test/java/com/shopledger/pos/LpsHardwareDomainTest.kt`

**Interfaces:**
- Consumes (Task 2): `CommandEnvironment`, `Actor`, `Role`, `CommandException.*`, `OutboxDocType`, `OutboxOp`, `MovementType`, `LedgerEntryType`.
- Produces:
  - `DEFAULT_SHOP_ID = "default_shop"` (top-level in `InventoryItemEntity.kt`)
  - `DeviceEntity(deviceId, shopId, deviceCode, label, isLocal, lastInvoiceSeq, lastSeqDate, createdAt)`, `SyncOutboxEntity(seq, docType, docId, op, createdAt, attempts, lastError)`, `AppliedActionEntity(actionId, kind, resultRef, createdAt)`
  - DAOs: `InventoryDao.insertItem/insertVariant/updateItem/updateVariant/getItemById/getVariantById/getVariantsForItem/addToStockProjection(variantId, deltaMilli): Int/rebuildAllStockProjections()/countItems()`. `ShopPartyAndLedgerDao.insertParty/updateParty/addToPartyBalance(partyId, deltaPaise): Int/rebuildAllPartyBalances()/insertLedgerEntry/insertStockMovement/getAllMovements()/getAllLedgerEntries()`. `DeviceDao.getLocalDevice()/bumpInvoiceSeq(deviceId, dayKey)/getLastInvoiceSeq(deviceId)`. `SyncOutboxDao.insert/count/all/oldest/deleteAcknowledged/observePendingCount`. `AppliedActionDao.get/insert/count`
  - `fun RoomDatabase.Builder<LpsDatabase>.withShopLedgerDefaults(): RoomDatabase.Builder<LpsDatabase>`
  - `class LedgerWriter(db, env)` with `runIdempotent`, `localDevice`, `appendMovement`, `appendLedgerEntry`, `enqueue`
  - `CatalogCommands(db, env)`: `createItem`, `addVariant`, `editItem`, `deleteItem`, `adjustStock`, `receivePurchase`. Data classes `VariantSpec`, `VariantEdit`, `PurchaseLine`
  - `KhataCommands(db, env)`: `createParty`, `updateParty`, `recordPayment`. `enum PaymentDirection { IN, OUT }`
  - `LegacyJsonImporter(db, env).import(json): LegacyJsonImporter.Report`
  - Test helpers: `TestEnv`, `newTestDatabase()`, `LpsDatabase.scalar(sql)`, `expectFailure<T>{}`, `ItemVariantEntity.toEdit(targetStockMilli)`

- [ ] **Step 1: Write the test helpers**

Create `app/src/test/java/com/shopledger/pos/testing/TestSupport.kt`:

```kotlin
package com.shopledger.pos.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopledger.pos.data.command.Actor
import com.shopledger.pos.data.command.CommandEnvironment
import com.shopledger.pos.data.command.Role
import com.shopledger.pos.data.command.VariantEdit
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.withShopLedgerDefaults
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
```

- [ ] **Step 2: Write the failing command tests**

Create `app/src/test/java/com/shopledger/pos/data/command/CatalogCommandsTest.kt`:

```kotlin
package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import com.shopledger.pos.testing.toEdit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogCommandsTest {
    private lateinit var db: LpsDatabase
    private lateinit var env: TestEnv
    private lateinit var catalog: CatalogCommands
    private lateinit var khata: KhataCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        env = TestEnv()
        catalog = CatalogCommands(db, env)
        khata = KhataCommands(db, env)
    }

    @After
    fun tearDown() = db.close()

    private fun hinge(openingMilli: Long = 10_000L) = VariantSpec(
        sizeLabel = "4 inch", purchasePricePaise = 4_800L, sellingPricePaise = 7_500L, openingStockMilli = openingMilli
    )

    private suspend fun onlyVariant(itemId: String) = db.inventoryDao().getVariantsForItem(itemId).single()

    @Test
    fun createItem_writesOpeningMovementProjectionAndOutbox() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        assertEquals(10_000L, onlyVariant(itemId).stockMilliUnits)
        val movement = db.shopPartyAndLedgerDao().getAllMovements().single()
        assertEquals(MovementType.OPENING, movement.movementType)
        assertEquals(10_000L, movement.deltaMilliUnits)
        assertEquals(listOf("ITEM", "VARIANT", "MOVEMENT"), db.syncOutboxDao().all().map { it.docType })
        assertEquals(1, env.commits.get())
    }

    @Test
    fun createItem_sameActionIdTwice_createsOnlyOneItem() = runBlocking {
        val first = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val second = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        assertEquals(first, second)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM inventory_items"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun createItem_demoItem_isNotQueuedForSync() = runBlocking {
        catalog.createItem("seed-1", "Demo Hinge", "Fittings", listOf(hinge()), isDemo = true)
        assertEquals(0, db.syncOutboxDao().count())
        assertTrue(db.inventoryDao().getAllRawItems().single().isDemo)
        assertEquals(10_000L, db.scalar("SELECT SUM(deltaMilliUnits) FROM stock_movements"))
    }

    @Test
    fun editItem_withStaleUpdatedAt_throwsAndChangesNothing() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val item = db.inventoryDao().getItemById(itemId)!!
        val variant = onlyVariant(itemId)
        val outboxBefore = db.syncOutboxDao().count()
        expectFailure<CommandException.StaleEdit> {
            catalog.editItem("a2", itemId, item.updatedAt - 1, "Renamed", "Fittings", variant.id, variant.updatedAt, variant.toEdit())
        }
        assertEquals("Butt Hinge", db.inventoryDao().getItemById(itemId)!!.displayName)
        assertEquals(outboxBefore, db.syncOutboxDao().count())
        assertEquals(1, db.appliedActionDao().count())
    }

    @Test
    fun editItem_targetStock_appendsAdjustmentRelativeToCurrentStock() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge(10_000L)))
        catalog.receivePurchase("a2", listOf(PurchaseLine(onlyVariant(itemId).id, 5_000L)))
        val item = db.inventoryDao().getItemById(itemId)!!
        val variant = onlyVariant(itemId)
        assertEquals(15_000L, variant.stockMilliUnits)
        catalog.editItem("a3", itemId, item.updatedAt, "Butt Hinge", "Fittings", variant.id, variant.updatedAt, variant.toEdit(12_000L))
        assertEquals(12_000L, onlyVariant(itemId).stockMilliUnits)
        val adjustment = db.shopPartyAndLedgerDao().getAllMovements().single { it.movementType == MovementType.ADJUSTMENT }
        assertEquals(-3_000L, adjustment.deltaMilliUnits)
    }

    @Test
    fun editItem_staffChangingCostPrice_isDenied() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val item = db.inventoryDao().getItemById(itemId)!!
        val variant = onlyVariant(itemId)
        env.role = Role.STAFF
        expectFailure<CommandException.PermissionDenied> {
            catalog.editItem("a2", itemId, item.updatedAt, "Butt Hinge", "Fittings", variant.id, variant.updatedAt, variant.toEdit().copy(purchasePricePaise = 1L))
        }
        assertEquals(4_800L, onlyVariant(itemId).purchasePricePaise)
    }

    @Test
    fun deleteItem_byStaff_isDenied() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        env.role = Role.STAFF
        expectFailure<CommandException.PermissionDenied> { catalog.deleteItem("a2", itemId, null) }
        Unit
    }

    @Test
    fun deleteItem_lastVariant_softDeletesItemAndVariant() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge()))
        val variantId = onlyVariant(itemId).id
        catalog.deleteItem("a2", itemId, variantId)
        assertTrue(db.inventoryDao().getItemById(itemId)!!.isDeleted)
        assertTrue(db.inventoryDao().getVariantById(variantId)!!.isDeleted)
        assertEquals(0, db.inventoryDao().getAllActiveItemsSnapshot().size)
    }

    @Test
    fun adjustStock_canGoNegative_withoutClamping() = runBlocking {
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge(2_000L)))
        val newStock = catalog.adjustStock("a2", onlyVariant(itemId).id, deltaMilli = -5_000L, reason = "Damaged")
        assertEquals(-3_000L, newStock)
        assertTrue(onlyVariant(itemId).isOversold)
    }

    @Test
    fun receivePurchase_onCredit_addsSupplierPayable() = runBlocking {
        val supplierId = khata.createParty("p1", "Gupta Traders", "9000000000", "SUPPLIER")
        val itemId = catalog.createItem("a1", "Butt Hinge", "Fittings", listOf(hinge(10_000L)))
        catalog.receivePurchase(
            "a2", listOf(PurchaseLine(onlyVariant(itemId).id, 4_000L)),
            supplierPartyId = supplierId, billTotalPaise = 20_000L, amountPaidPaise = 5_000L
        )
        assertEquals(14_000L, onlyVariant(itemId).stockMilliUnits)
        assertEquals(-15_000L, db.shopPartyAndLedgerDao().getPartyById(supplierId)!!.currentBalancePaise)
    }
}
```

Create `app/src/test/java/com/shopledger/pos/data/command/KhataCommandsTest.kt`:

```kotlin
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
}
```

- [ ] **Step 3: Run them and confirm they fail**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.command.*"`
Expected: FAIL with compilation errors (`Unresolved reference: CatalogCommands`, `VariantSpec`, `withShopLedgerDefaults`, …).

- [ ] **Step 4: Replace the catalog entities**

Replace `InventoryItemEntity.kt` entirely:

```kotlin
package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val DEFAULT_SHOP_ID = "default_shop"

/** Only the v2-shaped invoice and sale tables still use this; Task 4 deletes it. */
object SyncState {
    const val SYNCED = "SYNCED"
    const val PENDING_UPLOAD = "PENDING_UPLOAD"
}

typealias CatalogProductEntity = InventoryItemEntity

@Immutable
@Entity(
    tableName = "inventory_items",
    indices = [
        Index(value = ["sortKey"]),
        Index(value = ["shopId", "sortKey"]),
        Index(value = ["isDeleted"])
    ]
)
data class InventoryItemEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val displayName: String,
    val sortKey: String,
    val category: String = "Uncategorized",
    val defaultHsnCode: String = "",
    val defaultGstRateBps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Last catalog edit. Stock changes never touch this (stock lives in stock_movements). */
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    /** Starter-catalog rows: never queued for cloud sync. */
    val isDemo: Boolean = false,
    val createdByUid: String = "",
    val deviceId: String = ""
)
```

Replace `ItemVariantEntity.kt` entirely:

```kotlin
package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

typealias ProductSkuEntity = ItemVariantEntity

@Immutable
@Entity(
    tableName = "item_variants",
    foreignKeys = [
        ForeignKey(
            entity = InventoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["shopId", "barcode"]),
        Index(value = ["isDeleted"])
    ]
)
data class ItemVariantEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val itemId: String,
    val skuCode: String = "",
    val barcode: String = "",
    val sizeLabel: String = "",
    val materialLabel: String = "",
    val colorFinishLabel: String = "",
    val unit: String = "pcs",
    val mrpPaise: Long = 0L,
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val gstRateBps: Int = 0,
    /** Cached projection: always equals SUM(stock_movements.deltaMilliUnits) for this SKU (invariant I1). */
    val stockMilliUnits: Long = 0L,
    val lowStockMilliUnits: Long = 5_000L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val isDemo: Boolean = false,
    val createdByUid: String = "",
    val deviceId: String = ""
) {
    val stockQuantity: Double get() = stockMilliUnits / 1000.0
    val lowStockThreshold: Double get() = lowStockMilliUnits / 1000.0

    val attr1Value: String get() = sizeLabel
    val attr2Value: String get() = materialLabel
    val attr3Value: String get() = colorFinishLabel

    fun buildVariantSummary(): String {
        val parts = listOf(sizeLabel, materialLabel, colorFinishLabel).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "Standard" else parts.joinToString(" · ")
    }

    val isLowStock: Boolean
        get() = !isDeleted && stockMilliUnits > 0L && stockMilliUnits <= lowStockMilliUnits

    val isOutOfStock: Boolean
        get() = !isDeleted && stockMilliUnits <= 0L

    /** Several phones selling offline can push stock below zero; we show it instead of hiding it. */
    val isOversold: Boolean
        get() = !isDeleted && stockMilliUnits < 0L
}
```

- [ ] **Step 5: Replace `ShopLedgerEntities.kt` and add the support entities**

Replace `ShopLedgerEntities.kt` entirely. `InvoiceHeaderEntity` and `InvoiceLineEntity` are copied unchanged from the current file:

```kotlin
package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.nlp.ShopVertical

@Immutable
@Entity(tableName = "shops")
data class ShopProfileEntity(
    @PrimaryKey
    val shopId: String = DEFAULT_SHOP_ID,
    val shopName: String = "ShopLedger POS",
    val verticalType: String = ShopVertical.HARDWARE.name,
    val ownerUid: String = "",
    val gstin: String = "",
    val stateCode: String = "09",
    val gstEnabled: Boolean = false,
    val upiId: String = "",
    val whatsappPhone: String = "",
    val deviceShortCode: String = "",
    val invoicePrefix: String = "INV",
    val cipherMode: String = CipherMode.CUSTOM_CODE.name,
    val cipherWord10: String = CostCipherEngine.DEFAULT_WORD_10,
    val ownerPinHash: String = "",
    val isSetupCompleted: Boolean = false,
    /** Largest bill discount STAFF may give without the owner PIN, in basis points (1000 = 10%). */
    val maxStaffDiscountBps: Int = 1000,
    val updatedAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "parties",
    indices = [
        Index(value = ["shopId", "partyType"]),
        Index(value = ["shopId", "phone"])
    ]
)
data class PartyEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val partyType: String = "CUSTOMER",
    val name: String,
    val phone: String = "",
    val gstin: String = "",
    val billingAddress: String = "",
    /** Cached projection: always equals SUM(party_ledger_entries.amountDeltaPaise) (invariant I2). + = party owes shop. */
    val currentBalancePaise: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val createdByUid: String = "",
    val deviceId: String = ""
)

@Immutable
@Entity(
    tableName = "invoice_headers",
    indices = [
        Index(value = ["shopId", "invoiceNumber"], unique = true),
        Index(value = ["shopId", "issuedAt"]),
        Index(value = ["shopId", "docType"]),
        Index(value = ["syncStatus"])
    ]
)
data class InvoiceHeaderEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val deviceShortCode: String = "",
    val invoiceNumber: String,
    val docType: String = "SALE",
    val partyId: String? = null,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val paymentMode: String = "CASH",
    val subtotalPaise: Long,
    val discountPaise: Long = 0L,
    val cgstPaise: Long = 0L,
    val sgstPaise: Long = 0L,
    val igstPaise: Long = 0L,
    val grandTotalPaise: Long,
    val amountPaidPaise: Long,
    val balanceDuePaise: Long = 0L,
    val issuedAt: Long,
    val yearInt: Int,
    val monthInt: Int,
    val dayInt: Int,
    val isReturned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = SyncState.PENDING_UPLOAD
)

@Immutable
@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceHeaderEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["invoiceId"]),
        Index(value = ["shopId", "invoiceId"])
    ]
)
data class InvoiceLineEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val invoiceId: String,
    val productId: String,
    val skuId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val hsnSnapshot: String = "",
    val qtyMilliUnits: Long,
    val unitSnapshot: String,
    val unitCostPaise: Long,
    val unitSellPaise: Long,
    val gstRateBps: Int = 0,
    val lineTaxPaise: Long = 0L,
    val lineTotalPaise: Long,
    val lineProfitPaise: Long,
    val isReturned: Boolean = false
)

/** Append-only. Never updated or deleted; corrections are new rows. Source of truth for stock. */
@Immutable
@Entity(
    tableName = "stock_movements",
    indices = [
        Index(value = ["shopId", "skuId"]),
        Index(value = ["refDocId"])
    ]
)
data class StockMovementEntity(
    @PrimaryKey
    val movementId: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val skuId: String,
    val deviceId: String = "",
    val deviceShortCode: String = "",
    val deltaMilliUnits: Long,
    val movementType: String,
    val refDocId: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val createdByUid: String = ""
)

/** Append-only. Source of truth for Khata balances. + = party owes shop, − = shop owes party / advance. */
@Immutable
@Entity(
    tableName = "party_ledger_entries",
    indices = [
        Index(value = ["shopId", "partyId"]),
        Index(value = ["refDocId"])
    ]
)
data class PartyTransactionEntity(
    @PrimaryKey
    val entryId: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val partyId: String,
    val refDocId: String = "",
    val entryType: String,
    val amountDeltaPaise: Long,
    val paymentMode: String = "CASH",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val createdByUid: String = "",
    val deviceId: String = ""
)
```

Create `LedgerSupportEntities.kt`:

```kotlin
package com.shopledger.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per phone known to this shop. `isLocal = 1` marks this install. */
@Entity(
    tableName = "devices",
    indices = [Index(value = ["shopId", "deviceCode"], unique = true)]
)
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val shopId: String = DEFAULT_SHOP_ID,
    /** Short code used in receipt numbers (A, B, D1…). "L" until the phone joins a shop (sub-project 2). */
    val deviceCode: String,
    val label: String = "",
    val isLocal: Boolean = false,
    /** Last receipt sequence handed out on [lastSeqDate] (yyMMdd). */
    val lastInvoiceSeq: Long = 0L,
    val lastSeqDate: String = "",
    val createdAt: Long
)

/** Written in the same transaction as each change; deleted only after the server acknowledges it. */
@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["docType", "docId"])]
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val seq: Long = 0L,
    val docType: String,
    val docId: String,
    val op: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String = ""
)

/** Idempotency keys: a command with a seen actionId replays its stored result instead of writing again. */
@Entity(tableName = "applied_actions")
data class AppliedActionEntity(
    @PrimaryKey
    val actionId: String,
    val kind: String,
    val resultRef: String,
    val createdAt: Long
)
```

- [ ] **Step 6: Replace the DAOs**

Replace `InventoryDao.kt` entirely:

```kotlin
package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Transaction
    @Query("SELECT * FROM inventory_items WHERE isDeleted = 0 ORDER BY sortKey ASC")
    fun observeAllActiveItemsAlphabetically(): Flow<List<InventoryItemWithVariants>>

    @Transaction
    @Query("SELECT * FROM inventory_items WHERE isDeleted = 0 ORDER BY sortKey ASC")
    suspend fun getAllActiveItemsSnapshot(): List<InventoryItemWithVariants>

    @Query("SELECT * FROM inventory_items")
    suspend fun getAllRawItems(): List<InventoryItemEntity>

    @Query("SELECT * FROM item_variants")
    suspend fun getAllRawVariants(): List<ItemVariantEntity>

    @Query("SELECT COUNT(*) FROM inventory_items")
    suspend fun countItems(): Int

    @Query("SELECT * FROM inventory_items WHERE id = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): InventoryItemEntity?

    @Query("SELECT * FROM item_variants WHERE id = :variantId LIMIT 1")
    suspend fun getVariantById(variantId: String): ItemVariantEntity?

    @Query("SELECT * FROM item_variants WHERE itemId = :itemId")
    suspend fun getVariantsForItem(itemId: String): List<ItemVariantEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: InventoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVariant(variant: ItemVariantEntity)

    @Update
    suspend fun updateItem(item: InventoryItemEntity): Int

    @Update
    suspend fun updateVariant(variant: ItemVariantEntity): Int

    /** Projection update; called only by LedgerWriter right after inserting the matching movement. */
    @Query("UPDATE item_variants SET stockMilliUnits = stockMilliUnits + :deltaMilli WHERE id = :variantId")
    suspend fun addToStockProjection(variantId: String, deltaMilli: Long): Int

    @Query(
        "UPDATE item_variants SET stockMilliUnits = " +
            "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
    )
    suspend fun rebuildAllStockProjections()

    @Query("UPDATE inventory_items SET isDeleted = 1, updatedAt = :now WHERE id = :itemId")
    suspend fun softDeleteItem(itemId: String, now: Long)

    @Query("UPDATE item_variants SET isDeleted = 1, updatedAt = :now WHERE itemId = :itemId")
    suspend fun softDeleteVariantsForItem(itemId: String, now: Long)

    @Query("UPDATE item_variants SET isDeleted = 1, updatedAt = :now WHERE id = :variantId")
    suspend fun softDeleteSingleVariant(variantId: String, now: Long)
}
```

Replace `ShopPartyAndLedgerDao.kt` entirely:

```kotlin
package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.ShopProfileEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShopPartyAndLedgerDao {

    // Shop profile
    @Query("SELECT * FROM shops WHERE shopId = :shopId LIMIT 1")
    fun observeShopProfile(shopId: String = DEFAULT_SHOP_ID): Flow<ShopProfileEntity?>

    @Query("SELECT * FROM shops WHERE shopId = :shopId LIMIT 1")
    suspend fun getShopProfile(shopId: String = DEFAULT_SHOP_ID): ShopProfileEntity?

    @Upsert
    suspend fun upsertShopProfile(profile: ShopProfileEntity)

    // Parties (customers & suppliers)
    @Query("SELECT * FROM parties WHERE isDeleted = 0 ORDER BY currentBalancePaise DESC, name ASC")
    fun observeAllParties(): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE isDeleted = 0")
    suspend fun getAllActivePartiesSnapshot(): List<PartyEntity>

    @Query("SELECT * FROM parties")
    suspend fun getAllRawParties(): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE id = :id LIMIT 1")
    suspend fun getPartyById(id: String): PartyEntity?

    @Query("SELECT * FROM parties WHERE phone = :phone AND isDeleted = 0 LIMIT 1")
    suspend fun findPartyByPhone(phone: String): PartyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParty(party: PartyEntity)

    @Update
    suspend fun updateParty(party: PartyEntity): Int

    /** Projection update; called only by LedgerWriter right after inserting the matching entry. */
    @Query("UPDATE parties SET currentBalancePaise = currentBalancePaise + :deltaPaise WHERE id = :partyId")
    suspend fun addToPartyBalance(partyId: String, deltaPaise: Long): Int

    @Query(
        "UPDATE parties SET currentBalancePaise = " +
            "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
    )
    suspend fun rebuildAllPartyBalances()

    // Party ledger (append-only)
    @Query("SELECT * FROM party_ledger_entries WHERE partyId = :partyId ORDER BY createdAt DESC")
    fun observePartyLedger(partyId: String): Flow<List<PartyTransactionEntity>>

    @Query("SELECT * FROM party_ledger_entries ORDER BY createdAt DESC")
    fun observeAllLedgerEntries(): Flow<List<PartyTransactionEntity>>

    @Query("SELECT * FROM party_ledger_entries")
    suspend fun getAllLedgerEntries(): List<PartyTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedgerEntry(entry: PartyTransactionEntity)

    // Invoices. INTERIM: REPLACE inserts survive only until Task 4 makes invoices immutable.
    @Query("SELECT * FROM invoice_headers ORDER BY issuedAt DESC")
    fun observeAllInvoiceHeaders(): Flow<List<InvoiceHeaderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceHeader(header: InvoiceHeaderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceLines(lines: List<InvoiceLineEntity>)

    // Stock movements (append-only)
    @Query("SELECT * FROM stock_movements WHERE skuId = :skuId ORDER BY createdAt DESC")
    fun observeMovementsForSku(skuId: String): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements")
    suspend fun getAllMovements(): List<StockMovementEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStockMovement(movement: StockMovementEntity)
}
```

Create `LedgerSupportDaos.kt`:

```kotlin
package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shopledger.pos.data.local.entity.AppliedActionEntity
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE isLocal = 1 LIMIT 1")
    suspend fun getLocalDevice(): DeviceEntity?

    /** Hands out the next per-day sequence number. Call inside the transaction that uses it. */
    @Query(
        "UPDATE devices SET lastInvoiceSeq = CASE WHEN lastSeqDate = :dayKey THEN lastInvoiceSeq + 1 ELSE 1 END, " +
            "lastSeqDate = :dayKey WHERE deviceId = :deviceId"
    )
    suspend fun bumpInvoiceSeq(deviceId: String, dayKey: String): Int

    @Query("SELECT lastInvoiceSeq FROM devices WHERE deviceId = :deviceId")
    suspend fun getLastInvoiceSeq(deviceId: String): Long
}

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: SyncOutboxEntity): Long

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM sync_outbox ORDER BY seq ASC")
    suspend fun all(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox ORDER BY seq ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE seq IN (:seqs)")
    suspend fun deleteAcknowledged(seqs: List<Long>): Int
}

@Dao
interface AppliedActionDao {
    @Query("SELECT * FROM applied_actions WHERE actionId = :actionId LIMIT 1")
    suspend fun get(actionId: String): AppliedActionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(action: AppliedActionEntity)

    @Query("SELECT COUNT(*) FROM applied_actions")
    suspend fun count(): Int
}
```

- [ ] **Step 7: Seed rows and database v3**

Create `DatabaseSeed.kt`:

```kotlin
package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import java.util.UUID

/** Rows every database needs: a shop profile and exactly one `isLocal = 1` device. Idempotent. */
object DatabaseSeed {
    const val LOCAL_DEVICE_CODE = "L"

    fun seedFreshDatabase(
        db: SupportSQLiteDatabase,
        now: Long,
        deviceId: String = UUID.randomUUID().toString(),
        shopId: String = DEFAULT_SHOP_ID,
        deviceCode: String = LOCAL_DEVICE_CODE
    ) {
        db.execSQL(
            "INSERT OR IGNORE INTO shops (shopId, shopName, verticalType, ownerUid, gstin, stateCode, gstEnabled, " +
                "upiId, whatsappPhone, deviceShortCode, invoicePrefix, cipherMode, cipherWord10, ownerPinHash, " +
                "isSetupCompleted, maxStaffDiscountBps, updatedAt) VALUES (?, 'ShopLedger POS', 'HARDWARE', '', '', " +
                "'09', 0, '', '', '', 'INV', 'CUSTOM_CODE', ?, '', 1, 1000, ?)",
            arrayOf<Any>(shopId, CostCipherEngine.DEFAULT_WORD_10, now)
        )
        db.execSQL(
            "INSERT INTO devices (deviceId, shopId, deviceCode, label, isLocal, lastInvoiceSeq, lastSeqDate, createdAt) " +
                "SELECT ?, ?, ?, 'This phone', 1, 0, '', ? WHERE NOT EXISTS (SELECT 1 FROM devices WHERE isLocal = 1)",
            arrayOf<Any>(deviceId, shopId, deviceCode, now)
        )
    }
}
```

In `LpsDatabase.kt`:
1. Add `DeviceEntity::class, SyncOutboxEntity::class, AppliedActionEntity::class` to `entities`, and set `version = 3` (`exportSchema = true` is already set).
2. Add these abstract DAOs under the existing four:

```kotlin
    abstract fun deviceDao(): DeviceDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun appliedActionDao(): AppliedActionDao
```

3. Rename `private const val NEW_DB_NAME` to `const val DB_NAME` (public) and update its two uses.
4. Keep `MIGRATION_1_2` exactly as it is.
5. Replace the `Room.databaseBuilder(...)…build()` chain in `buildDatabase` with:

```kotlin
            return Room.databaseBuilder(appContext, LpsDatabase::class.java, DB_NAME)
                // WRITE_AHEAD_LOGGING allows concurrent UI reads while background work writes
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .withShopLedgerDefaults()
                .build()
```

6. Add the needed imports (`DeviceDao`, `SyncOutboxDao`, `AppliedActionDao`, `DeviceEntity`, `SyncOutboxEntity`, `AppliedActionEntity`). Then add this top-level function at the end of the file:

```kotlin
/** Shared by the app and tests so every database gets the same migrations and seed rows. */
fun RoomDatabase.Builder<LpsDatabase>.withShopLedgerDefaults(): RoomDatabase.Builder<LpsDatabase> =
    addMigrations(LpsDatabase.MIGRATION_1_2)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DatabaseSeed.seedFreshDatabase(db, System.currentTimeMillis())
            }
        })
```

- [ ] **Step 8: Implement `LedgerWriter`**

Create `LedgerWriter.kt`:

```kotlin
package com.shopledger.pos.data.command

import androidx.room.withTransaction
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.AppliedActionEntity
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import com.shopledger.pos.data.local.entity.SyncOutboxEntity

/**
 * The only code that appends ledger rows. Every append updates its cached projection in the same
 * transaction, so invariants I1 (stock) and I2 (balances) can never drift.
 */
class LedgerWriter(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val inventory = db.inventoryDao()
    private val ledger = db.shopPartyAndLedgerDao()
    private val outbox = db.syncOutboxDao()
    private val actions = db.appliedActionDao()
    private val devices = db.deviceDao()

    /**
     * Runs [body] in ONE transaction unless [actionId] was already applied, in which case [replay]
     * rebuilds the earlier result from its stored reference. `body` returns (result, resultRef).
     */
    suspend fun <T> runIdempotent(
        actionId: String,
        kind: String,
        replay: suspend (resultRef: String) -> T,
        body: suspend () -> Pair<T, String>
    ): T {
        if (actionId.isBlank()) throw CommandException.Validation("Missing action id")
        val result = db.withTransaction {
            val prior = actions.get(actionId)
            if (prior != null) {
                if (prior.kind != kind) {
                    throw CommandException.Validation("Action id already used for ${prior.kind}")
                }
                replay(prior.resultRef)
            } else {
                val (value, resultRef) = body()
                actions.insert(AppliedActionEntity(actionId = actionId, kind = kind, resultRef = resultRef, createdAt = env.now()))
                value
            }
        }
        env.afterCommit()
        return result
    }

    suspend fun localDevice(): DeviceEntity =
        devices.getLocalDevice() ?: throw IllegalStateException("Local device row is missing; database seed did not run")

    suspend fun appendMovement(
        skuId: String,
        deltaMilli: Long,
        movementType: String,
        refDocId: String,
        note: String,
        device: DeviceEntity,
        enqueue: Boolean
    ): StockMovementEntity {
        if (deltaMilli == 0L) throw CommandException.Validation("Stock change cannot be zero")
        val movement = StockMovementEntity(
            movementId = env.newId(),
            shopId = env.shopId(),
            skuId = skuId,
            deviceId = device.deviceId,
            deviceShortCode = device.deviceCode,
            deltaMilliUnits = deltaMilli,
            movementType = movementType,
            refDocId = refDocId,
            note = note,
            createdAt = env.now(),
            createdByUid = env.actor().uid
        )
        ledger.insertStockMovement(movement)
        if (inventory.addToStockProjection(skuId, deltaMilli) != 1) {
            throw CommandException.NotFound("Item variant $skuId")
        }
        if (enqueue) enqueue(OutboxDocType.MOVEMENT, movement.movementId, OutboxOp.APPEND)
        return movement
    }

    suspend fun appendLedgerEntry(
        partyId: String,
        deltaPaise: Long,
        entryType: String,
        paymentMode: String,
        refDocId: String,
        note: String,
        device: DeviceEntity,
        enqueue: Boolean
    ): PartyTransactionEntity {
        if (deltaPaise == 0L) throw CommandException.Validation("Amount cannot be zero")
        val entry = PartyTransactionEntity(
            entryId = env.newId(),
            shopId = env.shopId(),
            partyId = partyId,
            refDocId = refDocId,
            entryType = entryType,
            amountDeltaPaise = deltaPaise,
            paymentMode = paymentMode,
            note = note,
            createdAt = env.now(),
            createdByUid = env.actor().uid,
            deviceId = device.deviceId
        )
        ledger.insertLedgerEntry(entry)
        if (ledger.addToPartyBalance(partyId, deltaPaise) != 1) {
            throw CommandException.NotFound("Party $partyId")
        }
        if (enqueue) enqueue(OutboxDocType.LEDGER, entry.entryId, OutboxOp.APPEND)
        return entry
    }

    suspend fun enqueue(docType: String, docId: String, op: String) {
        outbox.insert(SyncOutboxEntity(docType = docType, docId = docId, op = op, createdAt = env.now()))
    }
}
```

- [ ] **Step 9: Implement `CatalogCommands`**

Create `CatalogCommands.kt`:

```kotlin
package com.shopledger.pos.data.command

import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import java.util.Locale

data class VariantSpec(
    val sizeLabel: String = "",
    val materialLabel: String = "",
    val colorFinishLabel: String = "",
    val unit: String = "pcs",
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val mrpPaise: Long = 0L,
    val openingStockMilli: Long = 0L,
    val lowStockMilli: Long = 5_000L,
    val barcode: String = "",
    val skuCode: String = "",
    val gstRateBps: Int = 0
)

data class VariantEdit(
    val sizeLabel: String,
    val materialLabel: String,
    val colorFinishLabel: String,
    val unit: String,
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val lowStockMilli: Long,
    val barcode: String,
    val skuCode: String,
    val gstRateBps: Int,
    /** Non-null: record an ADJUSTMENT so stock becomes exactly this (target − current, read in-transaction). */
    val targetStockMilli: Long? = null
)

data class PurchaseLine(val variantId: String, val qtyMilli: Long)

class CatalogCommands(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val writer = LedgerWriter(db, env)
    private val inventory = db.inventoryDao()
    private val parties = db.shopPartyAndLedgerDao()

    suspend fun createItem(
        actionId: String,
        displayName: String,
        category: String,
        variants: List<VariantSpec>,
        isDemo: Boolean = false
    ): String = writer.runIdempotent(actionId, "CREATE_ITEM", replay = { it }) {
        val name = displayName.trim()
        if (name.isEmpty()) throw CommandException.Validation("Item name is required")
        if (variants.isEmpty()) throw CommandException.Validation("Add at least one variant")
        variants.forEach(::validate)
        val device = writer.localDevice()
        val now = env.now()
        val itemId = env.newId()
        inventory.insertItem(
            InventoryItemEntity(
                id = itemId,
                shopId = env.shopId(),
                displayName = name,
                sortKey = name.uppercase(Locale.ROOT),
                category = category.trim().ifEmpty { "Uncategorized" },
                createdAt = now,
                updatedAt = now,
                isDemo = isDemo,
                createdByUid = env.actor().uid,
                deviceId = device.deviceId
            )
        )
        if (!isDemo) writer.enqueue(OutboxDocType.ITEM, itemId, OutboxOp.UPSERT)
        variants.forEach { insertVariant(itemId, it, device, isDemo) }
        itemId to itemId
    }

    suspend fun addVariant(
        actionId: String,
        itemId: String,
        spec: VariantSpec,
        renameTo: String? = null,
        recategorizeTo: String? = null
    ): String = writer.runIdempotent(actionId, "ADD_VARIANT", replay = { it }) {
        val item = inventory.getItemById(itemId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound("Item")
        validate(spec)
        val device = writer.localDevice()
        val newName = renameTo?.trim()?.takeIf { it.isNotEmpty() }
        val newCategory = recategorizeTo?.trim()?.takeIf { it.isNotEmpty() }
        if (newName != null || newCategory != null) {
            val name = newName ?: item.displayName
            inventory.updateItem(
                item.copy(
                    displayName = name,
                    sortKey = name.uppercase(Locale.ROOT),
                    category = newCategory ?: item.category,
                    updatedAt = env.now()
                )
            )
            if (!item.isDemo) writer.enqueue(OutboxDocType.ITEM, item.id, OutboxOp.UPSERT)
        }
        val variantId = insertVariant(item.id, spec, device, item.isDemo)
        variantId to variantId
    }

    suspend fun editItem(
        actionId: String,
        itemId: String,
        expectedItemUpdatedAt: Long,
        newDisplayName: String,
        newCategory: String,
        variantId: String,
        expectedVariantUpdatedAt: Long,
        edit: VariantEdit
    ) {
        writer.runIdempotent(actionId, "EDIT_ITEM", replay = { }) {
            val item = inventory.getItemById(itemId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound("Item")
            val variant = inventory.getVariantById(variantId)?.takeIf { it.itemId == itemId && !it.isDeleted }
                ?: throw CommandException.NotFound("Item variant")
            if (item.updatedAt != expectedItemUpdatedAt) throw CommandException.StaleEdit("Item \"${item.displayName}\"")
            if (variant.updatedAt != expectedVariantUpdatedAt) {
                throw CommandException.StaleEdit("Variant of \"${item.displayName}\"")
            }
            if (env.actor().role == Role.STAFF && edit.purchasePricePaise != variant.purchasePricePaise) {
                throw CommandException.PermissionDenied("Only the owner can change the cost price")
            }
            validatePrices(edit.purchasePricePaise, edit.sellingPricePaise, edit.lowStockMilli)
            val name = newDisplayName.trim()
            if (name.isEmpty()) throw CommandException.Validation("Item name is required")
            val device = writer.localDevice()
            val now = env.now()
            inventory.updateItem(
                item.copy(
                    displayName = name,
                    sortKey = name.uppercase(Locale.ROOT),
                    category = newCategory.trim().ifEmpty { "Uncategorized" },
                    updatedAt = now
                )
            )
            inventory.updateVariant(
                variant.copy(
                    sizeLabel = SmartItemMatcher.normalizeSizeString(edit.sizeLabel),
                    materialLabel = SmartItemMatcher.normalizeMaterialString(edit.materialLabel),
                    colorFinishLabel = SmartItemMatcher.normalizeColorString(edit.colorFinishLabel),
                    unit = edit.unit.trim().ifEmpty { "pcs" },
                    purchasePricePaise = edit.purchasePricePaise,
                    sellingPricePaise = edit.sellingPricePaise,
                    lowStockMilliUnits = edit.lowStockMilli,
                    barcode = edit.barcode.trim(),
                    skuCode = edit.skuCode.trim(),
                    gstRateBps = edit.gstRateBps,
                    updatedAt = now
                )
            )
            if (!item.isDemo) {
                writer.enqueue(OutboxDocType.ITEM, item.id, OutboxOp.UPSERT)
                writer.enqueue(OutboxDocType.VARIANT, variant.id, OutboxOp.UPSERT)
            }
            val target = edit.targetStockMilli
            if (target != null) {
                if (target < 0L) throw CommandException.Validation("Stock count cannot be negative")
                val current = inventory.getVariantById(variantId)!!.stockMilliUnits
                val delta = target - current
                if (delta != 0L) {
                    writer.appendMovement(
                        variantId, delta, MovementType.ADJUSTMENT, item.id,
                        "Stock counted: ${target / 1000.0}", device, enqueue = !item.isDemo
                    )
                }
            }
            Unit to itemId
        }
    }

    /** Deletes one variant, or the whole item when [variantId] is null or it is the last active variant. */
    suspend fun deleteItem(actionId: String, itemId: String, variantId: String?) {
        writer.runIdempotent(actionId, "DELETE_ITEM", replay = { }) {
            if (env.actor().role == Role.STAFF) throw CommandException.PermissionDenied("Only the owner can delete items")
            val item = inventory.getItemById(itemId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound("Item")
            val now = env.now()
            val active = inventory.getVariantsForItem(itemId).filter { !it.isDeleted }
            if (variantId == null || active.size <= 1) {
                inventory.softDeleteItem(itemId, now)
                inventory.softDeleteVariantsForItem(itemId, now)
                if (!item.isDemo) {
                    writer.enqueue(OutboxDocType.ITEM, itemId, OutboxOp.UPSERT)
                    active.forEach { writer.enqueue(OutboxDocType.VARIANT, it.id, OutboxOp.UPSERT) }
                }
            } else {
                if (active.none { it.id == variantId }) throw CommandException.NotFound("Item variant")
                inventory.softDeleteSingleVariant(variantId, now)
                if (!item.isDemo) writer.enqueue(OutboxDocType.VARIANT, variantId, OutboxOp.UPSERT)
            }
            Unit to itemId
        }
    }

    /** Give exactly one of [deltaMilli] (a correction) or [targetMilli] (a physical count). Returns new stock. */
    suspend fun adjustStock(
        actionId: String,
        variantId: String,
        deltaMilli: Long? = null,
        targetMilli: Long? = null,
        reason: String
    ): Long = writer.runIdempotent(
        actionId, "ADJUST_STOCK",
        replay = { ref -> inventory.getVariantById(ref)?.stockMilliUnits ?: 0L }
    ) {
        if ((deltaMilli == null) == (targetMilli == null)) {
            throw CommandException.Validation("Give either a stock change or a new count")
        }
        val variant = inventory.getVariantById(variantId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound("Item variant")
        if (targetMilli != null && targetMilli < 0L) throw CommandException.Validation("Stock count cannot be negative")
        val device = writer.localDevice()
        val delta = deltaMilli ?: (targetMilli!! - variant.stockMilliUnits)
        if (delta != 0L) {
            writer.appendMovement(
                variant.id, delta,
                if (targetMilli != null) MovementType.ADJUSTMENT else MovementType.CORRECTION,
                variant.itemId, reason.trim().ifEmpty { "Stock adjustment" }, device, enqueue = !variant.isDemo
            )
        }
        inventory.getVariantById(variant.id)!!.stockMilliUnits to variant.id
    }

    /** Stock received from a supplier. An unpaid remainder becomes a payable (negative supplier balance). */
    suspend fun receivePurchase(
        actionId: String,
        lines: List<PurchaseLine>,
        supplierPartyId: String? = null,
        billTotalPaise: Long = 0L,
        amountPaidPaise: Long = 0L,
        note: String = ""
    ): String = writer.runIdempotent(actionId, "RECEIVE_PURCHASE", replay = { it }) {
        if (lines.isEmpty()) throw CommandException.Validation("Add at least one item")
        if (lines.map { it.variantId }.toSet().size != lines.size) {
            throw CommandException.Validation("Each item can appear only once")
        }
        if (billTotalPaise < 0L || amountPaidPaise < 0L || amountPaidPaise > billTotalPaise) {
            throw CommandException.Validation("Paid amount must be between 0 and the bill total")
        }
        val device = writer.localDevice()
        val purchaseId = env.newId()
        for (line in lines) {
            if (line.qtyMilli <= 0L) throw CommandException.Validation("Quantity must be more than 0")
            val variant = inventory.getVariantById(line.variantId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound("Item variant")
            writer.appendMovement(
                variant.id, line.qtyMilli, MovementType.PURCHASE, purchaseId,
                note.trim().ifEmpty { "Purchase received" }, device, enqueue = !variant.isDemo
            )
        }
        val due = billTotalPaise - amountPaidPaise
        if (supplierPartyId != null && due > 0L) {
            parties.getPartyById(supplierPartyId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound("Supplier")
            writer.appendLedgerEntry(
                supplierPartyId, -due, LedgerEntryType.CREDIT_BILL, "CREDIT", purchaseId,
                "Purchase bill", device, enqueue = true
            )
        }
        purchaseId to purchaseId
    }

    private suspend fun insertVariant(itemId: String, spec: VariantSpec, device: DeviceEntity, isDemo: Boolean): String {
        val variantId = env.newId()
        val now = env.now()
        inventory.insertVariant(
            ItemVariantEntity(
                id = variantId,
                shopId = env.shopId(),
                itemId = itemId,
                skuCode = spec.skuCode.trim(),
                barcode = spec.barcode.trim(),
                sizeLabel = SmartItemMatcher.normalizeSizeString(spec.sizeLabel),
                materialLabel = SmartItemMatcher.normalizeMaterialString(spec.materialLabel),
                colorFinishLabel = SmartItemMatcher.normalizeColorString(spec.colorFinishLabel),
                unit = spec.unit.trim().ifEmpty { "pcs" },
                mrpPaise = spec.mrpPaise,
                purchasePricePaise = spec.purchasePricePaise,
                sellingPricePaise = spec.sellingPricePaise,
                gstRateBps = spec.gstRateBps,
                stockMilliUnits = 0L,
                lowStockMilliUnits = spec.lowStockMilli,
                createdAt = now,
                updatedAt = now,
                isDemo = isDemo,
                createdByUid = env.actor().uid,
                deviceId = device.deviceId
            )
        )
        if (!isDemo) writer.enqueue(OutboxDocType.VARIANT, variantId, OutboxOp.UPSERT)
        if (spec.openingStockMilli != 0L) {
            writer.appendMovement(variantId, spec.openingStockMilli, MovementType.OPENING, itemId, "Opening stock", device, enqueue = !isDemo)
        }
        return variantId
    }

    private fun validate(spec: VariantSpec) {
        validatePrices(spec.purchasePricePaise, spec.sellingPricePaise, spec.lowStockMilli)
        if (spec.openingStockMilli < 0L) throw CommandException.Validation("Opening stock cannot be negative")
    }

    private fun validatePrices(costPaise: Long, sellPaise: Long, lowStockMilli: Long) {
        if (costPaise < 0L || sellPaise < 0L) throw CommandException.Validation("Prices cannot be negative")
        if (lowStockMilli < 0L) throw CommandException.Validation("Low-stock alert cannot be negative")
    }
}
```

- [ ] **Step 10: Implement `KhataCommands` and `AppCommandEnvironment`**

Create `KhataCommands.kt`:

```kotlin
package com.shopledger.pos.data.command

import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.PartyEntity
import java.util.Locale

enum class PaymentDirection { IN, OUT }

class KhataCommands(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val writer = LedgerWriter(db, env)
    private val parties = db.shopPartyAndLedgerDao()

    suspend fun createParty(
        actionId: String,
        name: String,
        phone: String,
        partyType: String = "CUSTOMER",
        gstin: String = "",
        openingBalancePaise: Long = 0L
    ): String = writer.runIdempotent(actionId, "CREATE_PARTY", replay = { it }) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) throw CommandException.Validation("Name is required")
        val type = normalizeType(partyType)
        val device = writer.localDevice()
        val now = env.now()
        val partyId = env.newId()
        parties.insertParty(
            PartyEntity(
                id = partyId,
                shopId = env.shopId(),
                partyType = type,
                name = cleanName,
                phone = phone.trim(),
                gstin = gstin.trim().uppercase(Locale.ROOT),
                currentBalancePaise = 0L,
                createdAt = now,
                updatedAt = now,
                createdByUid = env.actor().uid,
                deviceId = device.deviceId
            )
        )
        writer.enqueue(OutboxDocType.PARTY, partyId, OutboxOp.UPSERT)
        if (openingBalancePaise != 0L) {
            writer.appendLedgerEntry(partyId, openingBalancePaise, LedgerEntryType.OPENING, "CASH", partyId, "Opening balance", device, enqueue = true)
        }
        partyId to partyId
    }

    suspend fun updateParty(
        actionId: String,
        partyId: String,
        expectedUpdatedAt: Long,
        name: String,
        phone: String,
        partyType: String,
        gstin: String
    ) {
        writer.runIdempotent(actionId, "UPDATE_PARTY", replay = { }) {
            val current = parties.getPartyById(partyId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound("Party")
            if (current.updatedAt != expectedUpdatedAt) throw CommandException.StaleEdit("Party \"${current.name}\"")
            val cleanName = name.trim()
            if (cleanName.isEmpty()) throw CommandException.Validation("Name is required")
            parties.updateParty(
                current.copy(
                    name = cleanName,
                    phone = phone.trim(),
                    partyType = normalizeType(partyType),
                    gstin = gstin.trim().uppercase(Locale.ROOT),
                    updatedAt = env.now()
                )
            )
            writer.enqueue(OutboxDocType.PARTY, partyId, OutboxOp.UPSERT)
            Unit to partyId
        }
    }

    /** IN = money received from the party (balance goes down). OUT = money paid to the party (goes up). */
    suspend fun recordPayment(
        actionId: String,
        partyId: String,
        amountPaise: Long,
        paymentMode: String = "CASH",
        direction: PaymentDirection = PaymentDirection.IN,
        note: String = ""
    ): String = writer.runIdempotent(actionId, "RECORD_PAYMENT", replay = { it }) {
        if (amountPaise <= 0L) throw CommandException.Validation("Amount must be more than 0")
        val party = parties.getPartyById(partyId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound("Party")
        val mode = paymentMode.trim().uppercase(Locale.ROOT).ifEmpty { "CASH" }
        val incoming = direction == PaymentDirection.IN
        val entry = writer.appendLedgerEntry(
            partyId = party.id,
            deltaPaise = if (incoming) -amountPaise else amountPaise,
            entryType = if (incoming) LedgerEntryType.PAYMENT_IN else LedgerEntryType.PAYMENT_OUT,
            paymentMode = mode,
            refDocId = "",
            note = note.trim().ifEmpty { if (incoming) "Payment received ($mode)" else "Payment made ($mode)" },
            device = writer.localDevice(),
            enqueue = true
        )
        entry.entryId to entry.entryId
    }

    private fun normalizeType(raw: String): String {
        val type = raw.trim().uppercase(Locale.ROOT)
        if (type !in setOf("CUSTOMER", "SUPPLIER", "BOTH")) throw CommandException.Validation("Unknown party type $raw")
        return type
    }
}
```

Create `AppCommandEnvironment.kt`:

```kotlin
package com.shopledger.pos.data.command

import android.content.Context
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.sync.SyncManager
import java.util.UUID

/** Production environment. Roles arrive with staff accounts in sub-project 2; until then everyone is OWNER. */
class AppCommandEnvironment(private val context: Context) : CommandEnvironment {
    override fun shopId(): String = SyncManager.getConfig(context).shopCollectionId
    override fun actor(): Actor = Actor(uid = GoogleAuthManager.currentUser.value?.firebaseUid.orEmpty(), role = Role.OWNER)
    override fun gstEnabled(): Boolean = SyncManager.gstEnabledFlow.value
    override fun now(): Long = System.currentTimeMillis()
    override fun newId(): String = UUID.randomUUID().toString()
    override fun afterCommit() = SyncManager.scheduleDeltaSync(context)
}
```

- [ ] **Step 11: Implement `LegacyJsonImporter` (catalog and parties; sales stay v2-shaped until Task 4)**

Create `LegacyJsonImporter.kt`:

```kotlin
package com.shopledger.pos.data.command

import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Imports v1/v2 JSON backups by MERGING: rows whose id already exists are skipped. Stock and
 * balances arrive as OPENING ledger rows, so invariants hold. Re-importing the same file is a no-op.
 */
class LegacyJsonImporter(private val db: LpsDatabase, private val env: CommandEnvironment) {
    data class Report(val items: Int, val variants: Int, val parties: Int, val sales: Int)

    suspend fun import(json: String): Report {
        val root = JSONObject(json)
        val writer = LedgerWriter(db, env)
        val inventory = db.inventoryDao()
        val ledger = db.shopPartyAndLedgerDao()
        return writer.runIdempotent("legacy-import-${sha256(json)}", "LEGACY_IMPORT", replay = { Report(0, 0, 0, 0) }) {
            val device = writer.localDevice()
            val shopId = env.shopId()
            val now = env.now()
            val itemIds = inventory.getAllRawItems().map { it.id }.toHashSet()
            val variantIds = inventory.getAllRawVariants().map { it.id }.toHashSet()
            val partyIds = ledger.getAllRawParties().map { it.id }.toHashSet()
            var items = 0
            var variants = 0
            var parties = 0

            root.optJSONArray("items").forEachObject { o ->
                val id = o.getString("id")
                if (id in itemIds) return@forEachObject
                val name = o.getString("displayName").trim()
                inventory.insertItem(
                    InventoryItemEntity(
                        id = id,
                        shopId = o.optString("shopId", shopId),
                        displayName = name,
                        sortKey = o.optString("sortKey", name.uppercase(Locale.ROOT)),
                        category = o.optString("category", "Uncategorized"),
                        createdAt = o.optLong("createdAt", now),
                        updatedAt = o.optLong("updatedAt", now),
                        isDeleted = o.optBoolean("isDeleted", false),
                        createdByUid = env.actor().uid,
                        deviceId = device.deviceId
                    )
                )
                itemIds += id
                writer.enqueue(OutboxDocType.ITEM, id, OutboxOp.UPSERT)
                items++
            }

            root.optJSONArray("variants").forEachObject { o ->
                val id = o.getString("id")
                val itemId = o.getString("itemId")
                if (id in variantIds || itemId !in itemIds) return@forEachObject
                inventory.insertVariant(
                    ItemVariantEntity(
                        id = id,
                        shopId = o.optString("shopId", shopId),
                        itemId = itemId,
                        skuCode = o.optString("skuCode", ""),
                        barcode = o.optString("barcode", ""),
                        sizeLabel = o.optString("sizeLabel", ""),
                        materialLabel = o.optString("materialLabel", ""),
                        colorFinishLabel = o.optString("colorFinishLabel", ""),
                        unit = o.optString("unit", "pcs"),
                        purchasePricePaise = o.getLong("purchasePricePaise"),
                        sellingPricePaise = o.getLong("sellingPricePaise"),
                        gstRateBps = o.optInt("gstRateBps", 0),
                        stockMilliUnits = 0L,
                        lowStockMilliUnits = (o.optDouble("lowStockThreshold", 5.0) * 1000.0).roundToLong(),
                        createdAt = o.optLong("updatedAt", now),
                        updatedAt = o.optLong("updatedAt", now),
                        isDeleted = o.optBoolean("isDeleted", false),
                        createdByUid = env.actor().uid,
                        deviceId = device.deviceId
                    )
                )
                variantIds += id
                writer.enqueue(OutboxDocType.VARIANT, id, OutboxOp.UPSERT)
                val stockMilli = (o.optDouble("stockQuantity", 0.0) * 1000.0).roundToLong()
                if (stockMilli != 0L) {
                    writer.appendMovement(id, stockMilli, MovementType.OPENING, itemId, "Imported opening stock", device, enqueue = true)
                }
                variants++
            }

            root.optJSONArray("parties").forEachObject { o ->
                val id = o.getString("id")
                if (id in partyIds) return@forEachObject
                ledger.insertParty(
                    PartyEntity(
                        id = id,
                        shopId = o.optString("shopId", shopId),
                        partyType = o.optString("partyType", "CUSTOMER"),
                        name = o.getString("name"),
                        phone = o.optString("phone", ""),
                        gstin = o.optString("gstin", ""),
                        currentBalancePaise = 0L,
                        createdAt = o.optLong("updatedAt", now),
                        updatedAt = o.optLong("updatedAt", now),
                        isDeleted = o.optBoolean("isDeleted", false),
                        createdByUid = env.actor().uid,
                        deviceId = device.deviceId
                    )
                )
                partyIds += id
                writer.enqueue(OutboxDocType.PARTY, id, OutboxOp.UPSERT)
                val balance = o.optLong("currentBalancePaise", 0L)
                if (balance != 0L) {
                    writer.appendLedgerEntry(id, balance, LedgerEntryType.OPENING, "CASH", id, "Imported balance", device, enqueue = true)
                }
                parties++
            }

            val sales = importSales(root.optJSONArray("sales"), shopId, now)
            Report(items, variants, parties, sales) to "imported"
        }
    }

    /** INTERIM (Task 3): writes the v2 sale_records table. Task 4 rewrites this to build immutable invoices. */
    private suspend fun importSales(sales: JSONArray?, shopId: String, now: Long): Int {
        val saleDao = db.saleDao()
        val existing = saleDao.getAllRawSales().map { it.id }.toHashSet()
        val rows = ArrayList<SaleRecordEntity>()
        sales.forEachObject { o ->
            val id = o.getString("id")
            if (id in existing) return@forEachObject
            val revenue = o.getLong("totalRevenuePaise")
            rows += SaleRecordEntity(
                id = id,
                shopId = o.optString("shopId", shopId),
                receiptNumber = o.optString("receiptNumber", "IMPORTED"),
                buyerName = o.optString("buyerName", ""),
                buyerPhone = o.optString("buyerPhone", ""),
                itemId = o.getString("itemId"),
                variantId = o.getString("variantId"),
                itemNameSnapshot = o.getString("itemNameSnapshot"),
                variantSummarySnapshot = o.optString("variantSummarySnapshot", "Standard"),
                quantitySold = o.getDouble("quantitySold"),
                unitSnapshot = o.optString("unitSnapshot", "pcs"),
                unitPurchasePricePaise = o.getLong("unitPurchasePricePaise"),
                unitSellingPricePaise = o.getLong("unitSellingPricePaise"),
                totalCostPaise = o.optLong("totalCostPaise", 0L),
                totalRevenuePaise = revenue,
                totalProfitPaise = o.optLong("totalProfitPaise", 0L),
                gainMarkupPercent = o.optDouble("gainMarkupPercent", 0.0),
                grossMarginPercent = o.optDouble("grossMarginPercent", 0.0),
                paymentMode = o.optString("paymentMode", "CASH"),
                soldAt = o.optLong("soldAt", now),
                yearInt = o.optInt("yearInt", 2026),
                monthInt = o.optInt("monthInt", 1),
                dayInt = o.optInt("dayInt", 1),
                isReturned = o.optBoolean("isReturned", false)
            )
        }
        if (rows.isNotEmpty()) saleDao.insertSales(rows)
        return rows.size
    }

    private inline fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) block(getJSONObject(i))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 12: Stub the v2 sync worker and gate scheduling**

Replace `FirestoreDeltaSyncWorker.kt` entirely:

```kotlin
package com.shopledger.pos.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Disabled until sub-project 2 (Cloud & accounts) rebuilds sync on top of `sync_outbox`.
 * The v2 implementation pushed absolute stock and balances, which loses updates across devices.
 */
class FirestoreDeltaSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}
```

In `SyncManager.kt`, add `private const val CLOUD_SYNC_ENABLED = false` next to `WORK_NAME`. Then put these lines at the very top of `scheduleDeltaSync`, before `val constraints = …`:

```kotlin
        if (!CLOUD_SYNC_ENABLED) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            _lastSyncMessage.value = "Cloud sync coming soon"
            return
        }
```

- [ ] **Step 13: Route repository writes through the commands**

In `LpsRepository.kt`:

1. Change the class header and the fields under it to:

```kotlin
class LpsRepository(
    private val context: Context,
    private val database: LpsDatabase,
    private val env: CommandEnvironment = AppCommandEnvironment(context)
) {
    private val inventoryDao = database.inventoryDao()
    private val saleDao = database.saleDao()
    private val searchHistoryDao = database.searchHistoryDao()
    private val shopDao = database.shopPartyAndLedgerDao()
    private val catalog = CatalogCommands(database, env)
    private val khata = KhataCommands(database, env)
```

2. Replace `totalPendingSyncCountFlow` with:

```kotlin
    val totalPendingSyncCountFlow: Flow<Int> = database.syncOutboxDao().observePendingCount()
```

3. Add `toVariantSpec()` inside `NewVariantSpec`, leaving its fields unchanged, and add a private id helper below the data class:

```kotlin
    data class NewVariantSpec(
        val sizeLabel: String = "",
        val materialLabel: String = "",
        val colorFinishLabel: String = "",
        val unit: String = "pcs",
        val purchasePricePaise: Long,
        val sellingPricePaise: Long,
        val stockQuantity: Double = 10.0,
        val lowStockThreshold: Double = 5.0,
        val barcode: String = "",
        val skuCode: String = "",
        val gstRateBps: Int = 0
    ) {
        fun toVariantSpec() = VariantSpec(
            sizeLabel = sizeLabel,
            materialLabel = materialLabel,
            colorFinishLabel = colorFinishLabel,
            unit = unit,
            purchasePricePaise = purchasePricePaise,
            sellingPricePaise = sellingPricePaise,
            openingStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(stockQuantity),
            lowStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(lowStockThreshold),
            barcode = barcode,
            skuCode = skuCode,
            gstRateBps = gstRateBps
        )
    }

    private fun newActionId(): String = UUID.randomUUID().toString()
```

4. Delete `private fun activeShopId()` and replace every remaining call to `activeShopId()` with `env.shopId()`.
5. Replace the bodies of `addNewItemWithVariants`, `addNewItemWithVariant`, `addVariantToExistingItem`, `updateItemAndVariant` and `deleteItemOrVariant` (keep their parameter lists exactly as they are):

```kotlin
    suspend fun addNewItemWithVariants(
        displayName: String,
        category: String,
        variants: List<NewVariantSpec>
    ): String = catalog.createItem(newActionId(), displayName, category, variants.map { it.toVariantSpec() })

    suspend fun addNewItemWithVariant(
        displayName: String,
        category: String,
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double,
        barcode: String = "",
        skuCode: String = "",
        gstRateBps: Int = 0
    ): String = addNewItemWithVariants(
        displayName, category,
        listOf(NewVariantSpec(sizeLabel, materialLabel, colorFinishLabel, unit, purchasePricePaise, sellingPricePaise, stockQuantity, lowStockThreshold, barcode, skuCode, gstRateBps))
    )

    suspend fun addVariantToExistingItem(
        itemId: String,
        newDisplayName: String? = null,
        newCategory: String? = null,
        sizeLabel: String,
        materialLabel: String,
        colorFinishLabel: String,
        unit: String,
        purchasePricePaise: Long,
        sellingPricePaise: Long,
        stockQuantity: Double,
        lowStockThreshold: Double,
        barcode: String = "",
        skuCode: String = "",
        gstRateBps: Int = 0
    ): String = catalog.addVariant(
        newActionId(), itemId,
        NewVariantSpec(sizeLabel, materialLabel, colorFinishLabel, unit, purchasePricePaise, sellingPricePaise, stockQuantity, lowStockThreshold, barcode, skuCode, gstRateBps).toVariantSpec(),
        renameTo = newDisplayName,
        recategorizeTo = newCategory
    )

    suspend fun updateItemAndVariant(
        item: InventoryItemEntity,
        newDisplayName: String,
        newCategory: String,
        variant: ItemVariantEntity,
        newSizeLabel: String,
        newMaterialLabel: String,
        newColorFinishLabel: String,
        newUnit: String,
        newPurchasePricePaise: Long,
        newSellingPricePaise: Long,
        newStockQuantity: Double,
        newLowStockThreshold: Double,
        newBarcode: String = variant.barcode,
        newSkuCode: String = variant.skuCode,
        newGstRateBps: Int = variant.gstRateBps
    ) {
        val newStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(newStockQuantity)
        catalog.editItem(
            actionId = newActionId(),
            itemId = item.id,
            expectedItemUpdatedAt = item.updatedAt,
            newDisplayName = newDisplayName,
            newCategory = newCategory,
            variantId = variant.id,
            expectedVariantUpdatedAt = variant.updatedAt,
            edit = VariantEdit(
                sizeLabel = newSizeLabel,
                materialLabel = newMaterialLabel,
                colorFinishLabel = newColorFinishLabel,
                unit = newUnit,
                purchasePricePaise = newPurchasePricePaise,
                sellingPricePaise = newSellingPricePaise,
                lowStockMilli = IndianCurrencyFormatter.doubleToMilliUnits(newLowStockThreshold),
                barcode = newBarcode,
                skuCode = newSkuCode,
                gstRateBps = newGstRateBps,
                // Only when the user actually changed the count in the form; otherwise leave stock alone.
                targetStockMilli = newStockMilli.takeIf { it != variant.stockMilliUnits }
            )
        )
    }

    suspend fun deleteItemOrVariant(itemId: String, variantId: String?, totalVariantsCount: Int) =
        catalog.deleteItem(newActionId(), itemId, variantId.takeIf { totalVariantsCount > 1 })
```

6. In `executeCheckoutAndDeductStock` (INTERIM, Task 4 replaces it), replace everything from the comment `// Resolve or create PartyEntity…` down to the end of `database.withTransaction { … }` with the block below. Keep the receipt computations above it and the `SaleReceiptData(...)` return below it. Also delete `syncStatus = SyncState.PENDING_UPLOAD` from the `SaleRecordEntity(...)` and `InvoiceHeaderEntity(...)` constructor calls in that function (both have defaults):

```kotlin
        var resolvedPartyId: String? = null
        var newParty: PartyEntity? = null
        if (balanceDuePaise > 0L || cleanBuyerPhone.isNotBlank()) {
            val phoneDigits = cleanBuyerPhone.filter { it.isDigit() }.takeLast(10)
            val matched = shopDao.getAllActivePartiesSnapshot().firstOrNull { p ->
                (phoneDigits.length >= 6 && p.phone.filter { it.isDigit() }.takeLast(10) == phoneDigits) ||
                    (cleanBuyerName.isNotBlank() && p.name.equals(cleanBuyerName, ignoreCase = true))
            }
            if (matched != null) {
                resolvedPartyId = matched.id
            } else if (balanceDuePaise > 0L || cleanBuyerName.isNotBlank() || phoneDigits.length >= 6) {
                val created = PartyEntity(
                    id = UUID.randomUUID().toString(),
                    shopId = shopId,
                    name = cleanBuyerName.ifBlank { cleanBuyerPhone.ifBlank { "Credit Customer" } },
                    phone = if (phoneDigits.length >= 10) phoneDigits else cleanBuyerPhone,
                    createdAt = now,
                    updatedAt = now
                )
                newParty = created
                resolvedPartyId = created.id
            }
        }
        // vals (not vars) so they can be used inside the transaction lambda below
        val partyToCreate: PartyEntity? = newParty
        val ledgerPartyId: String? = resolvedPartyId

        for (line in cartItems) {
            val analysis = IndianCurrencyFormatter.computeProfitAnalysis(
                unitPurchasePaise = line.unitPurchasePricePaise,
                unitSellingPaise = line.customSellingPricePaise,
                quantity = line.quantity
            )
            val lineId = UUID.randomUUID().toString()
            val qtyMilli = IndianCurrencyFormatter.doubleToMilliUnits(line.quantity)
            saleEntities.add(
                SaleRecordEntity(
                    id = lineId, shopId = shopId, receiptNumber = receiptNo, buyerName = cleanBuyerName,
                    buyerPhone = cleanBuyerPhone, partyId = resolvedPartyId, itemId = line.itemId,
                    variantId = line.variantId, itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary, quantitySold = line.quantity,
                    unitSnapshot = line.unit, unitPurchasePricePaise = line.unitPurchasePricePaise,
                    unitSellingPricePaise = line.customSellingPricePaise, totalCostPaise = analysis.totalCostPaise,
                    totalRevenuePaise = analysis.totalRevenuePaise, totalProfitPaise = analysis.totalProfitPaise,
                    gainMarkupPercent = analysis.gainMarkupPercent, grossMarginPercent = analysis.grossMarginPercent,
                    paymentMode = normalizedPaymentMode, amountPaidPaise = resolvedPaidPaise,
                    balanceDuePaise = balanceDuePaise, discountPaise = cleanDiscount,
                    gstPaise = taxBreakdown.totalTaxPaise, soldAt = now, yearInt = yearInt, monthInt = monthInt,
                    dayInt = dayInt, updatedAt = now
                )
            )
            invoiceLines.add(
                InvoiceLineEntity(
                    id = lineId, shopId = shopId, invoiceId = invoiceId, productId = line.itemId,
                    skuId = line.variantId, itemNameSnapshot = line.itemName,
                    variantSummarySnapshot = line.variantSummary, qtyMilliUnits = qtyMilli, unitSnapshot = line.unit,
                    unitCostPaise = line.unitPurchasePricePaise, unitSellPaise = line.customSellingPricePaise,
                    gstRateBps = effectiveGstBps, lineTotalPaise = analysis.totalRevenuePaise,
                    lineProfitPaise = analysis.totalProfitPaise
                )
            )
        }

        val invoiceHeader = InvoiceHeaderEntity(
            id = invoiceId, shopId = shopId, deviceShortCode = deviceCode, invoiceNumber = receiptNo,
            partyId = resolvedPartyId, buyerName = cleanBuyerName, buyerPhone = cleanBuyerPhone,
            paymentMode = normalizedPaymentMode, subtotalPaise = subtotalPaise, discountPaise = cleanDiscount,
            cgstPaise = taxBreakdown.cgstPaise, sgstPaise = taxBreakdown.sgstPaise, igstPaise = taxBreakdown.igstPaise,
            grandTotalPaise = grandTotalPaise, amountPaidPaise = resolvedPaidPaise, balanceDuePaise = balanceDuePaise,
            issuedAt = now, yearInt = yearInt, monthInt = monthInt, dayInt = dayInt, updatedAt = now
        )

        database.withTransaction {
            val device = database.deviceDao().getLocalDevice() ?: error("Local device row missing")
            for (line in invoiceLines) {
                shopDao.insertStockMovement(
                    StockMovementEntity(
                        movementId = UUID.randomUUID().toString(), shopId = shopId, skuId = line.skuId,
                        deviceId = device.deviceId, deviceShortCode = device.deviceCode,
                        deltaMilliUnits = -line.qtyMilliUnits, movementType = "SALE", refDocId = invoiceId,
                        note = "Sale #$receiptNo", createdAt = now
                    )
                )
                inventoryDao.addToStockProjection(line.skuId, -line.qtyMilliUnits)
            }
            saleDao.insertSales(saleEntities)
            shopDao.upsertInvoiceHeader(invoiceHeader)
            shopDao.upsertInvoiceLines(invoiceLines)
            if (partyToCreate != null) shopDao.insertParty(partyToCreate)
            if (ledgerPartyId != null && balanceDuePaise > 0L) {
                shopDao.insertLedgerEntry(
                    PartyTransactionEntity(
                        entryId = UUID.randomUUID().toString(), shopId = shopId, partyId = ledgerPartyId,
                        refDocId = invoiceId, entryType = "CREDIT_BILL", amountDeltaPaise = balanceDuePaise,
                        paymentMode = normalizedPaymentMode, note = "Invoice #$receiptNo", createdAt = now,
                        deviceId = device.deviceId
                    )
                )
                shopDao.addToPartyBalance(ledgerPartyId, balanceDuePaise)
            }
            database.syncOutboxDao().insert(SyncOutboxEntity(docType = OutboxDocType.INVOICE, docId = invoiceId, op = OutboxOp.APPEND, createdAt = now))
            if (partyToCreate != null) database.syncOutboxDao().insert(SyncOutboxEntity(docType = OutboxDocType.PARTY, docId = partyToCreate.id, op = OutboxOp.UPSERT, createdAt = now))
        }
```

Then delete the now-unused `val stockMovements = ArrayList<StockMovementEntity>(cartItems.size)` declaration near the top of the function. The old `partyToSave` and `partyLedgerToInsert` vars were inside the replaced region, so they're already gone.

7. Replace `returnSaleAndRestoreStock` (INTERIM, Task 4 replaces it):

```kotlin
    suspend fun returnSaleAndRestoreStock(sale: SaleRecordEntity) = withContext(Dispatchers.IO) {
        if (sale.isReturned) return@withContext
        val now = System.currentTimeMillis()
        val qtyMilli = IndianCurrencyFormatter.doubleToMilliUnits(sale.quantitySold)
        database.withTransaction {
            val device = database.deviceDao().getLocalDevice() ?: error("Local device row missing")
            saleDao.markSaleReturned(sale.id, now)
            val movement = StockMovementEntity(
                movementId = UUID.randomUUID().toString(), shopId = sale.shopId.ifBlank { env.shopId() },
                skuId = sale.variantId, deviceId = device.deviceId, deviceShortCode = device.deviceCode,
                deltaMilliUnits = qtyMilli, movementType = "SALE_RETURN", refDocId = sale.receiptNumber,
                note = "Return of #${sale.receiptNumber}", createdAt = now
            )
            shopDao.insertStockMovement(movement)
            inventoryDao.addToStockProjection(sale.variantId, qtyMilli)
            database.syncOutboxDao().insert(SyncOutboxEntity(docType = OutboxDocType.MOVEMENT, docId = movement.movementId, op = OutboxOp.APPEND, createdAt = now))
        }
        SyncManager.scheduleDeltaSync(context)
    }
```

8. Replace `addOrUpdateParty` and `recordPartyPaymentReceived`:

```kotlin
    suspend fun addOrUpdateParty(
        id: String? = null,
        name: String,
        phone: String,
        partyType: String = "CUSTOMER",
        gstin: String = "",
        openingBalancePaise: Long = 0L
    ): String {
        if (id.isNullOrBlank()) {
            return khata.createParty(newActionId(), name, phone, partyType, gstin, openingBalancePaise)
        }
        val current = shopDao.getPartyById(id) ?: throw CommandException.NotFound("Party")
        khata.updateParty(newActionId(), id, current.updatedAt, name, phone, partyType, gstin)
        return id
    }

    suspend fun recordPartyPaymentReceived(
        partyId: String,
        amountPaise: Long,
        paymentMode: String = "CASH",
        note: String = ""
    ) {
        khata.recordPayment(newActionId(), partyId, amountPaise, paymentMode, PaymentDirection.IN, note)
    }
```

9. Replace `importBackupJson`:

```kotlin
    suspend fun importBackupJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { LegacyJsonImporter(database, env).import(jsonString) }
            .onFailure { it.printStackTrace() }
            .isSuccess
    }
```

10. Replace `seedStarterCatalogIfEmpty`, and delete `addSeedItemWithVariant` and `addSeedVariantToItem`:

```kotlin
    suspend fun seedStarterCatalogIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("lps_seed_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("catalog_seeded_v1", false)) return@withContext
        if (inventoryDao.countItems() > 0) {
            prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
            return@withContext
        }
        fun spec(size: String, material: String, color: String, unit: String, cost: Long, sell: Long, stock: Long, low: Long) =
            VariantSpec(
                sizeLabel = size, materialLabel = material, colorFinishLabel = color, unit = unit,
                purchasePricePaise = cost, sellingPricePaise = sell,
                openingStockMilli = stock * 1000L, lowStockMilli = low * 1000L
            )
        // Fixed action ids make seeding idempotent even if the app is killed half-way.
        catalog.createItem("seed-v1-hinge", "Butt Hinge (Kabza)", "Door & Window Fittings", listOf(
            spec("4 inch (100mm)", "SS 304", "Silver / Chrome", "pcs", 4800L, 7500L, 40, 10),
            spec("4 inch (100mm)", "SS 304", "Matt Black", "pcs", 6500L, 9500L, 18, 6),
            spec("6 inch (150mm)", "Brass", "Antique Brass", "pcs", 14500L, 21000L, 12, 4)
        ), isDemo = true)
        catalog.createItem("seed-v1-screw", "Drywall Screw (Black)", "Fasteners & Screws", listOf(
            spec("1.5 inch (38mm)", "MS (Iron)", "Black", "box", 13500L, 19000L, 15, 5),
            spec("2 inch (50mm)", "MS (Iron)", "Black", "box", 16000L, 22500L, 4, 5)
        ), isDemo = true)
        catalog.createItem("seed-v1-bolt", "Tower Bolt (Chatkani)", "Door & Window Fittings", listOf(
            spec("6 inch (150mm)", "SS", "Silver / Chrome", "pcs", 8200L, 12500L, 24, 6),
            spec("8 inch (200mm)", "Aluminium", "Rose Gold", "pcs", 11000L, 16500L, 3, 5)
        ), isDemo = true)
        catalog.createItem("seed-v1-adhesive", "Wood Adhesive (Fevicol SH)", "Paints & Adhesives", listOf(
            spec("1 kg", "", "White", "pkt", 23000L, 29000L, 10, 3)
        ), isDemo = true)
        prefs.edit().putBoolean("catalog_seeded_v1", true).apply()
    }
```

11. Fix the imports. Add `com.shopledger.pos.data.command.*` (AppCommandEnvironment, CatalogCommands, CommandEnvironment, CommandException, KhataCommands, LegacyJsonImporter, OutboxDocType, OutboxOp, PaymentDirection, VariantEdit, VariantSpec) and `com.shopledger.pos.data.local.entity.SyncOutboxEntity`. Remove `SyncState`, `kotlin.math.roundToLong` and `combine` if they're unused.

- [ ] **Step 14: Update existing tests for the new constructors**

In `DatabaseSmokeTest.kt`, replace `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()` with `newTestDatabase()` (import `com.shopledger.pos.testing.newTestDatabase`). Replace `upsertItem(` with `insertItem(`, and add this assertion before `db.close()`:

```kotlin
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM devices WHERE isLocal = 1"))
```

(import `com.shopledger.pos.testing.scalar`).

In `LpsHardwareDomainTest.kt`, convert the `Double` stock arguments to milli-units:

```bash
perl -pi -e 's/stockQuantity = (\d+)\.0\b/"stockMilliUnits = ".($1*1000)."L"/ge; s/lowStockThreshold = (\d+)\.0\b/"lowStockMilliUnits = ".($1*1000)."L"/ge' app/src/test/java/com/shopledger/pos/LpsHardwareDomainTest.kt
grep -n "stockQuantity =\|lowStockThreshold =\|syncStatus" app/src/test/java/com/shopledger/pos/LpsHardwareDomainTest.kt
```

Expected: the `grep` prints nothing.

- [ ] **Step 15: Run the full suite**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`. `CatalogCommandsTest` (10), `KhataCommandsTest` (5), `DatabaseSmokeTest`, `LedgerPrimitivesTest` and all `LpsHardwareDomainTest` tests pass. `app/schemas/.../3.json` now exists.

If compilation fails in UI code that referenced removed DAO methods or `SyncState.DEMO_SEED`, the fix is always to call the repository facade instead. Grep with `grep -rn "DEMO_SEED\|getPendingSync\|markItemsSynced\|deductVariantStock" app/src/main` (expected: no matches).

- [ ] **Step 16: Commit**

```bash
git add -A app/src app/schemas
git commit -m "feat(ledger): make stock and khata append-only with cached projections; route catalog and khata writes through idempotent commands

Stubs the v2 Firestore worker (it synced absolute totals) until the outbox-based sync lands.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: Immutable invoices, checkout, credit-note returns, `sale_records` view

**Files:**
- Modify: `app/src/main/java/com/shopledger/pos/data/local/entity/ShopLedgerEntities.kt` (invoice entities)
- Modify: `app/src/main/java/com/shopledger/pos/data/local/entity/InventoryItemEntity.kt` (delete `SyncState`)
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/entity/SaleRecordEntity.kt`
- Modify (full replace): `app/src/main/java/com/shopledger/pos/data/local/dao/SaleDao.kt`
- Modify: `app/src/main/java/com/shopledger/pos/data/local/dao/ShopPartyAndLedgerDao.kt` (invoice section)
- Modify: `app/src/main/java/com/shopledger/pos/data/local/LpsDatabase.kt` (`views`)
- Create: `app/src/main/java/com/shopledger/pos/data/command/SalesCommands.kt`
- Modify: `app/src/main/java/com/shopledger/pos/data/command/LegacyJsonImporter.kt` (`importSales`)
- Modify: `app/src/main/java/com/shopledger/pos/data/repository/LpsRepository.kt`
- Modify: `app/src/main/java/com/shopledger/pos/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/shopledger/pos/ui/screens/sales/SaleHistoryScreen.kt`
- Modify: `app/src/main/java/com/shopledger/pos/MainActivity.kt`
- Modify: `app/src/test/java/com/shopledger/pos/testing/TestSupport.kt` (add `seedHinge`)
- Modify: `app/src/test/java/com/shopledger/pos/LpsHardwareDomainTest.kt`
- Create: `app/src/test/java/com/shopledger/pos/data/command/SalesCommandsTest.kt`
- Create: `app/src/test/java/com/shopledger/pos/data/command/ReturnsTest.kt`
- Create: `app/src/test/java/com/shopledger/pos/data/command/LegacyJsonImporterTest.kt`

**Interfaces:**
- Consumes: `LedgerWriter`, `CatalogCommands`, `KhataCommands`, `DeviceDao.bumpInvoiceSeq/getLastInvoiceSeq` (Task 3); `CalendarParts`, `ReceiptNumbers`, `InvoiceMath`, `CheckoutStep`, `FaultInjector` (Task 2).
- Produces:
  - `SalesCommands(db, env, faults = FaultInjector.NONE)`: `checkout(CheckoutRequest): InvoiceResult`, `returnItems(actionId, originalInvoiceId, List<ReturnLine>): InvoiceResult`
  - `CheckoutRequest(actionId, lines: List<CheckoutLine>, buyerName, buyerPhone, paymentMode, discountPaise, amountPaidPaise: Long?, gstRateBps, ownerPinVerified)`, `CheckoutLine(variantId, qtyMilli, unitSellPaise)`, `ReturnLine(originalLineId, qtyMilli)`, `InvoiceResult(header, lines, oversoldVariantIds, replayed)`, `object DocType { SALE; CREDIT_NOTE }`
  - DAO: `getInvoiceById`, `getInvoiceLines`, `getAllInvoiceHeaders`, `getAllInvoiceLines`, `insertInvoiceHeader`, `insertInvoiceLines`, `sumReturnedMilliForLine(lineId): Long`, `sumKhataCreditedForInvoice(invoiceId): Long`, `getInvoiceNumbers(shopId)`
  - `SaleRecordEntity` is now a Room `@DatabaseView` (`SALE_RECORDS_VIEW_SQL`) with new fields `invoiceId`, `quantitySoldMilli`, `returnedMilliUnits`
  - Repository: `executeCheckoutAndDeductStock(actionId, …): LpsRepository.CheckoutOutcome`, `returnSaleLine(actionId, sale, qtyMilli)`
  - Test helper `seedHinge(db, env, openingMilli, actionId): String` (returns the variant id)

- [ ] **Step 1: Add the `seedHinge` test helper**

Append to `TestSupport.kt`, adding imports for `CatalogCommands` and `VariantSpec`:

```kotlin
/** Creates "Butt Hinge" (cost ₹48, price ₹75) with the given opening stock; returns the variant id. */
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
```

- [ ] **Step 2: Write the failing checkout tests**

Create `SalesCommandsTest.kt`:

```kotlin
package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.core.ledger.ReceiptNumbers
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import com.shopledger.pos.testing.seedHinge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SalesCommandsTest {
    private lateinit var db: LpsDatabase
    private lateinit var env: TestEnv
    private lateinit var sales: SalesCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        env = TestEnv()
        sales = SalesCommands(db, env)
    }

    @After
    fun tearDown() = db.close()

    private fun dayKey() = CalendarParts.of(env.clock).dayKey
    private fun twoHinges(variantId: String, actionId: String = "c1", mode: String = "CASH", paid: Long? = null,
                          buyer: String = "", phone: String = "") =
        CheckoutRequest(actionId, listOf(CheckoutLine(variantId, 2_000L, 7_500L)), buyerName = buyer, buyerPhone = phone,
            paymentMode = mode, amountPaidPaise = paid)

    @Test
    fun cashCheckout_writesInvoiceMovementAndOneOutboxRow() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId))
        assertEquals("L-${dayKey()}-0001", result.header.invoiceNumber)
        assertEquals(15_000L, result.header.grandTotalPaise)
        assertEquals(15_000L, result.header.amountPaidPaise)
        assertEquals(0L, result.header.balanceDuePaise)
        assertEquals(5_400L, result.lines.single().lineProfitPaise)
        assertEquals(8_000L, db.inventoryDao().getVariantById(variantId)!!.stockMilliUnits)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'INVOICE'"))
        // The sale's movement travels inside the INVOICE document, not as its own outbox row.
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'MOVEMENT'"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM parties"))
    }

    @Test
    fun creditCheckout_createsPartyAndCreditBill() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId, mode = "CREDIT", buyer = "Ramesh", phone = "98765 43210"))
        val party = db.shopPartyAndLedgerDao().getAllRawParties().single()
        assertEquals(15_000L, party.currentBalancePaise)
        assertEquals(party.id, result.header.partyId)
        val entry = db.shopPartyAndLedgerDao().getAllLedgerEntries().single()
        assertEquals(LedgerEntryType.CREDIT_BILL, entry.entryType)
        assertEquals(result.header.id, entry.refDocId)
    }

    @Test
    fun partialPayment_putsOnlyTheRemainderOnKhata() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId, mode = "CASH", paid = 5_000L, buyer = "Ramesh", phone = "9876543210"))
        assertEquals(10_000L, result.header.balanceDuePaise)
        assertEquals(10_000L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
    }

    @Test
    fun existingParty_isMatchedByPhoneDigits() = runBlocking<Unit> {
        KhataCommands(db, env).createParty("p1", "Ramesh", "9876543210")
        val variantId = seedHinge(db, env)
        sales.checkout(twoHinges(variantId, mode = "CREDIT", phone = "+91 98765 43210"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM parties"))
        assertEquals(15_000L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
    }

    @Test
    fun sameActionIdTwice_returnsTheSameInvoiceAndWritesOnce() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val first = sales.checkout(twoHinges(variantId))
        val second = sales.checkout(twoHinges(variantId))
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.header.id, second.header.id)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers"))
        assertEquals(8_000L, db.inventoryDao().getVariantById(variantId)!!.stockMilliUnits)
    }

    @Test
    fun failureAtAnyStep_rollsBackEverything() = runBlocking<Unit> {
        for (step in CheckoutStep.ALL) {
            val db = newTestDatabase()
            val env = TestEnv()
            val variantId = seedHinge(db, env)
            val failing = SalesCommands(db, env, FaultInjector { if (it == step) throw IllegalStateException("boom at $it") })
            val before = snapshot(db)
            val error = expectFailure<IllegalStateException> {
                failing.checkout(CheckoutRequest("act-$step", listOf(CheckoutLine(variantId, 2_000L, 7_500L)),
                    buyerName = "Ramesh", buyerPhone = "9876543210", paymentMode = "CREDIT"))
            }
            assertEquals("boom at $step", error.message)
            assertEquals("state changed after failure at $step", before, snapshot(db))
            db.close()
        }
    }

    private fun snapshot(db: LpsDatabase): List<Long> = listOf(
        "SELECT COUNT(*) FROM invoice_headers",
        "SELECT COUNT(*) FROM invoice_lines",
        "SELECT COUNT(*) FROM stock_movements",
        "SELECT COUNT(*) FROM party_ledger_entries",
        "SELECT COUNT(*) FROM parties",
        "SELECT COUNT(*) FROM sync_outbox",
        "SELECT COUNT(*) FROM applied_actions",
        "SELECT lastInvoiceSeq FROM devices WHERE isLocal = 1",
        "SELECT COALESCE(SUM(stockMilliUnits), 0) FROM item_variants",
        "SELECT COALESCE(SUM(currentBalancePaise), 0) FROM parties"
    ).map { db.scalar(it) }

    @Test
    fun overselling_isAllowedAndReported() = runBlocking<Unit> {
        val variantId = seedHinge(db, env, openingMilli = 1_000L)
        val result = sales.checkout(CheckoutRequest("c1", listOf(CheckoutLine(variantId, 3_000L, 7_500L))))
        assertEquals(-2_000L, db.inventoryDao().getVariantById(variantId)!!.stockMilliUnits)
        assertEquals(listOf(variantId), result.oversoldVariantIds)
    }

    @Test
    fun staffDiscountAboveLimit_needsOwnerPin() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        env.role = Role.STAFF
        val request = twoHinges(variantId).copy(discountPaise = 2_000L) // 13.3% > default 10%
        expectFailure<CommandException.PermissionDenied> { sales.checkout(request) }
        val result = sales.checkout(request.copy(ownerPinVerified = true))
        assertEquals(13_000L, result.header.grandTotalPaise)
    }

    @Test
    fun gstEnabled_splitsTaxIntoCgstAndSgst() = runBlocking<Unit> {
        env.gst = true
        val variantId = seedHinge(db, env)
        val result = sales.checkout(twoHinges(variantId).copy(gstRateBps = 1_800))
        assertEquals(1_350L, result.header.cgstPaise)
        assertEquals(1_350L, result.header.sgstPaise)
        assertEquals(17_700L, result.header.grandTotalPaise)
    }

    @Test
    fun concurrentCheckouts_getUniqueGaplessNumbers() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        val numbers = (1..1000).map { i ->
            async(Dispatchers.IO) {
                sales.checkout(CheckoutRequest("c$i", listOf(CheckoutLine(variantId, 1_000L, 7_500L)))).header.invoiceNumber
            }
        }.awaitAll()
        val expected = (1L..1000L).map { ReceiptNumbers.format("L", dayKey(), it) }.toSet()
        assertEquals(expected, numbers.toSet())
        assertEquals(1000, numbers.size)
    }

    @Test
    fun newDay_restartsTheSequence() = runBlocking<Unit> {
        val variantId = seedHinge(db, env)
        sales.checkout(twoHinges(variantId, actionId = "d1"))
        env.clock += 24L * 60 * 60 * 1000
        val next = sales.checkout(twoHinges(variantId, actionId = "d2"))
        assertEquals("L-${dayKey()}-0001", next.header.invoiceNumber)
    }
}
```

Create `ReturnsTest.kt`:

```kotlin
package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import com.shopledger.pos.testing.seedHinge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReturnsTest {
    private lateinit var db: LpsDatabase
    private lateinit var env: TestEnv
    private lateinit var sales: SalesCommands

    @Before
    fun setUp() {
        db = newTestDatabase()
        env = TestEnv()
        sales = SalesCommands(db, env)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sellTwo(mode: String = "CASH", paid: Long? = null): InvoiceResult {
        val variantId = seedHinge(db, env)
        return sales.checkout(
            CheckoutRequest("sale", listOf(CheckoutLine(variantId, 2_000L, 7_500L)), buyerName = "Ramesh",
                buyerPhone = "9876543210", paymentMode = mode, amountPaidPaise = paid)
        )
    }

    @Test
    fun partialReturn_createsCreditNoteRestoresStockAndUpdatesView() = runBlocking<Unit> {
        val sale = sellTwo()
        val line = sale.lines.single()
        val note = sales.returnItems("r1", sale.header.id, listOf(ReturnLine(line.id, 1_000L)))
        assertEquals(DocType.CREDIT_NOTE, note.header.docType)
        assertEquals(sale.header.id, note.header.originalInvoiceId)
        assertEquals(-7_500L, note.header.grandTotalPaise)
        assertEquals(-1_000L, note.lines.single().qtyMilliUnits)
        assertEquals(9_000L, db.inventoryDao().getVariantById(line.skuId)!!.stockMilliUnits)

        val row = db.saleDao().getAllRawSales().single()
        assertEquals(1_000L, row.returnedMilliUnits)
        assertFalse(row.isReturned)

        sales.returnItems("r2", sale.header.id, listOf(ReturnLine(line.id, 1_000L)))
        assertTrue(db.saleDao().getAllRawSales().single().isReturned)
    }

    @Test
    fun overReturn_throwsAndWritesNothing() = runBlocking<Unit> {
        val sale = sellTwo()
        expectFailure<CommandException.ReturnExceedsSold> {
            sales.returnItems("r1", sale.header.id, listOf(ReturnLine(sale.lines.single().id, 3_000L)))
        }
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers"))
        assertEquals(8_000L, db.scalar("SELECT stockMilliUnits FROM item_variants"))
    }

    @Test
    fun secondReturnBeyondRemaining_isRejected() = runBlocking<Unit> {
        val sale = sellTwo()
        val lineId = sale.lines.single().id
        sales.returnItems("r1", sale.header.id, listOf(ReturnLine(lineId, 2_000L)))
        expectFailure<CommandException.ReturnExceedsSold> {
            sales.returnItems("r2", sale.header.id, listOf(ReturnLine(lineId, 1_000L)))
        }
        assertEquals(10_000L, db.scalar("SELECT stockMilliUnits FROM item_variants"))
    }

    @Test
    fun returnOfCreditSale_reducesKhataBeforeRefundingCash() = runBlocking<Unit> {
        val sale = sellTwo(mode = "CASH", paid = 5_000L) // ₹150 bill, ₹50 paid, ₹100 on khata
        val note = sales.returnItems("r1", sale.header.id, listOf(ReturnLine(sale.lines.single().id, 2_000L)))
        assertEquals(-10_000L, note.header.balanceDuePaise)
        assertEquals(-5_000L, note.header.amountPaidPaise)
        assertEquals(0L, db.shopPartyAndLedgerDao().getAllRawParties().single().currentBalancePaise)
        val credit = db.shopPartyAndLedgerDao().getAllLedgerEntries().single { it.entryType == LedgerEntryType.CREDIT_NOTE }
        assertEquals(-10_000L, credit.amountDeltaPaise)
    }

    @Test
    fun duplicateLineInOneRequest_isRejected() = runBlocking<Unit> {
        val sale = sellTwo()
        val lineId = sale.lines.single().id
        expectFailure<CommandException.Validation> {
            sales.returnItems("r1", sale.header.id, listOf(ReturnLine(lineId, 1_000L), ReturnLine(lineId, 1_000L)))
        }
    }
}
```

Create `LegacyJsonImporterTest.kt`:

```kotlin
package com.shopledger.pos.data.command

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyJsonImporterTest {
    private lateinit var db: LpsDatabase
    private lateinit var importer: LegacyJsonImporter

    @Before
    fun setUp() {
        db = newTestDatabase()
        importer = LegacyJsonImporter(db, TestEnv())
    }

    @After
    fun tearDown() = db.close()

    private val v2Backup = """
        {"schemaVersion":2,
         "items":[{"id":"i1","displayName":"Butt Hinge","sortKey":"BUTT HINGE","category":"Fittings","createdAt":1,"updatedAt":1,"isDeleted":false}],
         "variants":[{"id":"v1","itemId":"i1","sizeLabel":"4 inch","unit":"pcs","purchasePricePaise":4800,"sellingPricePaise":7500,"stockQuantity":8.0,"lowStockThreshold":5.0,"updatedAt":1,"isDeleted":false}],
         "sales":[
           {"id":"s1","receiptNumber":"202611010001","itemId":"i1","variantId":"v1","itemNameSnapshot":"Butt Hinge","variantSummarySnapshot":"4 inch","quantitySold":2.0,"unitSnapshot":"pcs","unitPurchasePricePaise":4800,"unitSellingPricePaise":7500,"totalCostPaise":9600,"totalRevenuePaise":15000,"totalProfitPaise":5400,"soldAt":1000,"isReturned":false},
           {"id":"s2","receiptNumber":"202611010001","itemId":"i1","variantId":"v1","itemNameSnapshot":"Butt Hinge","variantSummarySnapshot":"4 inch","quantitySold":1.0,"unitSnapshot":"pcs","unitPurchasePricePaise":4800,"unitSellingPricePaise":7500,"totalCostPaise":4800,"totalRevenuePaise":7500,"totalProfitPaise":2700,"soldAt":1000,"isReturned":true}],
         "parties":[{"id":"p1","partyType":"CUSTOMER","name":"Ramesh","phone":"9876543210","currentBalancePaise":500,"updatedAt":1,"isDeleted":false}]}
    """.trimIndent()

    @Test
    fun import_buildsLedgerRowsInvoicesAndCreditNotes() = runBlocking<Unit> {
        val report = importer.import(v2Backup)
        assertEquals(LegacyJsonImporter.Report(items = 1, variants = 1, parties = 1, sales = 2), report)
        assertEquals(8_000L, db.scalar("SELECT stockMilliUnits FROM item_variants WHERE id = 'v1'"))
        assertEquals(500L, db.scalar("SELECT currentBalancePaise FROM parties WHERE id = 'p1'"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers WHERE docType = 'SALE'"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM invoice_headers WHERE docType = 'CREDIT_NOTE'"))
        val rows = db.saleDao().getAllRawSales().associateBy { it.id }
        assertTrue(rows.getValue("s2").isReturned)
        assertEquals(false, rows.getValue("s1").isReturned)
    }

    @Test
    fun importingTheSameFileTwice_addsNothing() = runBlocking<Unit> {
        importer.import(v2Backup)
        val again = importer.import(v2Backup)
        assertEquals(LegacyJsonImporter.Report(0, 0, 0, 0), again)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM inventory_items"))
    }
}
```

- [ ] **Step 3: Run them and confirm they fail**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.command.*"`
Expected: FAIL with compilation errors (`Unresolved reference: SalesCommands`, `returnedMilliUnits`, …).

- [ ] **Step 4: Make invoices immutable and `sale_records` a view**

In `InventoryItemEntity.kt`, delete the `SyncState` object and its comment.

In `ShopLedgerEntities.kt`, replace `InvoiceHeaderEntity` and `InvoiceLineEntity` with:

```kotlin
/** Immutable once written. Returns are separate CREDIT_NOTE documents with negative amounts. */
@Immutable
@Entity(
    tableName = "invoice_headers",
    indices = [
        Index(value = ["shopId", "invoiceNumber"], unique = true),
        Index(value = ["shopId", "issuedAt"]),
        Index(value = ["shopId", "docType"]),
        Index(value = ["originalInvoiceId"])
    ]
)
data class InvoiceHeaderEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val deviceId: String = "",
    val deviceShortCode: String = "",
    val invoiceNumber: String,
    /** "SALE" or "CREDIT_NOTE". */
    val docType: String = "SALE",
    /** For a CREDIT_NOTE: the SALE it reverses. */
    val originalInvoiceId: String? = null,
    val partyId: String? = null,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val paymentMode: String = "CASH",
    val subtotalPaise: Long,
    val discountPaise: Long = 0L,
    val cgstPaise: Long = 0L,
    val sgstPaise: Long = 0L,
    val igstPaise: Long = 0L,
    val grandTotalPaise: Long,
    val amountPaidPaise: Long,
    val balanceDuePaise: Long = 0L,
    val issuedAt: Long,
    val yearInt: Int,
    val monthInt: Int,
    val dayInt: Int,
    val createdByUid: String = ""
)

@Immutable
@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceHeaderEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["invoiceId"]),
        Index(value = ["shopId", "invoiceId"]),
        Index(value = ["originalLineId"])
    ]
)
data class InvoiceLineEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val invoiceId: String,
    /** For a credit-note line: the sale line it returns. */
    val originalLineId: String? = null,
    val productId: String,
    val skuId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val hsnSnapshot: String = "",
    /** Negative on credit notes. */
    val qtyMilliUnits: Long,
    val unitSnapshot: String,
    val unitCostPaise: Long,
    val unitSellPaise: Long,
    val gstRateBps: Int = 0,
    val lineTaxPaise: Long = 0L,
    val lineTotalPaise: Long,
    val lineProfitPaise: Long
)
```

Replace `SaleRecordEntity.kt` entirely:

```kotlin
package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.DatabaseView
import kotlin.math.roundToLong

/**
 * Single-line SQL, shared by the @DatabaseView and Migration2To3, so the view text in sqlite_master
 * matches exactly what Room validates.
 */
const val SALE_RECORDS_VIEW_SQL =
    "SELECT l.id AS id, h.id AS invoiceId, h.shopId AS shopId, h.invoiceNumber AS receiptNumber, " +
        "h.buyerName AS buyerName, h.buyerPhone AS buyerPhone, h.partyId AS partyId, " +
        "l.productId AS itemId, l.skuId AS variantId, l.itemNameSnapshot AS itemNameSnapshot, " +
        "l.variantSummarySnapshot AS variantSummarySnapshot, l.qtyMilliUnits / 1000.0 AS quantitySold, " +
        "l.qtyMilliUnits AS quantitySoldMilli, l.unitSnapshot AS unitSnapshot, " +
        "l.unitCostPaise AS unitPurchasePricePaise, l.unitSellPaise AS unitSellingPricePaise, " +
        "l.lineTotalPaise - l.lineProfitPaise AS totalCostPaise, l.lineTotalPaise AS totalRevenuePaise, " +
        "l.lineProfitPaise AS totalProfitPaise, " +
        "CASE WHEN l.lineTotalPaise - l.lineProfitPaise > 0 THEN l.lineProfitPaise * 100.0 / (l.lineTotalPaise - l.lineProfitPaise) ELSE 0.0 END AS gainMarkupPercent, " +
        "CASE WHEN l.lineTotalPaise > 0 THEN l.lineProfitPaise * 100.0 / l.lineTotalPaise ELSE 0.0 END AS grossMarginPercent, " +
        "h.paymentMode AS paymentMode, h.amountPaidPaise AS amountPaidPaise, h.balanceDuePaise AS balanceDuePaise, " +
        "h.discountPaise AS discountPaise, h.cgstPaise + h.sgstPaise + h.igstPaise AS gstPaise, " +
        "h.issuedAt AS soldAt, h.yearInt AS yearInt, h.monthInt AS monthInt, h.dayInt AS dayInt, " +
        "COALESCE((SELECT -SUM(r.qtyMilliUnits) FROM invoice_lines r WHERE r.originalLineId = l.id), 0) AS returnedMilliUnits, " +
        "CASE WHEN COALESCE((SELECT -SUM(r2.qtyMilliUnits) FROM invoice_lines r2 WHERE r2.originalLineId = l.id), 0) >= l.qtyMilliUnits THEN 1 ELSE 0 END AS isReturned, " +
        "h.issuedAt AS updatedAt " +
        "FROM invoice_lines l INNER JOIN invoice_headers h ON h.id = l.invoiceId WHERE h.docType = 'SALE'"

/** Read-only per-line view of SALE invoices, kept for the Sales History screen and buyer history. */
@Immutable
@DatabaseView(viewName = "sale_records", value = SALE_RECORDS_VIEW_SQL)
data class SaleRecordEntity(
    val id: String,
    val invoiceId: String = "",
    val shopId: String = DEFAULT_SHOP_ID,
    val receiptNumber: String,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val partyId: String? = null,
    val itemId: String,
    val variantId: String,
    val itemNameSnapshot: String,
    val variantSummarySnapshot: String,
    val quantitySold: Double,
    val quantitySoldMilli: Long = (quantitySold * 1000.0).roundToLong(),
    val unitSnapshot: String,
    val unitPurchasePricePaise: Long,
    val unitSellingPricePaise: Long,
    val totalCostPaise: Long,
    val totalRevenuePaise: Long,
    val totalProfitPaise: Long,
    val gainMarkupPercent: Double,
    val grossMarginPercent: Double,
    val paymentMode: String = "CASH",
    val amountPaidPaise: Long = totalRevenuePaise,
    val balanceDuePaise: Long = 0L,
    val discountPaise: Long = 0L,
    val gstPaise: Long = 0L,
    val soldAt: Long,
    val yearInt: Int,
    val monthInt: Int,
    val dayInt: Int,
    val returnedMilliUnits: Long = 0L,
    /** True once the whole line has been returned (possibly over several credit notes). */
    val isReturned: Boolean = false,
    val updatedAt: Long = soldAt
)
```

Replace `SaleDao.kt` entirely:

```kotlin
package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import kotlinx.coroutines.flow.Flow

/** Read-only: sales are written as invoices through SalesCommands. */
@Dao
interface SaleDao {
    @Query("SELECT * FROM sale_records ORDER BY soldAt DESC")
    fun observeAllSalesChronological(): Flow<List<SaleRecordEntity>>

    @Query("SELECT * FROM sale_records")
    suspend fun getAllRawSales(): List<SaleRecordEntity>
}
```

In `ShopPartyAndLedgerDao.kt`, replace the whole `// Invoices. INTERIM …` section (the observe query and both REPLACE inserts) with:

```kotlin
    // Invoices (immutable)
    @Query("SELECT * FROM invoice_headers ORDER BY issuedAt DESC")
    fun observeAllInvoiceHeaders(): Flow<List<InvoiceHeaderEntity>>

    @Query("SELECT * FROM invoice_headers WHERE id = :id LIMIT 1")
    suspend fun getInvoiceById(id: String): InvoiceHeaderEntity?

    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :invoiceId")
    suspend fun getInvoiceLines(invoiceId: String): List<InvoiceLineEntity>

    @Query("SELECT * FROM invoice_headers")
    suspend fun getAllInvoiceHeaders(): List<InvoiceHeaderEntity>

    @Query("SELECT * FROM invoice_lines")
    suspend fun getAllInvoiceLines(): List<InvoiceLineEntity>

    @Query("SELECT invoiceNumber FROM invoice_headers WHERE shopId = :shopId")
    suspend fun getInvoiceNumbers(shopId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvoiceHeader(header: InvoiceHeaderEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvoiceLines(lines: List<InvoiceLineEntity>)

    /** Total already returned (positive milli-units) against one sale line. */
    @Query("SELECT COALESCE(-SUM(qtyMilliUnits), 0) FROM invoice_lines WHERE originalLineId = :lineId")
    suspend fun sumReturnedMilliForLine(lineId: String): Long

    /** Khata credit (positive paise) already given by earlier credit notes against [invoiceId]. */
    @Query(
        "SELECT COALESCE(-SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.entryType = 'CREDIT_NOTE' " +
            "AND e.refDocId IN (SELECT h.id FROM invoice_headers h WHERE h.originalInvoiceId = :invoiceId)"
    )
    suspend fun sumKhataCreditedForInvoice(invoiceId: String): Long
```

In `LpsDatabase.kt`, remove `SaleRecordEntity::class` from `entities` and add `views = [SaleRecordEntity::class],` to the `@Database` annotation.

- [ ] **Step 5: Implement `SalesCommands`**

Create `SalesCommands.kt`:

```kotlin
package com.shopledger.pos.data.command

import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.core.ledger.InvoiceMath
import com.shopledger.pos.core.ledger.ReceiptNumbers
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import java.util.Locale

object DocType {
    const val SALE = "SALE"
    const val CREDIT_NOTE = "CREDIT_NOTE"
}

data class CheckoutLine(val variantId: String, val qtyMilli: Long, val unitSellPaise: Long)

data class CheckoutRequest(
    val actionId: String,
    val lines: List<CheckoutLine>,
    val buyerName: String = "",
    val buyerPhone: String = "",
    val paymentMode: String = "CASH",
    val discountPaise: Long = 0L,
    /** null = paid in full (or nothing for CREDIT). */
    val amountPaidPaise: Long? = null,
    val gstRateBps: Int = 0,
    val ownerPinVerified: Boolean = false
)

data class ReturnLine(val originalLineId: String, val qtyMilli: Long)

data class InvoiceResult(
    val header: InvoiceHeaderEntity,
    val lines: List<InvoiceLineEntity>,
    /** Variants this bill pushed below zero (warn the cashier to recount). */
    val oversoldVariantIds: List<String> = emptyList(),
    /** True when this result is a replay of an earlier identical action (double tap / retry). */
    val replayed: Boolean = false
)

class SalesCommands(
    private val db: LpsDatabase,
    private val env: CommandEnvironment,
    private val faults: FaultInjector = FaultInjector.NONE
) {
    private val writer = LedgerWriter(db, env)
    private val inventory = db.inventoryDao()
    private val ledger = db.shopPartyAndLedgerDao()
    private val devices = db.deviceDao()

    suspend fun checkout(request: CheckoutRequest): InvoiceResult =
        writer.runIdempotent(request.actionId, "CHECKOUT", replay = { loadInvoice(it) }) {
            if (request.lines.isEmpty()) throw CommandException.Validation("The cart is empty")
            if (request.lines.map { it.variantId }.toSet().size != request.lines.size) {
                throw CommandException.Validation("Each item can appear only once in a bill")
            }
            for (line in request.lines) {
                if (line.qtyMilli <= 0L) throw CommandException.Validation("Quantity must be more than 0")
                if (line.unitSellPaise < 0L) throw CommandException.Validation("Price cannot be negative")
            }
            val device = writer.localDevice()
            val now = env.now()
            val shopId = env.shopId()
            val actor = env.actor()

            // Read everything inside the transaction: prices, names and costs come from the database.
            val resolved = request.lines.map { line ->
                val variant = inventory.getVariantById(line.variantId)?.takeUnless { it.isDeleted }
                    ?: throw CommandException.NotFound("Item variant")
                val item = inventory.getItemById(variant.itemId) ?: throw CommandException.NotFound("Item")
                Triple(line, variant, item)
            }
            val lineTotals = resolved.map { (line, _, _) -> InvoiceMath.lineTotalPaise(line.unitSellPaise, line.qtyMilli) }
            val subtotal = lineTotals.sum()
            if (request.discountPaise < 0L || request.discountPaise > subtotal) {
                throw CommandException.Validation("Discount must be between 0 and the bill total")
            }
            val maxStaffBps = ledger.getShopProfile(shopId)?.maxStaffDiscountBps ?: 1000
            if (actor.role == Role.STAFF && !request.ownerPinVerified &&
                request.discountPaise * 10_000L > subtotal * maxStaffBps
            ) {
                throw CommandException.PermissionDenied("A discount above ${maxStaffBps / 100}% needs the owner PIN")
            }
            val gstBps = if (env.gstEnabled() && request.gstRateBps > 0) request.gstRateBps else 0
            val tax = IndianCurrencyFormatter.computeGstBreakdown(subtotal - request.discountPaise, gstBps)
            val grand = tax.grandTotalPaise
            val mode = request.paymentMode.trim().uppercase(Locale.ROOT).ifEmpty { "CASH" }
            val paid = when {
                request.amountPaidPaise != null -> request.amountPaidPaise.coerceIn(0L, grand)
                mode == "CREDIT" -> 0L
                else -> grand
            }
            val due = grand - paid
            val buyerName = request.buyerName.trim()
            val buyerPhone = request.buyerPhone.trim()
            val party = resolveParty(buyerName, buyerPhone, due, device)

            val parts = CalendarParts.of(now)
            devices.bumpInvoiceSeq(device.deviceId, parts.dayKey)
            val number = ReceiptNumbers.format(device.deviceCode, parts.dayKey, devices.getLastInvoiceSeq(device.deviceId))
            faults.at(CheckoutStep.NUMBER)

            val invoiceId = env.newId()
            val header = InvoiceHeaderEntity(
                id = invoiceId,
                shopId = shopId,
                deviceId = device.deviceId,
                deviceShortCode = device.deviceCode,
                invoiceNumber = number,
                docType = DocType.SALE,
                partyId = party?.id,
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = mode,
                subtotalPaise = subtotal,
                discountPaise = request.discountPaise,
                cgstPaise = tax.cgstPaise,
                sgstPaise = tax.sgstPaise,
                igstPaise = tax.igstPaise,
                grandTotalPaise = grand,
                amountPaidPaise = paid,
                balanceDuePaise = due,
                issuedAt = now,
                yearInt = parts.year,
                monthInt = parts.month,
                dayInt = parts.day,
                createdByUid = actor.uid
            )
            ledger.insertInvoiceHeader(header)
            faults.at(CheckoutStep.HEADER)

            val lines = resolved.mapIndexed { index, (line, variant, item) ->
                val total = lineTotals[index]
                val cost = InvoiceMath.lineTotalPaise(variant.purchasePricePaise, line.qtyMilli)
                InvoiceLineEntity(
                    id = env.newId(),
                    shopId = shopId,
                    invoiceId = invoiceId,
                    productId = item.id,
                    skuId = variant.id,
                    itemNameSnapshot = item.displayName,
                    variantSummarySnapshot = variant.buildVariantSummary(),
                    hsnSnapshot = item.defaultHsnCode,
                    qtyMilliUnits = line.qtyMilli,
                    unitSnapshot = variant.unit,
                    unitCostPaise = variant.purchasePricePaise,
                    unitSellPaise = line.unitSellPaise,
                    gstRateBps = gstBps,
                    lineTotalPaise = total,
                    lineProfitPaise = total - cost
                )
            }
            ledger.insertInvoiceLines(lines)
            faults.at(CheckoutStep.LINES)

            val oversold = ArrayList<String>()
            for (line in lines) {
                // Movements and ledger entries ride inside the INVOICE outbox document (enqueue = false).
                writer.appendMovement(line.skuId, -line.qtyMilliUnits, MovementType.SALE, invoiceId, "Sale #$number", device, enqueue = false)
                if (inventory.getVariantById(line.skuId)!!.stockMilliUnits < 0L) oversold += line.skuId
            }
            faults.at(CheckoutStep.MOVEMENTS)

            if (due > 0L) {
                writer.appendLedgerEntry(party!!.id, due, LedgerEntryType.CREDIT_BILL, mode, invoiceId, "Bill #$number", device, enqueue = false)
            }
            faults.at(CheckoutStep.LEDGER)

            writer.enqueue(OutboxDocType.INVOICE, invoiceId, OutboxOp.APPEND)
            faults.at(CheckoutStep.OUTBOX)

            InvoiceResult(header, lines, oversold) to invoiceId
        }

    suspend fun returnItems(actionId: String, originalInvoiceId: String, lines: List<ReturnLine>): InvoiceResult =
        writer.runIdempotent(actionId, "RETURN", replay = { loadInvoice(it) }) {
            if (lines.isEmpty()) throw CommandException.Validation("Choose at least one item to return")
            if (lines.map { it.originalLineId }.toSet().size != lines.size) {
                throw CommandException.Validation("Each item can appear only once in a return")
            }
            val original = ledger.getInvoiceById(originalInvoiceId)?.takeIf { it.docType == DocType.SALE }
                ?: throw CommandException.NotFound("Original bill")
            val originalLines = ledger.getInvoiceLines(original.id).associateBy { it.id }
            val device = writer.localDevice()
            val now = env.now()
            val creditNoteId = env.newId()

            val creditLines = lines.map { request ->
                if (request.qtyMilli <= 0L) throw CommandException.Validation("Return quantity must be more than 0")
                val sold = originalLines[request.originalLineId] ?: throw CommandException.NotFound("Bill line")
                val remaining = sold.qtyMilliUnits - ledger.sumReturnedMilliForLine(sold.id)
                if (request.qtyMilli > remaining) throw CommandException.ReturnExceedsSold(sold.itemNameSnapshot, remaining)
                val total = InvoiceMath.lineTotalPaise(sold.unitSellPaise, request.qtyMilli)
                val cost = InvoiceMath.lineTotalPaise(sold.unitCostPaise, request.qtyMilli)
                InvoiceLineEntity(
                    id = env.newId(),
                    shopId = original.shopId,
                    invoiceId = creditNoteId,
                    originalLineId = sold.id,
                    productId = sold.productId,
                    skuId = sold.skuId,
                    itemNameSnapshot = sold.itemNameSnapshot,
                    variantSummarySnapshot = sold.variantSummarySnapshot,
                    hsnSnapshot = sold.hsnSnapshot,
                    qtyMilliUnits = -request.qtyMilli,
                    unitSnapshot = sold.unitSnapshot,
                    unitCostPaise = sold.unitCostPaise,
                    unitSellPaise = sold.unitSellPaise,
                    gstRateBps = sold.gstRateBps,
                    lineTotalPaise = -total,
                    lineProfitPaise = -(total - cost)
                )
            }

            val returnGross = -creditLines.sumOf { it.lineTotalPaise }
            // Give back the same share of the bill discount as the share of goods returned.
            val discountShare = if (original.subtotalPaise > 0L) {
                (original.discountPaise * returnGross + original.subtotalPaise / 2) / original.subtotalPaise
            } else 0L
            val tax = IndianCurrencyFormatter.computeGstBreakdown(returnGross - discountShare, creditLines.first().gstRateBps)
            val creditTotal = tax.grandTotalPaise
            // Money owed on the original bill is cancelled first; only the rest is refunded in cash.
            val khataCredit = if (original.partyId != null) {
                minOf(creditTotal, maxOf(0L, original.balanceDuePaise - ledger.sumKhataCreditedForInvoice(original.id)))
            } else 0L
            val cashRefund = creditTotal - khataCredit

            val parts = CalendarParts.of(now)
            devices.bumpInvoiceSeq(device.deviceId, parts.dayKey)
            val number = ReceiptNumbers.format(device.deviceCode, parts.dayKey, devices.getLastInvoiceSeq(device.deviceId))
            val header = InvoiceHeaderEntity(
                id = creditNoteId,
                shopId = original.shopId,
                deviceId = device.deviceId,
                deviceShortCode = device.deviceCode,
                invoiceNumber = number,
                docType = DocType.CREDIT_NOTE,
                originalInvoiceId = original.id,
                partyId = original.partyId,
                buyerName = original.buyerName,
                buyerPhone = original.buyerPhone,
                paymentMode = original.paymentMode,
                subtotalPaise = -returnGross,
                discountPaise = -discountShare,
                cgstPaise = -tax.cgstPaise,
                sgstPaise = -tax.sgstPaise,
                igstPaise = -tax.igstPaise,
                grandTotalPaise = -creditTotal,
                amountPaidPaise = -cashRefund,
                balanceDuePaise = -khataCredit,
                issuedAt = now,
                yearInt = parts.year,
                monthInt = parts.month,
                dayInt = parts.day,
                createdByUid = env.actor().uid
            )
            ledger.insertInvoiceHeader(header)
            ledger.insertInvoiceLines(creditLines)
            for (line in creditLines) {
                writer.appendMovement(line.skuId, -line.qtyMilliUnits, MovementType.SALE_RETURN, creditNoteId,
                    "Return against #${original.invoiceNumber}", device, enqueue = false)
            }
            if (khataCredit > 0L) {
                writer.appendLedgerEntry(original.partyId!!, -khataCredit, LedgerEntryType.CREDIT_NOTE, original.paymentMode,
                    creditNoteId, "Return against #${original.invoiceNumber}", device, enqueue = false)
            }
            writer.enqueue(OutboxDocType.INVOICE, creditNoteId, OutboxOp.APPEND)
            InvoiceResult(header, creditLines) to creditNoteId
        }

    /** Same matching rules as v2: phone digits first, then exact name. Creates a party only when needed. */
    private suspend fun resolveParty(name: String, phone: String, due: Long, device: DeviceEntity): PartyEntity? {
        if (due <= 0L && phone.isEmpty()) return null
        val phoneDigits = phone.filter { it.isDigit() }.takeLast(10)
        val existing = ledger.getAllActivePartiesSnapshot().firstOrNull { p ->
            (phoneDigits.length >= 6 && p.phone.filter { it.isDigit() }.takeLast(10) == phoneDigits) ||
                (name.isNotEmpty() && p.name.equals(name, ignoreCase = true))
        }
        if (existing != null) return existing
        if (due <= 0L && name.isEmpty() && phoneDigits.length < 6) return null
        val now = env.now()
        val created = PartyEntity(
            id = env.newId(),
            shopId = env.shopId(),
            partyType = "CUSTOMER",
            name = name.ifEmpty { phone.ifEmpty { "Credit Customer" } },
            phone = if (phoneDigits.length >= 10) phoneDigits else phone,
            createdAt = now,
            updatedAt = now,
            createdByUid = env.actor().uid,
            deviceId = device.deviceId
        )
        ledger.insertParty(created)
        writer.enqueue(OutboxDocType.PARTY, created.id, OutboxOp.UPSERT)
        return created
    }

    private suspend fun loadInvoice(invoiceId: String): InvoiceResult {
        val header = ledger.getInvoiceById(invoiceId)
            ?: throw IllegalStateException("Applied action points to missing invoice $invoiceId")
        return InvoiceResult(header, ledger.getInvoiceLines(invoiceId), replayed = true)
    }
}
```

- [ ] **Step 6: Import legacy sales as invoices**

In `LegacyJsonImporter.kt`, change the call to `val sales = importSales(root.optJSONArray("sales"), writer, device, shopId, now)`. Replace the whole `importSales` function with the version below, and swap the `SaleRecordEntity` import for `DeviceEntity`, `InvoiceHeaderEntity`, `InvoiceLineEntity` and `com.shopledger.pos.core.ledger.CalendarParts`:

```kotlin
    /**
     * Groups legacy per-line sales into immutable invoices. No stock movements are written: the
     * exported stock is already net of these sales. Returned lines become a cash credit note.
     */
    private suspend fun importSales(
        sales: JSONArray?,
        writer: LedgerWriter,
        device: DeviceEntity,
        shopId: String,
        now: Long
    ): Int {
        val ledger = db.shopPartyAndLedgerDao()
        val existingLineIds = ledger.getAllInvoiceLines().map { it.id }.toHashSet()
        val takenNumbers = ledger.getInvoiceNumbers(shopId).toHashSet()
        val byReceipt = LinkedHashMap<String, MutableList<JSONObject>>()
        sales.forEachObject { o ->
            if (o.getString("id") !in existingLineIds) {
                byReceipt.getOrPut(o.optString("receiptNumber", "IMPORTED")) { ArrayList() }.add(o)
            }
        }
        var imported = 0
        for ((receipt, rows) in byReceipt) {
            val number = generateSequence(receipt) { "$it-I" }.first { it !in takenNumbers }
            takenNumbers += number
            val invoiceId = env.newId()
            val issuedAt = rows.maxOf { it.optLong("soldAt", now) }
            val parts = CalendarParts.of(issuedAt)
            val first = rows.first()
            val lines = rows.map { o ->
                InvoiceLineEntity(
                    id = o.getString("id"),
                    shopId = shopId,
                    invoiceId = invoiceId,
                    productId = o.getString("itemId"),
                    skuId = o.getString("variantId"),
                    itemNameSnapshot = o.getString("itemNameSnapshot"),
                    variantSummarySnapshot = o.optString("variantSummarySnapshot", "Standard"),
                    qtyMilliUnits = (o.getDouble("quantitySold") * 1000.0).roundToLong(),
                    unitSnapshot = o.optString("unitSnapshot", "pcs"),
                    unitCostPaise = o.getLong("unitPurchasePricePaise"),
                    unitSellPaise = o.getLong("unitSellingPricePaise"),
                    lineTotalPaise = o.getLong("totalRevenuePaise"),
                    lineProfitPaise = o.optLong("totalProfitPaise", 0L)
                )
            }
            val total = lines.sumOf { it.lineTotalPaise }
            fun header(id: String, docNumber: String, docType: String, originalId: String?, amount: Long) = InvoiceHeaderEntity(
                id = id, shopId = shopId, deviceId = device.deviceId, deviceShortCode = device.deviceCode,
                invoiceNumber = docNumber, docType = docType, originalInvoiceId = originalId,
                buyerName = first.optString("buyerName", ""), buyerPhone = first.optString("buyerPhone", ""),
                paymentMode = first.optString("paymentMode", "CASH"), subtotalPaise = amount, grandTotalPaise = amount,
                amountPaidPaise = amount, balanceDuePaise = 0L, issuedAt = issuedAt, yearInt = parts.year,
                monthInt = parts.month, dayInt = parts.day, createdByUid = env.actor().uid
            )
            ledger.insertInvoiceHeader(header(invoiceId, number, DocType.SALE, null, total))
            ledger.insertInvoiceLines(lines)
            writer.enqueue(OutboxDocType.INVOICE, invoiceId, OutboxOp.APPEND)

            val returnedIds = rows.filter { it.optBoolean("isReturned", false) }.map { it.getString("id") }.toSet()
            val returned = lines.filter { it.id in returnedIds }
            if (returned.isNotEmpty()) {
                val creditNoteId = env.newId()
                val creditLines = returned.map { l ->
                    l.copy(id = env.newId(), invoiceId = creditNoteId, originalLineId = l.id, qtyMilliUnits = -l.qtyMilliUnits,
                        lineTotalPaise = -l.lineTotalPaise, lineProfitPaise = -l.lineProfitPaise)
                }
                val creditNumber = generateSequence("$number-R") { "$it-R" }.first { it !in takenNumbers }
                takenNumbers += creditNumber
                ledger.insertInvoiceHeader(header(creditNoteId, creditNumber, DocType.CREDIT_NOTE, invoiceId, creditLines.sumOf { it.lineTotalPaise }))
                ledger.insertInvoiceLines(creditLines)
                writer.enqueue(OutboxDocType.INVOICE, creditNoteId, OutboxOp.APPEND)
            }
            imported += rows.size
        }
        return imported
    }
```

- [ ] **Step 7: Repository facade for sales**

In `LpsRepository.kt`:

1. Add the field `private val sales = SalesCommands(database, env)` under `khata`.
2. Add this inside the class, near `NewVariantSpec`:

```kotlin
    data class CheckoutOutcome(
        val receipt: SaleReceiptData,
        val oversoldItemNames: List<String>,
        val replayed: Boolean
    )
```

3. Replace the whole interim `executeCheckoutAndDeductStock` with:

```kotlin
    suspend fun executeCheckoutAndDeductStock(
        actionId: String,
        cartItems: List<CartLineItem>,
        buyerName: String = "",
        buyerPhone: String = "",
        paymentMode: String = "CASH",
        discountPaise: Long = 0L,
        amountPaidPaise: Long? = null,
        gstRateBps: Int = 0
    ): CheckoutOutcome {
        val result = sales.checkout(
            CheckoutRequest(
                actionId = actionId,
                lines = cartItems.map {
                    CheckoutLine(it.variantId, IndianCurrencyFormatter.doubleToMilliUnits(it.quantity), it.customSellingPricePaise)
                },
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = paymentMode,
                discountPaise = discountPaise,
                amountPaidPaise = amountPaidPaise,
                gstRateBps = gstRateBps
            )
        )
        val oversoldNames = result.lines.filter { it.skuId in result.oversoldVariantIds }.map { it.itemNameSnapshot }
        return CheckoutOutcome(result.toReceipt(), oversoldNames, result.replayed)
    }

    private fun InvoiceResult.toReceipt(): SaleReceiptData = SaleReceiptData(
        receiptNumber = header.invoiceNumber,
        timestampMillis = header.issuedAt,
        buyerName = header.buyerName,
        buyerPhone = header.buyerPhone,
        sellerName = SyncManager.getSellerName(context),
        sellerWhatsAppPhone = SyncManager.getSellerWhatsAppNumber(context),
        isEstimate = false,
        items = lines.map {
            ReceiptLineItem(
                itemName = it.itemNameSnapshot,
                variantSummary = it.variantSummarySnapshot,
                quantity = it.qtyMilliUnits / 1000.0,
                unit = it.unitSnapshot,
                unitSellingPricePaise = it.unitSellPaise,
                lineTotalPaise = it.lineTotalPaise
            )
        },
        grandTotalPaise = header.grandTotalPaise,
        paymentMode = header.paymentMode,
        subtotalPaise = header.subtotalPaise,
        discountPaise = header.discountPaise,
        taxPaise = header.cgstPaise + header.sgstPaise + header.igstPaise,
        amountPaidPaise = header.amountPaidPaise,
        balanceDuePaise = header.balanceDuePaise,
        upiId = SyncManager.getUpiId(context),
        gstin = SyncManager.getGstin(context)
    )
```

4. In `prepareEstimateReceiptData`, replace the line `val receiptNo = generateNextSmartReceiptNumber(yearInt, monthInt, dayInt, deviceCode)` with the line below, and delete the now-unused `deviceCode`, `cal`, `yearInt`, `monthInt` and `dayInt` locals in that function. Estimates must not consume real receipt numbers.

```kotlin
        val receiptNo = "EST-" + java.text.SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(java.util.Date(now))
```

5. Delete `generateNextSmartReceiptNumber` and the `companion object` holding `formatSmartReceiptId`.
6. Replace the interim `returnSaleAndRestoreStock` with:

```kotlin
    suspend fun returnSaleLine(actionId: String, sale: SaleRecordEntity, qtyMilli: Long) {
        sales.returnItems(actionId, sale.invoiceId, listOf(ReturnLine(sale.id, qtyMilli)))
    }
```

7. Fix the imports: add `CheckoutLine`, `CheckoutRequest`, `InvoiceResult`, `ReturnLine`, `SalesCommands`, and remove imports that are now unused (`InvoiceHeaderEntity`, `InvoiceLineEntity`, `PartyTransactionEntity`, `StockMovementEntity`, `SyncOutboxEntity`, `OutboxDocType`, `OutboxOp`, `kotlin.math.max`, `Calendar` if unused).

- [ ] **Step 8: ViewModel and screens**

In `MainViewModel.kt`:

1. Add the field `val checkoutActionId: String = UUID.randomUUID().toString()` as the last field of `CustomerCartSession`.
2. Add `private var quickSellActionId: String = UUID.randomUUID().toString()` under `nextCartSequence`.
3. In `clearCart()`, change the transform to `cart.copy(items = emptyList(), checkoutActionId = UUID.randomUUID().toString())`.
4. Replace `completeSaleAndPrepareReceipt` with:

```kotlin
    fun completeSaleAndPrepareReceipt(
        itemsToSell: List<CartLineItem>,
        buyerName: String,
        buyerPhone: String,
        clearCounterCart: Boolean,
        paymentMode: String = "CASH",
        discountPaise: Long = 0L,
        amountPaidPaise: Long? = null,
        onComplete: (SaleReceiptData) -> Unit = {}
    ) {
        if (itemsToSell.isEmpty()) return
        // Captured before launching: a double tap reuses the same id and the second call is a no-op replay.
        val checkedOutCartId = _activeCartId.value
        val actionId = if (clearCounterCart) {
            _carts.value.firstOrNull { it.cartId == checkedOutCartId }?.checkoutActionId ?: UUID.randomUUID().toString()
        } else {
            quickSellActionId
        }
        launchSafe("Checkout Failed") {
            val outcome = repository.executeCheckoutAndDeductStock(
                actionId = actionId,
                cartItems = itemsToSell,
                buyerName = buyerName,
                buyerPhone = buyerPhone,
                paymentMode = paymentMode,
                discountPaise = discountPaise,
                amountPaidPaise = amountPaidPaise
            )
            if (outcome.replayed) return@launchSafe
            if (!clearCounterCart) quickSellActionId = UUID.randomUUID().toString()
            if (clearCounterCart) {
                if (_carts.value.size > 1) {
                    closeCart(checkedOutCartId)
                } else {
                    clearCart()
                    updateActiveCartBuyer("", "")
                }
            }
            if (outcome.oversoldItemNames.isNotEmpty()) {
                AppErrorManager.reportWarning(
                    "Stock below zero",
                    "Sold more than recorded stock: ${outcome.oversoldItemNames.distinct().joinToString()}. Please recount."
                )
            }
            _activeReceiptForSharing.value = outcome.receipt
            onComplete(outcome.receipt)
        }
    }
```

5. Replace `returnSaleAndRestoreStock` with:

```kotlin
    fun returnSaleLine(sale: SaleRecordEntity, qtyMilli: Long, actionId: String) {
        launchSafe("Failed to Return Sale") {
            repository.returnSaleLine(actionId, sale, qtyMilli)
            AppErrorManager.reportSuccess("Return Recorded", "Stock restored for #${sale.receiptNumber}.")
        }
    }
```

In `SaleHistoryScreen.kt`:

1. Change the parameter `onReturnSale: (SaleRecordEntity) -> Unit` to `onReturnSale: (sale: SaleRecordEntity, qtyMilli: Long, actionId: String) -> Unit`.
2. Replace the whole `saleToReturnConfirm?.let { target -> AlertDialog(...) }` block with:

```kotlin
    saleToReturnConfirm?.let { target ->
        val remainingMilli = (target.quantitySoldMilli - target.returnedMilliUnits).coerceAtLeast(0L)
        val returnActionId = remember(target.id) { UUID.randomUUID().toString() }
        var qtyText by remember(target.id) { mutableStateOf(IndianCurrencyFormatter.formatMilliUnits(remainingMilli)) }
        val qtyMilli = qtyText.trim().toDoubleOrNull()?.let { IndianCurrencyFormatter.doubleToMilliUnits(it) }
        val qtyValid = qtyMilli != null && qtyMilli in 1L..remainingMilli
        AlertDialog(
            onDismissRequest = { saleToReturnConfirm = null },
            title = { Text("Return Sale (Wapsi)?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "\"${target.itemNameSnapshot} (${target.variantSummarySnapshot})\": up to " +
                            "${IndianCurrencyFormatter.formatMilliUnits(remainingMilli)} ${target.unitSnapshot} can be returned. " +
                            "Stock is restored and a credit note is created."
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text("Quantity to return (${target.unitSnapshot})") },
                        singleLine = true,
                        isError = !qtyValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = qtyValid,
                    onClick = {
                        onReturnSale(target, qtyMilli!!, returnActionId)
                        saleToReturnConfirm = null
                    }
                ) {
                    Text(
                        text = "Confirm Return & Restore Stock",
                        color = if (qtyValid) CupertinoColors.DestructiveRed else CupertinoColors.LabelMuted,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { saleToReturnConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
```

3. In the sale card, find `if (sale.isReturned) { SemanticPillBadge(text = "Returned (Wapsi)", … ) } else if (isSellerUnlocked) {` and insert a branch between them:

```kotlin
            } else if (sale.returnedMilliUnits > 0L) {
                SemanticPillBadge(
                    text = "Returned ${IndianCurrencyFormatter.formatMilliUnits(sale.returnedMilliUnits)} ${sale.unitSnapshot}",
                    containerColor = CupertinoColors.DestructiveTint,
                    contentColor = CupertinoColors.DestructiveRed
                )
```

4. Add any missing imports: `java.util.UUID`, `androidx.compose.material3.OutlinedTextField`, `androidx.compose.foundation.text.KeyboardOptions`, `androidx.compose.ui.text.input.KeyboardType`, `androidx.compose.foundation.layout.Column`, `Spacer`, `height`, `androidx.compose.runtime.setValue`.

In `MainActivity.kt`, change `onReturnSale = viewModel::returnSaleAndRestoreStock` to `onReturnSale = viewModel::returnSaleLine`.

In `LpsHardwareDomainTest.kt`, replace the two `LpsRepository.formatSmartReceiptId(...)` assertions with the lines below (import `com.shopledger.pos.core.ledger.CalendarParts` and `com.shopledger.pos.core.ledger.ReceiptNumbers`):

```kotlin
        assertEquals("L-261101-0015", ReceiptNumbers.format("L", CalendarParts(2026, 11, 1).dayKey, 15))
        assertEquals("A-260409-0108", ReceiptNumbers.format("A", CalendarParts(2026, 4, 9).dayKey, 108))
```

- [ ] **Step 9: Run the full suite**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`. Everything passes, including `SalesCommandsTest` (11), `ReturnsTest` (5) and `LegacyJsonImporterTest` (2). `concurrentCheckouts_getUniqueGaplessNumbers` can take up to ~20 s.

Then confirm that no REPLACE or `SyncState` remains on write paths:

```bash
grep -rn "OnConflictStrategy.REPLACE\|SyncState\|syncStatus" app/src/main/java/com/shopledger/pos/data
```

Expected: only `SearchHistoryDao.kt` (local-only search history) may still show `REPLACE`; nothing else.

- [ ] **Step 10: Commit**

```bash
git add -A app/src app/schemas
git commit -m "feat(sales): immutable invoices with idempotent atomic checkout, credit-note returns and per-device receipt numbers

sale_records becomes a read-only view over invoices; returns are capped at what was sold and settle khata before cash.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Integrity checker (invariants I1–I5) and property test

**Files:**
- Create: `app/src/main/java/com/shopledger/pos/data/local/IntegrityChecker.kt`
- Modify: `app/src/main/java/com/shopledger/pos/ShopLedgerApp.kt` (debug-only check)
- Test: `app/src/test/java/com/shopledger/pos/data/command/LedgerInvariantPropertyTest.kt`

**Interfaces:**
- Consumes: all commands (Tasks 3–4).
- Produces: `IntegrityChecker.findViolations(db: SupportSQLiteDatabase): List<String>` (empty = healthy). Task 6 (migration) and Task 7 (restore) use it.

- [ ] **Step 1: Write the failing property test**

Create `LedgerInvariantPropertyTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.command.LedgerInvariantPropertyTest"`
Expected: FAIL. `Unresolved reference: IntegrityChecker`.

- [ ] **Step 3: Implement `IntegrityChecker`**

Create `IntegrityChecker.kt`:

```kotlin
package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** The spec's invariants as SQL. Returns human-readable violations; empty means healthy. */
object IntegrityChecker {

    fun findViolations(db: SupportSQLiteDatabase): List<String> {
        val violations = mutableListOf<String>()
        fun collect(label: String, sql: String) {
            db.query(sql).use { c -> while (c.moveToNext()) violations += "$label: ${c.getString(0)}" }
        }
        collect(
            "I1 stock differs from movements",
            "SELECT v.id FROM item_variants v WHERE v.stockMilliUnits != " +
                "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = v.id)"
        )
        collect(
            "I2 balance differs from ledger",
            "SELECT p.id FROM parties p WHERE p.currentBalancePaise != " +
                "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = p.id)"
        )
        collect(
            "I3 invoice totals inconsistent",
            "SELECT h.id FROM invoice_headers h WHERE " +
                "h.subtotalPaise != (SELECT COALESCE(SUM(l.lineTotalPaise), 0) FROM invoice_lines l WHERE l.invoiceId = h.id) " +
                "OR h.grandTotalPaise != h.subtotalPaise - h.discountPaise + h.cgstPaise + h.sgstPaise + h.igstPaise " +
                "OR h.amountPaidPaise + h.balanceDuePaise != h.grandTotalPaise"
        )
        collect(
            "I4 returned more than sold",
            "SELECT l.id FROM invoice_lines l WHERE l.originalLineId IS NULL AND " +
                "(SELECT COALESCE(-SUM(r.qtyMilliUnits), 0) FROM invoice_lines r WHERE r.originalLineId = l.id) > l.qtyMilliUnits"
        )
        collect(
            "I5 duplicate invoice number",
            "SELECT h.invoiceNumber FROM invoice_headers h GROUP BY h.shopId, h.invoiceNumber HAVING COUNT(*) > 1"
        )
        return violations
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.command.LedgerInvariantPropertyTest"`
Expected: PASS.

- [ ] **Step 5: Debug-only startup check**

In `ShopLedgerApp.kt`, add `import com.shopledger.pos.data.local.IntegrityChecker` and append `if (BuildConfig.DEBUG) verifyLedgerInBackground()` as the last line of `onCreate()`. Then add:

```kotlin
    /** Debug builds only: loudly report any ledger/projection drift so bugs surface during development. */
    private fun verifyLedgerInBackground() {
        Thread {
            runCatching { IntegrityChecker.findViolations(database.openHelper.readableDatabase) }
                .onSuccess { violations ->
                    if (violations.isNotEmpty()) {
                        AppErrorManager.reportWarning("Ledger check failed (debug)", violations.take(3).joinToString("\n"))
                    }
                }
                .onFailure { AppErrorManager.reportError("Ledger check crashed (debug)", it) }
        }.start()
    }
```

- [ ] **Step 6: Run the full suite and commit**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`, all tests pass.

```bash
git add app/src
git commit -m "test(ledger): add SQL invariant checker and randomized command-sequence property test

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: Safe v2 → v3 upgrade (migration, safety copy, corruption guard, recovery screen)

The recovery screen offers **Try again** and **Send data file to support** instead of the spec's "Restore previous data". Restoring the v2 safety copy into a v3 app would just re-run the same migration. A failed migration is rolled back by Room, so the live file stays intact at v2 until a fixed app update ships.

**Files:**
- Create: `app/src/main/java/com/shopledger/pos/data/local/OutboxRebuilder.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/migrations/Migration2To3.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/PreMigrationGuard.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/DatabaseHealth.kt`
- Create: `app/src/main/java/com/shopledger/pos/data/local/QuarantiningOpenHelperFactory.kt`
- Create: `app/src/main/java/com/shopledger/pos/core/backup/BackupShare.kt`
- Create: `app/src/main/java/com/shopledger/pos/core/backup/AppRestarter.kt`
- Create: `app/src/main/java/com/shopledger/pos/ui/screens/recovery/DatabaseRecoveryScreen.kt`
- Modify: `app/src/main/java/com/shopledger/pos/data/local/LpsDatabase.kt`
- Modify: `app/src/main/java/com/shopledger/pos/ShopLedgerApp.kt`
- Modify: `app/src/main/java/com/shopledger/pos/MainActivity.kt`
- Test: `app/src/test/java/com/shopledger/pos/data/local/migrations/Migration2To3Test.kt`
- Test: `app/src/test/java/com/shopledger/pos/data/local/PreMigrationGuardTest.kt`

**Interfaces:**
- Consumes: `IntegrityChecker` (Task 5), `SALE_RECORDS_VIEW_SQL` (Task 4), `DatabaseSeed` (Task 3), `app/schemas/.../2.json` (Task 1).
- Produces:
  - `data class MigrationContext(activeShopId, legacyDeviceCode)` and `class Migration2To3(context, clock, newDeviceId) : Migration(2, 3)`
  - `OutboxRebuilder.enqueueEverything(db: SupportSQLiteDatabase, now: Long)` (Task 7 uses it)
  - `PreMigrationGuard.prepare(context, dbName, targetVersion): Outcome`, `latestSafetyCopy(context)`, `deleteSafetyCopiesOlderThan(context, maxAgeMs, now)`
  - `DatabaseHealth.state: StateFlow<State>` (`State.Ok` / `State.Failed(reason, safetyCopy)`), `DatabaseHealth.markFailed(...)`
  - `withShopLedgerDefaults(migrationContext: MigrationContext = MigrationContext())`
  - `LpsDatabase.SCHEMA_VERSION = 3`, `LpsDatabase.closeInstance()`
  - `BackupShare.share(context, file, title)`, `AppRestarter.restart(context)`

- [ ] **Step 1: Write the failing migration test**

Create `Migration2To3Test.kt`:

```kotlin
package com.shopledger.pos.data.local.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LpsDatabase::class.java)

    @Test
    fun migrate2To3_preservesStockBalancesSalesAndReturns() {
        helper.createDatabase(TEST_DB, 2).apply {
            seedV2(this)
            close()
        }
        // validateDroppedTables = false: the v2 movement log is intentionally kept as stock_movements_v2_archive.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, false,
            Migration2To3(MigrationContext(activeShopId = "default_shop", legacyDeviceCode = "d1"), clock = { 5_000L }, newDeviceId = { "device-1" })
        )
        fun long(sql: String) = db.query(sql).use { it.moveToFirst(); it.getLong(0) }
        fun text(sql: String) = db.query(sql).use { it.moveToFirst(); it.getString(0) }

        // Stock: the Double the shopkeeper saw (8.5) wins over the drifted milli column (8000).
        assertEquals(8_500L, long("SELECT stockMilliUnits FROM item_variants WHERE id = 'v1'"))
        assertEquals(8_500L, long("SELECT deltaMilliUnits FROM stock_movements WHERE movementId = 'v3open_v1'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM stock_movements_v2_archive"))
        assertEquals(1L, long("SELECT isDemo FROM item_variants WHERE id = 'v2'"))
        assertEquals(1L, long("SELECT isDemo FROM inventory_items WHERE id = 'i2'"))

        // Khata: balances unchanged; clamped history repaired with an OPENING entry; JAMA → PAYMENT_IN.
        assertEquals(700L, long("SELECT currentBalancePaise FROM parties WHERE id = 'p1'"))
        assertEquals(0L, long("SELECT COUNT(*) FROM party_ledger_entries WHERE entryId = 'v3open_p1'"))
        assertEquals(0L, long("SELECT currentBalancePaise FROM parties WHERE id = 'p2'"))
        assertEquals(300L, long("SELECT amountDeltaPaise FROM party_ledger_entries WHERE entryId = 'v3open_p2'"))
        assertEquals(0L, long("SELECT COUNT(*) FROM party_ledger_entries WHERE entryType LIKE 'JAMA%'"))

        // Sales: 2 invoice lines + 1 orphan sale line, returned line becomes a credit note.
        assertEquals(3L, long("SELECT COUNT(*) FROM sale_records"))
        assertEquals(1L, long("SELECT isReturned FROM sale_records WHERE id = 'l2'"))
        assertEquals(0L, long("SELECT isReturned FROM sale_records WHERE id = 'l1'"))
        assertEquals(-7_500L, long("SELECT grandTotalPaise FROM invoice_headers WHERE id = 'v3cn_inv1'"))
        assertEquals("202611010001-R", text("SELECT invoiceNumber FROM invoice_headers WHERE id = 'v3cn_inv1'"))
        assertEquals(7_500L, long("SELECT grandTotalPaise FROM invoice_headers WHERE invoiceNumber = '202611010002'"))

        // New tables and rows.
        assertEquals("D1", text("SELECT deviceCode FROM devices WHERE isLocal = 1"))
        assertEquals("device-1", text("SELECT deviceId FROM devices WHERE isLocal = 1"))
        assertEquals(1000L, long("SELECT maxStaffDiscountBps FROM shops WHERE shopId = 'default_shop'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'ITEM'")) // demo item i2 excluded
        assertEquals(3L, long("SELECT COUNT(*) FROM sync_outbox WHERE docType = 'INVOICE'"))
        assertEquals(emptyList<String>(), IntegrityChecker.findViolations(db))
    }

    private fun seedV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO inventory_items (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, updatedAt, isDeleted, syncStatus) VALUES " +
                "('i1','default_shop','Butt Hinge','BUTT HINGE','Fittings','',0,1,1,0,'SYNCED'), " +
                "('i2','default_shop','Demo Screw','DEMO SCREW','Fasteners','',0,1,1,0,'DEMO_SEED')"
        )
        db.execSQL(
            "INSERT INTO item_variants (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, stockQuantity, lowStockThreshold, stockMilliUnits, lowStockMilliUnits, updatedAt, isDeleted, syncStatus) VALUES " +
                "('v1','default_shop','i1','','','4 inch','SS','','pcs',0,4800,7500,0,8.5,5.0,8000,5000,1,0,'SYNCED'), " +
                "('v2','default_shop','i2','','','1 inch','','','box',0,1000,1500,0,3.0,1.0,3000,1000,1,0,'DEMO_SEED')"
        )
        db.execSQL(
            "INSERT INTO parties (id, shopId, partyType, name, phone, gstin, billingAddress, openingBalancePaise, currentBalancePaise, updatedAt, isDeleted, syncStatus) VALUES " +
                "('p1','default_shop','CUSTOMER','Ramesh','9876543210','','',0,700,1,0,'SYNCED'), " +
                "('p2','default_shop','CUSTOMER','Suresh','9123456780','','',0,0,1,0,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, syncStatus) VALUES " +
                "('e1','default_shop','p1','202611010001','CREDIT_BILL',1000,'CREDIT','',1,'SYNCED'), " +
                "('e2','default_shop','p1','','JAMA_CASH',-300,'CASH','',2,'SYNCED'), " +
                "('e3','default_shop','p2','x','CREDIT_BILL',200,'CREDIT','',1,'SYNCED'), " +
                "('e4','default_shop','p2','','JAMA_UPI',-500,'UPI','',2,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO invoice_headers (id, shopId, deviceShortCode, invoiceNumber, docType, partyId, buyerName, buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, isReturned, updatedAt, syncStatus) VALUES " +
                "('inv1','default_shop','','202611010001','SALE',NULL,'Walk-in','','CASH',22500,0,0,0,0,22500,22500,0,1000,2026,11,1,0,1000,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO invoice_lines (id, shopId, invoiceId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise, isReturned) VALUES " +
                "('l1','default_shop','inv1','i1','v1','Butt Hinge','4 inch','',2000,'pcs',4800,7500,0,0,15000,5400,0), " +
                "('l2','default_shop','inv1','i1','v1','Butt Hinge','4 inch','',1000,'pcs',4800,7500,0,0,7500,2700,0)"
        )
        val saleColumns = "id, shopId, receiptNumber, buyerName, buyerPhone, partyId, itemId, variantId, itemNameSnapshot, variantSummarySnapshot, quantitySold, unitSnapshot, unitPurchasePricePaise, unitSellingPricePaise, totalCostPaise, totalRevenuePaise, totalProfitPaise, gainMarkupPercent, grossMarginPercent, paymentMode, amountPaidPaise, balanceDuePaise, discountPaise, gstPaise, soldAt, yearInt, monthInt, dayInt, isReturned, updatedAt, syncStatus"
        db.execSQL(
            "INSERT INTO sale_records ($saleColumns) VALUES " +
                "('l1','default_shop','202611010001','Walk-in','',NULL,'i1','v1','Butt Hinge','4 inch',2.0,'pcs',4800,7500,9600,15000,5400,56.25,36.0,'CASH',22500,0,0,0,1000,2026,11,1,0,1000,'SYNCED'), " +
                "('l2','default_shop','202611010001','Walk-in','',NULL,'i1','v1','Butt Hinge','4 inch',1.0,'pcs',4800,7500,4800,7500,2700,56.25,36.0,'CASH',22500,0,0,0,1000,2026,11,1,1,2000,'SYNCED'), " +
                "('s3','default_shop','202611010002','','',NULL,'i1','v1','Butt Hinge','4 inch',1.0,'pcs',4800,7500,4800,7500,2700,56.25,36.0,'CASH',7500,0,0,0,3000,2026,11,1,0,3000,'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO stock_movements (movementId, shopId, skuId, deviceShortCode, deltaMilliUnits, movementType, refDocId, note, createdAt, syncStatus) VALUES " +
                "('m1','default_shop','v1','',-2000,'SALE','202611010001','Sale',1000,'SYNCED')"
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
```

Create `PreMigrationGuardTest.kt`:

```kotlin
package com.shopledger.pos.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreMigrationGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "guard-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile?.listFiles { f -> f.name.contains(".pre-v") }?.forEach { it.delete() }
    }

    private fun createDb(version: Int) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE t (x INTEGER)")
            db.execSQL("INSERT INTO t VALUES (42)")
            db.version = version
        }
    }

    @Test
    fun olderDatabase_getsAReadableSafetyCopy() {
        createDb(2)
        val outcome = PreMigrationGuard.prepare(context, name, 3)
        assertTrue(outcome is PreMigrationGuard.Outcome.BackedUp)
        val copy = (outcome as PreMigrationGuard.Outcome.BackedUp).safetyCopy
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT x FROM t", null).use { c ->
                c.moveToFirst()
                assertEquals(42, c.getInt(0))
            }
        }
        assertEquals(copy, PreMigrationGuard.latestSafetyCopy(context))
    }

    @Test
    fun currentOrMissingDatabase_needsNoCopy() {
        assertEquals(PreMigrationGuard.Outcome.NotNeeded, PreMigrationGuard.prepare(context, "missing.db", 3))
        createDb(3)
        assertEquals(PreMigrationGuard.Outcome.NotNeeded, PreMigrationGuard.prepare(context, name, 3))
    }

    @Test
    fun oldSafetyCopies_areDeleted() {
        createDb(2)
        val copy = (PreMigrationGuard.prepare(context, name, 3) as PreMigrationGuard.Outcome.BackedUp).safetyCopy
        copy.setLastModified(1_000L)
        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, maxAgeMs = 14L * 86_400_000L, now = 30L * 86_400_000L)
        assertFalse(copy.exists())
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.local.*"`
Expected: FAIL. `Unresolved reference: Migration2To3` / `PreMigrationGuard`.

- [ ] **Step 3: Implement `OutboxRebuilder` and `Migration2To3`**

Create `OutboxRebuilder.kt`:

```kotlin
package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Queues every syncable document once (after the v3 upgrade and after a restore). Movements and
 * ledger entries that belong to an invoice travel inside that INVOICE document, so they are skipped.
 */
object OutboxRebuilder {
    fun enqueueEverything(db: SupportSQLiteDatabase, now: Long) {
        val insert = "INSERT INTO sync_outbox (docType, docId, op, createdAt, attempts, lastError) "
        val args = arrayOf<Any>(now)
        db.execSQL(insert + "SELECT 'ITEM', id, 'UPSERT', ?, 0, '' FROM inventory_items WHERE isDemo = 0", args)
        db.execSQL(insert + "SELECT 'VARIANT', id, 'UPSERT', ?, 0, '' FROM item_variants WHERE isDemo = 0", args)
        db.execSQL(insert + "SELECT 'PARTY', id, 'UPSERT', ?, 0, '' FROM parties", args)
        db.execSQL(
            insert + "SELECT 'MOVEMENT', m.movementId, 'APPEND', ?, 0, '' FROM stock_movements m " +
                "WHERE m.refDocId NOT IN (SELECT id FROM invoice_headers) " +
                "AND NOT EXISTS (SELECT 1 FROM item_variants v WHERE v.id = m.skuId AND v.isDemo = 1)",
            args
        )
        db.execSQL(
            insert + "SELECT 'LEDGER', e.entryId, 'APPEND', ?, 0, '' FROM party_ledger_entries e " +
                "WHERE e.refDocId NOT IN (SELECT id FROM invoice_headers)",
            args
        )
        db.execSQL(insert + "SELECT 'INVOICE', id, 'APPEND', ?, 0, '' FROM invoice_headers", args)
    }
}
```

Create `migrations/Migration2To3.kt`. Every `CREATE TABLE` / `CREATE INDEX` must match Room's generated `createSql` in `app/schemas/com.shopledger.pos.data.local.LpsDatabase/3.json`. If the test reports `Migration didn't properly handle <table>`, compare its *Expected/Found* output with that file and copy Room's statement verbatim.

```kotlin
package com.shopledger.pos.data.local.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shopledger.pos.data.local.DatabaseSeed
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.OutboxRebuilder
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import com.shopledger.pos.data.local.entity.SALE_RECORDS_VIEW_SQL
import java.util.Locale
import java.util.UUID

/** Values the migration needs from app settings (SharedPreferences are not reachable from SQL). */
data class MigrationContext(
    val activeShopId: String = DEFAULT_SHOP_ID,
    val legacyDeviceCode: String = ""
)

/**
 * v2 → v3: ledgers become the source of truth, invoices become immutable, sale_records becomes a view.
 * Runs inside Room's migration transaction: any exception rolls everything back and leaves v2 intact.
 */
class Migration2To3(
    private val context: MigrationContext = MigrationContext(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() }
) : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val now = clock()
        val deviceId = newDeviceId()
        val deviceCode = context.legacyDeviceCode.trim().uppercase(Locale.ROOT).take(4)
            .ifEmpty { DatabaseSeed.LOCAL_DEVICE_CODE }
        val itemsBefore = count(db, "inventory_items")
        val variantsBefore = count(db, "item_variants")
        val partiesBefore = count(db, "parties")
        val saleLinesBefore = count(db, "sale_records")

        shopsAndDevices(db, now, deviceId, deviceCode)
        rebuildInventoryItems(db)
        rebuildItemVariants(db)
        rebuildStockMovements(db, now, deviceId, deviceCode)
        rebuildParties(db)
        rebuildLedger(db, now, deviceId)
        rebuildInvoices(db)
        convertLegacySales(db)
        replaceSaleRecordsWithView(db)
        createSyncTables(db)
        recomputeProjections(db)
        repairLegacyInvoiceTotals(db)
        OutboxRebuilder.enqueueEverything(db, now)

        check(count(db, "inventory_items") == itemsBefore) { "Item count changed during upgrade" }
        check(count(db, "item_variants") == variantsBefore) { "Variant count changed during upgrade" }
        check(count(db, "parties") == partiesBefore) { "Party count changed during upgrade" }
        check(count(db, "sale_records") >= saleLinesBefore) { "Sale lines were lost during upgrade" }
        val violations = IntegrityChecker.findViolations(db)
        check(violations.isEmpty()) { "v3 upgrade check failed: ${violations.take(5)}" }
    }

    private fun shopsAndDevices(db: SupportSQLiteDatabase, now: Long, deviceId: String, deviceCode: String) {
        db.execSQL("ALTER TABLE `shops` ADD COLUMN `maxStaffDiscountBps` INTEGER NOT NULL DEFAULT 1000")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `devices` (`deviceId` TEXT NOT NULL, `shopId` TEXT NOT NULL, `deviceCode` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, `isLocal` INTEGER NOT NULL, `lastInvoiceSeq` INTEGER NOT NULL, `lastSeqDate` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`deviceId`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_shopId_deviceCode` ON `devices` (`shopId`, `deviceCode`)")
        DatabaseSeed.seedFreshDatabase(db, now, deviceId, context.activeShopId, deviceCode)
    }

    private fun rebuildInventoryItems(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `inventory_items_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`sortKey` TEXT NOT NULL, `category` TEXT NOT NULL, `defaultHsnCode` TEXT NOT NULL, `defaultGstRateBps` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `isDemo` INTEGER NOT NULL, " +
                "`createdByUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO inventory_items_v3 (id, shopId, displayName, sortKey, category, defaultHsnCode, defaultGstRateBps, createdAt, " +
                "updatedAt, isDeleted, isDemo, createdByUid, deviceId) SELECT id, shopId, displayName, sortKey, category, defaultHsnCode, " +
                "defaultGstRateBps, createdAt, updatedAt, isDeleted, CASE WHEN syncStatus = 'DEMO_SEED' THEN 1 ELSE 0 END, '', '' FROM inventory_items"
        )
        db.execSQL("DROP TABLE inventory_items")
        db.execSQL("ALTER TABLE inventory_items_v3 RENAME TO inventory_items")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_sortKey` ON `inventory_items` (`sortKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_shopId_sortKey` ON `inventory_items` (`shopId`, `sortKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_items_isDeleted` ON `inventory_items` (`isDeleted`)")
    }

    private fun rebuildItemVariants(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_variants_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                "`skuCode` TEXT NOT NULL, `barcode` TEXT NOT NULL, `sizeLabel` TEXT NOT NULL, `materialLabel` TEXT NOT NULL, " +
                "`colorFinishLabel` TEXT NOT NULL, `unit` TEXT NOT NULL, `mrpPaise` INTEGER NOT NULL, `purchasePricePaise` INTEGER NOT NULL, " +
                "`sellingPricePaise` INTEGER NOT NULL, `gstRateBps` INTEGER NOT NULL, `stockMilliUnits` INTEGER NOT NULL, " +
                "`lowStockMilliUnits` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, " +
                "`isDemo` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`itemId`) REFERENCES `inventory_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        // v2 screens showed the Double columns, so they are the truth when the two disagree.
        db.execSQL(
            "INSERT INTO item_variants_v3 (id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, mrpPaise, " +
                "purchasePricePaise, sellingPricePaise, gstRateBps, stockMilliUnits, lowStockMilliUnits, createdAt, updatedAt, isDeleted, " +
                "isDemo, createdByUid, deviceId) SELECT id, shopId, itemId, skuCode, barcode, sizeLabel, materialLabel, colorFinishLabel, unit, " +
                "mrpPaise, purchasePricePaise, sellingPricePaise, gstRateBps, CAST(ROUND(stockQuantity * 1000.0) AS INTEGER), " +
                "CAST(ROUND(lowStockThreshold * 1000.0) AS INTEGER), updatedAt, updatedAt, isDeleted, " +
                "CASE WHEN syncStatus = 'DEMO_SEED' THEN 1 ELSE 0 END, '', '' FROM item_variants"
        )
        db.execSQL("DROP TABLE item_variants")
        db.execSQL("ALTER TABLE item_variants_v3 RENAME TO item_variants")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variants_itemId` ON `item_variants` (`itemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variants_shopId_barcode` ON `item_variants` (`shopId`, `barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variants_isDeleted` ON `item_variants` (`isDeleted`)")
    }

    private fun rebuildStockMovements(db: SupportSQLiteDatabase, now: Long, deviceId: String, deviceCode: String) {
        // Keep v2's log for audit; v3 starts from one OPENING movement per variant so I1 holds from day one.
        db.execSQL("DROP INDEX IF EXISTS `index_stock_movements_shopId_skuId`")
        db.execSQL("DROP INDEX IF EXISTS `index_stock_movements_syncStatus`")
        db.execSQL("ALTER TABLE stock_movements RENAME TO stock_movements_v2_archive")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stock_movements` (`movementId` TEXT NOT NULL, `shopId` TEXT NOT NULL, `skuId` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `deviceShortCode` TEXT NOT NULL, `deltaMilliUnits` INTEGER NOT NULL, `movementType` TEXT NOT NULL, " +
                "`refDocId` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, PRIMARY KEY(`movementId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_shopId_skuId` ON `stock_movements` (`shopId`, `skuId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_refDocId` ON `stock_movements` (`refDocId`)")
        db.execSQL(
            "INSERT INTO stock_movements (movementId, shopId, skuId, deviceId, deviceShortCode, deltaMilliUnits, movementType, refDocId, note, createdAt, createdByUid) " +
                "SELECT 'v3open_' || id, shopId, id, ?, ?, stockMilliUnits, 'OPENING', itemId, 'Stock at upgrade', ?, '' " +
                "FROM item_variants WHERE stockMilliUnits != 0",
            arrayOf<Any>(deviceId, deviceCode, now)
        )
    }

    private fun rebuildParties(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parties_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `partyType` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`phone` TEXT NOT NULL, `gstin` TEXT NOT NULL, `billingAddress` TEXT NOT NULL, `currentBalancePaise` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO parties_v3 (id, shopId, partyType, name, phone, gstin, billingAddress, currentBalancePaise, createdAt, updatedAt, isDeleted, createdByUid, deviceId) " +
                "SELECT id, shopId, partyType, name, phone, gstin, billingAddress, currentBalancePaise, updatedAt, updatedAt, isDeleted, '', '' FROM parties"
        )
        db.execSQL("DROP TABLE parties")
        db.execSQL("ALTER TABLE parties_v3 RENAME TO parties")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_shopId_partyType` ON `parties` (`shopId`, `partyType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_shopId_phone` ON `parties` (`shopId`, `phone`)")
    }

    private fun rebuildLedger(db: SupportSQLiteDatabase, now: Long, deviceId: String) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `party_ledger_entries_v3` (`entryId` TEXT NOT NULL, `shopId` TEXT NOT NULL, `partyId` TEXT NOT NULL, " +
                "`refDocId` TEXT NOT NULL, `entryType` TEXT NOT NULL, `amountDeltaPaise` INTEGER NOT NULL, `paymentMode` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`entryId`))"
        )
        db.execSQL(
            "INSERT INTO party_ledger_entries_v3 (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, createdByUid, deviceId) " +
                "SELECT entryId, shopId, partyId, refDocId, CASE WHEN entryType IN ('JAMA_CASH', 'JAMA_UPI') THEN 'PAYMENT_IN' ELSE entryType END, " +
                "amountDeltaPaise, paymentMode, note, createdAt, '', '' FROM party_ledger_entries"
        )
        db.execSQL("DROP TABLE party_ledger_entries")
        db.execSQL("ALTER TABLE party_ledger_entries_v3 RENAME TO party_ledger_entries")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_party_ledger_entries_shopId_partyId` ON `party_ledger_entries` (`shopId`, `partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_party_ledger_entries_refDocId` ON `party_ledger_entries` (`refDocId`)")
        // v2 clamped payments at zero, so entries may not add up to the balance the shopkeeper saw.
        // An OPENING entry for the difference makes I2 hold without changing any balance.
        db.execSQL(
            "INSERT INTO party_ledger_entries (entryId, shopId, partyId, refDocId, entryType, amountDeltaPaise, paymentMode, note, createdAt, createdByUid, deviceId) " +
                "SELECT 'v3open_' || p.id, p.shopId, p.id, '', 'OPENING', " +
                "p.currentBalancePaise - COALESCE((SELECT SUM(e.amountDeltaPaise) FROM party_ledger_entries e WHERE e.partyId = p.id), 0), " +
                "'CASH', 'Balance at upgrade', ?, '', ? FROM parties p " +
                "WHERE p.currentBalancePaise != COALESCE((SELECT SUM(e.amountDeltaPaise) FROM party_ledger_entries e WHERE e.partyId = p.id), 0)",
            arrayOf<Any>(now, deviceId)
        )
    }

    private fun rebuildInvoices(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `invoice_headers_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`deviceShortCode` TEXT NOT NULL, `invoiceNumber` TEXT NOT NULL, `docType` TEXT NOT NULL, `originalInvoiceId` TEXT, " +
                "`partyId` TEXT, `buyerName` TEXT NOT NULL, `buyerPhone` TEXT NOT NULL, `paymentMode` TEXT NOT NULL, " +
                "`subtotalPaise` INTEGER NOT NULL, `discountPaise` INTEGER NOT NULL, `cgstPaise` INTEGER NOT NULL, `sgstPaise` INTEGER NOT NULL, " +
                "`igstPaise` INTEGER NOT NULL, `grandTotalPaise` INTEGER NOT NULL, `amountPaidPaise` INTEGER NOT NULL, " +
                "`balanceDuePaise` INTEGER NOT NULL, `issuedAt` INTEGER NOT NULL, `yearInt` INTEGER NOT NULL, `monthInt` INTEGER NOT NULL, " +
                "`dayInt` INTEGER NOT NULL, `createdByUid` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO invoice_headers_v3 (id, shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
                "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, " +
                "balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid) SELECT id, shopId, '', deviceShortCode, invoiceNumber, " +
                "'SALE', NULL, partyId, buyerName, buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, " +
                "grandTotalPaise, amountPaidPaise, balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, '' FROM invoice_headers"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `invoice_lines_v3` (`id` TEXT NOT NULL, `shopId` TEXT NOT NULL, `invoiceId` TEXT NOT NULL, " +
                "`originalLineId` TEXT, `productId` TEXT NOT NULL, `skuId` TEXT NOT NULL, `itemNameSnapshot` TEXT NOT NULL, " +
                "`variantSummarySnapshot` TEXT NOT NULL, `hsnSnapshot` TEXT NOT NULL, `qtyMilliUnits` INTEGER NOT NULL, `unitSnapshot` TEXT NOT NULL, " +
                "`unitCostPaise` INTEGER NOT NULL, `unitSellPaise` INTEGER NOT NULL, `gstRateBps` INTEGER NOT NULL, `lineTaxPaise` INTEGER NOT NULL, " +
                "`lineTotalPaise` INTEGER NOT NULL, `lineProfitPaise` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`invoiceId`) REFERENCES `invoice_headers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "INSERT INTO invoice_lines_v3 (id, shopId, invoiceId, originalLineId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, " +
                "hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise) " +
                "SELECT id, shopId, invoiceId, NULL, productId, skuId, itemNameSnapshot, variantSummarySnapshot, hsnSnapshot, qtyMilliUnits, " +
                "unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise FROM invoice_lines"
        )
        db.execSQL("DROP TABLE invoice_lines")
        db.execSQL("DROP TABLE invoice_headers")
        db.execSQL("ALTER TABLE invoice_headers_v3 RENAME TO invoice_headers")
        db.execSQL("ALTER TABLE invoice_lines_v3 RENAME TO invoice_lines")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_invoice_headers_shopId_invoiceNumber` ON `invoice_headers` (`shopId`, `invoiceNumber`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_headers_shopId_issuedAt` ON `invoice_headers` (`shopId`, `issuedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_headers_shopId_docType` ON `invoice_headers` (`shopId`, `docType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_headers_originalInvoiceId` ON `invoice_headers` (`originalInvoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_lines_invoiceId` ON `invoice_lines` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_lines_shopId_invoiceId` ON `invoice_lines` (`shopId`, `invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_lines_originalLineId` ON `invoice_lines` (`originalLineId`)")
    }

    /** v2 sync pulls and JSON imports wrote sale_records only; give those lines an invoice. Returns become credit notes. */
    private fun convertLegacySales(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO invoice_headers (id, shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
                "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, " +
                "balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid) " +
                "SELECT 'v3mig_' || s.shopId || '_' || s.receiptNumber, s.shopId, '', '', " +
                "CASE WHEN EXISTS (SELECT 1 FROM invoice_headers x WHERE x.shopId = s.shopId AND x.invoiceNumber = s.receiptNumber) " +
                "THEN s.receiptNumber || '-M' ELSE s.receiptNumber END, 'SALE', NULL, MAX(s.partyId), MAX(s.buyerName), MAX(s.buyerPhone), " +
                "MAX(s.paymentMode), SUM(s.totalRevenuePaise), 0, 0, 0, 0, SUM(s.totalRevenuePaise), SUM(s.totalRevenuePaise), 0, " +
                "MAX(s.soldAt), MAX(s.yearInt), MAX(s.monthInt), MAX(s.dayInt), '' FROM sale_records s " +
                "WHERE NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.id = s.id) GROUP BY s.shopId, s.receiptNumber"
        )
        db.execSQL(
            "INSERT INTO invoice_lines (id, shopId, invoiceId, originalLineId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, " +
                "hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise) " +
                "SELECT s.id, s.shopId, 'v3mig_' || s.shopId || '_' || s.receiptNumber, NULL, s.itemId, s.variantId, s.itemNameSnapshot, " +
                "s.variantSummarySnapshot, '', CAST(ROUND(s.quantitySold * 1000.0) AS INTEGER), s.unitSnapshot, s.unitPurchasePricePaise, " +
                "s.unitSellingPricePaise, 0, 0, s.totalRevenuePaise, s.totalProfitPaise FROM sale_records s " +
                "WHERE NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.id = s.id)"
        )
        // v2 already restored stock for returns (OPENING carries it) and never touched khata for them,
        // so historical credit notes are cash refunds at line value with no movements or ledger rows.
        db.execSQL(
            "INSERT INTO invoice_headers (id, shopId, deviceId, deviceShortCode, invoiceNumber, docType, originalInvoiceId, partyId, buyerName, " +
                "buyerPhone, paymentMode, subtotalPaise, discountPaise, cgstPaise, sgstPaise, igstPaise, grandTotalPaise, amountPaidPaise, " +
                "balanceDuePaise, issuedAt, yearInt, monthInt, dayInt, createdByUid) " +
                "SELECT 'v3cn_' || h.id, h.shopId, '', h.deviceShortCode, h.invoiceNumber || '-R', 'CREDIT_NOTE', h.id, h.partyId, h.buyerName, " +
                "h.buyerPhone, h.paymentMode, -SUM(l.lineTotalPaise), 0, 0, 0, 0, -SUM(l.lineTotalPaise), -SUM(l.lineTotalPaise), 0, " +
                "h.issuedAt, h.yearInt, h.monthInt, h.dayInt, '' FROM sale_records s " +
                "JOIN invoice_lines l ON l.id = s.id JOIN invoice_headers h ON h.id = l.invoiceId WHERE s.isReturned = 1 GROUP BY h.id"
        )
        db.execSQL(
            "INSERT INTO invoice_lines (id, shopId, invoiceId, originalLineId, productId, skuId, itemNameSnapshot, variantSummarySnapshot, " +
                "hsnSnapshot, qtyMilliUnits, unitSnapshot, unitCostPaise, unitSellPaise, gstRateBps, lineTaxPaise, lineTotalPaise, lineProfitPaise) " +
                "SELECT 'v3cn_' || l.id, l.shopId, 'v3cn_' || l.invoiceId, l.id, l.productId, l.skuId, l.itemNameSnapshot, l.variantSummarySnapshot, " +
                "l.hsnSnapshot, -l.qtyMilliUnits, l.unitSnapshot, l.unitCostPaise, l.unitSellPaise, l.gstRateBps, 0, -l.lineTotalPaise, " +
                "-l.lineProfitPaise FROM sale_records s JOIN invoice_lines l ON l.id = s.id WHERE s.isReturned = 1"
        )
    }

    private fun replaceSaleRecordsWithView(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `sale_records`")
        db.execSQL("CREATE VIEW `sale_records` AS $SALE_RECORDS_VIEW_SQL")
    }

    private fun createSyncTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `docType` TEXT NOT NULL, " +
                "`docId` TEXT NOT NULL, `op` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_docType_docId` ON `sync_outbox` (`docType`, `docId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `applied_actions` (`actionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `resultRef` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`actionId`))"
        )
    }

    private fun recomputeProjections(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE item_variants SET stockMilliUnits = " +
                "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
        )
        db.execSQL(
            "UPDATE parties SET currentBalancePaise = " +
                "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
        )
    }

    /** Odd legacy rows (partial syncs, old imports) may have header totals that don't match their lines. */
    private fun repairLegacyInvoiceTotals(db: SupportSQLiteDatabase) {
        val lineSum = "(SELECT COALESCE(SUM(l.lineTotalPaise), 0) FROM invoice_lines l WHERE l.invoiceId = invoice_headers.id)"
        var repaired = db.compileStatement("UPDATE invoice_headers SET subtotalPaise = $lineSum WHERE subtotalPaise != $lineSum").executeUpdateDelete()
        repaired += db.compileStatement(
            "UPDATE invoice_headers SET grandTotalPaise = subtotalPaise - discountPaise + cgstPaise + sgstPaise + igstPaise " +
                "WHERE grandTotalPaise != subtotalPaise - discountPaise + cgstPaise + sgstPaise + igstPaise"
        ).executeUpdateDelete()
        repaired += db.compileStatement(
            "UPDATE invoice_headers SET amountPaidPaise = grandTotalPaise - balanceDuePaise WHERE amountPaidPaise + balanceDuePaise != grandTotalPaise"
        ).executeUpdateDelete()
        if (repaired > 0) Log.w("Migration2To3", "Repaired $repaired legacy invoice total(s)")
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
}
```

- [ ] **Step 4: Implement the safety copy, health state and corruption guard**

Create `PreMigrationGuard.kt`:

```kotlin
package com.shopledger.pos.data.local

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Before Room upgrades the schema: verify the file and keep a byte-for-byte safety copy. */
object PreMigrationGuard {
    private const val MARKER = ".pre-v"

    sealed interface Outcome {
        data object NotNeeded : Outcome
        data class BackedUp(val safetyCopy: File) : Outcome
        data class Corrupt(val detail: String) : Outcome
    }

    fun prepare(context: Context, dbName: String, targetVersion: Int): Outcome {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return Outcome.NotNeeded
        // A no-op error handler: Android's default handler would DELETE a corrupt file.
        val sqlite = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { })
        try {
            if (sqlite.version >= targetVersion) return Outcome.NotNeeded
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val result = sqlite.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "no result" }
            if (!result.equals("ok", ignoreCase = true)) return Outcome.Corrupt(result)
        } finally {
            sqlite.close()
        }
        val copy = File(dbFile.parentFile, "${dbName.removeSuffix(".db")}$MARKER$targetVersion.db")
        if (!copy.exists()) {
            val tmp = File(copy.path + ".tmp")
            dbFile.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(copy)) { "Could not finalise safety copy" }
        }
        return Outcome.BackedUp(copy)
    }

    fun latestSafetyCopy(context: Context): File? =
        databaseDir(context)?.listFiles { f -> f.name.contains(MARKER) && f.name.endsWith(".db") }?.maxByOrNull { it.lastModified() }

    fun deleteSafetyCopiesOlderThan(context: Context, maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        databaseDir(context)?.listFiles { f -> f.name.contains(MARKER) }
            ?.filter { now - it.lastModified() > maxAgeMs }
            ?.forEach { it.delete() }
    }

    private fun databaseDir(context: Context): File? = context.getDatabasePath("probe").parentFile
}
```

Create `DatabaseHealth.kt`:

```kotlin
package com.shopledger.pos.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** App-wide "can we use the database?" flag. MainActivity shows the recovery screen when Failed. */
object DatabaseHealth {
    sealed interface State {
        data object Ok : State
        data class Failed(val reason: String, val safetyCopy: File?) : State
    }

    private val _state = MutableStateFlow<State>(State.Ok)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The first failure wins; later ones are usually consequences of it. */
    fun markFailed(reason: String, safetyCopy: File?) {
        if (_state.value is State.Ok) _state.value = State.Failed(reason, safetyCopy)
    }
}
```

Create `QuarantiningOpenHelperFactory.kt`:

```kotlin
package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * Android's default corruption handling deletes the database file, which would wipe a shop's
 * books. This factory keeps a copy, flags the app unhealthy and refuses to continue instead.
 */
class QuarantiningOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                val path = db.path
                runCatching { db.close() }
                val stamp = System.currentTimeMillis()
                val kept = path?.let { p ->
                    listOf("", "-wal").mapNotNull { suffix ->
                        File(p + suffix).takeIf { it.exists() }?.copyTo(File("$p.corrupt-$stamp$suffix"), overwrite = true)
                    }.firstOrNull()
                }
                DatabaseHealth.markFailed("The data file is damaged. A copy was kept for recovery and nothing was deleted.", kept)
                throw IllegalStateException("Database corruption detected; file kept, not deleted")
            }
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(guarded)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build()
        )
    }
}
```

- [ ] **Step 5: Wire them into the database builder**

In `LpsDatabase.kt`:
1. Change `version = 3` to `version = LpsDatabase.SCHEMA_VERSION`.
2. In the companion object add:

```kotlin
        /** Keep in sync with the newest migration. */
        const val SCHEMA_VERSION = 3

        /** Used by backup restore: the next getInstance() reopens (and migrates) the swapped file. */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
```

3. Replace `buildDatabase` with:

```kotlin
        private fun buildDatabase(appContext: Context): LpsDatabase {
            val legacyDbFile = appContext.getDatabasePath(LEGACY_DB_NAME)
            val targetDbFile = appContext.getDatabasePath(DB_NAME)
            if (legacyDbFile.exists() && !targetDbFile.exists()) {
                runCatching {
                    targetDbFile.parentFile?.mkdirs()
                    legacyDbFile.copyTo(targetDbFile, overwrite = false)
                }
            }
            when (val outcome = PreMigrationGuard.prepare(appContext, DB_NAME, SCHEMA_VERSION)) {
                is PreMigrationGuard.Outcome.Corrupt -> DatabaseHealth.markFailed(
                    "Your data file failed its safety check (${outcome.detail}). Nothing was changed.", null
                )
                else -> Unit
            }
            val migrationContext = MigrationContext(
                activeShopId = SyncManager.getConfig(appContext).shopCollectionId,
                legacyDeviceCode = SyncManager.getDeviceShortCode(appContext)
            )
            return Room.databaseBuilder(appContext, LpsDatabase::class.java, DB_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(QuarantiningOpenHelperFactory())
                .withShopLedgerDefaults(migrationContext)
                .build()
        }
```

4. Replace `withShopLedgerDefaults` at the bottom of the file with:

```kotlin
/** Shared by the app and tests so every database gets the same migrations and seed rows. */
fun RoomDatabase.Builder<LpsDatabase>.withShopLedgerDefaults(
    migrationContext: MigrationContext = MigrationContext()
): RoomDatabase.Builder<LpsDatabase> =
    addMigrations(LpsDatabase.MIGRATION_1_2, Migration2To3(migrationContext))
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DatabaseSeed.seedFreshDatabase(db, System.currentTimeMillis())
            }
        })
```

5. Add imports: `com.shopledger.pos.core.sync.SyncManager`, `com.shopledger.pos.data.local.migrations.Migration2To3`, `com.shopledger.pos.data.local.migrations.MigrationContext`.

- [ ] **Step 6: Run the migration and guard tests**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.data.local.*"`
Expected: PASS (`Migration2To3Test`, 3 × `PreMigrationGuardTest`, `DatabaseSmokeTest`).

If it fails with `Migration didn't properly handle: <table>`, the message prints *Expected* (from `3.json`) and *Found* (after migration). Fix the matching `CREATE TABLE`/`CREATE INDEX` in `Migration2To3` to equal Room's `createSql`. For a `sale_records` view mismatch, copy the `createSql` of the `views` entry in `3.json` verbatim into `replaceSaleRecordsWithView`.

- [ ] **Step 7: Recovery screen, share and restart helpers**

Create `core/backup/BackupShare.kt`:

```kotlin
package com.shopledger.pos.core.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object BackupShare {
    /** Shares [file] through the app's FileProvider (`cache/backups/`), copying it there if needed. */
    fun share(context: Context, file: File, title: String) {
        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val shareable = if (file.parentFile == dir) file else File(dir, file.name).also { file.copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareable)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
```

Create `core/backup/AppRestarter.kt`:

```kotlin
package com.shopledger.pos.core.backup

import android.content.Context
import android.content.Intent

/** Restarts the app process so every component reopens the (restored or repaired) database. */
object AppRestarter {
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
```

Create `ui/screens/recovery/DatabaseRecoveryScreen.kt`:

```kotlin
package com.shopledger.pos.ui.screens.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.ui.theme.CupertinoColors

@Composable
fun DatabaseRecoveryScreen(
    reason: String,
    hasSafetyCopy: Boolean,
    onShareSafetyCopy: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoColors.CardSurface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("We couldn't open your shop data", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = CupertinoColors.LabelPrimary)
        Spacer(modifier = Modifier.height(12.dp))
        Text(reason, fontSize = 15.sp, color = CupertinoColors.LabelSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Nothing has been deleted. Install the latest app update if one is available, or send the data file to support so it can be recovered.",
            fontSize = 14.sp,
            color = CupertinoColors.LabelSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        if (hasSafetyCopy) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onShareSafetyCopy, modifier = Modifier.fillMaxWidth()) { Text("Send data file to support") }
        }
    }
}
```

- [ ] **Step 8: Open the database at startup and route failures to the recovery screen**

Replace `ShopLedgerApp.onCreate()` with the code below. Keep `verifyLedgerInBackground()` from Task 5, and add imports for `DatabaseHealth`, `PreMigrationGuard` and `java.util.concurrent.TimeUnit`:

```kotlin
    override fun onCreate() {
        super.onCreate()
        AppErrorManager.installGlobalExceptionHandler(this)
        runCatching { ItemDraftManager.init(this) }
            .onFailure { AppErrorManager.reportError("Failed to restore saved item draft", it) }
        runCatching { GoogleAuthManager.init(this) }
            .onFailure { AppErrorManager.reportError("Auth initialization issue", it) }
        runCatching { SyncManager.init(this) }
            .onFailure { AppErrorManager.reportError("Settings initialization issue", it) }
        database = LpsDatabase.getInstance(this)
        if (DatabaseHealth.state.value is DatabaseHealth.State.Ok) {
            // Open (and migrate) now, so a failed upgrade shows the recovery screen instead of a random crash later.
            runCatching { database.openHelper.writableDatabase }
                .onFailure {
                    DatabaseHealth.markFailed(
                        "Upgrading your data failed (${it.message ?: it.javaClass.simpleName}). Your data is safe.",
                        PreMigrationGuard.latestSafetyCopy(this)
                    )
                }
        }
        repository = LpsRepository(this, database)
        if (DatabaseHealth.state.value is DatabaseHealth.State.Ok) {
            PreMigrationGuard.deleteSafetyCopiesOlderThan(this, TimeUnit.DAYS.toMillis(14))
            runCatching { SyncManager.scheduleDeltaSync(this) }
                .onFailure { AppErrorManager.reportError("Background sync scheduling failed", it) }
            if (BuildConfig.DEBUG) verifyLedgerInBackground()
        }
    }
```

In `MainActivity.onCreate`, replace the `setContent { … }` block with the following, adding imports for `DatabaseHealth`, `DatabaseRecoveryScreen`, `BackupShare` and `AppRestarter`:

```kotlin
        setContent {
            LpsCupertinoTheme {
                val health by DatabaseHealth.state.collectAsState()
                when (val current = health) {
                    is DatabaseHealth.State.Failed -> DatabaseRecoveryScreen(
                        reason = current.reason,
                        hasSafetyCopy = current.safetyCopy != null,
                        onShareSafetyCopy = {
                            current.safetyCopy?.let { BackupShare.share(this, it, "Send ShopLedger data file") }
                        },
                        onRetry = { AppRestarter.restart(this) }
                    )
                    DatabaseHealth.State.Ok -> LpsHardwareMainApp(viewModel = viewModel)
                }
            }
        }
```

- [ ] **Step 9: Run the full suite and commit**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`, all tests pass.

```bash
git add -A app/src app/schemas
git commit -m "feat(db): safe v2->v3 upgrade with safety copy, invariant post-check, corruption quarantine and recovery screen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Encrypted daily snapshots, shareable backup and restore

**Files:**
- Create: `app/src/main/java/com/shopledger/pos/core/backup/SnapshotCipher.kt`
- Create: `app/src/main/java/com/shopledger/pos/core/backup/BackupManager.kt`
- Create: `app/src/main/java/com/shopledger/pos/core/backup/LocalSnapshotWorker.kt`
- Modify: `app/src/main/java/com/shopledger/pos/ShopLedgerApp.kt`
- Modify: `app/src/main/java/com/shopledger/pos/data/repository/LpsRepository.kt` (delete `exportBackupJson`)
- Modify: `app/src/main/java/com/shopledger/pos/ui/MainViewModel.kt` (delete `exportBackupJson`)
- Modify: `app/src/main/java/com/shopledger/pos/ui/screens/settings/CloudSyncAndBackupSheet.kt`
- Modify: `app/src/main/java/com/shopledger/pos/MainActivity.kt`
- Modify: `app/src/test/java/com/shopledger/pos/testing/TestSupport.kt` (add `AesTestCipher`)
- Test: `app/src/test/java/com/shopledger/pos/core/backup/BackupManagerTest.kt`

**Interfaces:**
- Consumes: `LpsDatabase.DB_NAME`, `SCHEMA_VERSION`, `closeInstance()`, `OutboxRebuilder`, `IntegrityChecker`, `DatabaseHealth`, `BackupShare`, `AppRestarter` (Task 6).
- Produces: `interface SnapshotCipher { encrypt; decrypt }`, `KeystoreSnapshotCipher`, `BackupManager(context, cipher, databaseProvider, closeDatabase, dbName, clock)` with `createEncryptedSnapshot(keep = 7)`, `exportShareableBackup()`, `listSnapshots()`, `restoreFromSnapshot(file)`, `restoreFromShareable(input)`, `BackupManager.forApp(context)`; `BackupException`; `RestoreReport(fromSchemaVersion, violations)`; `LocalSnapshotWorker.schedule(context)`.

- [ ] **Step 1: Add the test cipher**

Append to `TestSupport.kt`, adding imports `javax.crypto.Cipher`, `javax.crypto.spec.GCMParameterSpec`, `javax.crypto.spec.SecretKeySpec` and `com.shopledger.pos.core.backup.SnapshotCipher`:

```kotlin
/** Fixed-key AES-GCM (Robolectric has no AndroidKeyStore). Output = 12-byte IV + ciphertext. */
class AesTestCipher : SnapshotCipher {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}
```

- [ ] **Step 2: Write the failing backup test**

Create `BackupManagerTest.kt`:

```kotlin
package com.shopledger.pos.core.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.CatalogCommands
import com.shopledger.pos.data.command.VariantSpec
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.withShopLedgerDefaults
import com.shopledger.pos.testing.AesTestCipher
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.scalar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "backup-test.db"
    private val env = TestEnv()
    private var db: LpsDatabase? = null
    private var clock = TestEnv.BASE_TIME

    private fun open(): LpsDatabase = db ?: Room.databaseBuilder(context, LpsDatabase::class.java, dbName)
        .withShopLedgerDefaults()
        .allowMainThreadQueries()
        .build()
        .also { db = it }

    private fun close() {
        db?.close()
        db = null
    }

    private val manager by lazy {
        BackupManager(context, AesTestCipher(), databaseProvider = ::open, closeDatabase = ::close, dbName = dbName, clock = { clock })
    }

    @After
    fun tearDown() {
        close()
        context.deleteDatabase(dbName)
        File(context.filesDir, "backups").deleteRecursively()
        File(context.cacheDir, "backups").deleteRecursively()
    }

    private suspend fun addItem(actionId: String, name: String, openingMilli: Long) {
        CatalogCommands(open(), env).createItem(
            actionId, name, "Fittings",
            listOf(VariantSpec(purchasePricePaise = 4_800L, sellingPricePaise = 7_500L, openingStockMilli = openingMilli))
        )
    }

    @Test
    fun encryptedSnapshot_thenChange_thenRestore_bringsBackTheSnapshot() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val snapshot = manager.createEncryptedSnapshot()
        addItem("a2", "Tower Bolt", 1_000L)
        assertEquals(2L, open().scalar("SELECT COUNT(*) FROM inventory_items"))

        val report = manager.restoreFromSnapshot(snapshot)

        assertEquals(3, report.fromSchemaVersion)
        assertEquals(emptyList<String>(), report.violations)
        assertEquals(1L, open().scalar("SELECT COUNT(*) FROM inventory_items"))
        assertEquals(10_000L, open().scalar("SELECT stockMilliUnits FROM item_variants"))
        assertTrue(open().scalar("SELECT COUNT(*) FROM sync_outbox") > 0)
    }

    @Test
    fun snapshots_keepOnlyTheNewestSeven() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        repeat(9) {
            clock += 1_000L
            manager.createEncryptedSnapshot()
        }
        assertEquals(7, manager.listSnapshots().size)
    }

    @Test
    fun shareableBackup_roundTrips() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val file = manager.exportShareableBackup()
        addItem("a2", "Tower Bolt", 1_000L)
        file.inputStream().use { manager.restoreFromShareable(it) }
        assertEquals(1L, open().scalar("SELECT COUNT(*) FROM inventory_items"))
    }

    @Test
    fun garbageFile_isRejectedAndLiveDataIsUntouched() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        expectFailure<BackupException> { manager.restoreFromShareable("not a backup".byteInputStream()) }
        assertEquals(1L, open().scalar("SELECT COUNT(*) FROM inventory_items"))
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.core.backup.BackupManagerTest"`
Expected: FAIL. `Unresolved reference: BackupManager` / `SnapshotCipher`.

- [ ] **Step 4: Implement the cipher and `BackupManager`**

Create `SnapshotCipher.kt`:

```kotlin
package com.shopledger.pos.core.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SnapshotCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output = 12-byte IV + ciphertext. */
class KeystoreSnapshotCipher(private val alias: String = "shopledger_snapshot_v1") : SnapshotCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}
```

Create `BackupManager.kt`:

```kotlin
package com.shopledger.pos.core.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.OutboxRebuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RestoreReport(val fromSchemaVersion: Int, val violations: List<String>)

/**
 * Whole-database backups. Works on API 24 (no VACUUM INTO): copies db + WAL while holding the
 * write lock, then folds the WAL into the copy and verifies it with PRAGMA integrity_check.
 */
class BackupManager(
    private val context: Context,
    private val cipher: SnapshotCipher,
    private val databaseProvider: () -> LpsDatabase,
    private val closeDatabase: () -> Unit,
    private val dbName: String = LpsDatabase.DB_NAME,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val snapshotDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    /** Encrypted, on-device only. Keeps the newest [keep]. */
    fun createEncryptedSnapshot(keep: Int = 7): File {
        val plain = copyConsistentDatabase()
        try {
            val out = File(snapshotDir, "snapshot-${stamp()}.slsnap")
            writeAtomically(out, cipher.encrypt(gzip(plain.readBytes())))
            listSnapshots().dropLast(keep).forEach { it.delete() }
            return out
        } finally {
            deleteWithSidecars(plain)
        }
    }

    /** Gzip only, so it can be restored on another phone. Lives in cache/backups for FileProvider sharing. */
    fun exportShareableBackup(): File {
        val plain = copyConsistentDatabase()
        try {
            val dir = File(context.cacheDir, "backups").apply { mkdirs() }
            val out = File(dir, "ShopLedger-backup-${stamp()}.slbak")
            writeAtomically(out, gzip(plain.readBytes()))
            return out
        } finally {
            deleteWithSidecars(plain)
        }
    }

    fun listSnapshots(): List<File> =
        snapshotDir.listFiles { f -> f.name.endsWith(".slsnap") }?.sortedBy { it.name }.orEmpty()

    fun restoreFromSnapshot(file: File): RestoreReport =
        restoreDatabaseBytes(decode { gunzip(cipher.decrypt(file.readBytes())) })

    fun restoreFromShareable(input: InputStream): RestoreReport =
        restoreDatabaseBytes(decode { gunzip(input.readBytes()) })

    private inline fun decode(block: () -> ByteArray): ByteArray =
        try {
            block()
        } catch (e: Exception) {
            throw BackupException("This file is not a ShopLedger backup", e)
        }

    private fun copyConsistentDatabase(): File {
        val sqlite = databaseProvider().openHelper.writableDatabase
        val live = context.getDatabasePath(dbName)
        val work = File(context.cacheDir, "snapshot-work").apply { mkdirs() }
        val copy = File(work, "copy-${clock()}.db")
        // Holding a write transaction blocks other writers and checkpoints, so db + wal are one consistent state.
        sqlite.beginTransaction()
        try {
            live.copyTo(copy, overwrite = true)
            val wal = File(live.path + "-wal")
            if (wal.exists()) wal.copyTo(File(copy.path + "-wal"), overwrite = true)
        } finally {
            sqlite.endTransaction()
        }
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { }).use { snapshot ->
            snapshot.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val integrity = snapshot.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
            if (!integrity.equals("ok", ignoreCase = true)) throw BackupException("Snapshot failed its integrity check: $integrity")
        }
        File(copy.path + "-wal").delete()
        File(copy.path + "-shm").delete()
        return copy
    }

    private fun restoreDatabaseBytes(bytes: ByteArray): RestoreReport {
        val live = context.getDatabasePath(dbName)
        live.parentFile?.mkdirs()
        val staged = File(live.path + ".restore-staged")
        staged.writeBytes(bytes)
        val version = try {
            SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { candidate ->
                val integrity = candidate.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                if (!integrity.equals("ok", ignoreCase = true)) throw BackupException("The backup file is damaged ($integrity)")
                val looksRight = candidate.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'inventory_items'", null
                ).use { it.moveToFirst() }
                if (!looksRight) throw BackupException("This file is not a ShopLedger backup")
                candidate.version
            }
        } catch (e: BackupException) {
            staged.delete()
            throw e
        } catch (e: Exception) {
            staged.delete()
            throw BackupException("This file is not a ShopLedger backup", e)
        }
        if (version > LpsDatabase.SCHEMA_VERSION) {
            staged.delete()
            throw BackupException("This backup was made by a newer version of ShopLedger. Update the app first.")
        }

        closeDatabase()
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        if (!staged.renameTo(live)) {
            staged.copyTo(live, overwrite = true)
            staged.delete()
        }

        // Reopening runs any needed migration (older backups); then rebuild everything derived.
        val db = databaseProvider()
        val sqlite = db.openHelper.writableDatabase
        db.runInTransaction {
            sqlite.execSQL(
                "UPDATE item_variants SET stockMilliUnits = " +
                    "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
            )
            sqlite.execSQL(
                "UPDATE parties SET currentBalancePaise = " +
                    "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
            )
            sqlite.execSQL("DELETE FROM sync_outbox")
            OutboxRebuilder.enqueueEverything(sqlite, clock())
        }
        return RestoreReport(version, IntegrityChecker.findViolations(sqlite))
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(clock()))

    private fun deleteWithSidecars(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    companion object {
        fun forApp(context: Context): BackupManager {
            val app = context.applicationContext
            return BackupManager(
                context = app,
                cipher = KeystoreSnapshotCipher(),
                databaseProvider = { LpsDatabase.getInstance(app) },
                closeDatabase = { LpsDatabase.closeInstance() }
            )
        }
    }
}
```

- [ ] **Step 5: Run it and confirm it passes**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.core.backup.BackupManagerTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Nightly worker and app wiring**

Create `LocalSnapshotWorker.kt`:

```kotlin
package com.shopledger.pos.core.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shopledger.pos.data.local.DatabaseHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class LocalSnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (DatabaseHealth.state.value !is DatabaseHealth.State.Ok) return@withContext Result.success()
        runCatching { BackupManager.forApp(applicationContext).createEncryptedSnapshot() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
            )
    }

    companion object {
        private const val WORK_NAME = "shopledger_local_snapshot"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocalSnapshotWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
```

In `ShopLedgerApp.kt`:
1. Add the property:

```kotlin
    lateinit var backupManager: BackupManager
        private set
```

2. Right after `repository = LpsRepository(this, database)`, add `backupManager = BackupManager.forApp(this)`.
3. In the second `if (DatabaseHealth.state.value is DatabaseHealth.State.Ok) { … }` block, add:

```kotlin
            runCatching { LocalSnapshotWorker.schedule(this) }
                .onFailure { AppErrorManager.reportError("Backup scheduling failed", it) }
```

4. Add the imports `com.shopledger.pos.core.backup.BackupManager` and `com.shopledger.pos.core.backup.LocalSnapshotWorker`.

- [ ] **Step 7: Backup UI**

Delete `exportBackupJson()` from both `LpsRepository.kt` and `MainViewModel.kt`. In `CloudSyncAndBackupSheet.kt`:

1. Replace the parameter `onExportBackupJson: suspend () -> String,` with:

```kotlin
    onExportBackupFile: suspend () -> File?,
    onRestoreBackupFile: suspend (InputStream) -> Boolean,
```

2. Next to the existing `showImportBox` state, add:

```kotlin
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val ok = runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { onRestoreBackupFile(it) }
                }.getOrDefault(false)
                if (ok) {
                    Toast.makeText(context, "Backup restored. Restarting…", Toast.LENGTH_SHORT).show()
                    AppRestarter.restart(context)
                } else {
                    Toast.makeText(context, "That file is not a ShopLedger backup", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Replace all data on this phone?") },
            text = { Text("Everything on this phone will be replaced by the backup file. Export a backup first if you might need today's data.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    restorePicker.launch(arrayOf("*/*"))
                }) { Text("Choose backup file", color = CupertinoColors.DestructiveRed) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
        )
    }
```

3. Replace the whole `// 4. Local JSON Backup Export / Restore` `CupertinoCard { … }` with:

```kotlin
            // 4. Backup & Restore
            CupertinoCard {
                Text(
                    text = "Backup & Restore",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This phone keeps an encrypted backup every night (last 7 days). Export a backup file to keep a copy on Google Drive or move to a new phone. The file contains your prices and customers, so share it only with yourself.",
                    fontSize = 12.sp,
                    color = CupertinoColors.LabelSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val file = onExportBackupFile()
                                if (file != null) BackupShare.share(context, file, "Save ShopLedger backup")
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Backup", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showRestoreConfirm = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restore Backup", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { showImportBox = !showImportBox }) {
                    Text("Import an old JSON backup (merge)", fontSize = 12.sp)
                }

                if (showImportBox) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CupertinoTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        label = "Paste Backup JSON Here",
                        singleLine = false,
                        capitalization = KeyboardCapitalization.None
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val ok = onImportBackupJson(importJsonText)
                                Toast.makeText(
                                    context,
                                    if (ok) "Backup merged successfully!" else "Invalid Backup JSON",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) {
                                    showImportBox = false
                                    importJsonText = ""
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import & Merge Records", fontWeight = FontWeight.Bold)
                    }
                }
            }
```

4. Add any missing imports: `java.io.File`, `java.io.InputStream`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `com.shopledger.pos.core.backup.AppRestarter`, `com.shopledger.pos.core.backup.BackupShare`. Remove `ClipData`/`ClipboardManager` if now unused.

In `MainActivity.kt`, inside `LpsHardwareMainApp`, add `val app = LocalContext.current.applicationContext as ShopLedgerApp` near the top. Then replace `onExportBackupJson = viewModel::exportBackupJson,` in the `CloudSyncAndBackupSheet(...)` call with:

```kotlin
            onExportBackupFile = {
                withContext(Dispatchers.IO) {
                    runCatching { app.backupManager.exportShareableBackup() }
                        .onFailure { AppErrorManager.reportError("Backup Export Failed", it) }
                        .getOrNull()
                }
            },
            onRestoreBackupFile = { stream ->
                withContext(Dispatchers.IO) {
                    runCatching { app.backupManager.restoreFromShareable(stream) }
                        .onFailure { AppErrorManager.reportError("Restore Failed", it) }
                        .isSuccess
                }
            },
```

Add the imports `androidx.compose.ui.platform.LocalContext`, `kotlinx.coroutines.Dispatchers` and `kotlinx.coroutines.withContext`.

- [ ] **Step 8: Run the full suite and commit**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`, all tests pass.

```bash
git add -A app/src
git commit -m "feat(backup): nightly encrypted snapshots, shareable backup export and verified restore

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Oversold display, honest sync status, docs and end-to-end verification

**Files:**
- Modify: `app/src/main/java/com/shopledger/pos/ui/screens/inventory/InventoryScreen.kt` (stock label)
- Modify: `app/src/main/java/com/shopledger/pos/MainActivity.kt` (status chip)
- Modify: `README.md` (feature 5)

**Interfaces:**
- Consumes: `ItemVariantEntity.isOversold` (Task 3).
- Produces: nothing new. This is the release-candidate gate for sub-project 1.

- [ ] **Step 1: Show oversold stock instead of "Out of Stock"**

In `InventoryScreen.kt`, replace the `val (stockLabel, stockColor) = when { … }` block with:

```kotlin
        val (stockLabel, stockColor) = when {
            activeVariant.isOversold -> "Oversold: $stockQty ${activeVariant.unit} (please recount)" to CupertinoColors.DestructiveRed
            activeVariant.isOutOfStock -> "Out of Stock" to CupertinoColors.DestructiveRed
            activeVariant.isLowStock -> "Low Stock: $stockQty ${activeVariant.unit}" to CupertinoColors.AmberWarning
            else -> "In Stock: $stockQty ${activeVariant.unit}" to CupertinoColors.EmeraldGreen
        }
```

- [ ] **Step 2: Stop claiming "Cloud Synced"**

Cloud sync is off until sub-project 2, so the outbox count only grows. In `MainActivity.kt`'s header chip, replace the `Icon(…)` block and the two `append` lines with:

```kotlin
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Saved on this phone",
                                tint = CupertinoColors.EmeraldGreen,
                                modifier = Modifier.size(13.dp)
                            )
```

and

```kotlin
                                    append("Saved on this phone")
```

(replacing `if (pendingSyncCount == 0) append("Cloud Synced") else append("$pendingSyncCount pending sync")`). Add `import androidx.compose.material.icons.filled.CheckCircle` if it's missing. `pendingSyncCount` is still passed to `CloudSyncAndBackupSheet`, which shows "Cloud sync coming soon • N pending".

- [ ] **Step 3: Update the README feature list**

In `README.md`, replace item **5** under "Key Features" with:

```markdown
5. **Offline-First Ledger Database with Backups**:
   - Room SQLite (WAL) where stock and Khata balances are calculated from append-only ledgers, every sale/return/payment is one atomic, double-tap-safe transaction, and returns are GST-style credit notes.
   - Nightly encrypted on-device backups (last 7) plus shareable backup files for Google Drive or a new phone. Multi-device cloud sync arrives in the next release.
```

- [ ] **Step 4: Full automated verification**

Run: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
Expected: `BUILD SUCCESSFUL`, 0 failures.

Run the following outside the sandbox, with the same `JAVA_HOME`/`JAVA_TOOL_OPTIONS` as `scripts/test.ps1`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\build\jtmp"; .\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`. `assembleRelease` proves the R8/minify config still keeps Room entities and views. If R8 strips something Room needs, add a keep rule to `app/proguard-rules.pro`: `-keep class com.shopledger.pos.data.local.** { *; }`.

- [ ] **Step 5: Manual upgrade check on a real phone or emulator**

1. Build the current production app from `main` in a separate worktree:

```bash
git worktree add ../Inventory-main main
```

then run `.\gradlew.bat assembleDebug` in `../Inventory-main` with the same environment variables. Install it with `adb install -r ../Inventory-main/app/build/outputs/apk/debug/app-debug.apk`.

2. In that v2 app, create: 2 items (one with stock 8.5), a cash sale, a credit sale for a named customer, a Khata payment, and a return of one sale line. Note every stock figure and every Khata balance.
3. Install the new build over it: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Both use `debug.keystore`, so this is an in-place upgrade.
4. Verify:
   - The app opens without the recovery screen.
   - Every stock figure and Khata balance matches your notes.
   - Sales History shows all sales, with the returned line struck through.
   - `adb shell run-as com.shopledger.pos ls databases` lists `shop_ledger.pre-v3.db`.
5. Double-tap **Complete Sale** quickly. Exactly one bill appears, numbered `L-<yyMMdd>-0001` (or with your device code).
6. Sell more than the stock on hand. The bill completes, a "Stock below zero" warning appears, and the item shows "Oversold".
7. Return 1 of 2 units from Sales History. A "Returned 1 pcs" badge appears and stock goes up by 1.
8. Settings → **Export Backup** → save to Drive or Files. Add an item. Then **Restore Backup** from that file. The app restarts and the added item is gone.
9. Clean up: `git worktree remove ../Inventory-main`.

- [ ] **Step 6: Commit**

```bash
git add app/src README.md
git commit -m "feat(ui): show oversold stock, honest local-save status; docs for ledger and backups

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Spec coverage map

| Spec section | Implemented in |
|---|---|
| §2.1 lost stock updates, §3.2 stock ledger + projection (I1) | Task 3 (`LedgerWriter.appendMovement`, `addToStockProjection`); Task 4 checkout/returns |
| §2.2 lost Khata balances, ledger + projection (I2), advances | Task 3 (`KhataCommands`, `appendLedgerEntry`) |
| §2.3 duplicate/overwritten bills, §3.3 receipt numbering, I5 | Task 2 (`ReceiptNumbers`), Task 4 (`bumpInvoiceSeq` in-transaction, ABORT inserts, `applied_actions`) |
| §2.4 stock clamping, oversold display | Task 3 (no clamp), Task 4 (warning), Task 8 (label) |
| §2.5 payment clamping | Task 3 (`recordPayment`) |
| §2.6 returns → credit notes, cap (I4), Khata adjustment | Task 4 (`returnItems`) |
| §2.7 sync-status race → outbox | Task 3 (`sync_outbox`, worker stubbed), Task 6 (`OutboxRebuilder`) |
| §2.8 unsafe schema evolution | Task 1 (schema export), Task 6 (migration, safety copy, post-check, recovery, corruption guard) |
| §2.9 floating-point quantities | Tasks 3–4 (milli-units everywhere; Double only as getters and view columns) |
| §3.1 audit columns | Tasks 3–4 entities (`createdAt`, `createdByUid`, `deviceId`) |
| §4 one transaction per command, idempotency, stale-edit, staff rules | Task 3 (`runIdempotent`, `editItem`, `deleteItem`), Task 4 (discount limit) |
| §5 outbox shape, one INVOICE doc per bill | Task 3 (`SyncOutboxEntity`), Task 4 (`enqueue = false` for bundled rows) |
| §6 migration v2 → v3 | Task 6 |
| §7 local backups and restore | Task 7 |
| §8 code structure, worker stub, UI touch points | Tasks 3, 4, 7, 8 |
| §9 tests | Tasks 1–7 (see each task's test file) |

## Notes for sub-project 2 (Cloud & accounts)

- **Device codes.** Every unjoined phone uses `deviceCode = "L"`. Joining a shop must assign a unique code before any synced invoice is created, or invoice numbers collide across phones.
- **Demo rows.** Demo rows (`isDemo = 1`) are never queued. If a user edits or sells a demo item, promote it (`isDemo = 0`, enqueue the item, its variants and their movements).
- **Restored device row.** A restored backup brings back the source phone's local `devices` row. Restoring onto a second, still-active phone must re-issue a new `deviceId`/code.
