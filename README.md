# LPS Hardware — Inventory & POS Android App

Ultra-lightweight, offline-first Android application built for **LPS Hardware** (`com.lpshardware.inventory`), optimized for smooth 60 FPS performance on low-RAM Android devices with a **Material 3 theme** (brand green, automatic light and dark mode) and an **English / Hindi** interface.

## Key Features
1. **Secret Coded Purchase Price (`CostCipherEngine` & `CostCipherGlyphs`)**:
   - Encodes purchase prices into the 10 geometric stroke symbols (`0–9`) from `IMG_20261031_222339.jpg` so buyers standing at the counter cannot read cost prices.
   - **Buyer-Safe Default**: Numeric purchase prices and profit margins (`₹` & `%`) are hidden by default and only revealed when the seller explicitly unlocks **Seller Mode** in the top bar.
2. **Smart Hardware Item Understanding & Multi-Variants (`SmartItemMatcher`)**:
   - Understands bilingual English + Hinglish hardware terms (`kabza` = `hinge`, `chatkani` = `tower bolt`, `pech` = `screw`, `toti` = `tap`), metric/imperial equivalences (`4"` = `100mm`), and phonetic typos.
   - Prevents duplicate item creation and offers 1-tap variant linking (`Size`, `Material`, `Color/Finish`).
3. **Sell and Stock In from the home screen**:
   - The home screen opens on **Sell** and **Stock In**, with an A-Z item list, search (including Hinglish synonyms), a Low-Stock filter with 1-tap WhatsApp supplier reorder sharing, and persistent **Search History**.
   - **Stock In by scanning**: photograph a printed supplier bill or a handwritten list; the on-device reader turns it into lines you review, fix and save. Saving adds stock and updates the supplier's Khata in one step. Items can also be entered by hand.
   - **One-screen checkout**: pick items, set the price, choose Cash, UPI or Udhar and share the receipt. Multi-cart, estimates, returns and barcode scan are all still there.
4. **Sales History with `₹` Margin, `%` Gain & WhatsApp Image Receipts**:
   - Collapsible `Year -> Month -> Day` sale history with Indian Rupee (`₹`) formatting, bargaining price override at sale time, 1-tap Sale Return (*Wapsi*), and automatic **Sale Receipt PNG Image generation** sharable directly to the buyer's WhatsApp phone number.
5. **Offline-First Ledger Database with Backups**:
   - Room SQLite (WAL) where stock and Khata balances are calculated from append-only ledgers, every sale/return/payment is one atomic, double-tap-safe transaction, and returns are GST-style credit notes.
   - Nightly encrypted on-device backups (last 7) plus shareable backup files for Google Drive or a new phone. Restore stages the file and restarts the app, with automatic rollback if the restored data fails its health check; a "Restore last night's backup" action is in the Cloud Sync & Backup sheet. Multi-device cloud sync arrives in the next release.

6. **English / Hindi, simple screens, and a first-run wizard**:
   - Every screen is available in English and Hindi (switch in **More**); text is in plain language with large touch targets, TalkBack labels and a Material 3 look that follows the phone's light or dark setting.
   - A **setup wizard** on first launch asks for the shop name, shop type and an optional owner PIN, and can add sample items. Existing installs keep their data and skip it.
