from decimal import Decimal
from uuid import UUID


class PaymentRepository:
    """Layer 4: Data Access Layer for student payments, installments, and bus ledger operations."""

    @staticmethod
    def get_student_with_lock(conn, student_id: UUID | str) -> dict | None:
        """Fetch student fee and bus subscription status with row-level lock (FOR UPDATE) to prevent race conditions."""
        row = conn.execute(
            "SELECT id, branch_id, bus_fee, bus_paid, has_bus_subscription "
            "FROM students WHERE id = %s AND deleted_at IS NULL FOR UPDATE",
            (student_id,),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def set_bus_subscription_fee(conn, student_id: UUID | str, target_fee: Decimal) -> None:
        """Atomically activate or renew student bus subscription and update target fee."""
        conn.execute(
            "UPDATE students SET has_bus_subscription = true, bus_fee = %s WHERE id = %s",
            (target_fee, student_id),
        )

    @staticmethod
    def increment_bus_paid(conn, student_id: UUID | str, bus_amount: Decimal) -> None:
        """Atomically increment student's bus_paid amount."""
        conn.execute(
            "UPDATE students SET bus_paid = COALESCE(bus_paid, 0) + %s WHERE id = %s",
            (bus_amount, student_id),
        )

    @staticmethod
    def get_student_balance(conn, student_id: UUID | str) -> dict | None:
        """Query v_student_balance view for tuition balance validation."""
        row = conn.execute(
            "SELECT total_planned, total_paid, balance "
            "FROM v_student_balance WHERE student_id = %s",
            (student_id,),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def get_installment_details(conn, installment_id: UUID | str) -> dict | None:
        """Fetch installment and linked student fee plan for ownership and target overflow checks."""
        row = conn.execute(
            "SELECT i.id, i.amount, i.paid_amount, fp.student_id "
            "FROM installments i JOIN fee_plans fp ON fp.id = i.fee_plan_id "
            "WHERE i.id = %s AND i.deleted_at IS NULL AND fp.deleted_at IS NULL",
            (installment_id,),
        ).fetchone()
        return dict(row) if row else None
