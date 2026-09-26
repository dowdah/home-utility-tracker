# V1.1 recharge and synchronization acceptance

Accepted on 2026-09-23 for the `codex/feature/v1-hardening-recharges` branch. The backend was deployed from `1464034c541be7c4779b595ce9504a7264683e04`; this report contains no credentials or household readings.

| Gate | Result | Evidence |
| --- | --- | --- |
| Backend protocol and migration | Passed | 18 Pytest cases, Ruff check/format. Schema 0001→0002 rehearsed on a SQLite online copy before production migration. |
| Android calculation and build | Passed | 25 unit tests; debug APK version 1.1 built and signature matched the prior AVD APKs. |
| Android device regression | Passed | 12 tests, zero failures/skips on an explicit AVD: Room 1→2, HTTP retries/conflicts, 100+ queued operations, linked recharge/reading, low-space pull, and form recovery. |
| Local two-device acceptance | Passed | Both AVDs completed recharge arithmetic, both conflict choices, water recharge without a fabricated reading, deletion propagation, restart recovery, export, and fixture cleanup. Temporary tokens revoked. |
| Localized UI and SAF | Passed | English and Chinese form rotation, year statistics and system CSV save on an isolated acceptance package. |
| Production preservation | Passed | The original meters, readings, tariffs, operations, tokens and change-log rows matched the pre-upgrade snapshot byte for byte. Backend instance identity remained unchanged. |
| Production HTTPS | Passed | Public TLS health responded 200, protected endpoints rejected unauthenticated calls, and an authenticated read-only status returned 200 without alerts. |
| Production two-device acceptance | Passed | Both AVDs completed the same live scenarios over HTTPS. All marked fixture readings/recharges were soft-deleted; temporary tokens revoked. |
| Backup and operations | Passed | A fresh online backup had a matching SHA-256 and `PRAGMA integrity_check=ok`; backend, dedicated tunnel, backup timer and monitor timer were active. Monitor reported no alerts. |
| Original AVD upgrades | Passed | Both installed apps upgraded in place to version 1.1 and Room schema 2; prior databases were retained. The AVD with a configured endpoint completed background sync. The other had no active endpoint and correctly showed an action-required status. |

This release requires sync protocol 2. Older clients receive 426 and keep their local queue until upgraded. Physical devices were outside this AVD acceptance and were not modified. The PR targets `main` for review; no merge or broader client rollout is part of this acceptance.
