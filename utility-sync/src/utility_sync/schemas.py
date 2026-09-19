from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field

UuidText = Annotated[
    str, Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
]


class Mutation(BaseModel):
    operation_id: UuidText
    entity_type: Literal["reading", "tariff"]
    entity_id: UuidText
    kind: Literal["upsert", "tombstone"]
    base_revision: int = Field(ge=0)
    payload: dict[str, Any] = Field(default_factory=dict)


class SyncRequest(BaseModel):
    backend_instance_id: UuidText
    cursor_revision: int = Field(ge=0)
    device_id: UuidText
    mutations: list[Mutation] = Field(default_factory=list, max_length=100)
    pull_limit: int = Field(default=500, ge=1, le=1000)
