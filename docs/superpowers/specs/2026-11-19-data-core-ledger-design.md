# ShopLedger POS — Production Launch, Sub-project 1: Data Core (Ledger, Atomicity, Outbox)

- **Date:** 2026-11-19
- **Status:** Approved design, awaiting spec review
- **Scope of this spec:** Sub-project 1 only. Sub-projects 2–4 are recorded under "Launch roadmap" so the decisions aren't lost; each gets its own spec.

## 1. Context and launch decisions

ShopLedger POS is an offline-first Android POS app (Kotlin, Jetpack Compose, Room/SQLite, about 20k LOC). It syncs to Firebase Firestore over REST from a WorkManager worker, and ships as APKs through GitHub Releases with in-app OTA.

Launch decisions agreed on 2026-11-19:

| Topic | Decision |
|---|---|
| Devices per shop | **Several at once** (owner and staff phones sell from the same stock at the same time, sometimes offline) |
| Launch size | **Pilot, under 20 shops**, under about 150 bills a day each. Must stay on the free tier, and should scale to about 100 shops without a redesign |
| Distribution | **Direct APK + in-app OTA** (no Play Store), with a proper private release key |
| Staff access | **Owner invites staff.** Each person uses their own Google account. Roles are OWNER and STAFF. The owner can revoke a phone |
| Backend | **Firebase Auth + Firestore (free Spark tier)**, with a redesigned append-only ledger and outbox sync |
| Build order | 1 Data core → 2 Cloud & accounts → 3 Release & ops → 4 UI refresh |

## 2. Problems this sub-project fixes (found in current code)

1. **Lost stock updates across devices.** `FirestoreDeltaSyncWorker` pushes absolute `stockQuantity` per variant, and the last write wins. Two offline sales (−2 and −3 from 10) converge to 7 or 8 instead of 5.
2. **Lost Khata balances.** `parties.currentBalancePaise` is an absolute value synced last-write-wins, and is read-modify-written outside the transaction (`LpsRepository.executeCheckoutAndDeductStock`, `recordPartyPaymentReceived`).
3. **Duplicate or overwritten bills.** `generateNextSmartReceiptNumber` runs outside the transaction. `upsertInvoiceHeader` uses `OnConflictStrategy.REPLACE` against the unique index `(shopId, invoiceNumber)`, so a colliding second checkout replaces the first header row.
4. **Stock clamped to 0** in `deductVariantStock`, so the cached stock stops matching the movement log.
5. **Payments clamped at 0** (`coerceAtLeast(0L)`), so overpayments and advances are silently lost.
6. **Returns** flip `isReturned` on a sale row. There's no credit note, no Khata adjustment for credit sales, and no cap on the returned quantity.
7. **Sync status race.** Pending rows are read, pushed, then marked SYNCED by ID. Edits made in between are marked synced without ever being pushed.
8. **Unsafe schema evolution.** `exportSchema = false`, there are no migration tests, and no backup is taken before a migration.
9. **Floating-point quantities** (`Double stockQuantity`, `quantitySold`) sit alongside milli-unit longs, and the two drift apart.

## 3. Data model (schema v3)

### 3.1 Conventions
- **Money:** `Long` paise. **Quantities:** `Long` milli-units (1 unit = 1000). No `Double` in persisted columns. Computed getters (for example `val stockQuantity: Double get() = stockMilliUnits / 1000.0`) keep UI call sites compiling.
- **IDs:** UUID v4 strings generated on device.
- **Audit columns on every table:** `shopId`, `createdAt` (device epoch ms), `createdByUid` (Firebase UID, or `""` before sign-in), `deviceId` (stable per install).
- **Mutable entities** (items, variants, parties, shop profile) also carry `updatedAt`. Cross-device conflicts on these resolve last-write-wins **per document** using a server timestamp (`serverUpdatedAt`, populated in sub-project 2). Ledger-type tables are append-only and never conflict.
- Per-table `syncStatus` columns are **removed**. Sync state lives only in `sync_outbox` (§5).

### 3.2 Tables

| Table | Kind | Notes |
|---|---|---|
| `shops` | mutable | Profile, GST, UPI, cipher config, `invoicePrefix`, discount limit |
| `devices` | mutable | `deviceId` (PK), `deviceCode` (A, B, C… unique per shop), `label`, `nextInvoiceSeq` (Long), `lastSeqDate` (yyMMdd) |
| `inventory_items` | mutable | Unchanged fields minus `syncStatus` |
| `item_variants` | mutable + **cached projection** | `stockMilliUnits` = Σ movements for the SKU (cache). `lowStockMilliUnits`. Prices in paise. `stockQuantity`/`lowStockThreshold` `Double` columns are dropped |
| `stock_movements` | **append-only, source of truth** | `movementId`, `skuId`, `deltaMilliUnits` (signed), `movementType` ∈ {OPENING, SALE, SALE_RETURN, PURCHASE, ADJUSTMENT, CORRECTION}, `refDocId`, `note` |
| `parties` | mutable + **cached projection** | `currentBalancePaise` = Σ ledger entries (cache). `openingBalancePaise` column removed (now an OPENING entry) |
| `party_ledger_entries` | **append-only, source of truth** | Signed `amountDeltaPaise` (+ = party owes shop). `entryType` ∈ {OPENING, CREDIT_BILL, PAYMENT_IN, PAYMENT_OUT, CREDIT_NOTE, ADJUSTMENT}. Negative balance = advance |
| `invoice_headers` | **immutable** | `docType` ∈ {SALE, CREDIT_NOTE}. `originalInvoiceId` (credit notes). `actionId` **UNIQUE** (idempotency key). `invoiceNumber` UNIQUE per shop |
| `invoice_lines` | **immutable** | `qtyMilliUnits` (negative on credit notes), `originalLineId` (credit notes), tax/profit snapshots |
| `sale_records` | **Room `@DatabaseView`** | Same column names as today, derived from `invoice_lines` ⨝ `invoice_headers`. `isReturned` / `returnedMilliUnits` come from credit-note lines. Read-only |
| `sync_outbox` | queue | See §5 |
| `search_history` | local only | Unchanged (not synced) |

Invariants. Tests check these, and a debug-only `verifyIntegrity()` asserts them:
- **I1:** For every SKU, `item_variants.stockMilliUnits == Σ stock_movements.deltaMilliUnits`.
- **I2:** For every party, `parties.currentBalancePaise == Σ party_ledger_entries.amountDeltaPaise`.
- **I3:** For every invoice, `grandTotalPaise == Σ lines.lineTotalPaise − discount + tax`, and `amountPaid + balanceDue == grandTotal`.
- **I4:** For every original line, Σ credit-note qty (absolute) ≤ original qty.
- **I5:** `invoiceNumber` and `actionId` are unique per shop.

Negative stock is **allowed** and shown as "−N (oversold)". Checkout warns when a line would take this device's view of stock below 0, but doesn't block it.

### 3.3 Receipt numbering
Format: `{deviceCode}-{yyMMdd}-{seq:04d}`, for example `A-261119-0007`. Before a device joins a shop (sub-project 2), `deviceCode = "L"` (local). The sequence resets daily per device. Inside the checkout transaction: `UPDATE devices SET nextInvoiceSeq = CASE WHEN lastSeqDate = :today THEN nextInvoiceSeq + 1 ELSE 1 END, lastSeqDate = :today WHERE deviceId = :me`, then read it back. That makes numbers unique across the shop's devices with no server round-trip. `invoicePrefix`, if set, is shown before it on printed receipts only.

## 4. Atomic business operations

All writes go through a new `ShopLedgerCommands` layer (split out of the 1,323-line `LpsRepository`). Each command is **one `database.withTransaction { }`** and performs, in order:

1. **Validate inside the transaction.** Read the current variant, party and original invoice rows. Enforce role rules: STAFF can't edit cost price, delete records, or exceed the shop discount limit without owner-PIN verification passed in as a parameter.
2. **Allocate the invoice number** (sale and credit note only).
3. **Insert the document rows.** `OnConflictStrategy.ABORT`; no `REPLACE` anywhere in write paths.
4. **Append `stock_movements`** and `party_ledger_entries`.
5. **Update cached projections** with relative SQL (`stockMilliUnits = stockMilliUnits + :delta`, no clamping).
6. **Insert `sync_outbox` rows** for every changed document.

Any exception rolls back the whole transaction. The command returns `Result<T>` with typed errors (`StaleEdit`, `ReturnExceedsSold`, `PermissionDenied`, `Validation`). The ViewModel keeps the cart or form intact and shows the error through `AppErrorManager`.

| Command | Idempotency | Writes |
|---|---|---|
| `checkout(actionId, cart, buyer, payment, discount, gst)` | `actionId` UUID created when the cart is opened. If it already exists, return the existing receipt and write nothing | header, lines, SALE movements, party (create if needed), CREDIT_BILL entry if balance due, outbox |
| `returnItems(actionId, originalInvoiceId, lines[qty])` | `actionId` | CREDIT_NOTE header and lines, SALE_RETURN movements, CREDIT_NOTE ledger entry if the original had a party balance, outbox |
| `recordPayment(actionId, partyId, amount, mode, direction)` | `actionId` | PAYMENT_IN/OUT entry, balance projection, outbox. No clamp (advance allowed) |
| `adjustStock(actionId, skuId, newCountMilli or delta, reason)` | `actionId` | ADJUSTMENT movement (delta = target − current, read inside the transaction), outbox. A "set count to N" is stored as a delta relative to this device's view at that moment. If another device's unsynced sales arrive later, they still apply on top. This is intended: the count reflects shelf stock at the time of counting |
| `receivePurchase(actionId, lines)` | `actionId` | PURCHASE movements, optional supplier PAYMENT_OUT/CREDIT_BILL, outbox |
| `createItem / editItem(expectedUpdatedAt) / deleteItem` | edit and delete check `expectedUpdatedAt`, else `StaleEdit` | item/variant rows (soft delete), OPENING movement on create, outbox |
| `upsertParty(expectedUpdatedAt)` | as above | party row, OPENING entry on create, outbox |

Idempotency keys for non-invoice commands live in a small `applied_actions(actionId PK, kind, resultRef, createdAt)` table, written in the same transaction.

## 5. Sync outbox (local half of sync)

```
sync_outbox(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  docType TEXT,       -- INVOICE (header + lines + its movements + ledger entries), MOVEMENT, LEDGER, ITEM, VARIANT, PARTY, SHOP
  docId TEXT,
  op TEXT,            -- UPSERT | APPEND
  createdAt INTEGER,
  attempts INTEGER DEFAULT 0,
  lastError TEXT
)
```
- Written in the same transaction as the change.
- **Sub-project 2's** worker reads the oldest N rows, builds the cloud payload from the *current* local rows, commits, and deletes **exactly those `seq` values** on success. An edit made during an upload inserts a new `seq`, so it's never lost.
- An invoice and its lines, movements and ledger entries form **one** outbox row and one cloud document. That's about 1 Firestore write per bill instead of about 12, which keeps the free tier usable.
- The UI's "pending sync" badge = `SELECT COUNT(*) FROM sync_outbox`.

## 6. Migration v2 → v3

1. **Pre-flight**, before Room opens the database, in `LpsDatabase.buildDatabase`: if the on-disk version is 2, checkpoint WAL, copy `shop_ledger.db` to `shop_ledger.pre-v3.db`, and run `PRAGMA integrity_check`. If the check fails, show the recovery screen instead of migrating.
2. **`MIGRATION_2_3`**, a single SQLite transaction (Room wraps migrations):
   - Create the `devices` row for this install (`deviceCode` from the existing `deviceShortCode`, or `"L"`).
   - Rebuild `item_variants` without the `Double`/`syncStatus` columns.
   - Replace existing `stock_movements` with one `OPENING` movement per variant equal to its current `stockMilliUnits`, so I1 holds from the start. v2's movement log is kept in `stock_movements_v2_archive` for audit.
   - Rebuild `parties` and insert an `OPENING` ledger entry equal to the difference between `currentBalancePaise` and the sum of existing entries, so I2 holds.
   - Turn `sale_records` into invoices where missing (v2 migration already created `inv_<receipt>` headers). For returned lines, create a CREDIT_NOTE per original invoice. Then drop the `sale_records` table and create the `sale_records` view.
   - Add `actionId`, `docType` and `originalInvoiceId` columns, and the unique indexes.
   - Drop all `syncStatus` columns and indexes. Create `sync_outbox` and `applied_actions`.
   - Enqueue one outbox row for every syncable document (first full upload under the new format).
3. **Post-check:** run the I1–I5 checks. On failure, don't delete the backup file, log via `AppErrorManager`, and offer "Restore previous data".
4. `exportSchema = true`, schemas in `app/schemas/` committed to git, and `room.schemaLocation` set through the KSP arg.
5. Keep the backup file for 14 days after a successful migration, then delete it.

## 7. Local backups and restore

- **Daily `LocalSnapshotWorker`** (WorkManager, idle + battery-not-low): `VACUUM INTO` a temp file, gzip, encrypt with an Android Keystore AES-GCM key, and write to `filesDir/backups/snapshot-YYYYMMDD.db.gz.enc`. Keeps the last 7.
- **Export:** an unencrypted or passphrase-encrypted copy shared through the Android share sheet (Drive, WhatsApp to self, USB). This replaces today's JSON export as the primary path. JSON import stays for legacy files.
- **Restore:** decrypt, check `integrity_check` plus the schema version, close Room, swap the file atomically (rename), reopen, recompute projections, and clear and rebuild the outbox from the restored data.

## 8. Code structure changes

- `data/local/LpsDatabase.kt`: v3, migration in its own file `data/local/migrations/Migration2To3.kt`, pre-flight backup helper.
- `data/local/entity/*`: updated entities, `SaleRecordView`.
- `data/local/dao/*`: append-only DAOs (no `REPLACE`), projection-update queries, outbox DAO.
- New `data/command/ShopLedgerCommands.kt` (+ `CommandErrors.kt`): all write paths from §4.
- `data/repository/LpsRepository.kt`: becomes read-side queries plus a thin facade delegating writes to commands. Seed, search history and legacy JSON import stay.
- `core/backup/LocalSnapshotWorker.kt`, `core/backup/BackupRestoreManager.kt`.
- `FirestoreDeltaSyncWorker`: **stubbed to a no-op behind a flag** in this sub-project, because its v2 payloads would be wrong. It's rewritten in sub-project 2. Until then, cloud sync shows "Cloud sync coming soon" (it can't succeed against current rules anyway).
- ViewModel and UI: only the changes needed to call the new commands (cart `actionId`, typed errors, return-quantity picker, "oversold" label).

## 9. Testing

Add Robolectric + `androidx.room:room-testing` + `kotlinx-coroutines-test` (JVM, no emulator), run by `./gradlew testDebugUnitTest` in CI on every push and PR.

| Test | Asserts |
|---|---|
| `Migration2To3Test` | A seeded v2 database (via `MigrationTestHelper`) migrates. The v2 schema JSON is generated first by building the current commit (`f75abe9`) with `exportSchema = true` and committing `app/schemas/.../2.json`, before any v3 entity changes. Stock per SKU, balances per party and invoice totals are unchanged. I1–I5 hold |
| `CheckoutAtomicityTest` | A fault injected after each step (number, header, lines, movements, ledger, outbox) leaves the database byte-for-byte unchanged in business tables |
| `IdempotencyTest` | `checkout` twice with the same `actionId` gives one invoice, one set of movements, and the same receipt returned |
| `ReturnsTest` | Partial and full returns; over-return gives `ReturnExceedsSold`; credit sale return adds a CREDIT_NOTE ledger entry |
| `LedgerInvariantPropertyTest` | 500 random sequences of commands, after which I1–I5 hold and the rebuilt projections equal the cached ones |
| `ReceiptNumberTest` | 1,000 concurrent checkouts (parallel coroutines) give unique, gapless per-day numbers; day rollover resets to 0001 |
| `StaleEditTest` | Editing with an old `expectedUpdatedAt` gives `StaleEdit`, and no write happens |
| `BackupRestoreTest` | snapshot, mutate, restore gives the snapshot state, with projections recomputed |
| Existing `LpsHardwareDomainTest` | Still passes |

## 10. Out of scope for sub-project 1
Cloud auth, Firestore schema, rules, push/pull, staff invites (sub-project 2); release signing, OTA verification, crash reporting (sub-project 3); visual redesign (sub-project 4).

## 11. Launch roadmap (later sub-projects, decisions recorded)

**2. Cloud & accounts (Firebase Spark, ₹0)**
- New dedicated Firebase project (not the shared `ubiverse-app`).
- Real Google sign-in (Credential Manager → Firebase Auth `signInWithIdp`). The shop namespace is keyed by a generated `shopId`, never by email. Remove the AccountManager email-only and anonymous path.
- Client-side shop provisioning: an atomic batch creates `shops/{shopId}` + `members/{uid}` with role OWNER. Staff join through a short-lived invite code document. The owner can revoke members, and revoked devices stop syncing.
- Firestore layout: `shops/{id}/invoices/{invoiceId}` (one document per bill, with lines, movements and ledger deltas embedded), `movements`, `ledger`, `items`, `variants`, `parties`. Each document carries `serverUpdatedAt` (REQUEST_TIME transform).
- Pull: `runQuery where serverUpdatedAt > cursor − 2 min overlap`, applied idempotently (unseen document IDs only for append-only types; LWW by `serverUpdatedAt` for mutable ones). After applying, recompute projections for affected SKUs and parties.
- Push: drain `sync_outbox` with `create`-only preconditions for append-only documents (`currentDocument.exists=false`). An "already exists" response is treated as success.
- WorkManager: `ExistingWorkPolicy.KEEP` plus a follow-up flag (don't cancel in-flight syncs), and a periodic safety sync.
- Rules: membership and role checks. STAFF can't read cost fields; cost prices move to a `variants_cost` subcollection readable only by OWNER. Append-only collections deny update and delete. Rules tested with the Firebase emulator in CI.
- Budget check: about 20 shops × 150 bills ≈ 3k writes a day (free limit 20k), with reads bounded by incremental pulls.

**3. Release & ops**
- Private release keystore stored only in GitHub Secrets; build `assembleRelease` (R8) instead of the debug APK. Remove `debug.keystore` from the repo and rotate. Existing installs signed with the debug key need a one-time reinstall migration plan (backup, then install the new key build, then restore), because Android rejects updates signed with a different key.
- OTA: a `version.json` signed with an Ed25519 key, checked in-app. The app checks the downloaded APK's signing certificate against the pinned release cert before launching the installer.
- Crash and ANR reporting: Firebase Crashlytics (free, works without Play).
- CI: unit tests, lint, and the rules emulator tests gate the release job.

**4. UI refresh**
- A professional, modern design system (typography, color, spacing, components) applied to Inventory, Cart/Checkout, Sales History, Khata and Settings, plus onboarding (shop setup, sign-in, join shop). Visual mockups get reviewed before implementation.
