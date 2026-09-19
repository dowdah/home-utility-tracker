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

The `ops/` units are systemd templates. Install the checked-out project at `/opt/utility-sync` (or consistently replace that path), create `/etc/utility-sync/environment` with private paths, and have an administrator run `uv sync --frozen --no-dev` before enabling the service.

The service deliberately listens on loopback only. The unit uses `uv run --frozen --no-sync`, so it starts only the already-installed environment and performs an idempotent migration. Confirm that `127.0.0.1:8088` is unused before installation. Reverse tunneling, web-server configuration, DNS, TLS, and token issuance are separate deployment decisions.

`utility-sync-backup.timer` creates an online SQLite backup only when at least 256 MiB is free. Published backups have a 128 MiB total budget, retain 14 daily and 12 monthly copies, and are pruned only by exact filenames after a verified new backup is published. Use `utility-sync prune-backups` to preview candidates; add `--apply` only when removal is intended.

## Contributing

Keep API, schema, migration, and test changes aligned. Run the verification commands above before opening a pull request, and never commit service tokens, private environment files, exports, or backup data.
