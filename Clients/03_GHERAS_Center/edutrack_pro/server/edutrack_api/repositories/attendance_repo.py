from datetime import date as dt_date
from uuid import UUID


class AttendanceRepository:
    """Layer 4: Data Access Layer for student attendance, staff attendance, room locks, and daily evaluations."""

    @staticmethod
    def find_approved_excuse(conn, student_id: UUID | str, excuse_date: dt_date | str) -> dict | None:
        """Fetch approved excuse for student on specific date if one exists."""
        row = conn.execute(
            """
            SELECT id FROM attendance_excuses
            WHERE student_id = %s AND excuse_date = %s AND status = 'approved' AND deleted_at IS NULL
            LIMIT 1
            """,
            (student_id, excuse_date),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def upsert_student_attendance_row(
        conn,
        student_id: UUID | str,
        att_date: dt_date,
        status: str,
        note: str | None,
        actor_id: UUID | str,
        is_draft: bool,
        excuse_id: UUID | str | None,
    ) -> dict:
        """Upsert student attendance record preserving draft status flags and linking excuse_id."""
        sql = """
            INSERT INTO student_attendance (
                student_id, date, status, note, recorded_by_user_id, is_draft, excuse_id
            )
            VALUES (
                %(student_id)s, %(date)s, %(status)s, %(note)s, %(actor)s, %(is_draft)s, %(excuse_id)s
            )
            ON CONFLICT (student_id, date) DO UPDATE SET
                status = EXCLUDED.status,
                note = EXCLUDED.note,
                updated_at = now(),
                deleted_at = NULL,
                recorded_by_user_id = EXCLUDED.recorded_by_user_id,
                is_draft = student_attendance.is_draft AND EXCLUDED.is_draft,
                excuse_id = CASE WHEN EXCLUDED.status = 'غياب بعذر' THEN EXCLUDED.excuse_id ELSE NULL END
            RETURNING *
        """
        row = conn.execute(
            sql,
            {
                "student_id": student_id,
                "date": att_date,
                "status": status,
                "note": note,
                "actor": actor_id,
                "is_draft": is_draft,
                "excuse_id": excuse_id,
            },
        ).fetchone()
        return dict(row)

    @staticmethod
    def upsert_staff_attendance_row(
        conn,
        staff_id: UUID | str,
        att_date: dt_date,
        status: str,
        note: str | None,
    ) -> dict:
        """Upsert staff attendance record."""
        sql = """
            INSERT INTO staff_attendance (staff_id, date, status, note)
            VALUES (%(staff_id)s, %(date)s, %(status)s, %(note)s)
            ON CONFLICT (staff_id, date) DO UPDATE SET
                status = EXCLUDED.status,
                note = EXCLUDED.note,
                updated_at = now(),
                deleted_at = NULL
            RETURNING *
        """
        row = conn.execute(
            sql,
            {
                "staff_id": staff_id,
                "date": att_date,
                "status": status,
                "note": note,
            },
        ).fetchone()
        return dict(row)

    @staticmethod
    def get_active_room_students(conn, room_id: UUID | str) -> list[dict]:
        """Fetch all active students in room ordered by name."""
        rows = conn.execute(
            """
            SELECT id, name, branch_id
            FROM students
            WHERE room_id = %s AND status = 'active' AND deleted_at IS NULL
            ORDER BY name ASC
            """,
            (room_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    @staticmethod
    def verify_students_in_room(conn, student_ids: list[UUID], room_id: UUID | str) -> set[UUID]:
        """Return set of valid student IDs that belong to the specified room and are active."""
        rows = conn.execute(
            """
            SELECT id FROM students
            WHERE id = ANY(%s) AND room_id = %s AND status = 'active' AND deleted_at IS NULL
            """,
            (student_ids, room_id),
        ).fetchall()
        return {r["id"] for r in rows}

    @staticmethod
    def lock_room_attendance(conn, student_ids: list[UUID], att_date: dt_date) -> list[dict]:
        """Lock attendance rows for active students on this date (FOR UPDATE)."""
        rows = conn.execute(
            """
            SELECT id, student_id, status, is_draft, excuse_id
            FROM student_attendance
            WHERE student_id = ANY(%s) AND date = %s AND deleted_at IS NULL
            FOR UPDATE
            """,
            (student_ids, att_date),
        ).fetchall()
        return [dict(r) for r in rows]

    @staticmethod
    def approve_attendance_row(
        conn,
        att_id: UUID | str,
        status: str | None = None,
        excuse_id: UUID | str | None = None,
    ) -> None:
        """Mark student attendance row as approved (is_draft = FALSE), optionally updating status and excuse_id."""
        if status and excuse_id:
            conn.execute(
                """
                UPDATE student_attendance
                SET status = %s, excuse_id = %s, is_draft = FALSE, updated_at = now()
                WHERE id = %s
                """,
                (status, excuse_id, att_id),
            )
        else:
            conn.execute(
                """
                UPDATE student_attendance
                SET is_draft = FALSE, updated_at = now()
                WHERE id = %s
                """,
                (att_id,),
            )

    @staticmethod
    def get_student_for_notification(conn, student_id: UUID | str) -> dict | None:
        """Retrieve student basic details for absence notifications."""
        row = conn.execute(
            "SELECT name, room_id, branch_id FROM students WHERE id = %s",
            (student_id,),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def get_guardians_for_student(conn, student_id: UUID | str) -> list[dict]:
        """Fetch active guardian user accounts linked to a student."""
        rows = conn.execute(
            """
            SELECT u.id AS user_id FROM users u
            JOIN student_guardians sg ON sg.guardian_id = u.guardian_id
            WHERE sg.student_id = %s AND sg.deleted_at IS NULL AND u.is_active = true AND u.deleted_at IS NULL
            """,
            (student_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    @staticmethod
    def has_attendance_notification(conn, user_id: UUID | str, action_url: str) -> bool:
        """Check whether a duplicate attendance notification already exists for user."""
        row = conn.execute(
            "SELECT id FROM notifications WHERE user_id = %s AND action_url = %s",
            (user_id, action_url),
        ).fetchone()
        return bool(row)

    @staticmethod
    def insert_attendance_notification(
        conn,
        branch_id: UUID | str,
        user_id: UUID | str,
        title: str,
        body: str,
        target_id: UUID | str,
        actor_id: UUID | str,
        action_url: str,
    ) -> None:
        """Insert urgent attendance notification record."""
        conn.execute(
            """
            INSERT INTO notifications (
                branch_id, user_id, kind, title, body, target_type, target_id,
                priority, sender_user_id, action_url, sent_at
            ) VALUES (
                %s, %s, 'attendance', %s, %s, 'attendance', %s,
                'urgent', %s, %s, now()
            )
            """,
            (branch_id, user_id, title, body, target_id, actor_id, action_url),
        )

    @staticmethod
    def get_staff_base_and_branch(conn, staff_id: UUID | str) -> dict | None:
        """Retrieve staff base salary and branch_id for attendance deductions."""
        row = conn.execute(
            "SELECT base_salary, branch_id FROM staff WHERE id = %s",
            (staff_id,),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def upsert_payroll_deduction(
        conn,
        branch_id: UUID | str,
        staff_id: UUID | str,
        month: str,
        base: float,
        deduction: float,
        note: str,
    ) -> None:
        """Upsert staff absence deduction into payroll_runs table."""
        conn.execute(
            """
            INSERT INTO payroll_runs (branch_id, staff_id, month, base, deductions, note)
            VALUES (%(branch_id)s, %(staff_id)s, %(month)s, %(base)s, %(deduction)s, %(note)s)
            ON CONFLICT (staff_id, month) DO UPDATE
            SET deductions = payroll_runs.deductions + EXCLUDED.deductions,
                updated_at = now()
            """,
            {
                "branch_id": branch_id,
                "staff_id": staff_id,
                "month": month,
                "base": base,
                "deduction": deduction,
                "note": note,
            },
        )

    @staticmethod
    def find_daily_evaluations(
        conn,
        date: str | None = None,
        room_id: str | None = None,
        student_id: str | None = None,
        scope_student_ids: list | None = None,
    ) -> list[dict]:
        """Query daily evaluations joined with student info."""
        query = """
            SELECT e.*, s.name as student_name, s.group_name
            FROM evaluations e
            JOIN students s ON s.id = e.student_id AND s.deleted_at IS NULL
            WHERE e.eval_type = 'daily' AND e.deleted_at IS NULL
        """
        params = []
        if date:
            query += " AND e.date = %s"
            params.append(date)
        if student_id:
            query += " AND e.student_id = %s"
            params.append(student_id)
        if room_id:
            query += " AND s.room_id = %s"
            params.append(room_id)
        if scope_student_ids is not None:
            query += " AND e.student_id = ANY(%s)"
            params.append(scope_student_ids)
        query += " ORDER BY s.name ASC"

        rows = conn.execute(query, params).fetchall()
        return [dict(r) for r in rows]

    @staticmethod
    def get_student_branch(conn, student_id: UUID | str) -> dict | None:
        """Fetch student branch ID."""
        row = conn.execute("SELECT branch_id FROM students WHERE id = %s", (student_id,)).fetchone()
        return dict(row) if row else None

    @staticmethod
    def find_existing_daily_evaluation(conn, student_id: UUID | str, eval_date: str, subject: str) -> dict | None:
        """Find existing daily evaluation record for student, date, and subject."""
        row = conn.execute(
            """
            SELECT id FROM evaluations
            WHERE student_id = %s AND date = %s AND subject = %s AND eval_type = 'daily' AND deleted_at IS NULL
            """,
            (student_id, eval_date, subject),
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def update_daily_evaluation(conn, eval_id: UUID | str, val: float, user_id: UUID | str) -> dict:
        """Update existing daily evaluation score and recorder."""
        row = conn.execute(
            """
            UPDATE evaluations
            SET value = %s, teacher_user_id = %s, updated_at = now()
            WHERE id = %s
            RETURNING *
            """,
            (val, user_id, eval_id),
        ).fetchone()
        return dict(row)

    @staticmethod
    def insert_daily_evaluation(
        conn,
        branch_id: UUID | str,
        student_id: UUID | str,
        subject: str,
        eval_date: str,
        val: float,
        user_id: UUID | str,
    ) -> dict:
        """Insert new daily evaluation record."""
        row = conn.execute(
            """
            INSERT INTO evaluations (branch_id, student_id, subject, eval_type, date, value, teacher_user_id)
            VALUES (%s, %s, %s, 'daily', %s, %s, %s)
            RETURNING *
            """,
            (branch_id, student_id, subject, eval_date, val, user_id),
        ).fetchone()
        return dict(row)
