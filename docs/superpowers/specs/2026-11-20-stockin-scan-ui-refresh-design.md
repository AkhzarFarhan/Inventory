# ShopLedger POS — Stock-in Scan + Production UI Refresh

- **Date:** 2026-11-20
- **Status:** Approved design (brainstormed with visual mockups), awaiting spec review
- **Builds on:** `2026-11-19-data-core-ledger-design.md` (ledger, commands, outbox — all writes still go through `*Commands` / `LedgerWriter.runIdempotent`)

## 1. Decisions (from brainstorming, 2026-11-20)

| Topic | Decision |
|---|---|
| Scan flows | Two separate flows: **Stock In** scan (goods ingestion: supplier bills *and* handwritten stock lists) and the existing **Sell** scan (estimate/receipt → cart, unchanged behaviour) |
| Unmatched scanned lines | Review list with one-tap creation; nothing saved until "Add N items to stock" |
| Review layout | **Compact table** with filter tabs All / New / Check (mockup B) |
| Cost price on restock | Bill rate **updates** the saved cost price; row shows "was ₹X" when it differs |
| Language | **English + Hindi** toggle; all UI text in string resources; item names untranslated |
| Home | **Item list first** with two big fixed buttons **Sell** and **Stock In**; bottom tabs Items · Bills · Khata · More (mockup B) |
| Visual style | **Material 3**, money-green brand, system dark mode, ≥ 48 dp targets (mockup B) |
| Extra UX scope | First-run setup wizard, one-screen checkout, plain-language messages. (Today-summary strip: **not** in scope.) |

## 2. Stock-in scan

### 2.1 Entry
"Stock In" button (home) → bottom sheet: **Scan bill / list** (camera via ML Kit document scanner), **Pick photo** (gallery), **Add item manually** (existing Add Item sheet). The Sell flow keeps its own "Scan estimate/receipt" entry inside the Sell/cart sheet.

### 2.2 Text extraction
Reuse `OnDeviceOrderScannerManager.extractTextFromUri/Bitmap` (ML Kit on-device OCR, spatial line reconstruction) and the optional on-device LLM. New LLM prompt for stock lists: one line per item `ITEM | QTY | UNIT | RATE | AMOUNT` (`-` when unknown).

### 2.3 Parser (`core/scanner/StockListParser.kt`, pure Kotlin, unit-tested)
Output `ParsedStockLine(name, qtyMilli, unit?, ratePaise?, amountPaise?, rawLine)`. Handles:
- Bill rows: `Door Handle 6" 10 x 120 = 1200`, `Fevicol SH 1kg 5 pkt 235 1175`, `Hinge 4in SS | 12 | pcs | 48 | 576`.
- `@` rates: `5 pkt Fevicol @235`.
- Handwritten lists: `Hinge 12`, `12 hinge`, `Cement - 5 bag`, numbered/bulleted lines.
- Noise: totals, GST/CGST/SGST lines, invoice/bill numbers, dates, phone numbers, GSTIN, headers.
- Cross-check: when qty × rate ≈ amount (±1%), accept; when two of three are known, derive the third; when the numbers are inconsistent, keep the line but mark it **Check**. Size tokens inside names (`6"`, `4 inch`, `1kg`, `100mm`) are not mistaken for quantities.

### 2.4 Matching (`core/scanner/StockLineMatcher.kt`)
Uses `SmartItemMatcher` scores against active inventory (item + variant). Thresholds (constants, tested):
- **Matched** — best score ≥ HIGH and clearly ahead of the runner-up.
- **Check** — best score ≥ LOW (shows best guess, tap to change via item picker).
- **New** — below LOW; prefilled name/qty/unit/cost; selling price suggested = cost × (1 + shop's median markup across existing variants, default 25%), rounded to whole rupees.
Duplicate lines for the same variant are merged (qty summed; rate kept if equal, else Check).

### 2.5 Review screen (`ui/screens/stockin/StockInReviewScreen.kt`)
Table rows: status icon, item, qty, unit, cost, sell. Filter tabs All / New / Check with counts. Every cell editable; swipe/❌ to remove row; tap a ⚠ row → item picker or "make new item". Header: supplier (pick existing SUPPLIER party or type a new name), bill total (prefilled from scanned total or Σ amounts), amount paid (default 0). Bottom: one large button **"Add N items to stock"**, disabled while any row is invalid (qty ≤ 0, new item without sell price, unresolved Check). Original photo viewable from a top-bar icon.

### 2.6 Atomic save — `StockInCommands.receiveScannedStock`
New command in `data/command/StockInCommands.kt`, one `runIdempotent(actionId, "RECEIVE_SCANNED_STOCK")` transaction:
1. Resolve/create supplier party (type SUPPLIER) if a name is given.
2. Create every New item (item + variant, opening stock 0) with given cost/sell/unit.
3. For matched/confirmed rows whose cost differs: update variant `purchasePricePaise` (normal catalog update, outbox VARIANT UPSERT).
4. Append one PURCHASE movement per row (shared `purchaseId` as refDocId).
5. If supplier present and paid < bill total: supplier ledger CREDIT_BILL of −(total − paid).
6. Outbox rows as per existing rules.
Returns a summary (items created, restocked, total qty, supplier due). Failure anywhere → nothing saved; the review screen keeps its state and shows the plain-language error. Action id is generated when the review screen opens (double-tap safe).

## 3. UI refresh

### 3.1 Design system (`ui/theme/`)
`ShopLedgerTheme` (Material 3): green primary (e.g. `#1A7F4B`), light + dark colour schemes (follow system), typography with 16 sp body / 20 sp titles, shapes (12–16 dp corners), spacing tokens. Components: `PrimaryActionButton` (≥ 56 dp tall), `ItemRow`, `QtyStepper`, `MoneyField` (₹, paise-exact), `StatusChip`, `EmptyState`, `ConfirmDialog`, `SectionCard`. The Cupertino theme/components are removed once no screen uses them.

### 3.2 Strings & Hindi
All user-visible text → `res/values/strings.xml`; Hindi in `res/values-hi/strings.xml`. Language chosen in setup/More via `AppCompatDelegate.setApplicationLocales` (androidx.appcompat per-app locales, works API 24+; requires AppCompat dependency and `AppLocalesMetadataHolderService` autoStoreLocales). A unit test asserts every key in `values` exists in `values-hi`. Formatting stays Indian (₹, lakh grouping) in both languages.

### 3.3 Navigation & home
Bottom navigation: **Items · Bills · Khata · More**.
- **Items (home):** top bar (shop name + "Saved" status), search field with barcode icon, chip filter "Low stock", item list (`ItemRow`: name, variant summary, stock with low/oversold colouring, selling price; seller-locked cost hidden), two fixed bottom buttons **Sell** (opens checkout with current cart) and **Stock In** (§2.1). Tapping an item → quick add to cart / edit.
- **Bills:** existing sales history restyled.
- **Khata:** existing parties screen restyled.
- **More:** shop profile, language, seller lock/PIN, cost-code key, backup & restore, cloud sync status, setup wizard re-run, about/version/update.

### 3.4 One-screen checkout (`ui/screens/checkout/CheckoutScreen.kt`)
Cart lines with `QtyStepper` and tap-to-edit price; add-item search at top; total block; customer name/phone (optional, auto-suggest from known buyers); discount (optional, collapsed); payment buttons **Cash · UPI · Udhar** (Udhar requires a customer name or phone; UPI shows the shop's UPI QR on completion); one **Complete sale** button → receipt share dialog. Multi-cart switcher and "Share estimate" move into the top-bar overflow menu. Uses existing `executeCheckoutAndDeductStock` (idempotent action id per cart).

### 3.5 First-run setup wizard (`ui/screens/setup/SetupWizard.kt`)
Shown when no setup-completed flag: (1) shop name + language, (2) shop type (existing `ShopVertical`s), (3) owner PIN (optional, skip). Final step: "Start with sample items" or "Start empty" (sample = existing demo seed, which then runs; empty = mark seed done). Reachable again from More (without the sample-data step).

### 3.6 Plain-language messages
All `AppErrorManager.report*` call sites and `CommandException` messages use string resources with short, action-oriented text in both languages (e.g. "Not enough information — add a selling price for 'Door Handle'."). Raw exception text only in debug builds' details. Every list has an `EmptyState` with a next step.

### 3.7 Remaining screens
Add/Edit item sheet, receipt share dialog, return dialog, Khata detail, backup sheet, recovery screen: restyled with the new components, strings extracted, no behaviour change.

## 4. Testing
- Unit: `StockListParser` (bill, @-rate, handwritten, noise, size tokens, cross-check), `StockLineMatcher` thresholds/merging/markup, `StockInCommands` (atomicity with fault injection, idempotency, supplier due, cost update, invariants via `IntegrityChecker`), string-key parity.
- Compose UI tests on Robolectric (`ui-test-junit4`): checkout complete-sale flow, setup wizard path, stock-in review validation/enable state.
- Manual on emulator/phone: full pass in English and Hindi, light and dark.

## 5. Out of scope
Cloud sync / staff accounts (sub-project 2), release signing (sub-project 3), today-summary strip, tablet layouts.
