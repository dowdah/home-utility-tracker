from __future__ import annotations

import secrets
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError

from .database import Database

_hasher = PasswordHasher()


@dataclass(frozen=True)
class Principal:
    token_id: str
    scopes: frozenset[str]


def now_utc() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def issue_token(database: Database, scopes: set[str] | None = None) -> str:
    scopes = scopes or {"sync:read", "sync:write"}
    token_id = str(uuid.uuid4())
    secret = secrets.token_urlsafe(32)
    token = f"uts_{token_id}_{secret}"
    with database.write() as connection:
        connection.execute(
            "INSERT INTO tokens(id, hash, scopes, revoked, created_at) VALUES (?, ?, ?, 0, ?)",
            (token_id, _hasher.hash(secret), " ".join(sorted(scopes)), now_utc()),
        )
    return token


def revoke_token(database: Database, token_id: str) -> bool:
    with database.write() as connection:
        cursor = connection.execute(
            "UPDATE tokens SET revoked = 1, revoked_at = ? WHERE id = ? AND revoked = 0",
            (now_utc(), token_id),
        )
        return cursor.rowcount == 1


def authenticate(connection: sqlite3.Connection, token: str) -> Principal | None:
    try:
        prefix, token_id, secret = token.split("_", 2)
        if prefix != "uts" or not secret:
            return None
        row = connection.execute(
            "SELECT * FROM tokens WHERE id = ? AND revoked = 0", (token_id,)
        ).fetchone()
        if row is None:
            return None
        _hasher.verify(row["hash"], secret)
        return Principal(token_id=token_id, scopes=frozenset(row["scopes"].split()))
    except (InvalidHashError, VerifyMismatchError, ValueError):
        return None
