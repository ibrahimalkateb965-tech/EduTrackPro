"""FastAPI application factory for the EduTrack Pro API."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware

import os
from pathlib import Path

from edutrack_api import __version__
from edutrack_api.config import Settings, get_settings
from edutrack_api.db import introspect_columns, make_pool
from edutrack_api.errors import register_handlers
from edutrack_api.routers import assignments, attendance, auth, crud, daily_summaries, excuses, importer, me, media, monthly_reports, pickup, print, reports, staff_attendance, student_photos, threads
from edutrack_api.routers import settings as settings_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings: Settings = get_settings()
    app.state.settings = settings
    app.state.pool = make_pool(settings)
    app.state.pool.open()
    with app.state.pool.connection() as conn:
        app.state.columns = introspect_columns(conn)
    yield
    app.state.pool.close()


def create_app() -> FastAPI:
    enable_docs = os.getenv("ENABLE_API_DOCS", "false").lower() in ("1", "true", "yes")
    app = FastAPI(
        title="Gheras EduTrack API",
        version=__version__,
        docs_url="/api/v1/docs" if enable_docs else None,
        redoc_url="/api/v1/redoc" if enable_docs else None,
        openapi_url="/api/v1/openapi.json" if enable_docs else None,
        lifespan=lifespan,
    )
    settings = get_settings()
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )
    register_handlers(app)

    api = APIRouter(prefix="/api/v1")
    api.include_router(auth.router)
    api.include_router(attendance.router)
    api.include_router(reports.router)
    api.include_router(print.router)
    api.include_router(importer.router)
    api.include_router(settings_router.router)
    api.include_router(me.router)
    api.include_router(student_photos.router)
    api.include_router(pickup.router)
    api.include_router(assignments.router)
    api.include_router(media.router)
    api.include_router(excuses.router)
    api.include_router(threads.router)
    api.include_router(daily_summaries.router)
    api.include_router(monthly_reports.router)
    api.include_router(staff_attendance.router)
    api.include_router(crud.router)

    @api.get("/health")
    async def health() -> dict:
        return {"status": "ok", "version": __version__}

    app.include_router(api)
    upload_dir = Path(os.getenv("UPLOAD_DIR", "uploads")).resolve()
    upload_dir.mkdir(parents=True, exist_ok=True)
    return app


app = create_app()
