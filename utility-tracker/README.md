# Utility Tracker Android

An offline-first household ledger for remaining electricity (kWh), cold water (t), and hot water (t). Version 1.1 adds explicit recharges, month/year statistics, persistent sync diagnostics and non-destructive database upgrades.

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

Protocol 2 is required on both client and server. An incompatible peer preserves the local queue and requests an upgrade. Room schema 1 upgrades to 2 with explicit migration and exported schemas; destructive migration is disabled. Per-installation tokens remain encrypted with Android Keystore and are not included in CSV or logs.

Every configured endpoint must identify the same backend instance. HTTP is permitted for local/LAN testing with a visible plaintext-token warning; production should use HTTPS. Health checks validate service/database availability, including read-only degraded operation. Activation authenticates `/api/v1/meta`; server operational details require `/api/v1/status` authentication.

## Build and repeatable verification

Use the repository Gradle wrapper, JDK 24 compilation toolchain, configured daemon JVM and Android SDK API 37. Android 15+ is required.

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

KAPT compatibility flags remain intentionally unchanged; migrating the build toolchain is a separate task. Never commit tokens, private device databases, exported personal ledgers or production credentials.
