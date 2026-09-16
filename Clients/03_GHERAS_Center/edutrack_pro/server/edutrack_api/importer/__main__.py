"""Command line entry for the gheras_simple_v1 importer."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import uuid

import psycopg
from psycopg.rows import dict_row

from edutrack_api.config import get_settings
from edutrack_api.importer.gheras_simple_v1 import import_backup


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Import a gheras_simple_v1 backup")
    parser.add_argument("backup", nargs="?", default=None, help="Path to backup JSON file")
    parser.add_argument("--dry-run", action="store_true", help="Validate and count without committing")
    parser.add_argument("--branch-id", default=None, help="Branch id, defaults to MAIN_BRANCH_ID")
    parser.add_argument("--set-admin-password", action="store_true", help="Set the admin password")
    return parser


def _resolve_branch_id(raw: str | None):
    if raw:
        return uuid.UUID(raw)
    return get_settings().main_branch_id


def _read_admin_password() -> str | None:
    """Return the new admin password, or None when the two prompts disagree.

    Non-interactive deployments (deploy.sh, CI) pass EDUTRACK_ADMIN_PASSWORD
    instead of answering the getpass prompts.
    """
    from_env = os.environ.get("EDUTRACK_ADMIN_PASSWORD")
    if from_env is not None:
        return from_env
    first = getpass.getpass("New admin password: ")
    second = getpass.getpass("Confirm admin password: ")
    return first if first == second else None


def _run_set_admin_password() -> int:
    first = _read_admin_password()
    if first is None:
        print("error: passwords do not match", file=sys.stderr)
        return 1
    if len(first) < 8:
        print("error: password must be at least 8 characters", file=sys.stderr)
        return 1
    from edutrack_api.auth import hash_password

    settings = get_settings()
    password_hash = hash_password(first)
    with psycopg.connect(settings.database_url, row_factory=dict_row) as conn:
        with conn.transaction():
            cur = conn.execute(
                "UPDATE users SET password_hash = %s, updated_at = now() "
                "WHERE username = 'admin' AND deleted_at IS NULL",
                (password_hash,),
            )
            updated = cur.rowcount or 0
        conn.commit()
    print(f"admin password updated ({updated} row(s))")
    return 0


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.set_admin_password:
        return _run_set_admin_password()
    if not args.backup:
        print("error: backup file is required", file=sys.stderr)
        return 1
    try:
        branch_id = _resolve_branch_id(args.branch_id)
    except ValueError:
        print("error: invalid branch id", file=sys.stderr)
        return 1
    try:
        with open(args.backup, encoding="utf-8") as handle:
            payload = json.load(handle)
    except FileNotFoundError:
        print(f"error: backup file not found: {args.backup}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as exc:
        print(f"error: invalid JSON: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"error: cannot read backup file: {exc}", file=sys.stderr)
        return 1
    settings = get_settings()
    with psycopg.connect(settings.database_url, row_factory=dict_row) as conn:
        report = import_backup(conn, payload, branch_id=branch_id, dry_run=args.dry_run)
        if not args.dry_run:
            conn.commit()
    print(report.format_table())
    if args.dry_run:
        print("DRY RUN - nothing was committed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
