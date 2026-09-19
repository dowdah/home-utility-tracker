from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from utility_sync.api import create_app
from utility_sync.cli import _migrate
from utility_sync.config import Settings
from utility_sync.service import SyncService


@pytest.fixture()
def settings(tmp_path) -> Settings:
    setting = Settings(
        database=tmp_path / "data" / "utility.sqlite3",
        data_dir=tmp_path / "data",
        exports_dir=tmp_path / "exports",
        backups_dir=tmp_path / "backups",
        write_min_free_bytes=0,
        backup_min_free_bytes=0,
        backup_max_bytes=128 * 1024**2,
    )
    _migrate(setting)
    return setting


@pytest.fixture()
def service(settings: Settings) -> SyncService:
    return SyncService(settings)


@pytest.fixture()
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings)) as test_client:
        yield test_client
