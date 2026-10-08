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
| Date range | Material 3 DateRangePicker / SavedStateHandle | Include intervals whose later reading is within the inclusive range. Official consumption totals never allocate use to calendar days; the separate daily balance chart labels its bounded interpolation as an estimate. | Range boundary tests |
| Statistics | `statisticsForRange` / `StatisticsCard` | Share consumption/cost intervals. Distinguish unknown, zero, incomplete subtotal, and estimated cost. | StatisticsTest and device statistics check |
| Tariff recovery | Existing tariff-history route | Both absent and insufficient tariff coverage provide a settings action; Back returns to statistics with its range intact. | Device navigation check |

No existing reading is rewritten. Recharge-aware accounting remains a separate feature: observed increases are uncalculable intervals, and a top-up hidden within an overall decrease cannot be detected from balances alone.

## Recharge ledger and synchronization protocol 2

| Capability | Canonical owner | Contract | Verification |
| --- | --- | --- | --- |
| Recharge form | `RechargeEditor`, shared `MeterChooser` and `DateTimeField` | Save amount, purchase-price snapshot and scale-12 HALF_EVEN quantity; optional electricity post-credit reading is one atomic group. Retain draft on validation failure. | RechargeFormTest, live two-device acceptance |
| Recharge calculation | `statisticsForRange` | Previous balance + credits in `(previous, current]` - current balance. Spending follows credit date separately. Never infer missing credits or current balances. | RechargeStatisticsTest |
| Statistics | `StatisticsScreen`, `SummaryValues`, `IntervalAverageTrend`, `DailyRemainingTrend`, `ConsumptionTrend` | Month/custom show interval average points on later reading dates as a line, breaking at unknown intervals; year consumption uses monthly bars. Daily remaining appears in all range modes per meter, using the last actual reading on reading days and labeled day-end estimates only between bounding readings. Recharge times enter the estimate exactly; unknown, zero-duration, negative estimates and days after the latest reading are gaps. Text details distinguish sources and retain each meter's unit. | Interval and daily remaining unit tests, device chart inspection |
| Sync recovery | `BackendRepository` | One mutex for manual/worker sync; immutable sent operations, per-entity successors, complete atomic groups, visible replayed conflicts. | LedgerRepositoryTest |
| Data upgrade | `MIGRATION_1_2` | Preserve readings, outbox, conflicts, cursor and endpoint identity; never destructively recreate Room. | DatabaseMigrationTest |
| Operational status | `PersistentSyncSummary` | Persist success/error/status, show alert freshness and unresolved conflicts. Old cached status does not imply current health. | Live status acceptance |

Default date/time controls remain Material pickers. Form quantities and monetary values use Decimal; Float is permitted only for chart geometry. New recharge records default to electricity; water recharge does not fabricate a reading. Editing or deleting a recharge never deletes its linked real reading.

## V1.2 data protection and updates

| Behavior | Contract | Verification |
| --- | --- | --- |
| Backup/restore | Encrypted cloud or D2D transport; closed/checkpointed ledger only. Preserve queue/group/conflict/cursor; credentials and device identity are excluded. Restores request a token and clear cached health. | BackupRecoveryTest, tools/backup_acceptance.py on both AVDs |
| Offline CSV | Default local snapshot includes tombstones, pending work and conflict drafts; sync_status is explicit. Server source retains its contract and warns about excluded local work. Freeze picker arguments across recreation, show cancel/failure, prevent duplicate export. | CsvSnapshotTest, OfflineExportTest, ExportUiAcceptanceTest |
| Cross-device updates | Unique 15-minute network-constrained periodic work, immediate foreground/save/configuration refresh, 60-second foreground debounce. Scheduling may be delayed; stale status never proves current health. | WorkSchedulerTest, opt-in real periodic live acceptance |

## V1.3 forecasts and reminders

| Behavior | Contract | Verification |
| --- | --- | --- |
| Forecast | Separate from actual readings and historical statistics. Weighted complete recharge-aware intervals, electricity/water windows and age limits, independent per-meter uncertainty/conflict handling. | BalanceForecastTest, ForecastRepositoryTest, ForecastCardTest |
| Reminder preferences | Device-local Room schema 3; default 15:00, seven days, optional quantity threshold. Explicit notification permission; rejected permission leaves forecasts/settings usable. | Migration tests, ReminderSettingsTest |
| Notification | One combined alert per local date, silent existing-notification updates, no repost after dismissal, cancel on recovery or invalid forecasts. No network constraint; Android scheduling may be delayed. | ReminderPolicyTest, ReminderRuntimeTest, real offline reminder harness |
| Restore | Preserve threshold/time in the backed-up ledger; clear notification opt-in and daily state alongside credentials/installation identity. | BackupRecoveryTest, actual local-transport backup acceptance |
| Conflict display | A server tombstone is shown as deleted even when its old business value is present. Missing server entity is unavailable; local tombstone is a deletion draft. | ConflictCardTest in Chinese and English |

## V1.3.1 statistics and navigation

| Behavior | Contract | Verification |
| --- | --- | --- |
| Range presets | Six wrapping choices. Last30/183 include today through now; all-time has no lower bound and ignores future records. Custom cancellation preserves the prior mode. Keep preceding baselines and later-reading interval attribution. | StatisticsRangeTest, StatisticsScreenChartsTest |
| Statistics order | Electricity, cold water, hot water on statistics only; DAO and other UI order unchanged. | Pure ordering and rendered-card tests |
| Long histories | Background, cached statistics; indexed Decimal calculations preserve existing results. Interval canvas <=2400dp, collapsed 240dp lazy details remain selectable through the final item. | StatisticsIndexTest oracle/ten-year fixture, interval UI tests |
| Predictive back | One root NavHost; each destination owns its complete layout. Main preview/settled chrome and menu bounds match, preserving tab/range/drafts and notification-home behavior. | PredictiveBackTest controlled progress, NavigationGestureAcceptanceTest real OS touches |

## V1.3.2 saved reading visibility and identity

| Behavior | Contract | Verification |
| --- | --- | --- |
| New reading | Freeze the submitted draft; atomic local save returns its ID. Wait for the Room visible list, reveal the exact ID (including backfills/ties), and highlight for about 2 seconds with localized accessibility state. | ReadingRevealTest actual form and viewport assertions |
| Filters and scrolling | Keep All or a matching meter; switch an incompatible filter to the saved meter. Empty state uses the same filtered list. Stable keys remain; edits, deletions, sync acknowledgements and remote additions do not request a reveal. | ReadingRevealTest filters/empty/remote/edit/delete |
| Recovery | Preserve failed input without requesting a reveal. Store an unconsumed request in SavedStateHandle; consume only its matching ID once visible. Form and visible list survive Activity recreation. | ReadingRevealTest delayed transaction/new ViewModel; ReadingActivityTest full Activity |
| App icon | Original droplet with transparent lightning, light/dark color resources, adaptive foreground/background, dedicated monochrome and density fallbacks. Splash inherits the mark. Actual themed coloring is launcher-dependent. | LauncherIconTest resource rendering and separate launcher inspection |
