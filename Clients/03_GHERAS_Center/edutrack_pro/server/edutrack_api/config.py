"""Application settings loaded from environment variables."""

from __future__ import annotations

import os
import uuid
from dataclasses import dataclass, field
from functools import lru_cache

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:  # pragma: no cover - dotenv is optional
    pass


@dataclass(frozen=True)
class Settings:
    database_url: str
    jwt_secret: str
    jwt_ttl_minutes: int = 720
    cors_origins: list[str] = field(default_factory=list)
    main_branch_id: uuid.UUID = uuid.UUID("00000000-0000-0000-0000-000000000001")


@lru_cache
def get_settings() -> Settings:
    database_url = os.environ.get("DATABASE_URL")
    if not database_url:
        raise RuntimeError("DATABASE_URL is not set")
    jwt_secret = os.environ.get("JWT_SECRET")
    if not jwt_secret:
        raise RuntimeError("JWT_SECRET is not set")
    ttl_raw = os.environ.get("JWT_TTL_MINUTES", "720")
    try:
        jwt_ttl_minutes = int(ttl_raw)
    except ValueError:
        raise RuntimeError("JWT_TTL_MINUTES is not a valid integer") from None
    cors_raw = os.environ.get("CORS_ORIGINS", "")
    cors_origins = [o.strip() for o in cors_raw.split(",") if o.strip()]
    branch_raw = os.environ.get("MAIN_BRANCH_ID")
    if branch_raw:
        main_branch_id = uuid.UUID(branch_raw)
    else:
        main_branch_id = uuid.UUID("00000000-0000-0000-0000-000000000001")
    return Settings(
        database_url=database_url,
        jwt_secret=jwt_secret,
        jwt_ttl_minutes=jwt_ttl_minutes,
        cors_origins=cors_origins,
        main_branch_id=main_branch_id,
    )
