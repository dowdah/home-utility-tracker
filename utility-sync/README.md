# Utility Sync

`utility-sync` is the Python 3.13/FastAPI authority for the offline-first utility-meter app. It uses SQLite rollback journaling and a single Uvicorn worker; no Docker or Compose stack is involved.

## Local development

```sh
uv sync --all-groups
uv run utility-sync migrate
uv run utility-sync issue-token
uv run uvicorn utility_sync.api:app --host 127.0.0.1 --port 8088 --workers 1
```

The default local data root is `utility-sync/.local-data/`. Set the variables in `.env.example` for a service installation. Data, exports, backups, and configuration must be owned by the dedicated service account and must not be SMB writable.

Run verification with:

```sh
uv run ruff check .
uv run pytest
```

## Deployment boundary

The `ops/` units are templates. Install the checked-out project at `/opt/utility-sync` (or consistently replace that path), create `/etc/utility-sync/environment` with the private paths, and have an administrator run `uv sync --frozen --no-dev` before enabling the service. The unit deliberately uses `uv run --frozen --no-sync`: it only runs the already-installed environment, then performs an idempotent migration. Re-check that `127.0.0.1:8088` is unused. The service is deliberately loopback-only. Pi/ECS systemd installation, SSH reverse tunneling, Nginx, DNS, TLS, and token issuance remain separately authorized deployment steps.

`utility-sync-backup.timer` creates an online SQLite backup only when its filesystem has at least 256 MiB free. Published backups have a 128 MiB total budget, retain 14 daily plus 12 monthly copies, and are pruned only by exact filenames after a verified new backup is published. `prune-backups` remains available to preview or manually apply the same policy.
