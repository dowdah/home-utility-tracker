# Utility Tracker UX Contract

| Operation | Trigger | Feedback and recovery |
| --- | --- | --- |
| Sync | Pull down on Home or Records | Stable refresh indicator; success/error message; Room remains the rendered source. |
| Add/edit reading or tariff | Picker-based form | Local entity and outbox write atomically; queued work syncs when connected. |
| Delete reading or tariff | Overflow menu, then confirmation | Unsynced entity is removed locally; synced entity becomes a tombstone mutation. |
| Resolve conflict | Conflict center | Keep server discards local draft; override creates a new operation using server revision. |
| Switch endpoint | Card/radio selection | Health and backend identity validate before activation. |
| Export CSV | Export page | System document picker selects destination; export errors remain visible in-app. |

The application must preserve draft form values, filters, date ranges, and current navigation across configuration changes. Token values are masked, Keystore-backed, never shown in messages, and controlled only from the endpoint-page overflow menu.

## Remaining readings (approved correction, 2026-09-22)

Business source: the owner confirmed all meters record remaining quantities and approved the remaining-reading repair plan. `README.md` documents the calculation and top-up limitations; `data/Statistics.kt` owns the shared calculation.

| Capability | Canonical owner | Contract | Verification |
| --- | --- | --- | --- |
| Reading selection | `MeterChooser` / `AppViewModel.openNewReading` | A new form selects active ELECTRICITY by type; missing meters require explicit selection. Editing and restored drafts retain their meter. | Default-selection tests and device form check |
| Reading form | `ReadingEditor` / `remainingReadingIncreases` | Decreases save normally; warn on a chronological neighbouring increase, including backfills and edits. | Neighbour tests and device form check |
| Date range | Material 3 DateRangePicker / SavedStateHandle | Include intervals whose later reading is within the inclusive range; do not invent daily allocation. | Range boundary tests |
| Statistics | `statisticsForRange` / `StatisticsCard` | Share consumption/cost intervals. Distinguish unknown, zero, incomplete subtotal, and estimated cost. | StatisticsTest and device statistics check |
| Tariff recovery | Existing tariff-history route | Both absent and insufficient tariff coverage provide a settings action; Back returns to statistics with its range intact. | Device navigation check |

No existing reading is rewritten. Recharge-aware accounting remains a separate feature: observed increases are uncalculable intervals, and a top-up hidden within an overall decrease cannot be detected from balances alone.
