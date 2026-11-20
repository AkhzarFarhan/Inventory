# ShopLedger POS — Visible Multiple Carts + Clean "Add to Cart" Item View

- **Date:** 2026-11-20
- **Base:** `main` at `319edff` (v1.0.62)
- **Branch to create:** `feat/multicart-declutter`
- **Audience:** an AI coding agent implementing this plan task by task. Every instruction is literal. Do not skip steps, do not invent extra features, do not change anything not named here.

---

## 0. What the shop owner asked for

1. **Multiple carts.** Today several customers can be served at once only through a hidden "⋮" menu inside checkout, so it looks like multiple carts were removed. Make multiple carts **visible and easy**: see all open carts, switch with one tap, start a new cart with one tap, from the home screen and from checkout.
2. **Declutter the item view.** Tapping an item opens a "Quick sell" sheet with a **sideways-scrolling** row of sizes/types and a full buyer, payment and discount form. That is cluttered. **Sales must happen only from the cart (checkout).** The item view must only let the shopkeeper pick a size/type and a quantity and put it into the cart.

### Decisions (fixed — do not change)

| Topic | Decision |
|---|---|
| Where selling happens | **Only** in `CheckoutScreen` (the cart). The quick-sell sheet is deleted. |
| Item view | New `AddToCartSheet`: item name, which cart you are adding to, sizes/types as a **vertical list** (never horizontal scroll), each with price, stock and an **Add** button or a quantity stepper. Footer: **Go to cart** + **Done**. No buyer, payment, discount, estimate or profit on this sheet. |
| Cart switcher | New shared `CartSwitcher` composable: a **wrapping** row (FlowRow, no sideways scroll) of chips, one per open cart (`label · N items`), the active one selected, plus a **+ New cart** chip. |
| Where the switcher shows | Home (Items tab) just above the Sell / Stock In bar, **only when** there is more than one cart **or** the active cart has items. Checkout: always, directly under the top bar. |
| Cart names | A cart's name is: the name the user typed (Rename) → otherwise the customer name typed in checkout → otherwise "Cart N". |
| Max carts | 8 open carts. At 8 the **+ New cart** chip is hidden. |
| Cart numbers | A new default cart gets the **smallest unused** number (close Cart 2, the next new cart is Cart 2 again). |
| Closing a cart | From checkout ⋮ → **Close this cart**. Asks for confirmation if the cart has items. Closing the last cart leaves one fresh empty "Cart 1". |
| Survive app kill | Open carts are saved to SharedPreferences and restored on the next launch (several customers' carts must not vanish if Android kills the app). |
| Sell button on home | Shows the active cart: text `Sell · Cart 2`, badge = **active cart's** line count (not the sum of all carts). |
| Confirmation on add | After adding from home, show a short success message "Added to Cart 2" through `AppErrorManager.reportSuccess`. |

---

## 1. Global rules (apply to every task)

1. **Language/stack:** Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.03, Material3), Room, minSdk 24. No new libraries. `FlowRow` is available from `androidx.compose.foundation.layout` (needs `@OptIn(ExperimentalLayoutApi::class)`). JSON: use the Android built-in `org.json.JSONObject` / `JSONArray` (works in Robolectric).
2. **Package root:** `app/src/main/java/com/shopledger/pos/` (written `pos/` below). Tests: `app/src/test/java/com/shopledger/pos/`.
3. **Money is `Long` paise.** Never parse money with `Double`. Quantities in carts stay `Double` (existing `CartLineItem.quantity`).
4. **Every user-visible text is a string resource** in `app/src/main/res/values/strings.xml` **and** `app/src/main/res/values-hi/strings.xml` (same key, same format args `%1$s`, `%2$d`…). `StringParityTest` fails otherwise. Counts use `<plurals>` with `one` and `other` in both files. Item names and customer names are never translated.
5. **Design system only:** use components from `pos/ui/theme/Components.kt` (`PrimaryActionButton`, `SecondaryActionButton`, `QtyStepper`, `SectionCard`, `EmptyState`, `ConfirmDialog`, `StatusChip`) and `MaterialTheme` colours/typography and `ShopLedgerTokens` spacing. **No hard-coded colours.** Every tappable thing is **≥ 48 dp** (`ShopLedgerTokens.minTouch`). Icon-only buttons have a `contentDescription` from strings. Layout must work at **360 dp** width.
6. **Seller lock:** cost price must never show while seller mode is locked. Only use `CodedPurchasePriceBadge(purchasePricePaise = …, isSellerUnlocked = …)` for cost (it already hides correctly). Never show profit outside checkout.
7. **Do not change** the data layer (`pos/data/command/*`, `LedgerWriter`, Room entities/DAOs, `LpsRepository` signatures) and do not change how a sale is saved: checkout still calls `MainViewModel.completeSaleAndPrepareReceipt(...)` which calls `repository.executeCheckoutAndDeductStock(actionId = cart.checkoutActionId, …)`. One `checkoutActionId` per cart, kept until the sale is saved (double-tap safety). **Never** generate a new `checkoutActionId` except where this plan says so.
8. **Line endings LF, UTF-8.** Stage with `git -c core.autocrlf=false add <files>`. Before each commit, `git ls-files --eol <files>` must show `i/lf`.
9. **Commits:** one commit per task, message given in the task, ending with a blank line and `Co-Authored-By: <your model name> <noreply@example.com>` (or your tool's standard trailer).
10. **Test-first:** write the listed tests, run them and see them fail, then implement, then run them green.

### Commands (Windows PowerShell, from `C:\GitHub\Inventory`)

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\GitHub\Inventory\build\jtmp"
```
- One test class: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1 --tests "com.shopledger.pos.ui.CartSwitcherTest"`
- Full suite: `powershell -ExecutionPolicy Bypass -File scripts/test.ps1`
- Build: `.\gradlew.bat assembleDebug --console=plain > build\assemble.log 2>&1`
- Final: `.\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain > build\final.log 2>&1`

**Always redirect Gradle output to a file under `build\`** and read the file; never pipe Gradle through `Select-Object` (it hangs). If a run hangs for more than 10 minutes: `.\gradlew.bat --stop`, then rerun. Run `.\gradlew.bat --stop` when completely done. Test results: `app/build/test-results/testDebugUnitTest/*.xml`.

Current baseline: **349 tests, 0 failures.** It must stay at 0 failures after every task.

---

## 2. Current code you will touch (read these first)

| File | What is there now |
|---|---|
| `pos/ui/MainViewModel.kt` | `data class CustomerCartSession(cartId, label, cartNumber, buyerName, buyerPhone, items, createdAt, checkoutActionId)` (~line 42); `fun CustomerCartSession.displayLabel(context)` (~55); `nextCartSequence`, `_carts`, `_activeCartId`, `cartsState`, `activeCartSessionState`, `cartItems` (~77–100); `quickSellActionId` (~78); `updateActiveCart`, `switchActiveCart`, `createNewCart`, `closeCart`, `updateActiveCartBuyer`, `addOrUpdateCartItem`, `updateCartLineQuantityAndPrice`, `removeCartLine`, `removeMultipleCartLines`, `clearCart`, `batchAddItemsToActiveCart`, `fillCartFromBuyerHistory` (~460–640); `completeSaleAndPrepareReceipt(itemsToSell, buyerName, buyerPhone, clearCounterCart, paymentMode, discountPaise, amountPaidPaise, onComplete)` (~675–722). |
| `pos/ui/screens/checkout/CheckoutScreen.kt` | `CheckoutCartTab`, `CheckoutState`, `CheckoutEvent` (incl. `NewCart`, `CloseCart`, `SwitchCart`), `CheckoutTopBar` with a `DropdownMenu` that lists other carts (~233–316). |
| `pos/ui/screens/checkout/CheckoutRoute.kt` | Builds `CheckoutState`, maps events to the ViewModel; calls `completeSaleAndPrepareReceipt(..., clearCounterCart = true, ...)`. |
| `pos/ui/screens/inventory/QuickSellAndCartSheet.kt` | Only `QuickSellSheet`, `BuyerSuggestAndHistoryCard`, `PaymentModeAndDiscountCard`. The variant chooser is a `Row(Modifier.horizontalScroll(...))` of `FilterChip`s (~140). **This whole file will be deleted.** |
| `pos/ui/screens/items/ItemsHomeScreen.kt` | `ItemsHomeScreen(..., cartLineCount, onSell, onOpenItem, onEditItem, onAddToCart, ...)`; `GroupRow` (tap row → `onOpen`; cart icon → single variant adds, multi variant calls `onOpenItem`); bottom bar with Sell (+ badge) and Stock In. |
| `pos/MainActivity.kt` | `quickSellTarget` state (~209), `ItemsHomeScreen(...)` call (~238–275, `cartLineCount = carts.sumOf { it.items.size }`, `onOpenItem = { … quickSellTarget = Triple(...) }`), `CheckoutRoute` overlay (~420), `QuickSellSheet(...)` (~470–503). |
| Tests | `ui/ItemsHomeScreenTest.kt` (`cartTapOnMultiVariantOpensQuickSellInsteadOfAdding` ~194), `ui/RestyledScreensTest.kt` (quick-sell test using `QuickSellSheet` ~196), `ui/CheckoutScreenTest.kt`, `ui/MainViewModelSetupTest.kt` (pattern for building a real `MainViewModel` with a test DB: `MainViewModel(LpsRepository(context, db))`, `newTestDatabase()`, `awaitUntil`). |

---

## Task 1 — Cart logic in `MainViewModel`

**Goal:** better cart names and numbering, a max of 8, rename, keep a typed price when adding again, and remove the quick-sell path.

### 1.1 Tests first — create `app/src/test/java/com/shopledger/pos/ui/MainViewModelCartTest.kt`

Use the same Robolectric setup as `MainViewModelSetupTest` (`@Config(application = android.app.Application::class)`, `@RunWith(AndroidJUnit4::class)`, test DB, clear the prefs file `shopledger_cart_prefs` in `@Before` as well — Task 2 adds it). Create the ViewModel with `MainViewModel(LpsRepository(context, db))`. Build `InventoryItemEntity` / `ItemVariantEntity` objects in memory (no need to insert them) exactly like `ItemsHomeScreenTest` does.

Tests (names exactly):
1. `newCartUsesSmallestFreeNumber` — create 2 new carts (carts 1,2,3 exist), close the cart numbered 2, create a new cart → it has `cartNumber == 2`.
2. `cannotOpenMoreThanEightCarts` — call `createNewCart()` 10 times → `cartsState.value.size == 8`; the last three calls (8th–10th, since cart 1 already exists) return `null`.
3. `displayLabelPrefersTypedNameThenCustomerThenNumber` — use `ApplicationProvider.getApplicationContext<Context>()`: default cart → `"Cart 1"`; after `updateActiveCartBuyer("Ramesh", "")` → `"Ramesh"`; after `renameCart(activeId, "Site order")` → `"Site order"`; after `renameCart(activeId, "  ")` → back to `"Ramesh"`.
4. `addingAgainKeepsTypedPrice` — add variant (selling 5000 paise), `updateCartLineQuantityAndPrice(variantId, 1.0, 4500L)`, then `addOrUpdateCartItem(item, variant, 1.0)` → line quantity `2.0`, `customSellingPricePaise == 4500L`.
5. `itemsGoOnlyIntoTheActiveCart` — add to cart 1, create cart 2 (becomes active), add another variant → cart 1 has 1 line, cart 2 has 1 line, different variants.
6. `switchingCartsKeepsEachCartsItems` — after test 5's setup, `switchActiveCart(cart1Id)` → `cartItems.value` is cart 1's line.
7. `closingLastCartLeavesFreshCartOne` — with one cart containing items, `closeCart(it)` → exactly one cart, empty, `cartNumber == 1`.

Collect values with `cartsState.value` / `activeCartSessionState.value`; `activeCartSessionState` uses `stateIn(viewModelScope, …)` so read it after `shadowOf(Looper.getMainLooper()).idle()` (use the `awaitUntil` helper pattern from `MainViewModelSetupTest`).

Run → they must fail to compile / fail.

### 1.2 Implementation (in `pos/ui/MainViewModel.kt`)

1. Add constant at file top level: `const val MAX_OPEN_CARTS = 8`.
2. Replace `displayLabel`:
```kotlin
/** The cart name to show: the user's own name, else the customer's name, else "Cart N" in the current language. */
fun CustomerCartSession.displayLabel(context: android.content.Context): String =
    label.trim().ifBlank { buyerName.trim() }.ifBlank { context.getString(R.string.cart_default_label, cartNumber) }
```
3. Delete the field `private var nextCartSequence = 2` and every use of it. Add:
```kotlin
private fun nextFreeCartNumber(carts: List<CustomerCartSession>): Int {
    val used = carts.map { it.cartNumber }.toSet()
    var n = 1
    while (n in used) n++
    return n
}
```
4. The existing function is `fun createNewCart(customLabel: String? = null, initialBuyerName: String = "", initialBuyerPhone: String = ""): String` (returns the new cart id). Keep the parameters; change the return type to `String?` (`null` = limit reached) and always give a number:
```kotlin
fun createNewCart(
    customLabel: String? = null,
    initialBuyerName: String = "",
    initialBuyerPhone: String = ""
): String? {
    val current = _carts.value
    if (current.size >= MAX_OPEN_CARTS) return null
    val created = CustomerCartSession(
        cartId = "cart-${UUID.randomUUID()}",
        label = customLabel?.trim().orEmpty(),
        cartNumber = nextFreeCartNumber(current),
        buyerName = initialBuyerName,
        buyerPhone = initialBuyerPhone,
    )
    _carts.value = current + created
    _activeCartId.value = created.cartId
    return created.cartId
}
```
   The only caller today is `CheckoutRoute.kt` (`CheckoutEvent.NewCart -> viewModel.createNewCart()`), which ignores the result. In tests, "returns false" below means "returns `null`".
5. In `closeCart`, when no carts remain, create `CustomerCartSession(cartId = "cart-${UUID.randomUUID()}", cartNumber = 1)` (remove the `nextCartSequence = 2` line).
6. Add:
```kotlin
fun renameCart(cartId: String, newLabel: String) {
    _carts.value = _carts.value.map { if (it.cartId == cartId) it.copy(label = newLabel.trim().take(24)) else it }
}
```
7. `addOrUpdateCartItem`: change the parameter to `customSellingPricePaise: Long? = null`. When the line already exists keep `existing.customSellingPricePaise` unless a non-null value was passed; when it is new use `customSellingPricePaise ?: variant.sellingPricePaise`. Fix all callers (callers that passed nothing need no change).
8. **Remove the quick-sell path:** delete `private var quickSellActionId`, delete the `clearCounterCart` parameter from `completeSaleAndPrepareReceipt`, and always use the cart branch:
```kotlin
val checkedOutCartId = _activeCartId.value
val actionId = _carts.value.firstOrNull { it.cartId == checkedOutCartId }?.checkoutActionId ?: UUID.randomUUID().toString()
```
   and in the cleanup keep only the `else` branch body (the `stillThere` check, `closeCart` when more than one cart, otherwise `clearCart()` + `updateActiveCartBuyer("", "")`). Update `CheckoutRoute.kt` (remove `clearCounterCart = true,`). Fix any test that passes `clearCounterCart`.
9. Leave `clearCart()` as it is (it already rotates `checkoutActionId` — that is correct because the old bill is gone).

Run the new tests and the full suite → green. Build `assembleDebug` → success.

**Commit:** `feat(cart): smallest-free cart numbers, max 8 carts, rename, keep typed price, checkout-only selling`

---

## Task 2 — Keep open carts when Android kills the app

### 2.1 Tests first — `app/src/test/java/com/shopledger/pos/core/cart/CartDraftStoreTest.kt` (Robolectric)

1. `roundTripKeepsEverything` — save two carts (one with label "Site", buyerName "Ramesh", buyerPhone "9876543210", two lines with fractional quantity `2.5`, custom price, skuCode, barcode, gstRateBps 1800, a known `checkoutActionId` and `createdAt`), active id = second cart → `load()` returns equal objects (data-class equality) and the same active id.
2. `corruptJsonLoadsNothing` — write `"{not json"` into the prefs key directly → `load()` returns `null` and does not throw.
3. `emptyPrefsLoadsNothing` — `load()` on clean prefs returns `null`.

And in `MainViewModelCartTest` add:
4. `cartsSurviveANewViewModel` — VM A: add an item to cart 1, create cart 2, add an item, rename cart 2 "Site"; wait for the save (use `awaitUntil` on the prefs containing `"Site"`); create VM B with the same context → B has 2 carts with the same ids, items, label, `checkoutActionId`, and the same active cart.

### 2.2 Implementation — create `pos/core/cart/CartDraftStore.kt`

```kotlin
package com.shopledger.pos.core.cart

/** Saves the open counter carts so several customers' carts survive the app being killed. */
object CartDraftStore {
    private const val PREFS = "shopledger_cart_prefs"
    private const val KEY = "open_carts_v1"

    data class Snapshot(val carts: List<CustomerCartSession>, val activeCartId: String)

    fun save(context: Context, carts: List<CustomerCartSession>, activeCartId: String) { /* JSON → prefs.edit().putString(KEY, json).apply() */ }
    fun load(context: Context): Snapshot? { /* null when missing, empty list, or any parse error (catch Exception) */ }
}
```
JSON shape (write and read every field explicitly):
```json
{"active":"cart-…","carts":[{"cartId":"…","label":"","cartNumber":1,"buyerName":"","buyerPhone":"","createdAt":0,"checkoutActionId":"…",
  "items":[{"itemId":"…","variantId":"…","itemName":"…","variantSummary":"…","unit":"…","availableStock":0.0,
            "unitPurchasePricePaise":0,"defaultSellingPricePaise":0,"customSellingPricePaise":0,"quantity":1.0,
            "skuCode":"","barcode":"","gstRateBps":0}]}]}
```
Use `optString/optLong/optInt/optDouble` with the defaults of the data classes so older/missing fields never crash. `import com.shopledger.pos.ui.CustomerCartSession` and `com.shopledger.pos.data.model.CartLineItem`.

### 2.3 Wire into `MainViewModel`

`MainViewModel` only receives `repository`. `LpsRepository` is declared `class LpsRepository(private val context: Context, private val database: LpsDatabase, …)` in `pos/data/repository/LpsRepository.kt`, so add one **read-only** property inside the class body: `val appContext: Context get() = context` (this is the only allowed change to the data layer).

In `MainViewModel` `init` (before anything reads `_carts`):
```kotlin
CartDraftStore.load(repository.appContext)?.let { snap ->
    if (snap.carts.isNotEmpty()) {
        _carts.value = snap.carts.take(MAX_OPEN_CARTS)
        _activeCartId.value = snap.activeCartId.takeIf { id -> snap.carts.any { it.cartId == id } } ?: snap.carts.first().cartId
    }
}
viewModelScope.launch {
    combine(_carts, _activeCartId) { c, a -> c to a }
        .drop(1)
        .debounce(300)
        .collect { (c, a) -> withContext(Dispatchers.IO) { CartDraftStore.save(repository.appContext, c, a) } }
}
```
(`debounce` needs `@OptIn(FlowPreview::class)`.) Because `_carts` is initialised with a default "cart-1" before `init`, do the load as the **first** statement of `init`.

Run tests (all) → green; `assembleDebug` → success.

**Commit:** `feat(cart): keep open carts across app restarts`

---

## Task 3 — Shared `CartSwitcher` component

### 3.1 Tests first — `app/src/test/java/com/shopledger/pos/ui/CartSwitcherTest.kt` (Compose + Robolectric, same setup as `CheckoutScreenTest`, wrap content in `ShopLedgerTheme { }`, use `@Config(qualifiers = "w360dp-h800dp")` like other screen tests)

1. `showsEveryCartWithItsCount` — 3 carts ("Cart 1" 2 lines, "Ramesh" 0 lines, "Cart 3" 1 line) → nodes with text `"Cart 1 · 2 items"`, `"Ramesh · 0 items"`, `"Cart 3 · 1 item"` exist (use the plural string).
2. `activeCartIsSelected` — the active chip `assertIsSelected()`, others `assertIsNotSelected()`.
3. `tapSwitchesCart` — tap "Ramesh · 0 items" → callback receives that cart id.
4. `newCartChipCallsNewAndHidesAtMax` — with 3 carts the "New cart" chip exists and calls `onNewCart`; with 8 carts it does not exist.
5. `wrapsInsteadOfScrollingAt360dp` — with 6 carts, every chip is displayed (`assertIsDisplayed()`) without scrolling (proves wrapping).
6. `chipsAreAtLeast48dpTall` — `onNodeWithText("Cart 1 · 2 items").assertHeightIsAtLeast(48.dp)`.

### 3.2 Implementation — create `pos/ui/screens/cart/CartSwitcher.kt`

```kotlin
package com.shopledger.pos.ui.screens.cart

data class CartChip(val cartId: String, val label: String, val lineCount: Int)

/**
 * All open carts as wrapping chips (never sideways scroll) + a "New cart" chip.
 * Used on the Items home screen and at the top of checkout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CartSwitcher(
    carts: List<CartChip>,
    activeCartId: String,
    onSwitch: (String) -> Unit,
    onNewCart: () -> Unit,
    modifier: Modifier = Modifier,
    maxCarts: Int = MAX_OPEN_CARTS,
)
```
- `FlowRow(modifier.fillMaxWidth().padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS), horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS), verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS))`.
- Each cart: `FilterChip(selected = cart.cartId == activeCartId, onClick = { onSwitch(cart.cartId) }, label = { Text(pluralStringResource(R.plurals.cart_chip_label, cart.lineCount, cart.label, cart.lineCount), maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = if selected Icon(Icons.Filled.ShoppingCart, null, Modifier.size(18.dp)) else null, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch).widthIn(max = 220.dp))`.
- If `carts.size < maxCarts`: `AssistChip(onClick = onNewCart, label = { Text(stringResource(R.string.cart_new)) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch))`.
- Selected chip: `Modifier.semantics { selected = true }` is provided by `FilterChip`; do not add a second one.

Strings (Task 7 lists all of them; add the ones you use now in both files):
- `cart_chip_label` (plurals): en one `%1$s · %2$d item`, other `%1$s · %2$d items`; hi one `%1$s · %2$d सामान`, other `%1$s · %2$d सामान`.
- `cart_new`: en `New cart`, hi `नई कार्ट`.

Tests green; `assembleDebug`.

**Commit:** `feat(cart): wrapping cart switcher component`

---

## Task 4 — Checkout shows the carts

### 4.1 Tests first — add to `ui/CheckoutScreenTest.kt`

1. `cartSwitcherVisibleUnderTopBar` — state with 2 carts → both chips displayed without opening any menu.
2. `tappingCartChipSendsSwitchCart` — tap the second chip → `CheckoutEvent.SwitchCart(id)` received.
3. `newCartChipSendsNewCart` — tap "New cart" → `CheckoutEvent.NewCart`.
4. `closeCartWithItemsAsksFirst` — open ⋮, tap "Close this cart" with lines present → a dialog with title "Close this cart?" shows; confirming sends `CheckoutEvent.CloseCart`; cancelling sends nothing.
5. `renameCartSendsNewName` — ⋮ → "Rename cart" → dialog with a text field; type "Site order", confirm → `CheckoutEvent.RenameCart("Site order")`.
6. `menuNoLongerListsOtherCarts` — open ⋮ with 2 carts → there is no menu item whose text starts with the other cart's label.

Existing CheckoutScreenTest tests must still pass. If an existing test opens the ⋮ menu to switch or create carts, change only that interaction to tap the chip instead (keep its assertions).

### 4.2 Implementation

`CheckoutScreen.kt`:
1. Add `data class RenameCart(val name: String) : CheckoutEvent` to `CheckoutEvent`.
2. Add field to `CheckoutState`: `val maxCarts: Int = MAX_OPEN_CARTS` (import from `com.shopledger.pos.ui`).
3. In `CheckoutScreen`, right after `CheckoutTopBar(...)`, add:
```kotlin
CartSwitcher(
    carts = state.carts.map { CartChip(it.cartId, it.label, it.lineCount) },
    activeCartId = state.activeCartId,
    onSwitch = { onEvent(CheckoutEvent.SwitchCart(it)) },
    onNewCart = { onEvent(CheckoutEvent.NewCart) },
    maxCarts = state.maxCarts,
)
HorizontalDivider()
```
4. `CheckoutTopBar`: title becomes `state.cartLabel` (bold title) with `stringResource(R.string.checkout_title)` as the small line above it **only** removed — i.e. show one line: `stringResource(R.string.checkout_title_with_cart, state.cartLabel)` ("Bill · %1$s"). In the dropdown **delete** the `state.carts.filter { … }.forEach { … }` block and the "New cart" item. Keep "Share estimate", "Scan list", "Clear cart". Add **"Rename cart"** (opens a rename dialog) and keep **"Close this cart"** but show it always (also with one cart) and route it through a confirmation when `state.lines.isNotEmpty()`.
5. Rename dialog: an `AlertDialog` with an `OutlinedTextField` (singleLine, max 24 chars enforced in `onValueChange`, initial value = current label), confirm button `cart_rename_save`, dismiss button uses the existing key `R.string.action_cancel`. Hold `showRename` and the text with `rememberSaveable`.
6. Close confirmation: reuse `ConfirmDialog` from `Components.kt` with `cart_close_title` / `cart_close_message` and confirm label `cart_close_confirm`. Hold `showCloseConfirm` with `rememberSaveable`.

`CheckoutRoute.kt`:
- `CheckoutEvent.RenameCart -> viewModel.renameCart(cartId, event.name)`.
- `CheckoutEvent.NewCart -> if (viewModel.createNewCart() == null) AppErrorManager.reportInfo(UiText.Res(R.string.cart_limit_title), UiText.Res(R.string.cart_limit_message, listOf(MAX_OPEN_CARTS)))`. (`UiText` is `sealed interface` with `data class Res(@StringRes val id: Int, val args: List<Any> = emptyList())` and `data class Raw(val text: String)` in `pos/ui/text/UiText.kt`.)
- `maxCarts = MAX_OPEN_CARTS` in the state.
- Make sure `CheckoutCartTab.label` uses `displayLabel(appContext)` (already does) so customer names appear on chips.

Tests green; `assembleDebug`.

**Commit:** `feat(checkout): visible cart switcher, rename and close-with-confirm`

---

## Task 5 — Home screen shows the carts

### 5.1 Tests first — `ui/ItemsHomeScreenTest.kt`

Update the helper `show(...)` to pass the new parameters (defaults below make most old tests compile unchanged).
1. `cartSwitcherHiddenWithOneEmptyCart` — one cart, 0 lines → no "New cart" chip on screen.
2. `cartSwitcherShownWhenActiveCartHasItems` — one cart with 2 lines → chip "Cart 1 · 2 items" and "New cart" shown.
3. `cartSwitcherShownWithTwoCarts` — two empty carts → both chips shown.
4. `sellButtonNamesActiveCartAndCountsOnlyIt` — carts: "Cart 1" 3 lines, "Cart 2" (active) 1 line → button text "Sell · Cart 2", badge content description "1 items in cart" (existing `cd_cart_lines`), not 4.
5. Rename `cartTapOnMultiVariantOpensQuickSellInsteadOfAdding` → `cartTapOnMultiVariantOpensItemSheetInsteadOfAdding` (same body).

### 5.2 Implementation — `ItemsHomeScreen.kt`

Replace the parameter `cartLineCount: Int` with:
```kotlin
carts: List<CartChip> = emptyList(),
activeCartId: String = "",
onSwitchCart: (String) -> Unit = {},
onNewCart: () -> Unit = {},
```
(keep the parameter position where `cartLineCount` was; give defaults so other call sites/tests compile).
- `val active = carts.firstOrNull { it.cartId == activeCartId }`; `val activeLines = active?.lineCount ?: 0`.
- Above the bottom `Surface` (inside the main `Column`, after the list `Box`), add:
```kotlin
if (carts.size > 1 || activeLines > 0) {
    Surface(tonalElevation = 1.dp) {
        CartSwitcher(carts = carts, activeCartId = activeCartId, onSwitch = onSwitchCart, onNewCart = onNewCart)
    }
}
```
- Sell button text: `if (active != null && (carts.size > 1 || activeLines > 0)) stringResource(R.string.action_sell_cart, active.label) else stringResource(R.string.action_sell)`; badge uses `activeLines` instead of `cartLineCount`.

`MainActivity.kt` `ItemsHomeScreen(...)` call:
```kotlin
carts = carts.map { CartChip(it.cartId, it.displayLabel(context), it.items.size) },
activeCartId = activeCartId,          // collect viewModel.activeCartIdState with collectAsState()
onSwitchCart = viewModel::switchActiveCart,
onNewCart = {
    if (viewModel.createNewCart() == null) AppErrorManager.reportInfo(
        UiText.Res(R.string.cart_limit_title),
        UiText.Res(R.string.cart_limit_message, listOf(MAX_OPEN_CARTS)),
    )
},
```
Remove `cartLineCount = …`. `onAddToCart` (single-variant cart icon) becomes:
```kotlin
onAddToCart = { item, variant ->
    viewModel.addOrUpdateCartItem(item, variant, 1.0)
    AppErrorManager.reportSuccess(
        UiText.Res(R.string.cart_added_title),
        UiText.Res(R.string.cart_added_message, listOf(item.displayName, activeCartLabel)),
    )
},
```
where `activeCartLabel` = `activeCartSession.displayLabel(context)` and `activeCartSession` is `val activeCartSession by viewModel.activeCartSessionState.collectAsState()` declared next to the other `collectAsState()` calls in `MainActivity`.

Tests green; `assembleDebug`.

**Commit:** `feat(home): show open carts and the active cart on the Sell button`

---

## Task 6 — Replace quick sell with a clean "Add to cart" sheet

### 6.1 Tests first — create `app/src/test/java/com/shopledger/pos/ui/AddToCartSheetTest.kt`

Test the **stateless** content composable `AddToCartContent` (not the bottom sheet wrapper) inside `ShopLedgerTheme`, at `w360dp-h800dp`.
1. `variantsAreAVerticalListNotSideways` — item with 6 variants → every variant label node is displayed after `performScrollToNode` on the list **vertically**; assert there is **no** node with `hasScrollAction()` that is horizontally scrollable: simplest check — each variant row's left edge (`fetchSemanticsNode().boundsInRoot.left`) is the same for all rows.
2. `addButtonAddsOneToCart` — variant not in cart shows "Add"; tap → `AddToCartEvent.Add(variantId)`.
3. `variantAlreadyInCartShowsStepper` — cart qty 2 for variant B → row B shows stepper text "2"; tap plus → `AddToCartEvent.SetQty(variantId, 3.0)`; tap minus → `SetQty(variantId, 1.0)`.
4. `minusAtOneRemoves` — qty 1, tap minus → `AddToCartEvent.SetQty(variantId, 0.0)`.
5. `showsWhichCartAndGoToCart` — header text "Adding to Cart 2"; footer button "Go to cart · 3 items" sends `AddToCartEvent.GoToCart`; "Done" sends `AddToCartEvent.Done`.
6. `noSaleControlsOnThisSheet` — nodes with text "Buyer", "Cash", "UPI", "Udhar", "Discount", "Share estimate picture", "Profit" do **not** exist.
7. `costHiddenWhenLocked` — `isSellerUnlocked = false`, variant purchase 4000 → no node with text "₹40" (cost); price ₹50 is shown.
8. `outOfStockStillAddable` — variant with stock 0 shows the "Out of stock" status text and its Add button is enabled (selling into negative is allowed today; checkout shows the oversold warning).

In `ItemsHomeScreenTest`, keep test 5 from Task 5. In `RestyledScreensTest`, **delete** the quick-sell test (and its `QuickSellSheet` import).

### 6.2 Implementation — create `pos/ui/screens/items/AddToCartSheet.kt`

```kotlin
package com.shopledger.pos.ui.screens.items

data class AddToCartVariantRow(
    val variantId: String,
    val label: String,            // already localised: variant summary .variantLabel()
    val pricePaise: Long,
    val purchasePricePaise: Long,
    val stockText: String,        // already formatted, e.g. "12 pcs in stock" / "Out of stock"
    val status: StockStatus,      // existing public enum com.shopledger.pos.ui.theme.StockStatus { OK, LOW, OUT, OVERSOLD } in Components.kt
    val qtyInCart: Double,        // 0.0 when not in the active cart
)

data class AddToCartState(
    val itemName: String,
    val cartLabel: String,
    val variants: List<AddToCartVariantRow>,
    val cartLineCount: Int,
    val cartTotalPaise: Long,
    val isSellerUnlocked: Boolean,
)

sealed interface AddToCartEvent {
    data class Add(val variantId: String) : AddToCartEvent
    data class SetQty(val variantId: String, val qty: Double) : AddToCartEvent
    data object GoToCart : AddToCartEvent
    data object Done : AddToCartEvent
}

@Composable fun AddToCartContent(state: AddToCartState, onEvent: (AddToCartEvent) -> Unit, modifier: Modifier = Modifier)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AddToCartSheet(state: AddToCartState, onEvent: (AddToCartEvent) -> Unit, onDismiss: () -> Unit)
// = ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { AddToCartContent(...) }
```
Layout of `AddToCartContent` (a `Column`, `fillMaxWidth()`, padding `spacingL`):
1. Header: item name (`titleLarge`, max 2 lines, ellipsis); below it `stringResource(R.string.add_sheet_adding_to, state.cartLabel)` (`bodyMedium`, `onSurfaceVariant`).
2. `LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp))` of variant rows, `HorizontalDivider` between rows. **No `horizontalScroll` anywhere.** Each row (`Row`, `heightIn(min = 64.dp)`, vertically centred):
   - Left `Column(Modifier.weight(1f))`: label (`titleMedium`, max 2 lines); `stockText` (`bodyMedium`, colour by status — reuse the same colour mapping `ItemRow`/`StatusChip` uses for LOW/OUT/OVERSOLD); if `isSellerUnlocked` or the cipher mode shows codes, `CodedPurchasePriceBadge(purchasePricePaise = row.purchasePricePaise, isSellerUnlocked = state.isSellerUnlocked)` (the badge itself hides when it must).
   - Middle: price `IndianCurrencyFormatter.formatPaise(row.pricePaise)` (`titleMedium`, primary colour).
   - Right: if `row.qtyInCart <= 0.0` → `FilledTonalButton(onClick = { onEvent(Add(row.variantId)) }, modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch)) { Text(stringResource(R.string.add_sheet_add)) }`; else `QtyStepper(valueText = IndianCurrencyFormatter.formatQuantity(row.qtyInCart), onMinus = { onEvent(SetQty(row.variantId, (row.qtyInCart - 1.0).coerceAtLeast(0.0))) }, onPlus = { onEvent(SetQty(row.variantId, row.qtyInCart + 1.0)) }, onValueClick = { /* open a quantity dialog: reuse the sanitiser + dialog pattern from CheckoutScreen.QuantityDialog (copy it as a private composable here; decimals allowed up to 3 places) and send SetQty */ })`.
   - Row semantics: `Modifier.semantics(mergeDescendants = false) { contentDescription = "<label>, <price>, <stockText>" }` built from `stringResource(R.string.add_sheet_row_cd, label, price, stock)`.
3. Footer (`Column`, spacing `spacingS`, bottom padding `spacingXl`):
   - `PrimaryActionButton(text = if (state.cartLineCount == 0) stringResource(R.string.add_sheet_go_to_cart_empty) else pluralStringResource(R.plurals.add_sheet_go_to_cart, state.cartLineCount, state.cartLineCount, IndianCurrencyFormatter.formatPaise(state.cartTotalPaise)), icon = Icons.Filled.ShoppingCart, enabled = state.cartLineCount > 0, onClick = { onEvent(GoToCart) })` (`PrimaryActionButton(text, onClick, modifier, icon, enabled, containerColor)` already has `enabled`).
   - `SecondaryActionButton(text = stringResource(R.string.add_sheet_done), onClick = { onEvent(Done) }, modifier = Modifier.fillMaxWidth())`.

### 6.3 Wire it in `MainActivity.kt`

1. Replace `var quickSellTarget by remember { … }` with `var addSheetItemId by rememberSaveable { mutableStateOf<String?>(null) }` (survives rotation).
2. `onOpenItem = { item, _, _ -> addSheetItemId = item.id }`. Keep `ItemsHomeScreen`'s `onOpenItem` signature unchanged.
3. Delete the whole `quickSellTarget?.let { QuickSellSheet(...) }` block and the `QuickSellSheet` import. Add:
```kotlin
addSheetItemId?.let { itemId ->
    val group = allInventory.firstOrNull { it.item.id == itemId }
    if (group == null) { addSheetItemId = null; return@let }   // item deleted meanwhile
    val session = activeCartSession                               // collected from viewModel.activeCartSessionState
    val state = AddToCartState(
        itemName = group.item.displayName,
        cartLabel = session.displayLabel(context),
        variants = group.variants.map { v ->
            AddToCartVariantRow(
                variantId = v.id,
                label = v.buildVariantSummary().variantLabel(),
                pricePaise = v.sellingPricePaise,
                purchasePricePaise = v.purchasePricePaise,
                stockText = /* same text rules as GroupRow uses for one variant (stock_in_stock / stock_low / stock_out / stock_oversold) */,
                status = statusOf(v),
                qtyInCart = session.items.firstOrNull { it.variantId == v.id }?.quantity ?: 0.0,
            )
        },
        cartLineCount = session.items.size,
        cartTotalPaise = session.items.sumOf { it.lineRevenuePaise },
        isSellerUnlocked = isSellerUnlocked,
    )
    AddToCartSheet(
        state = state,
        onDismiss = { addSheetItemId = null },
        onEvent = { e ->
            when (e) {
                is AddToCartEvent.Add -> group.variants.firstOrNull { it.id == e.variantId }?.let { viewModel.addOrUpdateCartItem(group.item, it, 1.0) }
                is AddToCartEvent.SetQty -> {
                    val line = session.items.firstOrNull { it.variantId == e.variantId }
                    when {
                        e.qty <= 0.0 -> viewModel.removeCartLine(e.variantId)
                        line != null -> viewModel.updateCartLineQuantityAndPrice(e.variantId, e.qty, line.customSellingPricePaise)
                    }
                }
                AddToCartEvent.GoToCart -> { addSheetItemId = null; showCheckout = true }
                AddToCartEvent.Done -> addSheetItemId = null
            }
        },
    )
}
```
   `statusOf` and the stock-text helper are private in `ItemsHomeScreen.kt`: move `StockStatus`-related helpers (`statusOf`, `qty`) and a new `@Composable fun variantStockText(v: ItemVariantEntity): String` into a new file `pos/ui/screens/items/StockText.kt` as `internal`, and use them from both places. Do **not** change their logic.
4. Delete `pos/ui/screens/inventory/QuickSellAndCartSheet.kt` entirely. `grep -rn "QuickSellSheet\|BuyerSuggestAndHistoryCard\|PaymentModeAndDiscountCard" app/src` must return nothing.
5. Remove strings only used by the deleted file: for each key starting `sell_` (`sell_title` … `sell_estimate_photo`, `sell_past_visits` plurals) run `grep -rn "R.string.<key>\|R.plurals.<key>" app/src/main/java`; if there are no matches, delete the key from **both** `values/strings.xml` and `values-hi/strings.xml`. Do not delete keys that still have matches.
6. In `ItemsHomeScreen.GroupRow`: tapping the row and tapping the cart icon on a multi-variant item both call `onOpenItem` (already the case); the single-variant cart icon adds directly (already the case, now with the "Added to …" message from Task 5).

Tests green (full suite); `assembleDebug`.

**Commit:** `feat(items): clean add-to-cart sheet with a vertical size list; selling only from the cart`

---

## Task 7 — All new strings (English + Hindi)

Add every key below to **both** files (keep alphabetical-ish grouping near existing `cart_*` / `checkout_*` keys). Remove `checkout_menu_switch` and `checkout_menu_new_cart` from both files if nothing references them any more (grep first).

| Key | English | Hindi |
|---|---|---|
| `cart_new` | New cart | नई कार्ट |
| `cart_chip_label` (plurals) | one: `%1$s · %2$d item` / other: `%1$s · %2$d items` | one: `%1$s · %2$d सामान` / other: `%1$s · %2$d सामान` |
| `cart_limit_title` | Too many carts | बहुत ज़्यादा कार्ट |
| `cart_limit_message` | You can keep up to %1$d carts open. Finish or close one first. | एक साथ %1$d कार्ट तक खुली रख सकते हैं। पहले कोई एक पूरी करें या बंद करें। |
| `cart_added_title` | Added | जोड़ दिया |
| `cart_added_message` | %1$s added to %2$s | %1$s, %2$s में जोड़ा गया |
| `cart_rename` | Rename cart | कार्ट का नाम बदलें |
| `cart_rename_hint` | Customer or cart name | ग्राहक या कार्ट का नाम |
| `cart_rename_save` | Save name | नाम सेव करें |
| `cart_close_title` | Close this cart? | यह कार्ट बंद करें? |
| `cart_close_message` | The items in this cart will be removed. Nothing is sold. | इस कार्ट का सामान हट जाएगा। कोई बिक्री नहीं होगी। |
| `cart_close_confirm` | Close cart | कार्ट बंद करें |
| `checkout_title_with_cart` | Bill · %1$s | बिल · %1$s |
| `action_sell_cart` | Sell · %1$s | बेचें · %1$s |
| `add_sheet_adding_to` | Adding to %1$s | %1$s में जोड़ रहे हैं |
| `add_sheet_add` | Add | जोड़ें |
| `add_sheet_done` | Done | हो गया |
| `add_sheet_go_to_cart_empty` | Go to cart | कार्ट खोलें |
| `add_sheet_go_to_cart` (plurals) | one: `Go to cart · %1$d item · %2$s` / other: `Go to cart · %1$d items · %2$s` | one: `कार्ट खोलें · %1$d सामान · %2$s` / other: `कार्ट खोलें · %1$d सामान · %2$s` |
| `add_sheet_row_cd` | %1$s, %2$s, %3$s | %1$s, %2$s, %3$s |
| `add_sheet_qty_title` | Quantity | मात्रा |

If the existing `checkout_menu_close_cart` text differs from "Close this cart", keep the key and its text (it is the menu item); the dialog uses the new `cart_close_*` keys.

Run `StringParityTest` → green.

(Do this task's string additions **while** doing Tasks 3–6 — whenever a task uses a key, add it then. Task 7 is the checklist that all of them exist and that unused old keys are removed. Commit any leftovers as `chore(strings): cart and add-to-cart strings, remove unused quick-sell strings`.)

---

## Task 8 — Final verification

1. `grep -rn "horizontalScroll" app/src/main/java/com/shopledger/pos/ui/screens/items app/src/main/java/com/shopledger/pos/ui/screens/cart` → nothing.
2. `grep -rn "QuickSell\|quickSellActionId\|clearCounterCart" app/src` → nothing.
3. `grep -rnE 'Text\("[A-Za-z]' app/src/main/java` → nothing.
4. Full test suite → 0 failures (expect about 349 + ~30 new).
5. `.\gradlew.bat assembleDebug assembleRelease lintDebug --console=plain > build\final.log 2>&1` → `BUILD SUCCESSFUL`, 0 lint errors (warnings that existed before are fine; list any new warning).
6. `git ls-files --eol` on every changed file → `i/lf`.
7. Update `README.md` features: "Several customers at once: open carts are shown as chips on the home screen and in checkout; switch with one tap; carts are kept if the app closes." and "Tap an item to add sizes to the cart; all selling happens in the cart."
8. Manual check on a phone or emulator (write the result into the PR/commit description):
   - Fresh start → add 2 items to Cart 1 → chips appear above Sell; Sell reads "Sell · Cart 1".
   - Tap **New cart** → Cart 2 active → add 1 item → chips show "Cart 1 · 2 items", "Cart 2 · 1 item".
   - Open checkout → chips under the top bar → switch to Cart 1 → its 2 items show; type customer "Ramesh" → chip shows "Ramesh · 2 items".
   - Complete a Cash sale for Ramesh → that cart closes, Cart 2 remains and is active.
   - Add items to two carts → swipe the app away (kill) → reopen → both carts and items are back.
   - Tap an item with many sizes → sizes are a **vertical** list; no buyer / payment / discount fields; Add → stepper appears; **Go to cart** opens checkout.
   - Seller mode locked → no cost numbers on the add sheet. Hindi: switch language in More and repeat one round.
   - Rotate the phone with the add sheet open → it stays open on the same item.

**Commit:** `docs: README for multiple carts and add-to-cart sheet`

---

## 9. Things that must NOT change (reviewer checklist)

- Sale saving path, ledger, stock movements, khata, receipts, oversold warning, UPI QR, estimate sharing (now only from checkout ⋮).
- `checkoutActionId` is created with the cart, survives restarts (Task 2 saves it) and is only replaced by `clearCart()`.
- After a successful sale the paid cart is closed (or cleared if it was the only one) — existing behaviour in `completeSaleAndPrepareReceipt`.
- Order scan ("Scan list" in checkout ⋮) still fills the **active** cart.
- Seller lock rules everywhere.
- Hindi/English parity; 48 dp targets; works at 360 dp.
