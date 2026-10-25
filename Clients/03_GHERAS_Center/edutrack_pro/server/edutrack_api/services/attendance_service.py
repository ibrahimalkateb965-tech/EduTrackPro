from datetime import date as dt_date
from uuid import UUID

from edutrack_api.audit import write_audit
from edutrack_api.errors import ApiError
from edutrack_api.scope import Scope
from edutrack_api.serializers import row_to_json
from ..repositories.attendance_repo import AttendanceRepository

MAIN_BRANCH_ID = "00000000-0000-0000-0000-000000000001"
VALID_STATUSES = {"حاضر", "غائب", "متأخر", "مستأذن"}


class AttendanceService:
    """Layer 3: Pure domain business service for attendance recording, drafts, excuse matching, notifications, and evaluations."""

    @classmethod
    def validate_items(cls, items: list[dict], key: str) -> dt_date | None:
        """Validate status against allowed inputs and parse date consistency."""
        dates: set[dt_date] = set()
        for item in items:
            if not item.get(key) or item.get("status") not in VALID_STATUSES:
                raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
            d = item.get("date")
            if isinstance(d, dt_date):
                dates.add(d)
            else:
                try:
                    dates.add(dt_date.fromisoformat(str(d)))
                except (ValueError, TypeError) as exc:
                    raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc
        return next(iter(dates)) if len(dates) == 1 else None

    @classmethod
    def send_absence_notifications(
        cls,
        conn,
        student_id: UUID | str,
        att_date: dt_date | str,
        actor_id: UUID | str,
        branch_id: UUID | str | None = None,
    ) -> int:
        """Send urgent notification to guardians when absence is recorded, preventing duplicates."""
        st_row = AttendanceRepository.get_student_for_notification(conn, student_id)
        if not st_row:
            return 0
        st_name = st_row["name"]
        br_id = branch_id or st_row.get("branch_id")
        guardians = AttendanceRepository.get_guardians_for_student(conn, student_id)
        act_url = f"gheras://attendance?student_id={student_id}&date={att_date}"
        sent = 0
        for gu in guardians:
            if not AttendanceRepository.has_attendance_notification(conn, gu["user_id"], act_url):
                AttendanceRepository.insert_attendance_notification(
                    conn,
                    br_id,
                    gu["user_id"],
                    "تسجيل غياب",
                    f"تم تسجيل غياب الطالب/ـة {st_name} بتاريخ {att_date}",
                    student_id,
                    actor_id,
                    act_url,
                )
                sent += 1
        return sent

    @classmethod
    def save_attendance_batch(
        cls,
        conn,
        items: list[dict],
        key: str,
        table: str,
        actor: dict,
        scope: Scope | None = None,
        is_draft: bool = False,
    ) -> dict:
        """Validate and persist batch attendance records."""
        report_date = cls.validate_items(items, key)
        if scope is not None:
            scope.assert_students(item[key] for item in items)
        if not items:
            return {"saved": 0, "items": []}

        saved = []
        for item in items:
            status = item["status"]
            excuse_id = None
            if table == "student_attendance":
                # G3b / P5: Check for approved excuse if status is 'غائب'
                if status == "غائب":
                    excuse = AttendanceRepository.find_approved_excuse(conn, item[key], item["date"])
                    if excuse:
                        status = "غياب بعذر"
                        excuse_id = excuse["id"]

                row = AttendanceRepository.upsert_student_attendance_row(
                    conn,
                    item[key],
                    item["date"],
                    status,
                    item.get("note"),
                    actor["id"],
                    is_draft,
                    excuse_id,
                )
                saved.append(row_to_json(row))

                # Emit attendance notification to guardians on absence (only if resulting row is non-draft and status is 'غائب')
                if not row["is_draft"] and status == "غائب":
                    cls.send_absence_notifications(conn, item[key], item["date"], actor["id"])
            else:
                row = AttendanceRepository.upsert_staff_attendance_row(
                    conn,
                    item[key],
                    item["date"],
                    status,
                    item.get("note"),
                )
                saved.append(row_to_json(row))

        write_audit(conn, actor["id"], "update", table, None, {
            "count": len(items),
            "date": report_date.isoformat() if report_date else None,
            "is_draft": is_draft if table == "student_attendance" else False,
        })
        return {"saved": len(saved), "items": saved}

    @classmethod
    def save_students(cls, conn, items: list[dict], actor: dict, scope: Scope | None = None) -> dict:
        """Handle official student attendance submission."""
        return cls.save_attendance_batch(
            conn, items, "student_id", "student_attendance", actor, scope=scope, is_draft=False
        )

    @classmethod
    def draft_students(cls, conn, payload: dict, actor: dict, scope: Scope | None = None) -> dict:
        """Validate and store draft student attendance for room on a given date."""
        room_id_raw = payload.get("room_id")
        date_raw = payload.get("date")
        items_raw = payload.get("items")

        if not room_id_raw or not date_raw or items_raw is None:
            raise ApiError(422, "validation_error", "بيانات المسودة غير مكتملة")

        if not isinstance(items_raw, list):
            raise ApiError(422, "validation_error", "قائمة الطلاب غير صحيحة")

        try:
            room_id = UUID(str(room_id_raw))
        except (ValueError, TypeError) as exc:
            raise ApiError(422, "validation_error", "معرف القاعة غير صحيح") from exc

        try:
            draft_date = dt_date.fromisoformat(str(date_raw))
        except (ValueError, TypeError) as exc:
            raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc

        if scope is not None:
            scope.assert_rooms([room_id])

        if not items_raw:
            return {"saved": 0, "items": [], "is_draft": True}

        items = []
        student_ids = []
        for item in items_raw:
            if not isinstance(item, dict):
                raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
            sid = item.get("student_id")
            st = item.get("status")
            if not sid or st not in VALID_STATUSES:
                raise ApiError(422, "validation_error", "بيانات الحضور غير صحيحة")
            try:
                student_uuid = UUID(str(sid))
            except (ValueError, TypeError) as exc:
                raise ApiError(422, "validation_error", "معرف الطالب غير صحيح") from exc
            student_ids.append(student_uuid)
            items.append({
                "student_id": student_uuid,
                "date": draft_date,
                "status": st,
                "note": item.get("note"),
            })

        # G3c: Verify all students in items belong to this room and are active
        valid_ids = AttendanceRepository.verify_students_in_room(conn, student_ids, room_id)
        for sid in student_ids:
            if sid not in valid_ids:
                raise ApiError(422, "validation_error", f"الطالب {sid} غير موجود أو غير نشط في هذا الفصل")

        res = cls.save_attendance_batch(conn, items, "student_id", "student_attendance", actor, is_draft=True)
        res["is_draft"] = True
        return res

    @classmethod
    def approve_room_attendance(
        cls,
        conn,
        room_id: UUID,
        date_str: str,
        actor: dict,
        scope: Scope | None = None,
    ) -> dict:
        """Approve full room attendance atomically, resolving approved excuses and dispatching absence alerts."""
        if scope is not None:
            scope.assert_rooms([room_id])

        try:
            report_date = dt_date.fromisoformat(str(date_str))
        except (ValueError, TypeError) as exc:
            raise ApiError(422, "validation_error", "التاريخ غير صحيح") from exc

        with conn.transaction():
            # 1. Fetch all active students in the room
            active_students = AttendanceRepository.get_active_room_students(conn, room_id)
            if not active_students:
                raise ApiError(422, "validation_error", "لا توجد طالبات نشطات في هذا الفصل")

            active_student_ids = [s["id"] for s in active_students]

            # 2. Lock attendance rows for active students on this date (m5)
            attendance_rows = AttendanceRepository.lock_room_attendance(conn, active_student_ids, report_date)

            # 3. Check complete room attendance (P1, P2)
            recorded_student_ids = {r["student_id"] for r in attendance_rows}
            missing_student_ids = set(active_student_ids) - recorded_student_ids
            if missing_student_ids:
                raise ApiError(422, "validation_error", "لا يمكن اعتماد الحضور قبل اكتمال رصد كافة طلاب الفصل")

            # 4. Check for approved excuses for absent students, convert to 'غياب بعذر', or send notifications
            notifications_sent = 0
            student_map = {s["id"]: s for s in active_students}

            for att in attendance_rows:
                sid = att["student_id"]
                current_status = att["status"]

                if current_status == "غائب":
                    excuse = AttendanceRepository.find_approved_excuse(conn, sid, report_date)
                    if excuse:
                        AttendanceRepository.approve_attendance_row(
                            conn, att["id"], status="غياب بعذر", excuse_id=excuse["id"]
                        )
                    else:
                        AttendanceRepository.approve_attendance_row(conn, att["id"])
                        st_info = student_map.get(sid)
                        br_id = st_info["branch_id"] if st_info else (actor.get("branch_id") or MAIN_BRANCH_ID)
                        notifications_sent += cls.send_absence_notifications(conn, sid, report_date, actor["id"], br_id)
                else:
                    AttendanceRepository.approve_attendance_row(conn, att["id"])

            write_audit(conn, actor["id"], "approve", "student_attendance", None, {
                "room_id": str(room_id),
                "date": report_date.isoformat(),
                "total_students": len(active_students),
                "notifications_sent": notifications_sent,
            })

        return {
            "approved": True,
            "room_id": str(room_id),
            "date": report_date.isoformat(),
            "total_students": len(active_students),
            "notifications_sent": notifications_sent,
        }

    @classmethod
    def save_staff(cls, conn, items: list[dict], actor: dict) -> dict:
        """Handle staff attendance submission with payroll absence deduction calculations."""
        res = cls.save_attendance_batch(conn, items, "staff_id", "staff_attendance", actor)

        # Persist monetary deductions directly into payroll_runs
        for item in items:
            try:
                deduction = float(item.get("deduction", 0) or 0)
            except (ValueError, TypeError):
                deduction = 0
            if deduction > 0 and item.get("staff_id") and item.get("date"):
                month = str(item["date"])[:7]
                staff_row = AttendanceRepository.get_staff_base_and_branch(conn, item["staff_id"])
                if staff_row:
                    branch_id = staff_row["branch_id"]
                    base = staff_row["base_salary"] or 0
                    AttendanceRepository.upsert_payroll_deduction(
                        conn,
                        branch_id,
                        item["staff_id"],
                        month,
                        base,
                        deduction,
                        f"خصم غياب {item['date']}",
                    )
        return res

    @classmethod
    def list_daily_evaluations(
        cls,
        conn,
        date: str | None = None,
        room_id: str | None = None,
        student_id: str | None = None,
        scope: Scope | None = None,
    ) -> dict:
        """Query and return daily evaluations matching filters and scope."""
        if scope is not None and not scope.student_ids:
            return {"items": [], "total": 0}

        scope_ids = list(scope.student_ids) if scope is not None else None
        rows = AttendanceRepository.find_daily_evaluations(
            conn, date=date, room_id=room_id, student_id=student_id, scope_student_ids=scope_ids
        )
        return {"items": [row_to_json(r) for r in rows], "total": len(rows)}

    @classmethod
    def save_daily_evaluations(cls, conn, items: list[dict], actor: dict, scope: Scope | None = None) -> dict:
        """Validate and record daily student evaluations."""
        if not items:
            return {"saved": 0, "items": []}
        if scope is not None:
            scope.assert_students(item["student_id"] for item in items if item.get("student_id"))

        saved = []
        for item in items:
            student_id = item.get("student_id")
            eval_date = item.get("date")
            subject = item.get("subject") or "القرآن"
            raw_val = item.get("value")
            if raw_val is None:
                raw_val = item.get("score")
            if raw_val is None:
                raise ApiError(422, "validation_error", "درجة التقييم مفقودة")

            if not student_id or not eval_date:
                raise ApiError(422, "validation_error", "بيانات التقييم غير مكتملة")
            try:
                val = float(raw_val)
                if val < 0 or val > 10:
                    raise ValueError()
            except (ValueError, TypeError):
                raise ApiError(422, "validation_error", "درجة التقييم يجب أن تكون رقماً بين 0 و 10")

            # Resolve branch_id from student
            st_row = AttendanceRepository.get_student_branch(conn, student_id)
            branch_id = st_row["branch_id"] if st_row else (actor.get("branch_id") or MAIN_BRANCH_ID)

            # Check existing evaluation for (student_id, date, subject, eval_type='daily')
            existing = AttendanceRepository.find_existing_daily_evaluation(conn, student_id, eval_date, subject)
            if existing:
                row = AttendanceRepository.update_daily_evaluation(conn, existing["id"], val, actor["id"])
            else:
                row = AttendanceRepository.insert_daily_evaluation(
                    conn, branch_id, student_id, subject, eval_date, val, actor["id"]
                )
            saved.append(row_to_json(row))

        write_audit(conn, actor["id"], "update", "evaluations", None, {"count": len(items), "type": "daily"})
        return {"saved": len(saved), "items": saved}
