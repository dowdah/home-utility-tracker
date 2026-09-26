from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from alembic.config import Config

from alembic import command

from .auth import issue_token, revoke_token
from .config import Settings
from .service import ServiceError, SyncService


def _settings_from_args(args: argparse.Namespace) -> Settings:
    if args.database:
        os.environ["UTILITY_SYNC_DATABASE"] = str(Path(args.database).resolve())
    return Settings.from_environment()


def _migrate(settings: Settings) -> None:
    settings.ensure_directories()
    config = Config(str(Path(__file__).parents[2] / "alembic.ini"))
    config.set_main_option("sqlalchemy.url", f"sqlite:///{settings.database}")
    command.upgrade(config, "head")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        prog="utility-sync", description="Utility Sync local administration"
    )
    result.add_argument("--database", help="override UTILITY_SYNC_DATABASE for this invocation")
    subcommands = result.add_subparsers(dest="command", required=True)
    subcommands.add_parser("monitor", help="publish local service, disk and backup status")
    subcommands.add_parser("migrate", help="apply schema migrations")
    issue = subcommands.add_parser(
        "issue-token", help="create a device token; print it exactly once"
    )
    issue.add_argument("--read-only", action="store_true", help="issue only sync:read")
    revoke = subcommands.add_parser("revoke-token", help="revoke a token by its UUID component")
    revoke.add_argument("token_id")
    subcommands.add_parser("export", help="atomically generate readings.csv")
    subcommands.add_parser(
        "backup", help="make, restore-verify, and retain a budgeted SQLite online backup"
    )
    prune = subcommands.add_parser(
        "prune-backups", help="list or remove exact expired backup names"
    )
    prune.add_argument(
        "--apply", action="store_true", help="delete only names listed by this policy"
    )
    return result


def main(argv: list[str] | None = None) -> None:
    args = parser().parse_args(argv)
    settings = _settings_from_args(args)
    try:
        if args.command == "migrate":
            _migrate(settings)
            return
        service = SyncService(settings)
        if args.command == "monitor":
            from .monitor import run_monitor

            print(run_monitor(service))
        elif args.command == "issue-token":
            print(issue_token(service.database, {"sync:read"} if args.read_only else None))
        elif args.command == "revoke-token":
            if not revoke_token(service.database, args.token_id):
                raise SystemExit("token was not found or was already revoked")
        elif args.command == "export":
            print(service.generate_csv())
        elif args.command == "backup":
            print(service.backup())
        elif args.command == "prune-backups":
            for path in service.prune_backups(dry_run=not args.apply):
                print(path)
    except ServiceError as error:
        raise SystemExit(error.detail) from error


if __name__ == "__main__":
    main(sys.argv[1:])
