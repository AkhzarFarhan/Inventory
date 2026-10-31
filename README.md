# LPS Hardware — Inventory & POS Android App

Ultra-lightweight, offline-first Android application built for **LPS Hardware** (`com.lpshardware.inventory`), optimized for smooth 60 FPS performance on low-RAM Android devices with a **Modern iOS (Cupertino) Light Theme**.

## Key Features
1. **Secret Coded Purchase Price (`CostCipherEngine` & `CostCipherGlyphs`)**:
   - Encodes purchase prices into the 10 geometric stroke symbols (`0–9`) from `IMG_20261031_222339.jpg` so buyers standing at the counter cannot read cost prices.
   - **Buyer-Safe Default**: Numeric purchase prices and profit margins (`₹` & `%`) are hidden by default and only revealed when the seller explicitly unlocks **Seller Mode** in the top bar.
2. **Smart Hardware Item Understanding & Multi-Variants (`SmartItemMatcher`)**:
   - Understands bilingual English + Hinglish hardware terms (`kabza` = `hinge`, `chatkani` = `tower bolt`, `pech` = `screw`, `toti` = `tap`), metric/imperial equivalences (`4"` = `100mm`), and phonetic typos.
   - Prevents duplicate item creation and offers 1-tap variant linking (`Size`, `Material`, `Color/Finish`).
3. **Alphabetical Inventory & Search History**:
   - Strict `A–Z` alphabetical sorting with a right-edge **iOS Alphabet Scrubber**, Low-Stock filter with 1-tap WhatsApp supplier reorder sharing, and persistent **Search History** (recent & frequent searches).
4. **Sales History with `₹` Margin, `%` Gain & WhatsApp Image Receipts**:
   - Collapsible `Year -> Month -> Day` sale history with Indian Rupee (`₹`) formatting, bargaining price override at sale time, 1-tap Sale Return (*Wapsi*), and automatic **Sale Receipt PNG Image generation** sharable directly to the buyer's WhatsApp phone number.
5. **Offline-First Room SQLite + Firebase Cloud Firestore Sync**:
   - 100% offline Room SQLite (WAL mode) primary database with background `WorkManager` delta sync to Firebase Cloud Firestore + local JSON backup export/restore.
