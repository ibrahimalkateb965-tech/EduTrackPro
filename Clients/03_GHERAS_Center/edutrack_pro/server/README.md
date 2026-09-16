# EduTrack Pro — API Server (Gheras Center)

## Overview

FastAPI + PostgreSQL 16 server for the Gheras EduTrack dashboard and print templates. It exposes a versioned REST API under `/api/v1` and ships with an MVP importer that migrates the Day-10 legacy backup into the normalized schema.

## Requirements

- Python 3.12+
- [uv](https://docs.astral.sh/uv/) package manager
- PostgreSQL 16, or Docker (recommended for local setup)

## Setup

```bash
uv sync
```

Copy `.env.example` to `.env` and fill values:

```bash
cp .env.example .env
```

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | PostgreSQL connection string for the app role |
| `JWT_SECRET` | Secret used to sign HS256 tokens |
| `JWT_TTL_MINUTES` | Token lifetime in minutes (default 720) |
| `CORS_ORIGINS` | Comma-separated list of allowed origins |
| `MAIN_BRANCH_ID` | Default branch UUID |

## Database

Apply the SQL migrations in order with `psql`:

```bash
psql -d gheras_edutrack -f ../db/postgres/001_schema.sql
psql -d gheras_edutrack -f ../db/postgres/002_reference.sql
psql -d gheras_edutrack -f ../db/postgres/003_phase2.sql
```

Or run `docker compose up db`, which applies all files in `../db/postgres/` automatically on first start (they run in name order).

## Run

```bash
uv run uvicorn edutrack_api.main:app --reload --port 8000
```

Interactive API docs are served at `/api/v1/docs`.

## Importer

Migrate the legacy backup (dry run first, then commit):

```bash
python -m edutrack_api.importer GHERAS_Backup.json --dry-run
python -m edutrack_api.importer GHERAS_Backup.json
```

Set the initial manager password (also required on a fresh database before login works):

```bash
python -m edutrack_api.importer --set-admin-password
```

## API conventions

- Base path: `/api/v1`
- Authentication: `Authorization: Bearer <JWT>` header
- List responses use the envelope `{"items": [...], "total": n, "limit": l, "offset": o}`
- Errors use the envelope `{"error": {"code": "<snake_case>", "message": "<Arabic message>"}}` with codes: `unauthorized`, `forbidden`, `not_found`, `validation_error`, `conflict`, `internal_error`

## Testing

Tests live in `tests/` and are executed by the reviewer only.
