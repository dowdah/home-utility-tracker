from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _path(name: str, default: Path) -> Path:
    return Path(os.environ.get(name, str(default))).expanduser().resolve()


@dataclass(frozen=True)
class Settings:
    database: Path
    data_dir: Path
    exports_dir: Path
    backups_dir: Path
    write_min_free_bytes: int
    backup_min_free_bytes: int
    backup_max_bytes: int

    @classmethod
    def from_environment(cls) -> Settings:
        root = Path(os.environ.get("UTILITY_SYNC_LOCAL_ROOT", ".local-data")).resolve()
        data_dir = _path("UTILITY_SYNC_DATA_DIR", root)
        return cls(
            database=_path("UTILITY_SYNC_DATABASE", data_dir / "utility.sqlite3"),
            data_dir=data_dir,
            exports_dir=_path("UTILITY_SYNC_EXPORTS_DIR", root / "exports"),
            backups_dir=_path("UTILITY_SYNC_BACKUPS_DIR", root / "backups"),
            write_min_free_bytes=int(
                os.environ.get("UTILITY_SYNC_WRITE_MIN_FREE_BYTES", 10 * 1024**3)
            ),
            backup_min_free_bytes=int(
                os.environ.get("UTILITY_SYNC_BACKUP_MIN_FREE_BYTES", 256 * 1024**2)
            ),
            backup_max_bytes=int(os.environ.get("UTILITY_SYNC_BACKUP_MAX_BYTES", 128 * 1024**2)),
        )

    def ensure_directories(self) -> None:
        for directory in (self.data_dir, self.exports_dir, self.backups_dir):
            directory.mkdir(mode=0o700, parents=True, exist_ok=True)
