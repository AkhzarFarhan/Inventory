# Principal Android Architecture Plan: LPS Hardware Inventory & POS

See the full interactive architectural blueprint created for this repository.

## Summary of Modules (`com.lpshardware.inventory`)
1. **`core/cipher/CostCipherEngine`**: Encodes purchase prices (stored in paise `Long`) into custom vector glyphs so customers at the counter cannot read cost prices.
2. **`core/nlp/SmartItemMatcher`**: Offline semantic, dimensional, and Hinglish/English hardware ontology matcher that detects duplicate items vs. new variants (`Size`, `Material`, `Color/Finish`).
3. **`data/local/LpsDatabase`**: Offline-first Room SQLite database (WAL mode) with `InventoryItemEntity`, `ItemVariantEntity`, `SaleRecordEntity`, and `SearchHistoryEntity`.
4. **`core/sync/FirestoreDeltaSyncWorker`**: Low-RAM batched delta synchronization with Firebase Cloud Firestore (Free Spark Tier) via `WorkManager`.
5. **`ui/`**: Modern iOS (Cupertino) Light Theme over Jetpack Compose with A–Z Alphabet Scrubber, collapsible `Year -> Month -> Day` Sale History with `₹` Margin & `%` Gain metrics, and Counter Privacy Shield.
