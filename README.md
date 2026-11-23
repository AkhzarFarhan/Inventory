# ShopLedger POS — Inventory, Khata & Counter POS Android App

Ultra-lightweight, offline-first multi-shop Android application (`com.shopledger.pos`), optimized for smooth 60 FPS performance on low-RAM Android devices with a **Material 3 theme** (brand green, automatic light and dark mode) and a bilingual **English / Hindi** interface.

## Key Features
1. **Seller-Safe Secret Coded Purchase Price (`CostCipherEngine` & `CostCipherGlyphs`)**:
   - Encodes purchase prices into customizable 10-symbol cipher codes (or hidden mode) so buyers at the counter cannot read cost prices.
   - **Buyer-Safe Default**: Numeric purchase prices, net margins (`₹` & `%`), and Shop Margin Breakdown are hidden by default and only revealed when the seller explicitly unlocks **Seller Mode** in the top bar.
   - **Live Counter Preview & Progressive Item Entry (`AddEditItemSheet`)**: Shows a real-time buyer-safe / Seller-Mode counter preview card while adding or editing items, keeping common fields upfront and tucking optional dimensions, unit, and barcode behind an expand toggle.
2. **Fast Counter Catalog, Inline Stepper & Multi-Cart (`ItemsHomeScreen` & `CartSwitcher`)**:
   - Compact counter header with **Voice Search** (`Mic`), Hinglish/English fuzzy search (`SmartItemMatcher`), flowing `Top sold`, `Low Stock`, and category filter chips, plus an A–Z quick-jump index for 25+ item catalogs.
   - **Inline `- qty +` Stepper** directly on single-variant cards, smart fraction/pack **Quantity Presets** in `AddToCartSheet`, and **"N in cart"** badges for multi-variant items.
   - **Multi-Cart Counter Workflow**: Switch between concurrent customer carts on home or checkout with item count & live subtotal chips, or long-press any cart chip to rename, duplicate, clear, or close.
3. **One-Screen Checkout, Bargaining & Live Counter UPI QR (`CheckoutScreen` & `ReceiptShareDialog`)**:
   - Voice search & barcode scanning right in checkout, customer picker with existing **Udhar** & live **"After this bill"** balance preview, and 1-tap **"Last sold at ₹X"** price match pills per buyer.
   - **1-Tap Round-Off & Target Total**: Bargaining chips (`-₹5`, `-₹10`, round to nearest ₹10/₹50), tappable Grand Total dialog, and Seller-Mode margin safety pills (`Gain` / `At cost` / `Loss`) with sticky 1-line bottom summary.
   - **Live Counter UPI QR**: Renders a live scannable UPI QR card on receipt confirmation (auto-expanded for UPI bills, toggleable for Cash/Udhar) with a brightness-boosted **Full-Screen QR** display for customers.
4. **Daily Galla Summary, Business Calendar, Udhar Khata & Distributors (`BillsTab`, `KhataTab` & `DistributorDetailScreen`)**:
   - Collapsible `Year -> Month -> Day` sales & credit-note (*Wapsi*) history with a live **Daily Galla Breakdown Card** (Cash, UPI, Udhar Given, Khata Jama, Net Cash in Drawer) and Seller-Lock-safe WhatsApp closing report sharing.
   - **Calendar in Bills**: sale, Nagad, UPI, Udhaar and stock-in for every day of the month.
   - **Actionable Udhar Khata**: Priority overdue/due indicators, sort by highest balance or most overdue, clickable party statement preview, and 1-tap crisp **WhatsApp Party Statement PNG** export.
   - **Distributors**: account per distributor (Udhaar and Nagad/UPI payments), every purchase bill, which items came from which distributor, and the last distributor on each item.
5. **Stock In Scanner & Offline-First Ledger Database with Backups**:
   - Room SQLite (WAL) multi-tenant ledger where stock and Khata balances come from append-only logs and every sale, return, purchase, or payment runs atomically.
   - Photograph printed supplier bills or handwritten lists for on-device extraction into Stock In / Khata lines; nightly encrypted on-device backups (last 7) plus shareable backup files and health-checked restore.

6. **English / Hindi, TalkBack Accessibility & First-Run Setup Wizard**:
   - Every screen is localized in **English** and **Hindi** (parity-tested, switchable in **More**) with large touch targets and TalkBack labels, plus a **setup wizard** on first launch for shop profile, custom coded price mode, optional owner PIN, and sample catalog.
