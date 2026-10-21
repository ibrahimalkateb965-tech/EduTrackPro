"""SQL persistence for pickup delegates and request state transitions."""

from __future__ import annotations

from psycopg import sql
from psycopg.types.json import Jsonb

_REQUEST_SELECT = (
    "SELECT pr.*, s.name AS student_name, s.room_id, pd.name AS delegate_name, "
    "pd.national_id AS delegate_national_id, pd.phone AS delegate_phone, "
    "pd.relation AS delegate_relation, u.national_id AS guardian_national_id, "
    "COALESCE(g.name, u.username) AS guardian_name, u.phone AS guardian_phone "
    "FROM pickup_requests pr JOIN students s ON s.id = pr.student_id "
    "JOIN users u ON u.id = pr.requested_by_user_id "
    "LEFT JOIN guardians g ON g.id = u.guardian_id AND g.deleted_at IS NULL "
    "LEFT JOIN pickup_delegates pd ON pd.id = pr.delegate_id "
)
_TIMESTAMP_FIELDS = frozenset({
    "early_leave_approved_at", "prep_started_at", "ready_at", "completed_at",
    "cancelled_at",
})
_STATUS_FIELDS = _TIMESTAMP_FIELDS | {
    "early_leave_approved", "early_leave_approved_by", "prep_checklist",
    "teacher_note", "failure_reason", "cancel_reason", "verified_receiver_name",
    "verified_receiver_id_no", "verified_receiver_phone", "verified_receiver_relation",
    "verified_by_user_id",
}


def _mask_national_id(national_id) -> str | None:
    if not national_id:
        return None
    nid = str(national_id)
    if len(nid) <= 4:
        return "*" * len(nid)
    return "*" * (len(nid) - 4) + nid[-4:]


def mask_request_row(row: dict | None) -> dict | None:
    """Replace raw delegate/guardian/receiver national IDs with masked variants (K2, L2)."""
    if row is None:
        return None
    d = dict(row)
    raw_delegate_id = d.pop("delegate_national_id", None)
    if raw_delegate_id is not None:
        d["delegate_national_id_masked"] = _mask_national_id(raw_delegate_id)
    elif "delegate_national_id_masked" not in d:
        d["delegate_national_id_masked"] = None

    raw_guardian_id = d.pop("guardian_national_id", None)
    if raw_guardian_id is not None:
        d["guardian_national_id_masked"] = _mask_national_id(raw_guardian_id)
    elif "guardian_national_id_masked" not in d:
        d["guardian_national_id_masked"] = None

    raw_receiver_id = d.pop("verified_receiver_id_no", None)
    if raw_receiver_id is not None:
        d["verified_receiver_id_no_masked"] = _mask_national_id(raw_receiver_id)
    elif "verified_receiver_id_no_masked" not in d:
        d["verified_receiver_id_no_masked"] = None
    return d


def get_delegate_by_client_id(conn, client_request_id: str, user_id: str):
    return conn.execute(
        "SELECT * FROM pickup_delegates WHERE client_request_id = %s "
        "AND created_by_user_id = %s AND deleted_at IS NULL",
        (client_request_id, user_id),
    ).fetchone()


def get_delegate_by_id(conn, delegate_id: str):
    return conn.execute(
        "SELECT * FROM pickup_delegates WHERE id = %s AND deleted_at IS NULL",
        (delegate_id,),
    ).fetchone()


def get_delegate_by_student_and_national_id(conn, student_id: str, national_id: str):
    return conn.execute(
        "SELECT * FROM pickup_delegates WHERE student_id = %s "
        "AND national_id = %s AND deleted_at IS NULL",
        (student_id, national_id),
    ).fetchone()


def list_delegates_by_student_ids(conn, student_ids: list[str]):
    return conn.execute(
        "SELECT * FROM pickup_delegates WHERE student_id = ANY(%s::uuid[]) "
        "AND deleted_at IS NULL ORDER BY created_at DESC",
        (student_ids,),
    ).fetchall()


def create_delegate(
    conn, branch_id: str, student_id: str, user_id: str, name: str,
    national_id: str, phone: str, relation: str, client_request_id: str | None = None,
):
    return conn.execute(
        "INSERT INTO pickup_delegates "
        "(branch_id, student_id, created_by_user_id, name, national_id, phone, "
        "relation, client_request_id) VALUES (%s, %s, %s, %s, %s, %s, %s, %s) RETURNING *",
        (branch_id, student_id, user_id, name, national_id, phone, relation, client_request_id),
    ).fetchone()


def soft_delete_delegate(conn, delegate_id: str, user_id: str):
    return conn.execute(
        "UPDATE pickup_delegates SET deleted_at = now(), updated_at = now() "
        "WHERE id = %s AND created_by_user_id = %s AND deleted_at IS NULL RETURNING *",
        (delegate_id, user_id),
    ).fetchone()


def is_delegate_in_active_request(conn, delegate_id: str) -> bool:
    return conn.execute(
        "SELECT 1 FROM pickup_requests WHERE delegate_id = %s "
        "AND status IN ('notified', 'in_prep', 'ready') AND deleted_at IS NULL LIMIT 1",
        (delegate_id,),
    ).fetchone() is not None


def get_request_by_id(conn, request_id: str, mask: bool = False):
    row = conn.execute(
        _REQUEST_SELECT + "WHERE pr.id = %s AND pr.deleted_at IS NULL",
        (request_id,),
    ).fetchone()
    if mask:
        return mask_request_row(row)
    return row


def get_request_by_client_id(conn, client_request_id: str, user_id: str):
    return conn.execute(
        "SELECT * FROM pickup_requests WHERE client_request_id = %s "
        "AND requested_by_user_id = %s AND deleted_at IS NULL",
        (client_request_id, user_id),
    ).fetchone()


def get_active_request_for_student(conn, student_id: str, pickup_date):
    return conn.execute(
        "SELECT * FROM pickup_requests WHERE student_id = %s AND pickup_date = %s "
        "AND status IN ('notified', 'in_prep', 'ready') AND deleted_at IS NULL",
        (student_id, pickup_date),
    ).fetchone()


def create_request(
    conn, branch_id: str, student_id: str, requested_by_user_id: str,
    delegate_id: str | None, is_early_leave: bool, pickup_date,
    client_request_id: str | None = None,
):
    return conn.execute(
        "INSERT INTO pickup_requests "
        "(branch_id, student_id, requested_by_user_id, delegate_id, is_early_leave, "
        "pickup_date, client_request_id, status, notified_at) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, 'notified', now()) RETURNING *",
        (branch_id, student_id, requested_by_user_id, delegate_id, is_early_leave,
         pickup_date, client_request_id),
    ).fetchone()


def approve_early_leave_cas(conn, request_id: str, user_id: str) -> dict | None:
    """Approve only pending early leave requests, preventing duplicate approvals."""
    return conn.execute(
        """
        UPDATE pickup_requests
        SET early_leave_approved = TRUE,
            early_leave_approved_by = %s,
            early_leave_approved_at = now(),
            updated_at = now()
        WHERE id = %s
          AND status = 'notified'
          AND is_early_leave
          AND NOT early_leave_approved
          AND deleted_at IS NULL
        RETURNING *
        """,
        (user_id, request_id),
    ).fetchone()


def update_request_status_cas(
    conn, request_id: str, to_status: str, allowed_from_statuses: list[str], **kwargs,
):
    if kwargs.keys() - _STATUS_FIELDS:
        raise ValueError("Unsupported pickup status fields")
    assignments = [sql.SQL("status = %(to_status)s"), sql.SQL("updated_at = now()")]
    params = {"to_status": to_status, "request_id": request_id,
              "allowed_from": allowed_from_statuses}
    for field, field_value in kwargs.items():
        if field in _TIMESTAMP_FIELDS and field_value == "now()":
            expression = sql.SQL("now()")
        else:
            expression = sql.Placeholder(field)
            params[field] = Jsonb(field_value) if field == "prep_checklist" else field_value
        assignments.append(sql.SQL("{} = {}").format(sql.Identifier(field), expression))
    query = sql.SQL(
        "UPDATE pickup_requests SET {} WHERE id = %(request_id)s "
        "AND status = ANY(%(allowed_from)s) AND deleted_at IS NULL RETURNING *"
    ).format(sql.SQL(", ").join(assignments))
    return conn.execute(query, params).fetchone()


def list_active_requests(
    conn, branch_id: str, pickup_date, room_ids: list[str] | None = None,
    is_teacher: bool = False,
):
    query = (
        _REQUEST_SELECT + "WHERE pr.branch_id = %s AND pr.pickup_date = %s "
        "AND pr.status IN ('notified', 'in_prep', 'ready') AND pr.deleted_at IS NULL"
    )
    params = [branch_id, pickup_date]
    if is_teacher:
        query += " AND NOT (pr.is_early_leave = TRUE AND pr.early_leave_approved = FALSE)"
    # An explicitly empty room scope must not expose the entire branch queue.
    if room_ids is not None:
        query += " AND s.room_id = ANY(%s::uuid[])"
        params.append(room_ids)
    query += " ORDER BY pr.notified_at, pr.id"
    rows = conn.execute(query, params).fetchall()
    return [mask_request_row(row) for row in rows]
