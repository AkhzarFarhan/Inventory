# ShopLedger POS Stock-in Scan + UI Refresh — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Stock In scan flow (supplier bill or handwritten list → review table → one atomic save) and give the app a production-grade Material 3 UI with English + Hindi, an item-list home with Sell / Stock In, a one-screen checkout, a first-run setup wizard and plain-language messages.

**Architecture:** Pure-Kotlin `StockListParser` and `StockLineMatcher` turn OCR text into review rows; a new `StockInCommands.receiveScannedStock` saves the reviewed rows in one `LedgerWriter.runIdempotent` transaction. The UI moves to a new `ui/theme` Material 3 design system and string resources (`values` + `values-hi`) with an in-app language switch; screens are rebuilt (home, checkout, setup, stock-in review) or restyled (the rest) one task at a time, keeping every existing repository/command API.

**Tech Stack:** Kotlin 2.0.20, Jetpack Compose (Material3, BOM 2024.09.03), Room 2.6.1, ML Kit text recognition + document scanner (already present), JUnit4 + Robolectric 4.13 + Compose `ui-test-junit4`.

**Spec:** `docs/superpowers/specs/2026-11-20-stockin-scan-ui-refresh-design.md`

## Global Constraints

- All writes still go through `*Commands` / `LedgerWriter.runIdempotent`; never write ledger, invoice or projection rows from UI or repository code directly. Money = `Long` paise, quantity = `Long` milli-units.
- Every user-visible string lives in `app/src/main/res/values/strings.xml` with a Hindi entry in `app/src/main/res/values-hi/strings.xml`. Item names, party names and user input are never translated. `StringParityTest` (Task 2) must stay green.
- Theme: Material 3 `ShopLedgerTheme` (Task 1) only. Brand primary `#1A7F4B`. Light + dark schemes follow the system. Body text ≥ 16 sp, touch targets ≥ 48 dp, primary actions ≥ 56 dp tall.
- No raw exception text in release builds' UI (`BuildConfig.DEBUG` may append details).
- Seller-locked data (cost price, profit, cost code, backup) stays hidden unless `isSellerUnlocked`, as today.
- Run tests with `powershell -ExecutionPolicy Bypass -File scripts/test.ps1` (Claude Code: outside the sandbox). Never pipe Gradle output through `Select-Object`; redirect to a file under `build/`. Builds: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\GitHub\Inventory\build\jtmp"; .\gradlew.bat assembleDebug --console=plain`. Stop daemons when done (`.\gradlew.bat --stop`, same env).
- Keep LF line endings and UTF-8 (`git -c core.autocrlf=false add …`; verify with `git ls-files --eol`).
- Branch `feat/stockin-ui-refresh`. One commit per task (Conventional Commits), message ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- UI tasks: read the existing screen file(s) before rewriting; keep every existing feature reachable (multi-cart, estimates, returns, receipt share, barcode scan, buyer suggestions, cost code, PIN lock, OTA update, backup/restore). If a feature has no obvious home in the new layout, put it in the More tab or an overflow menu and say so in the report.
- Hindi: write natural, simple Hindi (Devanagari) as a shopkeeper would read it; keep common loanwords (बिल, स्टॉक, कैश, UPI). Flag in the report any string you were unsure about.

## File Structure (new)

```
app/src/main/java/com/shopledger/pos/
  ui/theme/Color.kt, Type.kt, ShopLedgerTheme.kt, Components.kt   Material 3 design system
  ui/i18n/AppLanguage.kt                                            language pref + context wrapping
  ui/text/UiText.kt                                                 string-resource message model
  ui/navigation/AppScaffold.kt                                      bottom tabs Items · Bills · Khata · More
  ui/screens/items/ItemsHomeScreen.kt                               new home (list + Sell / Stock In)
  ui/screens/more/MoreScreen.kt                                     settings hub
  ui/screens/stockin/StockInEntrySheet.kt, StockInReviewScreen.kt, StockInViewModel.kt
  ui/screens/checkout/CheckoutScreen.kt
  ui/screens/setup/SetupWizard.kt
  core/scanner/StockListParser.kt, StockLineMatcher.kt
  data/command/StockInCommands.kt
app/src/main/res/values/strings.xml, values-hi/strings.xml
app/src/test/java/com/shopledger/pos/
  ui/StringParityTest.kt, ui/ThemeComponentsTest.kt
  core/scanner/StockListParserTest.kt, StockLineMatcherTest.kt
  data/command/StockInCommandsTest.kt
  ui/StockInReviewScreenTest.kt, ui/CheckoutScreenTest.kt, ui/SetupWizardTest.kt
```

---

### Task 1: Material 3 design system + Compose UI test harness

**Files:** Create `ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/ShopLedgerTheme.kt`, `ui/theme/Components.kt`, test `ui/ThemeComponentsTest.kt`. Modify `gradle/libs.versions.toml`, `app/build.gradle.kts`, `MainActivity.kt` (wrap content in `ShopLedgerTheme` *outside* the existing `LpsCupertinoTheme`, so old screens keep rendering until restyled).

**Interfaces produced:**
- `@Composable fun ShopLedgerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
- `object ShopLedgerTokens { val spacingXs = 4.dp; spacingS = 8.dp; spacingM = 12.dp; spacingL = 16.dp; spacingXl = 24.dp; minTouch = 48.dp; primaryActionHeight = 56.dp }`
- Semantic colours available via `MaterialTheme.colorScheme` plus `ShopLedgerTheme.extra: ExtraColors(success, warning, danger, onSuccess, onWarning, onDanger)` (CompositionLocal).
- Components (all accept `modifier`):
  - `PrimaryActionButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, containerColor: Color = MaterialTheme.colorScheme.primary)` — height ≥ 56 dp, full width by default.
  - `SecondaryActionButton(text, onClick, icon = null, enabled = true)` — outlined, ≥ 48 dp.
  - `ItemRow(title: String, subtitle: String, trailingPrimary: String, trailingSecondary: String? = null, status: StockStatus = StockStatus.OK, onClick: () -> Unit)` where `enum class StockStatus { OK, LOW, OUT, OVERSOLD }` colours the secondary text (warning/danger).
  - `QtyStepper(valueText: String, onMinus: () -> Unit, onPlus: () -> Unit, onValueClick: () -> Unit)` — 48 dp buttons.
  - `MoneyField(valuePaise: Long?, onValueChange: (Long?) -> Unit, label: String)` — ₹ prefix, decimal keyboard, parses with `IndianCurrencyFormatter.parseRupeesToPaise`, blank → null.
  - `StatusChip(text: String, tone: ChipTone)` with `enum class ChipTone { NEUTRAL, SUCCESS, WARNING, DANGER, INFO }`.
  - `EmptyState(title: String, message: String, actionText: String? = null, onAction: (() -> Unit)? = null)`.
  - `ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false)`.
  - `SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit)`.

- [ ] **Step 1: Test harness.** Add to `libs.versions.toml`: `androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }`, `androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }`. In `app/build.gradle.kts`: `testImplementation(platform(libs.androidx.compose.bom))`, `testImplementation(libs.androidx.compose.ui.test.junit4)`, `debugImplementation(libs.androidx.compose.ui.test.manifest)`.
- [ ] **Step 2: Write the failing test** `ThemeComponentsTest` (Robolectric, `@RunWith(AndroidJUnit4::class)`, `@get:Rule val compose = createComposeRule()`): (a) `PrimaryActionButton` inside `ShopLedgerTheme` has text displayed, is clickable, invokes onClick, and `onNodeWithText("Sell").assertHeightIsAtLeast(56.dp)`; (b) `ItemRow` with `StockStatus.OVERSOLD` shows title/subtitle/trailing texts; (c) `QtyStepper` plus/minus callbacks fire and each button `assertHeightIsAtLeast(48.dp)`; (d) `MoneyField` typing "12.50" emits `1250L`, clearing emits `null`; (e) `ShopLedgerTheme(darkTheme = true)` exposes a `colorScheme.background` different from light.
- [ ] **Step 3:** Run `scripts/test.ps1 --tests "com.shopledger.pos.ui.ThemeComponentsTest"` → fails (unresolved references).
- [ ] **Step 4: Implement** the theme: light scheme from seed `#1A7F4B` (primary `#1A7F4B`, onPrimary white, primaryContainer `#B7F1CF`, secondary `#2563EB` (used for Stock In), tertiary `#B45309` (Khata), error `#B3261E`, background `#F7F9F7`, surface white); dark scheme with primary `#7DDBA3`, background `#101411`, surface `#1A1F1B`. Typography: bodyLarge 16 sp, bodyMedium 15 sp, titleMedium 18 sp semibold, titleLarge 22 sp bold, headlineSmall 24 sp bold, labelLarge 16 sp semibold. Shapes small 8 dp, medium 12 dp, large 16 dp. Implement the components above using Material3 primitives only.
- [ ] **Step 5:** Wrap `MainActivity.setContent { ShopLedgerTheme { LpsCupertinoTheme { … } } }` (no visual change yet for old screens).
- [ ] **Step 6:** Run focused test, then full suite and `assembleDebug` → pass.
- [ ] **Step 7: Commit** `feat(ui): Material 3 ShopLedger design system and Compose UI test harness`.

---

### Task 2: Strings, Hindi and in-app language switch

**Files:** Create `ui/i18n/AppLanguage.kt`, `ui/text/UiText.kt`, `res/values-hi/strings.xml`, test `ui/StringParityTest.kt`, test `ui/AppLanguageTest.kt`. Modify `res/values/strings.xml`, `MainActivity.kt`, `ShopLedgerApp.kt`.

**Interfaces produced:**
- `enum class AppLanguage(val tag: String) { ENGLISH("en"), HINDI("hi") }`
- `object AppLanguageStore { fun get(context: Context): AppLanguage; fun set(context: Context, language: AppLanguage); fun wrap(base: Context): Context }` — persisted in SharedPreferences `shopledger_ui_prefs` key `app_language` (default ENGLISH); `wrap` returns `base.createConfigurationContext(config with Locale(tag))`.
- `MainActivity.attachBaseContext(newBase)` calls `super.attachBaseContext(AppLanguageStore.wrap(newBase))`; changing language calls `AppLanguageStore.set` then `activity.recreate()`.
- `sealed interface UiText { data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText; data class Raw(val text: String) : UiText }` with `@Composable fun UiText.asString(): String` and `fun UiText.resolve(context: Context): String`.

- [ ] **Step 1: Failing tests.** `StringParityTest` (plain JUnit, reads files from `app/src/main/res/values/strings.xml` and `values-hi/strings.xml` relative to the module dir via `System.getProperty("user.dir")`, parses `<string name=…>` and `<plurals name=…>`): every name in `values` exists in `values-hi` and vice versa; no Hindi value is empty; format-argument counts (`%1$s`, `%d`…) match per key. `AppLanguageTest` (Robolectric): `set(HINDI)` then `wrap(context).resources.configuration.locales[0].language == "hi"`; default is ENGLISH.
- [ ] **Step 2:** Run → fails.
- [ ] **Step 3: Implement** `AppLanguage.kt`, `UiText.kt`, the `attachBaseContext` hook, and seed both strings files with the shared vocabulary every later task uses (English | Hindi): `app_name` ShopLedger POS | शॉपलेजर POS; `tab_items` Items | सामान; `tab_bills` Bills | बिल; `tab_khata` Khata | खाता; `tab_more` More | और; `action_sell` Sell | बेचें; `action_stock_in` Stock In | माल आया; `action_save` Save | सेव करें; `action_cancel` Cancel | रद्द करें; `action_done` Done | हो गया; `action_delete` Delete | हटाएँ; `action_retry` Try again | फिर से कोशिश करें; `status_saved_on_phone` Saved on this phone | इस फ़ोन में सेव है; `language_english` English | English; `language_hindi` हिन्दी | हिन्दी; `settings_language` Language | भाषा. Keep existing keys in `values/strings.xml` and add their Hindi.
- [ ] **Step 4:** Tests + `assembleDebug` pass.
- [ ] **Step 5: Commit** `feat(i18n): English/Hindi string resources, UiText and in-app language switch`.

---

### Task 3: Navigation + Items home + More tab

**Files:** Create `ui/navigation/AppScaffold.kt`, `ui/screens/items/ItemsHomeScreen.kt`, `ui/screens/more/MoreScreen.kt`, `ui/screens/stockin/StockInEntrySheet.kt` (manual-add only in this task). Modify `MainActivity.kt` (replace the old 3-tab `NavigationBar` + header with `AppScaffold`), `InventoryScreen.kt` (reuse or retire), strings files. Read `MainActivity.kt`, `InventoryScreen.kt`, `CloudSyncAndBackupSheet.kt` first.

**Behaviour (spec §3.3):**
- `AppScaffold(selectedTab, onTabSelected, …)` with Material3 `NavigationBar`: Items · Bills · Khata · More (icons Inventory2, ReceiptLong, AccountBalanceWallet, MoreHoriz), labels from strings.
- **Items home:** `TopAppBar` with shop name (from `SyncManager.sellerNameFlow`) + `StatusChip(status_saved_on_phone, SUCCESS)` + seller lock toggle icon (existing PIN flow). Search `OutlinedTextField` with barcode-scan trailing icon (existing `BarcodeScannerHelper`), filter chip "Low stock" (existing `InventoryFilterMode.LOW_STOCK_ONLY`). `LazyColumn` of `ItemRow` per variant group as today's cards show (title = item name, subtitle = variant summary + stock text, trailingPrimary = selling price, trailingSecondary = coded cost when seller unlocked); `StockStatus` from `isOversold/isOutOfStock/isLowStock`. Keep the A–Z scrubber if it fits; otherwise drop it and note it. Tap row → existing quick sell / add-to-cart sheet; long-press or trailing edit icon → existing edit sheet. `EmptyState` when no items ("No items yet" / "Tap Stock In to add your first items"). Fixed bottom bar above the navigation bar: `PrimaryActionButton(action_sell)` (opens existing cart/checkout sheet; badge with cart line count) and `PrimaryActionButton(action_stock_in, containerColor = secondary)` opening `StockInEntrySheet`.
- `StockInEntrySheet` (ModalBottomSheet): in this task it offers only "Add item manually" (opens the existing Add Item sheet). Task 6 adds "Scan bill or list" and "Pick a photo"; don't add disabled placeholders for them now.
- **More tab:** `LazyColumn` of `SectionCard`s: Shop (name, WhatsApp, UPI, GSTIN — reuse existing save functions in `SyncManager`), Language (radio English/हिन्दी → `AppLanguageStore.set` + recreate), Security (owner PIN set/change, seller lock), Cost code key (existing cipher editor, seller-locked), Backup & restore (open existing backup UI — seller-locked as today), Cloud sync status (existing message), Device counter code, App version + update check (existing `AppUpdateManager`), "Run setup again" (wired in Task 8; until then hidden). Move content out of `CloudSyncAndBackupSheet` into these cards or open the existing sheet sections from them — no feature may disappear.
- Bills/Khata tabs: render existing `SaleHistoryScreen` / `PartiesKhataScreen` unchanged for now.

- [ ] **Step 1:** Compose test `ui/ItemsHomeScreenTest.kt`: given a fake list of 2 items (one oversold), the screen shows both names, the oversold status text, the Sell and Stock In buttons; typing in search filters to one item; tapping Stock In shows "Add item manually". Drive `ItemsHomeScreen` with plain parameters (state + lambdas), not the ViewModel.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Tests + `assembleDebug` pass; manually verify in the report that every feature from the old header/settings sheet is reachable (list them with their new location).
- [ ] **Step 5: Commit** `feat(ui): item-list home with Sell/Stock In, bottom tabs and More hub`.

---

### Task 4: Stock list parser + matcher (pure Kotlin)

**Files:** Create `core/scanner/StockListParser.kt`, `core/scanner/StockLineMatcher.kt`, tests `core/scanner/StockListParserTest.kt`, `core/scanner/StockLineMatcherTest.kt`.

**Interfaces produced:**
```kotlin
data class ParsedStockLine(
    val name: String,
    val qtyMilli: Long,
    val unit: String?,
    val ratePaise: Long?,
    val amountPaise: Long?,
    /** false when qty × rate disagrees with the printed amount by more than 1% */
    val consistent: Boolean,
    val rawLine: String
)
object StockListParser {
    fun parse(text: String): List<ParsedStockLine>
    /** Σ amounts if every line has one, else null; or the value of a "total"/"grand total" line if present (preferred). */
    fun detectBillTotalPaise(text: String): Long?
}

enum class MatchStatus { MATCHED, CHECK, NEW }
data class StockLineMatch(
    val line: ParsedStockLine,
    val status: MatchStatus,
    val itemId: String?,      // null for NEW
    val variantId: String?,   // null for NEW
    val currentCostPaise: Long?,
    val currentSellPaise: Long?,
    val suggestedSellPaise: Long?  // for NEW: cost × (1 + medianMarkup), whole rupees
)
object StockLineMatcher {
    const val HIGH_SCORE = 1200.0
    const val LOW_SCORE = 500.0
    const val LEAD_RATIO = 1.25
    const val DEFAULT_MARKUP = 0.25
    fun match(lines: List<ParsedStockLine>, inventory: List<InventoryItemWithVariants>, vertical: ShopVertical = SyncManager.getActiveVertical()): List<StockLineMatch>
    fun medianMarkup(inventory: List<InventoryItemWithVariants>): Double
}
```

**Parsing rules (spec §2.3)** — implement exactly these, in order, per input line:
1. Trim; drop serial prefixes `^\(?\d{1,3}\s*[.)\]:-]+\s+` and bullets `^[-•*✓☑➤]+\s*`; drop lines shorter than 2 chars or with no letters.
2. Noise → skip: lowercase line starts with or contains (as a word) any of: `total`, `sub total`, `subtotal`, `grand total`, `round off`, `cgst`, `sgst`, `igst`, `gst`, `tax`, `discount`, `invoice`, `bill no`, `date`, `gstin`, `mobile`, `phone`, `ph.`, `thank`, `amount in words`, `rupees`; or contains a 10-digit phone number; or matches a date `\d{1,2}[/-]\d{1,2}[/-]\d{2,4}`. (A "total" line is still used by `detectBillTotalPaise`.)
3. Pipe format `name | qty | unit | rate | amount` (`-`/blank = unknown) → direct mapping.
4. Protect size tokens inside the name: a number immediately followed (optionally one space) by `"`, `''`, `in`, `inch`, `mm`, `cm`, `ft`, `kg`, `g`, `gm`, `ml`, `l`, `ltr`, `mtr`, `m`, `w`, `v`, `amp` when the line has another number later, OR when it is attached with no space (e.g. `1kg`, `6"`) — these stay in the name.
5. Leading-quantity form: `^(\d+(\.\d+)?)\s*(UNIT)?\s+(.+)$` where the rest contains letters → qty = first number, unit = UNIT, rest continues at step 6 for an optional `@ rate`.
6. Trailing numeric tail: tokenize the remainder; the name is everything before the first standalone numeric token (`\d+(\.\d+)?`, `₹`/`rs`/`rs.` prefixed allowed) that is followed only by numerics, units, and the operators `x × * @ = -`. In the tail: a number after `@` is the rate; `A x B` → qty A, rate B; a number after `=` is the amount; a unit word after the first number is the unit. Remaining plain numbers, in order: 1 → qty; 2 → qty, rate; ≥3 → qty, rate, amount (last).
7. If qty missing → 1. Derive: rate missing but qty+amount known → rate = round(amount / qty); amount missing → leave null. `consistent` = amount == null || rate == null || |qty×rate − amount| ≤ 1% of amount (minimum 1 rupee).
8. Money: rupees with up to 2 decimals → paise (`IndianCurrencyFormatter.parseRupeesToPaise`); quantities → milli-units (`IndianCurrencyFormatter.doubleToMilliUnits`). Unit normalised to lowercase singular: pcs, pkt, box, kg, g, ltr, ml, mtr, ft, bag, roll, set, pair, dozen, bottle, can, strip, tube (map plurals/synonyms: pc/piece/pieces → pcs; packet/pack/pkts → pkt; kgs/kilo → kg; litre/liter/l → ltr; gm/gms/gram → g; boxes → box; bags → bag; nos/no → pcs).

- [ ] **Step 1: Write the failing parser tests** — each asserts `name`, `qtyMilli`, `unit`, `ratePaise`, `amountPaise`, `consistent`:
  - `"Door Handle 6\" 10 x 120 = 1200"` → `Door Handle 6"`, 10000, null, 12000, 120000, true
  - `"Fevicol SH 1kg 5 pkt 235 1175"` → `Fevicol SH 1kg`, 5000, "pkt", 23500, 117500, true
  - `"Hinge 4in SS | 12 | pcs | 48 | 576"` → `Hinge 4in SS`, 12000, "pcs", 4800, 57600, true
  - `"5 pkt Fevicol @235"` → `Fevicol`, 5000, "pkt", 23500, null, true
  - `"Hinge 12"` → `Hinge`, 12000, null, null, null, true
  - `"12 hinge"` → `hinge`, 12000, null, null, null, true
  - `"3. Cement - 5 bags"` → `Cement`, 5000, "bag", null, null, true
  - `"Tower Bolt 8\" 10 125 1300"` → rate 12500, amount 130000, consistent **false**
  - `"Screw 2.5 kg 180 450"` → qty 2500, unit "kg", rate 18000, amount 45000, true
  - noise: `"GSTIN: 09ABCDE1234F1Z5"`, `"Grand Total 4860"`, `"CGST 9% 120"`, `"Ph: 9876543210"`, `"Date 19/11/2026"`, `"Thank you"` → no lines
  - `parse` on a 6-line bill (3 items + total + GST + phone) → exactly 3 lines; `detectBillTotalPaise` → the grand-total value.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement `StockListParser` to the rules. **Step 4:** Parser tests pass.
- [ ] **Step 5: Write the failing matcher tests** using a small in-memory `InventoryItemWithVariants` list built like `LpsHardwareDomainTest` does (Butt Hinge 4" SS / Fevicol SH 1kg / Drywall Screw 2" with costs/sells): exact name → MATCHED with ids, `currentCostPaise`; "Scrw blk 2" (weak) → CHECK with best guess; "Door Handle Brass" → NEW with `suggestedSellPaise` = cost × 1.25 rounded to whole rupees when inventory markups median is 25%; `medianMarkup` of an empty inventory = `DEFAULT_MARKUP`; two lines for the same variant are merged (qty summed; rate kept if equal; if rates differ → status CHECK, rate = the later line's).
- [ ] **Step 6:** Implement `StockLineMatcher` (reuse `SmartItemMatcher.calculateSearchRelevanceScore` and the variant-selection heuristic from `OnDeviceOrderScannerManager.matchLineToInventory`, moved into a shared internal function rather than duplicated). MATCHED requires best ≥ HIGH_SCORE and best ≥ LEAD_RATIO × runner-up; CHECK requires best ≥ LOW_SCORE; else NEW. Inconsistent lines (`consistent == false`) are never MATCHED (downgrade to CHECK).
- [ ] **Step 7:** All tests + full suite pass. **Step 8: Commit** `feat(scanner): stock list parser and catalogue matcher for stock-in scans`.

---

### Task 5: `StockInCommands.receiveScannedStock` (atomic save)

**Files:** Create `data/command/StockInCommands.kt`, test `data/command/StockInCommandsTest.kt`. Modify `data/repository/LpsRepository.kt` (facade method).

**Interfaces produced:**
```kotlin
data class StockInRow(
    val existingVariantId: String?,     // null → create new item
    val newItemName: String = "",       // required when existingVariantId == null
    val newCategory: String = "Uncategorized",
    val unit: String = "pcs",
    val qtyMilli: Long,                 // > 0
    val costPaise: Long,                // ≥ 0; updates the variant's cost when different
    val sellPaise: Long?                // required for new items; ignored for existing
)
data class StockInRequest(
    val actionId: String,
    val rows: List<StockInRow>,
    val supplierPartyId: String? = null,
    val newSupplierName: String? = null, // creates a SUPPLIER party when set and no id given
    val billTotalPaise: Long = 0L,
    val amountPaidPaise: Long = 0L
)
data class StockInResult(val purchaseId: String, val createdItems: Int, val restockedItems: Int, val totalQtyMilli: Long, val supplierDuePaise: Long, val replayed: Boolean)
class StockInCommands(db: LpsDatabase, env: CommandEnvironment, faults: FaultInjector = FaultInjector.NONE) {
    suspend fun receiveScannedStock(request: StockInRequest): StockInResult
}
// LpsRepository
suspend fun receiveScannedStock(request: StockInRequest): StockInResult
```
**Rules (spec §2.6):** one `writer.runIdempotent(request.actionId, "RECEIVE_SCANNED_STOCK", replay = { load summary from applied action ref + movements with refDocId }) { … }` transaction. Validate (rows non-empty; qty > 0; cost ≥ 0; new rows need name + sellPaise ≥ 0; each existing variant appears at most once; paid in 0..billTotal) → `CommandException.Validation` with a `UiText`-ready message key (see Task 9; for now plain English text). Steps inside the transaction: resolve or create supplier (type SUPPLIER via the same insert path `KhataCommands.createParty` uses — refactor that body into a shared internal function, no duplication) → create new items via the shared insert path of `CatalogCommands` (item + variant, opening 0; refactor into an internal function reused by both) → for existing rows whose cost differs, update `purchasePricePaise` (+ `updatedAt`, outbox VARIANT UPSERT unless demo) → one PURCHASE movement per row with refDocId = purchaseId → if supplier and billTotal − paid > 0 → CREDIT_BILL ledger entry of −due. Fault points `"SUPPLIER"`, `"ITEMS"`, `"COSTS"`, `"MOVEMENTS"`, `"LEDGER"` in that order.
- [ ] **Step 1: Failing tests** (Robolectric + `newTestDatabase()`, `TestEnv`, `seedHinge`): mixed request (1 existing with new cost, 1 new item, new supplier, bill 20_000 paid 5_000) → hinge stock +qty, hinge cost updated, new item exists with stock = qty and sell price, supplier party balance −15_000, one PURCHASE movement per row, `IntegrityChecker.findViolations` empty; same actionId twice → `replayed`, no duplicates; fault at each step → database unchanged (snapshot like `SalesCommandsTest`); validation errors (new row without sell price, duplicate variant, paid > total) throw and write nothing.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Tests + full suite pass. **Step 5: Commit** `feat(stockin): atomic receiveScannedStock command`.

---

### Task 6: Stock-in scan flow UI (entry → OCR → review table → save)

**Files:** Create `ui/screens/stockin/StockInViewModel.kt`, `ui/screens/stockin/StockInReviewScreen.kt`, test `ui/StockInReviewScreenTest.kt`. Modify `StockInEntrySheet.kt` (add "Scan bill or list" via ML Kit document scanner and "Pick a photo" via `GetContent`, reusing the launch code in `OrderScanSheet.kt`), `OnDeviceOrderScannerManager.kt` (add `suspend fun extractStockLines(context, rawText): String?` using the LLM with the stock prompt from spec §2.2, falling back to raw text), `MainActivity.kt`/navigation (full-screen review destination), strings.

**Behaviour (spec §2.5):** after OCR → (optional LLM) → `StockListParser.parse` → `StockLineMatcher.match` → review state. `StockInReviewScreen(state, onEvent)` stateless; `StockInViewModel` holds `actionId = UUID` created when the review opens, editable rows, supplier/bill/paid, filter tab. Table columns: status icon (✅ MATCHED, ⚠ CHECK, 🆕 NEW), item (name or matched item + variant summary; "was ₹X" under cost when it differs), qty (+unit), cost, sell. Tabs All / New / Check with counts. Tap a cell to edit inline (numeric keyboard for numbers); row delete icon; tapping a CHECK row's item opens an item picker (search over inventory) with "Make new item instead". Header: supplier (dropdown of SUPPLIER/BOTH parties + "New supplier…" text), bill total (`MoneyField`, prefilled from `detectBillTotalPaise` or Σ amounts), amount paid (`MoneyField`, default 0). Top-bar icon shows the scanned photo. Bottom `PrimaryActionButton("Add N items to stock")` disabled while any row is invalid (qty ≤ 0, NEW without sell price or name, unresolved CHECK) — show the reason as helper text. On save → `repository.receiveScannedStock(...)` → success message ("Added 12 items to stock" + supplier due if any) and close; on failure keep state and show the plain-language error. Empty parse → `EmptyState` with "Try again" (rescan) and "Add item manually".
- [ ] **Step 1: Failing Compose tests** driving the stateless screen: rows render with correct status icons and counts per tab; NEW row without sell price disables the save button and shows the reason; entering the sell price enables it; deleting a row updates the count in the button text; save click invokes `onEvent(Save)` exactly once even on double tap (button disabled while saving).
- [ ] **Step 2:** Run → fails. **Step 3:** Implement screen, ViewModel (unit-test its row validation + request building in `StockInViewModelTest` with a fake repository interface), entry sheet options, LLM stock prompt. **Step 4:** Tests + `assembleDebug` pass. **Step 5: Commit** `feat(stockin): scan supplier bills and stock lists into a review table`.

---

### Task 7: One-screen checkout

**Files:** Create `ui/screens/checkout/CheckoutScreen.kt`, test `ui/CheckoutScreenTest.kt`. Modify `MainActivity.kt` (Sell opens checkout), `MainViewModel.kt` (only if needed for state exposure), `QuickSellAndCartSheet.kt` (keep quick-sell from a row tap; the cart sheet's checkout UI is replaced by `CheckoutScreen`), strings.

**Behaviour (spec §3.4):** stateless `CheckoutScreen(state, onEvent)` as a full-screen destination: top search field to add items (existing search + barcode); cart lines with name, variant, `QtyStepper` (step 1 unit; tap value → numeric entry incl. decimals for loose units), tap price → edit (`MoneyField`), remove; total block (subtotal, discount row collapsed under "Add discount", GST line when enabled, grand total large); customer name + phone fields with known-buyer suggestions (existing `knownBuyersState`/`BuyerHistoryHelper`); payment segmented big buttons **Cash · UPI · Udhar** (Udhar requires name or phone — inline error otherwise; UPI selection shows the UPI QR after completion via the existing receipt dialog); `PrimaryActionButton("Complete sale ₹X")`. Overflow menu: switch/new cart (existing multi-cart), share estimate, clear cart. Completion calls existing `completeSaleAndPrepareReceipt` (idempotent cart action id) and shows the existing receipt share dialog. Oversold warning behaviour unchanged.
- [ ] **Step 1: Failing Compose tests:** plus/minus change quantity and total; Udhar without customer shows the error and doesn't call complete; Cash with lines calls `onEvent(Complete(mode = CASH))` once; empty cart shows `EmptyState` and a disabled complete button.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Tests + `assembleDebug` pass. **Step 5: Commit** `feat(checkout): one-screen checkout with big Cash/UPI/Udhar buttons`.

---

### Task 8: First-run setup wizard

**Files:** Create `ui/screens/setup/SetupWizard.kt`, test `ui/SetupWizardTest.kt`. Modify `core/sync/SyncManager.kt` (add `saveSetupCompleted(context, Boolean)`; change the default of `KEY_SETUP_COMPLETED` to **false for fresh installs only** — existing installs that already have any stored seller name or items keep `true`: decide in `init` by checking `p.contains(KEY_SETUP_COMPLETED)` and, if absent, `p.contains(KEY_SELLER_NAME)`), `MainActivity.kt` (show wizard before the main UI when not completed), `MainViewModel.kt` (`seedStarterCatalogIfEmpty` runs only after the wizard chose sample data, or for existing installs), `MoreScreen.kt` ("Run setup again", without the sample-data step), strings.

**Behaviour (spec §3.5):** stateless `SetupWizard(state, onEvent)`: Step 1 shop name (required) + language (English/हिन्दी, applies immediately via `AppLanguageStore` + recreate, wizard resumes at step 1); Step 2 shop type (cards for each `ShopVertical` with display names from strings); Step 3 owner PIN (optional, 4–6 digits, confirm field, "Skip"); Step 4 (first run only) "Start with sample items" / "Start empty". Back button per step; progress indicator. Finishing saves via existing `SyncManager.saveSellerName`, `saveShopVertical`, `saveOwnerPin`, then `saveSetupCompleted(true)`.
- [ ] **Step 1: Failing tests:** Compose — Next disabled until shop name entered; Skip PIN goes to step 4; mismatched PIN confirm shows error; finishing emits `Finish(name, vertical, pin?, sampleData)`. Robolectric — `SyncManager.init` on fresh prefs → setup not completed; with an existing seller name → completed.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement. **Step 4:** Tests + `assembleDebug` pass. **Step 5: Commit** `feat(setup): first-run setup wizard`.

---

### Task 9: Plain-language messages

**Files:** Modify `data/command/CommandSupport.kt` (`CommandException` carries `val uiText: UiText`), all command files (use resource ids for messages), `core/error/AppErrorManager.kt` (`report*(title: UiText, detail: UiText, cause: Throwable? = null)` overloads; release builds never show `cause` text), `core/error/FloatingMessageBanner.kt` (resolve `UiText`), every `AppErrorManager.report*` call site and Toast text in `ui/` and `MainActivity.kt`, strings files, `EmptyState` usage on every list (Bills, Khata, party ledger, backup snapshots). Test `ui/MessagesTest.kt`.

**Rules (spec §3.6):** short, everyday, action-oriented; say what to do next; no jargon ("transaction", "exception", "database", "sync failed (403)"). Examples: StaleEdit → "This item was changed on another screen. Open it again and retry." / "यह सामान किसी और स्क्रीन पर बदला गया है। इसे दोबारा खोलकर फिर कोशिश करें।"; ReturnExceedsSold → "You can return at most %1$s of %2$s." ; checkout failed → "Sale not saved. Check the items and try again."; backup export failed → "Backup could not be made. Free some phone storage and try again."
- [ ] **Step 1: Failing tests:** every `CommandException` subclass has a non-raw `UiText.Res`; in a release-flag configuration `AppErrorManager.reportError` with a throwable produces a detail without the throwable message (inject a `showTechnicalDetails: Boolean` provider defaulting to `BuildConfig.DEBUG`); `StringParityTest` still passes.
- [ ] **Step 2:** Run → fails. **Step 3:** Implement; grep afterwards: `grep -rn "reportError(\"\|reportWarning(\"\|reportSuccess(\"\|reportInfo(\"\|makeText(context, \"" app/src/main/java` returns nothing. **Step 4:** Tests + `assembleDebug` pass. **Step 5: Commit** `feat(ui): plain-language English/Hindi messages and empty states`.

---

### Task 10: Restyle remaining screens, remove Cupertino, full verification

**Files:** Modify `AddEditItemSheet.kt`, `QuickSellAndCartSheet.kt` (remaining quick-sell), `SaleHistoryScreen.kt`, `ReceiptShareDialog.kt`, `PartiesKhataScreen.kt`, `CloudSyncAndBackupSheet.kt` (or its remains under More), `OrderScanSheet.kt` (Sell-side scan, restyle only), `DatabaseRecoveryScreen.kt`, `FloatingMessageBanner.kt`, `InventoryScreen.kt` (delete if fully replaced); delete `ui/theme/CupertinoTheme.kt`, `ui/theme/CupertinoComponents.kt` once unused (move `AlphabetScrubberBar` / cipher glyph components into `ui/theme/Components.kt` or their screen if still used); `README.md`.

- [ ] **Step 1:** For each screen: replace Cupertino components/colours with `ShopLedgerTheme` components and `MaterialTheme` colours, extract every literal string to resources (+ Hindi), apply ≥ 48 dp targets, add `EmptyState`s. No behaviour change.
- [ ] **Step 2:** `grep -rn "Cupertino" app/src/main/java` → no results; `grep -rnE 'Text\("[A-Za-z]' app/src/main/java` → no results (all text from resources).
- [ ] **Step 3:** Full suite, `assembleDebug assembleRelease lintDebug` pass (fix new lint errors only; list pre-existing ones).
- [ ] **Step 4:** Update README features (stock-in scan, Hindi, new home/checkout/setup).
- [ ] **Step 5:** Write the manual checklist into the report: fresh install → wizard (English) → sample data; switch to Hindi in More and walk every tab; dark mode; Stock In scan of a printed bill photo and a handwritten list photo → review → save → stock and supplier Khata updated; checkout Cash/UPI/Udhar; returns; backup export/restore; existing-install upgrade keeps data and skips the wizard.
- [ ] **Step 6: Commit** `feat(ui): restyle remaining screens to Material 3 and retire Cupertino theme`.
