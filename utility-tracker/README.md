# Utility Tracker Android

Utility Tracker is the offline-first Android client for the Home Utility Tracker system. It records remaining electricity, cold-water, and hot-water readings locally, then synchronizes them with `utility-sync` when a configured endpoint is reachable.

## V1 capabilities

- Material 3 Compose UI with system dark mode and Android dynamic colors.
- English default resources and Chinese `values-zh` resources.
- Portrait bottom navigation and landscape navigation rail/card grid. Reading/tariff drafts, filters, and date ranges are held in `SavedStateHandle`, so rotation and process recreation retain the visible work.
- Room-backed meter, reading, tariff, outbox, conflict, endpoint, and sync-state storage. UI reads local flows rather than direct network responses.
- A managed list of backend URLs. Each URL is checked through unauthenticated `/healthz` before saving; enabling an endpoint then verifies `/api/v1/meta` against the single expected `backend_instance_id`.
- Per-installation API token encrypted with an Android Keystore AES-GCM key. Tokens are not stored in Room or logged.
- A single named, network-constrained WorkManager chain. It uses Hilt's worker factory, appends a follow-up pass for mutations created during a running sync, and is re-enqueued at application start after a force-stop.
- Pull-to-refresh on Home and Records; picker-based reading/tariff timestamps; record and tariff edit/tombstone flows; conflict resolution; and Storage Access Framework CSV export.

## Remaining readings and statistics

All meters record remaining balances. Consumption is the previous remaining reading minus the next reading, grouped by meter and attributed to the later reading date. New readings default to the active electricity meter; editing and restored drafts retain their selected meter.

A decrease is normal. An increase may indicate a top-up or an input error: that interval is excluded, known consumption is labeled incomplete, and total cost is unavailable. Top-up amounts cannot be inferred from two balances, including decreases that conceal a top-up; full top-up accounting is outside this version.

No usable interval displays an unknown value, not zero. Missing or incomplete tariff coverage also displays an unknown cost with a link to tariff settings. Pricing requires a tariff at the first reading and uses the rate effective at the later reading, following the existing end-of-interval convention. Rate changes between those readings are explicitly labeled estimates; zero is shown only for a calculable zero. Existing records, IDs, sync payloads and database schema are unchanged.

## Endpoint security

Both `http://` and `https://` URLs are accepted so local and LAN development can use the backend's loopback/LAN path. HTTP sends the Bearer token without transport encryption and is displayed as a persistent warning in the app. Production use must select the ECS HTTPS endpoint after its reverse SSH, Nginx, DNS, and certificate deployment is complete.

An endpoint must return `200`, `status: "ok"`, `database: "open"`, and `writes_enabled: true` from `/healthz` before it can be added or edited. Health status refreshes whenever the endpoint list opens or the user requests refresh. Health does not prove authorization; the authenticated `/api/v1/meta` check is required before an endpoint can become active.

## Development

Requirements: JDK 24 (the current AGP 9 compatibility configuration), Android SDK API 37, and a connected Android 15+ device or emulator.

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:test
./gradlew :app:assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

The app module presently uses the AGP 9 Kotlin compatibility flags because Room and Hilt annotation processing use KAPT. Migrate both processors to KSP before removing those flags in a future build-tooling update.

## Data and sync boundaries

The backend remains authoritative for `server_revision`. New local changes use stable outbox operation IDs, and the client writes mutation acknowledgement, pulled changes, and cursor progress together. A conflict is moved out of the outbox into the conflict center; keeping the server discards every stale conflict for that entity, while overriding creates a fresh operation against the server revision. Network failures retry through WorkManager; authorization, validation, low-space, identity, and conflict failures are shown rather than retried blindly.

## V1 acceptance baseline

The 2026-09-22 V1 acceptance used two AVDs against the production HTTPS endpoint. It covered offline outbox recovery across force-stop, background Worker recovery, concurrent conflict resolution using both choices, reading and tariff tombstones, Storage Access Framework CSV export, Chinese resources, portrait/landscape draft restoration, and a clean production reset. The reset was preceded by an online SQLite backup with integrity and restore-count verification; it touched only `utility-sync`, its dedicated tunnel, and the exact SQLite database/journal. The final production database has its three seeded meters, no readings or tariffs, and one Pixel 6 Pro AVD token.

Never commit API tokens, endpoint credentials, local Room databases, exported CSV files, or device data.
