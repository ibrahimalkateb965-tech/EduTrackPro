from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from uuid import UUID, uuid4

from psycopg.types.json import Jsonb

from edutrack_api.errors import ApiError

_CENT = Decimal("0.01")


def _money(value) -> Decimal:
    return Decimal(str(value or 0)).quantize(_CENT, rounding=ROUND_HALF_UP)


def generate_installments(conn, plan_row: dict) -> list[dict]:
    total, count = _money(plan_row["total_amount"]), int(plan_row["count"])
    each = (total / count).quantize(_CENT, rounding=ROUND_HALF_UP)
    start = plan_row["start_date"]
    if isinstance(start, str):
        start = date.fromisoformat(start)
    rows = []
    for sequence in range(1, count + 1):
        amount = total - each * (count - 1) if sequence == count else each
        row = conn.execute(
            "INSERT INTO installments (branch_id, fee_plan_id, seq_no, due_date, amount) "
            "VALUES (%s, %s, %s, %s, %s) RETURNING *",
            (plan_row["branch_id"], plan_row["id"], sequence, start + timedelta(days=(sequence - 1) * int(plan_row["interval_days"])), amount),
        ).fetchone()
        rows.append(dict(row))
    return rows


def allocate_payment(conn, payment_row: dict, installment_id: UUID | None) -> list[dict]:
    remaining = _money(payment_row["amount"])
    if installment_id:
        installments = conn.execute(
            "SELECT * FROM installments WHERE id = %s AND deleted_at IS NULL FOR UPDATE", (installment_id,)
        ).fetchall()
    else:
        installments = conn.execute(
            "SELECT i.* FROM installments i JOIN fee_plans f ON f.id = i.fee_plan_id "
            "WHERE f.student_id = %s AND f.deleted_at IS NULL AND i.deleted_at IS NULL "
            "AND i.paid_amount < i.amount ORDER BY i.due_date, i.seq_no FOR UPDATE",
            (payment_row["student_id"],),
        ).fetchall()
    touched = []
    for raw in installments:
        if remaining <= 0:
            break
        item = dict(raw)
        due = max(_money(item["amount"]) - _money(item["paid_amount"]), Decimal("0"))
        applied = min(remaining, due)
        paid = _money(item["paid_amount"]) + applied
        status = "paid" if paid >= _money(item["amount"]) else "partial"
        updated = conn.execute(
            "UPDATE installments SET paid_amount = %s, status = %s WHERE id = %s RETURNING *",
            (paid, status, item["id"]),
        ).fetchone()
        touched.append(dict(updated))
        remaining -= applied
    if touched and not payment_row.get("installment_id"):
        conn.execute("UPDATE payments SET installment_id = %s WHERE id = %s", (touched[0]["id"], payment_row["id"]))
        payment_row["installment_id"] = touched[0]["id"]
    return touched


def issue_receipt(conn, payment_id) -> dict:
    row = conn.execute("INSERT INTO receipts (payment_id) VALUES (%s) ON CONFLICT (payment_id) DO UPDATE "
                       "SET payment_id = EXCLUDED.payment_id RETURNING *", (payment_id,)).fetchone()
    return dict(row)


def default_cash_account_id(conn, branch_id) -> UUID:
    row = conn.execute("SELECT id FROM ledger_accounts WHERE branch_id = %s AND kind = 'cash' AND deleted_at IS NULL "
                       "ORDER BY created_at LIMIT 1", (branch_id,)).fetchone()
    if row:
        return row["id"]
    row = conn.execute("INSERT INTO ledger_accounts (branch_id, name, kind, opening_balance) VALUES (%s, %s, 'cash', 0) RETURNING id",
                       (branch_id, "الصندوق")).fetchone()
    return row["id"]


def post_ledger_entry(conn, account_id, entry_type, amount, ref_table, ref_id, occurred_on, branch_id) -> dict:
    account_id = account_id or default_cash_account_id(conn, branch_id)
    row = conn.execute(
        "INSERT INTO ledger_entries (branch_id, account_id, entry_type, amount, ref_table, ref_id, occurred_on) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING *",
        (branch_id, account_id, entry_type, _money(amount), ref_table, ref_id, occurred_on),
    ).fetchone()
    return dict(row)


def resolve_expense_category(conn, name_or_id, branch_id) -> UUID:
    try:
        identifier = UUID(str(name_or_id))
    except (TypeError, ValueError):
        identifier = None
    if identifier:
        row = conn.execute("SELECT id FROM expense_categories WHERE id = %s AND branch_id = %s AND deleted_at IS NULL", (identifier, branch_id)).fetchone()
    else:
        row = conn.execute("SELECT id FROM expense_categories WHERE name = %s AND branch_id = %s AND deleted_at IS NULL", (name_or_id, branch_id)).fetchone()
    if row:
        return row["id"]
    if not name_or_id or identifier:
        raise ApiError(422, "validation_error", "فئة المصروف مطلوبة")
    return conn.execute("INSERT INTO expense_categories (branch_id, name) VALUES (%s, %s) RETURNING id", (branch_id, name_or_id)).fetchone()["id"]


def apply_advance_deduction(conn, staff_id, month, body: dict) -> Decimal:
    """Cap the requested advance deduction by what is still open on the oldest unsettled advance.

    Runs for other months are counted as already deducted; the run for `month` is the one being created.
    """
    advance = conn.execute(
        "SELECT id, amount FROM staff_advances WHERE staff_id = %s AND NOT settled AND deleted_at IS NULL "
        "ORDER BY date, created_at LIMIT 1 FOR UPDATE",
        (staff_id,),
    ).fetchone()
    if not advance:
        return Decimal("0.00")
    deducted = conn.execute(
        "SELECT COALESCE(SUM(advance_deducted), 0) AS value FROM payroll_runs "
        "WHERE staff_id = %s AND month <> %s AND deleted_at IS NULL",
        (staff_id, month),
    ).fetchone()["value"]
    remaining = max(_money(advance["amount"]) - _money(deducted), Decimal("0"))
    requested = _money(body.get("advance_deducted"))
    deduction = min(remaining, requested)
    if deduction > 0 and deduction >= remaining:
        conn.execute("UPDATE staff_advances SET settled = true, updated_at = now() WHERE id = %s", (advance["id"],))
    return deduction


def month_totals(conn, month) -> dict:
    period = f"{month}-%"
    collected = conn.execute("SELECT COALESCE(SUM(amount), 0) AS value FROM payments WHERE paid_on::text LIKE %s AND deleted_at IS NULL", (period,)).fetchone()["value"]
    expenses = conn.execute("SELECT COALESCE(SUM(amount), 0) AS value FROM expenses WHERE paid_on::text LIKE %s AND deleted_at IS NULL", (period,)).fetchone()["value"]
    payroll = conn.execute("SELECT COALESCE(SUM(net), 0) AS value FROM payroll_runs WHERE month = %s AND deleted_at IS NULL", (month,)).fetchone()["value"]
    students = conn.execute("SELECT COUNT(*) AS value FROM students WHERE deleted_at IS NULL").fetchone()["value"]
    values = {"collected": _money(collected), "expenses": _money(expenses), "payroll": _money(payroll)}
    return values | {"net": _money(values["collected"] - values["expenses"] - values["payroll"]), "students_count": students}


def close_month(conn, month, actor_id, branch_id) -> dict:
    exists = conn.execute("SELECT 1 FROM month_closures WHERE month = %s AND deleted_at IS NULL", (month,)).fetchone()
    if exists:
        raise ApiError(409, "conflict", "تم إغلاق الشهر مسبقاً")
    totals = month_totals(conn, month)
    row = conn.execute(
        "INSERT INTO month_closures (branch_id, month, closed_by, closed_at, totals_json) "
        "VALUES (%s, %s, %s, now(), %s) RETURNING *",
        (branch_id, month, actor_id, Jsonb({key: float(value) if isinstance(value, Decimal) else value for key, value in totals.items()})),
    ).fetchone()
    return dict(row)


def transfer_between_accounts(conn, body: dict, branch_id) -> dict:
    """Post a matched transfer_out / transfer_in pair sharing one ref_id; returns the outgoing entry."""
    source, target = str(body.get("from_account_id")), str(body.get("to_account_id"))
    amount = _money(body.get("amount"))
    if source == target:
        raise ApiError(422, "validation_error", "اختر حسابين مختلفين")
    if amount <= 0:
        raise ApiError(422, "validation_error", "المبلغ يجب أن يكون أكبر من صفر")
    occurred_on = body.get("occurred_on") or date.today().isoformat()
    ref_id = uuid4()
    outgoing = post_ledger_entry(conn, UUID(source), "transfer_out", amount, "transfer", ref_id, occurred_on, branch_id)
    incoming = post_ledger_entry(conn, UUID(target), "transfer_in", amount, "transfer", ref_id, occurred_on, branch_id)
    outgoing["counterpart"] = incoming
    return outgoing
