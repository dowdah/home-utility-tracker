# Utility Sync

Utility Sync is the Python 3.13/FastAPI backend for Home Utility Tracker. It provides authenticated synchronization, meter metadata, readings and tariff changes, CSV exports, and local operational commands for an offline-first utility-meter workflow.

The service uses SQLite rollback journaling and one Uvicorn worker. It is designed to run as a native Python service rather than in Docker or Compose.

## Requirements

- Python 3.13
- `uv`

## Local development

```sh
uv sync --all-groups
uv run utility-sync migrate
uv run utility-sync issue-token
uv run uvicorn utility_sync.api:app --host 127.0.0.1 --port 8088 --workers 1
```

The default local data root is `.local-data/`. For a service installation, configure the variables from `.env.example` in a private environment file. Data, exports, backups, and configuration must be owned by the dedicated service account and must not be SMB writable.

## Verification

```sh
uv run ruff check .
uv run pytest
```

## Deployment boundary

The `ops/` units are systemd templates. Install the checked-out project at `/opt/utility-sync` (or consistently replace that path), copy `ops/utility-sync.environment` to `/etc/utility-sync/environment` with mode `0640 root:utility-sync`, and have an administrator run `uv sync --frozen --no-dev` before enabling the service. The tracked template contains only paths and capacity thresholds; it must never contain tokens or private keys.

The service deliberately listens on loopback only. The unit uses `uv run --frozen --no-sync`, so it starts only the already-installed environment and performs an idempotent migration. Confirm that `127.0.0.1:8088` is unused before installation.

For the production ECS reverse-SSH topology, install `ops/utility-sync-tunnel.service` after creating a restricted `utility-tunnel` account on the ECS. It exposes only the Pi loopback listener through ECS loopback port `18089`; it requires a root-owned private key and pinned ECS host key in `/etc/utility-sync/`. Nginx, DNS, TLS, and token issuance remain separate deployment decisions.

`utility-sync-backup.timer` creates an online SQLite backup only when at least 256 MiB is free. Published backups have a 128 MiB total budget, retain 14 daily and 12 monthly copies, and are pruned only by exact filenames after a verified new backup is published. Use `utility-sync prune-backups` to preview candidates; add `--apply` only when removal is intended.

## Contributing

Keep API, schema, migration, and test changes aligned. Run the verification commands above before opening a pull request, and never commit service tokens, private environment files, exports, or backup data.

## Recharge protocol (schema 0002)

Clients must send `client_protocol_version: 2` to `/api/v1/sync`; older requests receive 426 without advancing a cursor. `/meta` advertises the minimum protocol and recharge/group features. Upgrade all clients before using recharge accounting.

A `recharge` mutation carries `meter_id`, positive decimal-string `amount_decimal` and `unit_price_decimal`, `quantity_decimal` (amount / price, scale 12, HALF_EVEN), `currency=CNY`, UTC `credited_at`, and optional `note`. Amount and purchase price are historical snapshots. A recharge and optional post-credit electricity reading share `group_id` and `group_size=2`; submit all members together, including on retries. Group members cannot be changed after submission.

`batch_aborted` operations remain queued with their original IDs. A real conflict is replayed as `conflict`, never a successful duplicate. `group_conflict` requires one decision for the entire group. Accepted retries return `duplicate`. Operation IDs cannot be reused with different content.

Existing readings CSV columns are unchanged; `/api/v1/exports/recharges.csv` and `/api/v1/exports/tariffs.csv` add the remaining ledger. Authenticated `/api/v1/status` exposes disk, backup and monitor alerts. Public `/healthz` exposes only service/database/write readiness. Install the monitor service/timer to check every five minutes; backup age over 36 hours is actionable. No external notifications are sent.

Schema upgrades preserve meters, instance identity, tokens, readings, tariffs, operation history and revisions. Take and restore-verify a backup before upgrade. After new writes, do not roll back by restoring an older database: retain current data and forward-fix.
