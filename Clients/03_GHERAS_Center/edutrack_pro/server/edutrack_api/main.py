"""FastAPI application factory for the EduTrack Pro API."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import APIRouter, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from edutrack_api import __version__
from edutrack_api.config import Settings, get_settings
from edutrack_api.db import introspect_columns, make_pool
from edutrack_api.errors import register_handlers
from edutrack_api.routers import attendance, auth, crud, importer, print, reports


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
    app = FastAPI(
        title="Gheras EduTrack API",
        version=__version__,
        docs_url="/api/v1/docs",
        openapi_url="/api/v1/openapi.json",
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
    api.include_router(crud.router)

    @api.get("/health")
    async def health() -> dict:
        return {"status": "ok", "version": __version__}

    app.include_router(api)
    return app


app = create_app()
