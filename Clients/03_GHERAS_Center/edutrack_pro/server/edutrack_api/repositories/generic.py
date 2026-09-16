"""Generic CRUD repository over introspected columns (Phase 2, Worker B)."""

from __future__ import annotations

import uuid

from psycopg import sql

from edutrack_api.db import ColumnInfo


class GenericRepository:
    SYSTEM_COLUMNS = {"id", "created_at", "updated_at", "deleted_at"}
    TEXT_SEARCH_COLUMNS = ("name", "title", "description", "username", "note", "student_name")
    NUMERIC_TYPES = frozenset(
        {"integer", "int", "smallint", "bigint", "numeric", "decimal", "real", "double precision"}
    )

    def __init__(self, conn, table: str, columns: dict[str, ColumnInfo]):
        self.conn = conn
        self.table = table
        self.columns = columns

    def _filter_fragment(self, col: str, raw):
        info = self.columns[col]
        dtype = info.data_type.lower()
        if dtype == "boolean":
            if isinstance(raw, bool):
                return sql.SQL("{} = %s").format(sql.Identifier(col)), [raw]
            text = str(raw).lower()
            if text in ("true", "1"):
                return sql.SQL("{} = %s").format(sql.Identifier(col)), [True]
            if text in ("false", "0"):
                return sql.SQL("{} = %s").format(sql.Identifier(col)), [False]
            raise ValueError(col)
        if dtype == "uuid":
            value = raw if isinstance(raw, uuid.UUID) else uuid.UUID(str(raw))
            return sql.SQL("{} = %s").format(sql.Identifier(col)), [value]
        if dtype in self.NUMERIC_TYPES:
            return (
                sql.SQL("{} = %s::{}").format(sql.Identifier(col), sql.SQL(info.data_type)),
                [raw],
            )
        return sql.SQL("{} = %s").format(sql.Identifier(col)), [raw]

    def list(
        self,
        *,
        filters: dict[str, str] | None = None,
        limit: int = 100,
        offset: int = 0,
        q: str | None = None,
        order_by: str = "created_at",
        descending: bool = True,
    ) -> tuple[list[dict], int]:
        if order_by not in self.columns:
            raise ValueError(order_by)
        where: list[sql.Composable] = []
        params: list = []
        if "deleted_at" in self.columns:
            where.append(sql.SQL("deleted_at IS NULL"))
        if filters:
            for col, raw in filters.items():
                if col not in self.columns:
                    raise ValueError(col)
                frag, vals = self._filter_fragment(col, raw)
                where.append(frag)
                params.extend(vals)
        if q is not None and q != "":
            search = [c for c in self.TEXT_SEARCH_COLUMNS if c in self.columns]
            if search:
                ors = [
                    sql.SQL("{} ILIKE '%%' || %s || '%%'").format(sql.Identifier(c))
                    for c in search
                ]
                where.append(sql.SQL("({})").format(sql.SQL(" OR ").join(ors)))
                params.extend([q] * len(search))
        direction = sql.SQL("DESC" if descending else "ASC")
        query = sql.SQL("SELECT *, COUNT(*) OVER() AS _total FROM {}").format(
            sql.Identifier(self.table)
        )
        if where:
            query += sql.SQL(" WHERE ") + sql.SQL(" AND ").join(where)
        query += sql.SQL(" ORDER BY {} {}, id LIMIT %s OFFSET %s").format(
            sql.Identifier(order_by), direction
        )
        params.extend([limit, offset])
        rows = self.conn.execute(query, params).fetchall()
        if not rows:
            return ([], 0)
        total = rows[0].get("_total", 0)
        for row in rows:
            row.pop("_total", None)
        return (rows, total)

    def get(self, id: uuid.UUID) -> dict | None:
        query = sql.SQL("SELECT * FROM {} WHERE id = %s AND deleted_at IS NULL").format(
            sql.Identifier(self.table)
        )
        return self.conn.execute(query, [id]).fetchone()

    def _writable(self, data: dict) -> list[str]:
        return [
            key
            for key in data.keys()
            if key in self.columns
            and key not in self.SYSTEM_COLUMNS
            and not self.columns[key].is_generated
        ]

    def create(self, data: dict) -> dict:
        cols = self._writable(data)
        if not cols:
            raise ValueError("data")
        query = sql.SQL("INSERT INTO {} ({}) VALUES ({}) RETURNING *").format(
            sql.Identifier(self.table),
            sql.SQL(", ").join(sql.Identifier(c) for c in cols),
            sql.SQL(", ").join(sql.SQL("%s") for _ in cols),
        )
        return self.conn.execute(query, [data[c] for c in cols]).fetchone()

    def update(self, id: uuid.UUID, data: dict) -> dict | None:
        cols = self._writable(data)
        parts: list[sql.Composable] = [
            sql.SQL("{} = %s").format(sql.Identifier(c)) for c in cols
        ]
        parts.append(sql.SQL("updated_at = now()"))
        query = sql.SQL("UPDATE {} SET {} WHERE id = %s AND deleted_at IS NULL RETURNING *").format(
            sql.Identifier(self.table),
            sql.SQL(", ").join(parts),
        )
        params = [data[c] for c in cols]
        params.append(id)
        return self.conn.execute(query, params).fetchone()

    def soft_delete(self, id: uuid.UUID) -> bool:
        query = sql.SQL(
            "UPDATE {} SET deleted_at = now(), updated_at = now()"
            " WHERE id = %s AND deleted_at IS NULL"
        ).format(sql.Identifier(self.table))
        cur = self.conn.execute(query, [id])
        return cur.rowcount == 1
