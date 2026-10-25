from decimal import Decimal
from uuid import UUID

MAIN_BRANCH_ID = UUID("00000000-0000-0000-0000-000000000001")


class StudentRepository:
    """Layer 4: Data Access Layer for student records, guardian linkages, and student persistence."""

    @staticmethod
    def get_student_for_bus_update(conn, student_id: UUID | str) -> dict | None:
        """Fetch student fee and bus subscription status for update validations."""
        row = conn.execute(
            "SELECT branch_id, has_bus_subscription, bus_fee, bus_paid "
            "FROM students WHERE id = %s AND deleted_at IS NULL",
            (student_id,),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def get_student_allergy_state(conn, student_id: UUID | str) -> dict | None:
        """Fetch student allergy status and notes for allergy update validation."""
        row = conn.execute(
            "SELECT has_allergies, allergy_notes "
            "FROM students WHERE id = %s AND deleted_at IS NULL",
            (student_id,),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def find_or_create_guardian(conn, branch_id: UUID | str, name: str, phone: str, relation: str = "ولي الأمر") -> UUID:
        """Lookup existing guardian by phone number or insert new guardian identity."""
        existing = conn.execute(
            "SELECT id FROM guardians WHERE phone = %s AND deleted_at IS NULL",
            (phone,),
        ).fetchone()
        if existing:
            return existing["id"]
        new_g = conn.execute(
            "INSERT INTO guardians (branch_id, name, phone, relation) VALUES (%s, %s, %s, %s) RETURNING id",
            (branch_id, name, phone, relation),
        ).fetchone()
        return new_g["id"]

    @staticmethod
    def link_student_guardian(conn, branch_id: UUID | str, student_id: UUID | str, guardian_id: UUID | str, is_primary: bool = True) -> None:
        """Link student to guardian in student_guardians junction table."""
        conn.execute(
            "INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary) "
            "VALUES (%s, %s, %s, %s) ON CONFLICT (student_id, guardian_id) DO NOTHING",
            (branch_id, student_id, guardian_id, is_primary),
        )

    @staticmethod
    def sync_user_national_id(conn, student_id: UUID | str, national_id: str | None) -> None:
        """Synchronize national_id into users table for students upon update."""
        conn.execute(
            "UPDATE users SET national_id = %s WHERE student_id = %s AND role = 'student' AND deleted_at IS NULL",
            (national_id, student_id),
        )

    @staticmethod
    def create_fee_plan(conn, branch_id: UUID | str, student_id: UUID | str, total_amount: Decimal, count: int, interval_days: int) -> dict:
        """Insert fee plan record for newly onboarded student."""
        plan_row = conn.execute(
            "INSERT INTO fee_plans (branch_id, student_id, total_amount, count, start_date, interval_days) "
            "VALUES (%s, %s, %s, %s, CURRENT_DATE, %s) RETURNING *",
            (branch_id, student_id, total_amount, count, interval_days),
        ).fetchone()
        return dict(plan_row)

    @staticmethod
    def create_student_payment(conn, branch_id: UUID | str, student_id: UUID | str, total_paid: Decimal, bus_paid: Decimal, method: str, note: str) -> dict:
        """Record down payment or bus payment upon student registration or bus fee update."""
        pay_row = conn.execute(
            "INSERT INTO payments (branch_id, student_id, amount, bus_amount, paid_on, method, note) "
            "VALUES (%s, %s, %s, %s, CURRENT_DATE, %s, %s) RETURNING *",
            (branch_id, student_id, total_paid, bus_paid, method, note),
        ).fetchone()
        return dict(pay_row)
