# Utility Tracker Android

An offline-first household ledger for remaining electricity (kWh), cold water (t), and hot water (t). Version 1.3 adds separate balance forecasts and local daily reminders, alongside backup/restore protection, offline CSV exports and cross-device synchronization.

## Reading and recharge accounting

Record remaining quantities, not cumulative meter counters. A recharge records the amount paid in CNY, the actual purchase price per unit, its credit time and an optional note. Credited quantity is stored as `amount / purchase price`, rounded to 12 decimal places using HALF_EVEN. Later tariff edits never rewrite purchase snapshots.

Electricity recharges may also include the post-credit remaining reading. Both records are saved locally and synchronized as one atomic group. Water recharges need no invented balance: record the amount and price, then wait for the next actual reading. Editing or deleting a recharge does not delete a real reading.

For each meter, consumption is `previous balance + recharges in (previous reading time, current reading time] - current balance`. A reading at exactly the credit time is the post-credit balance. Record every recharge, including those hidden by an overall decrease. The app cannot infer missing recharges. Negative results are unknown/incomplete, not zero. Without a subsequent reading, a recharge establishes spending and credited quantity only.

Consumption and its cost follow the later reading date. Pricing requires tariff coverage at the beginning and uses the rate effective at the end; rate changes are explicitly estimated. Actual recharge spending follows the credit date and is a separate figure, never added to consumption cost. All calculations use Decimal; floating point is limited to drawing chart geometry.

## Interface

- Material 3, dynamic colors and system light/dark modes; English and Chinese resources.
- Portrait bottom navigation and landscape rail. Drafts, filters and selected periods survive configuration changes.
- Home shows actual latest readings, age, monthly consumption/cost/spending, pending changes, conflicts and last successful synchronization.
- Records offers readings/recharges and per-meter filters. Forms use the shared meter selector and Material date/time pickers; errors preserve entered values.
- Statistics offers month, year and custom ranges. Month/custom consumption charts connect each interval's average per elapsed 24-hour day at its later reading date; the line is a trend, not actual daily use. Yearly consumption uses monthly bars. Each meter also shows daily remaining quantities: the last reading on a reading day is measured, and unmeasured days are labeled linear day-end estimates only between two readings, accounting for recorded recharge times. No balance is projected beyond the latest reading. Text details accompany both charts; unknown values remain gaps.
- Settings manages tariff history, conflicts, backend endpoints and SAF exports of readings, recharges or tariffs.

## Synchronization and data protection

Room is the UI source. Entity changes and outbox operations are committed in one transaction. A mutex serializes manual/WorkManager sync, while per-entity operation chains preserve edits made during an in-flight request. Sent operation IDs and contents remain immutable across retries. Acknowledgements, changes and cursor updates commit together.

A real conflict preserves the latest local draft and refreshes the authoritative server snapshot. Keep-server and override-server resolve complete linked groups; overrides use new IDs and current base revisions. `batch_aborted` remains queued. Low server space retains local work while permitting pulls. Authentication, validation, version and identity problems remain visible instead of retrying blindly.

Protocol 2 is required on both client and server. An incompatible peer preserves the local queue and requests an upgrade. Room schemas 1 and 2 upgrade to 3 with explicit migrations and exported schemas; destructive migration is disabled. Per-installation tokens remain encrypted with Android Keystore and are not included in CSV or logs.

Every configured endpoint must identify the same backend instance. HTTP is permitted for local/LAN testing with a visible plaintext-token warning; production should use HTTPS. Health checks validate service/database availability, including read-only degraded operation. Activation authenticates `/api/v1/meta`; server operational details require `/api/v1/status` authentication.

## Build and repeatable verification

Use the repository Gradle wrapper, JDK 25 for Gradle and compilation, JVM 24 bytecode targets, configured daemon JVM and Android SDK API 37. Android 15+ is required.

```sh
./gradlew :app:testAcceptanceUnitTest :app:assembleDebug :app:assembleAcceptanceAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedAcceptanceAndroidTest
```

Connected tests require an explicit serial so they cannot silently select all attached personal devices. The `.acceptance` application is isolated from the installed user's app. Tests cover Room migration, real HTTP failure/retry behavior, concurrent edits, groups, low space and Compose form recovery. Live two-device/locale/SAF tests are opt-in, not silently counted as covered by a smoke test.

From the repository root, run temporary-backend acceptance using:

```sh
utility-sync/.venv/bin/python tools/live_acceptance.py --mode local
```

Production acceptance additionally requires explicit `--mode production --endpoint <https-base> --ssh-host <pi-host>`. It issues temporary per-device tokens, uses uniquely marked synthetic records, verifies both conflict choices and force-stop recovery, soft-deletes only its own records, and revokes its tokens. Credentials remain in process memory and Android Keystore. It never resets a database. Each run uses a separate application namespace.

`tools/deploy_backend.py` stages a committed backend revision, rehearses additive migration on an online SQLite copy, preserves original-row hashes and old source, and verifies backup/monitor tasks. Read its arguments and the acceptance report before using it on a live host. After new writes, rollback must preserve the current ledger and prefer a forward fix.

The build uses AGP built-in Kotlin and its new DSL with KSP 2.3.6, Hilt 2.59.2 and Kotlin/Compose/serialization plugins 2.2.21. The old KAPT and AGP compatibility flags are removed. Room schema 3 adds local reminder settings; both 1→2→3 and 2→3 migrations preserve the ledger. Never commit tokens, private device databases, exported personal ledgers or production credentials.

## Android backup and restore (1.2)

Cloud backup is permitted only when the Android transport has client-side encryption. Device-to-device transfer is also supported. Both use an explicit allowlist containing only `utility-tracker.db`: ledger rows, endpoints, backend identity/cursor, pending operation chains and conflicts are retained. The backup agent completes a WAL checkpoint and closes its connection before framework backup; a failed checkpoint aborts the backup. Tokens, the per-installation identity, WorkManager data, cache and logs are excluded.

A normal signed APK upgrade retains the token and migrates the existing installation identity into `noBackupFilesDir`. Restore removes historical credential preferences too, creates a new installation identity and clears cached health/success information. Re-enter a token for the same backend to resume the retained queue. Operation IDs and linked groups are never regenerated during restore.

For local transport acceptance (emulated encryption/D2D flags, not a Google cloud upload):

```sh
utility-sync/.venv/bin/python tools/backup_acceptance.py --serial emulator-5554
```

Run this from the repository root. It builds a separate `.acceptancebackup` package, exercises upgrade and actual `bmgr` backup/restore, and restores the AVD's transport, enabled state and transport parameters even after failure. Only this isolated package and its own backup are cleared.

## Offline exports and background updates (1.2)

The export screen defaults to a local Room snapshot and also offers the existing authenticated server snapshot. Local exports include unsynchronized additions/edits, tombstones and conflict drafts, even without network or credentials. They retain each server CSV's business columns and append `sync_status` (`synced`, `pending`, `conflict`; conflicts take precedence). All rows and flags are captured in one transaction before writing UTF-8/RFC-style quoted CSV. CSV is a record copy, not an application/queue restore format.

The chosen source/type is frozen while the system document picker is open and survives recreation. Controls are disabled during selection/writing; cancellation and write failures are visible. Partial files are removed when the document provider supports removal. The server option warns when local pending work or conflicts may be absent from its snapshot.

One unique, network-constrained WorkManager job schedules a periodic pull every 15 minutes, with a 15-minute initial delay; Android can delay execution. Registration uses UPDATE and preserves job identity. Opening/returning to the app triggers an immediate pass, with repeated foreground events debounced for 60 seconds. Saving records, activating endpoints, configuring tokens and manual refresh trigger immediate work. Missing endpoint/token configuration produces a visible prompt without network requests. All paths share the repository mutex and retain immutable retries and grouped conflicts.

`tools/live_acceptance.py --mode local --include-periodic` adds a real timer observation to the two-device ledger acceptance. It creates a record on A and verifies B receives it while backgrounded through a delayed periodic worker, without a manual pull.

## Balance forecasts and local reminders (1.3)

Home keeps actual reading values/timestamps and displays a separate estimated current balance and days remaining. Forecasts never create readings or change historical charts, consumption totals or exports. Electricity uses approximately 30 days of complete intervals ending at the latest reading, with at least 24 hours of history; water uses 180 days, with at least seven days. The full boundary interval is retained. Mean use is total recharge-adjusted consumption divided by total elapsed time, not an average of interval rates. The estimate adds subsequent recorded recharges and subtracts projected consumption since the last reading.

Older invalid intervals stop the usable continuous history. The latest invalid interval, ambiguous equal-time readings, future readings and unresolved reading/recharge conflicts block the affected meter. Pending local work is included. Electricity readings are marked old after seven days and expire after 30; water readings are marked old after 30 days and expire after 90. Recharges never reset actual-reading age. Zero observed use has no finite days estimate; valid quantity thresholds still work. Exhaustion is explicitly a projection requiring verification.

Settings → Balance reminders enables phone notifications and requests Android permission only on an explicit user action. Each meter defaults to a seven-day threshold, with an optional non-negative quantity threshold; either can trigger a reminder. The daily time defaults to 15:00 and is editable. All low meters share one notification per device-local calendar day. Repeat evaluations silently update an existing notification; dismissing it does not cause another notification that day. Recovery removes the affected meter. Notification permission denial does not disable forecasts or settings.

An independent network-unconstrained hourly WorkManager task and a delayed check for the selected time evaluate local snapshots, including while offline. Foreground entry, ledger/conflict changes and settings changes also trigger evaluation. Android can delay execution; a force-stopped app must be reopened. This is not an exact alarm or a server push service. Thresholds/time are local Room settings included in ledger backups; notification opt-in and recent notified dates live in noBackupFilesDir. Restore requires opting in again. Settings and deduplication do not sync across devices.

```sh
# Explicit AVD only; installs/removes a separate acceptance package and restores network state.
python3 tools/reminder_acceptance.py --serial emulator-5554
```

Run the command from the repository root. It verifies denied notification permission, real background delivery with airplane mode enabled and Wi-Fi disabled, silent updates, dismissal and process restart. The synthetic test time is near-future; production defaults remain 15:00.
