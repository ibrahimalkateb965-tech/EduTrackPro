"""Database pool and connection helpers (Phase 2, Worker B)."""

from __future__ import annotations

from collections.abc import Generator
from dataclasses import dataclass

import psycopg
import psycopg.rows
from fastapi import Request
from psycopg_pool import ConnectionPool


@dataclass(frozen=True)
class ColumnInfo:
    name: str
    data_type: str
    is_nullable: bool
    has_default: bool
    is_generated: bool


def make_pool(settings) -> ConnectionPool:
    return ConnectionPool(
        conninfo=settings.database_url,
        min_size=1,
        max_size=8,
        open=False,
        kwargs={"row_factory": psycopg.rows.dict_row},
    )


def get_conn(request: Request) -> Generator:
    pool: ConnectionPool = request.app.state.pool
    with pool.connection() as conn:
        try:
            yield conn
        except Exception:
            conn.rollback()
            raise
        else:
            conn.commit()


def introspect_columns(conn) -> dict[str, dict[str, ColumnInfo]]:
    rows = conn.execute(
        """
        SELECT table_name, column_name, data_type, is_nullable,
               column_default, is_generated
          FROM information_schema.columns
         WHERE table_schema = 'public'
         ORDER BY table_name, ordinal_position
        """
    ).fetchall()
    out: dict[str, dict[str, ColumnInfo]] = {}
    for row in rows:
        table = row["table_name"]
        info = ColumnInfo(
            name=row["column_name"],
            data_type=row["data_type"],
            is_nullable=row["is_nullable"] == "YES",
            has_default=row["column_default"] is not None,
            is_generated=row["is_generated"] == "ALWAYS",
        )
        out.setdefault(table, {})[info.name] = info
    return out
