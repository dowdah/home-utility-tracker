from __future__ import annotations

import logging
import uuid
from collections.abc import Callable
from pathlib import Path
from time import perf_counter

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse

from .auth import Principal, authenticate
from .config import Settings
from .schemas import SyncRequest
from .service import ServiceError, SyncService

logger = logging.getLogger("utility_sync.request")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_environment()
    service = SyncService(settings)
    app = FastAPI(title="Utility Sync", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.service = service

    @app.middleware("http")
    async def request_audit(request: Request, call_next):
        supplied_id = request.headers.get("X-Request-ID")
        try:
            request_id = str(uuid.UUID(supplied_id)) if supplied_id else str(uuid.uuid4())
        except ValueError:
            request_id = str(uuid.uuid4())
        request.state.request_id = request_id
        started = perf_counter()
        error_category = "none"
        try:
            response = await call_next(request)
        except Exception:
            error_category = "internal_error"
            logger.exception(
                "request_complete request_id=%s method=%s path=%s status=500 duration_ms=%d error_category=%s",
                request_id,
                request.method,
                request.url.path,
                (perf_counter() - started) * 1000,
                error_category,
            )
            raise
        if response.status_code >= 500:
            error_category = "server_error"
        elif response.status_code >= 400:
            error_category = "client_error"
        response.headers["X-Request-ID"] = request_id
        logger.info(
            "request_complete request_id=%s method=%s path=%s status=%d duration_ms=%d error_category=%s",
            request_id,
            request.method,
            request.url.path,
            response.status_code,
            (perf_counter() - started) * 1000,
            error_category,
        )
        return response

    @app.exception_handler(ServiceError)
    async def service_error_handler(request: Request, error: ServiceError) -> JSONResponse:
        logger.warning(
            "service_error request_id=%s status=%d error_category=service_error",
            getattr(request.state, "request_id", "unavailable"),
            error.status_code,
        )
        return JSONResponse(status_code=error.status_code, content={"detail": error.detail})

    def authenticated(authorization: str | None = Header(default=None)) -> Principal:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Bearer token required")
        with service.database.read() as connection:
            principal = authenticate(connection, authorization.removeprefix("Bearer "))
        if principal is None:
            raise HTTPException(status_code=401, detail="invalid or revoked token")
        return principal

    def requires(*scopes: str) -> Callable[[Principal], Principal]:
        def dependency(principal: Principal = Depends(authenticated)) -> Principal:
            if not set(scopes).issubset(principal.scopes):
                raise HTTPException(status_code=403, detail="token lacks required scope")
            return principal

        return dependency

    @app.get("/healthz")
    async def healthz() -> dict[str, object]:
        return service.health()

    @app.get("/api/v1/meta")
    async def meta(_: Principal = Depends(requires("sync:read"))) -> dict[str, object]:
        return service.meta()

    @app.post("/api/v1/sync")
    async def sync(
        http_request: Request,
        sync_request: SyncRequest,
        principal: Principal = Depends(authenticated),
    ) -> dict[str, object]:
        if "sync:read" not in principal.scopes or (
            sync_request.mutations and "sync:write" not in principal.scopes
        ):
            raise HTTPException(status_code=403, detail="token lacks required scope")
        result = service.sync(sync_request)
        logger.info(
            "sync_complete request_id=%s revision_start=%d revision_end=%d status=200",
            http_request.state.request_id,
            sync_request.cursor_revision,
            result["high_water_revision"],
        )
        return result

    @app.get("/api/v1/exports/readings.csv")
    async def export_readings(
        _: Principal = Depends(requires("sync:read")),
    ) -> FileResponse:
        path: Path = service.generate_csv()
        return FileResponse(path, media_type="text/csv", filename="readings.csv")

    return app


app = create_app()
