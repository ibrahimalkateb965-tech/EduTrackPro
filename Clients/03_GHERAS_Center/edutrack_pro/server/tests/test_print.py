"""Integration tests for all 11 print endpoints in edutrack_api.routers.print.

Verified strictly against PostgreSQL 001_schema.sql.
"""

from __future__ import annotations

import uuid
from datetime import date


def _seed_basic_data(db):
    """Seed room, student, payment, schedule, fee plan adhering strictly to PostgreSQL schema constraints."""
    # Room (group_name in 'الصباح', 'المساء', 'الإنجليزي', 'القدرات')
    room_row = db.execute(
        "INSERT INTO rooms (name, group_name) VALUES (%s, %s) RETURNING id",
        ("قاعة ابن كثير", "الصباح"),
    ).fetchone()
    room_id = room_row["id"]

    # Student (group_name in 'الصباح', 'المساء', 'الإنجليزي', 'القدرات', status in 'active', 'dismissed', 'archived')
    student_row = db.execute(
        """INSERT INTO students (name, birth_date, status, guardian_phone, father_phone,
                                  mother_phone, pickup_type, pickup_name, pickup_relation, pickup_phone,
                                  group_name, room_id)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
        (
            "عمر إبراهيم الكاتب",
            date(2018, 5, 10),
            "active",
            "0501112233",
            "0501112233",
            "0502223344",
            "ولي الأمر",
            "إبراهيم الكاتب",
            "أب",
            "0501112233",
            "الصباح",
            room_id,
        ),
    ).fetchone()
    student_id = student_row["id"]

    # Fee plan and installment (interval_days in 7, 14, 30)
    plan_row = db.execute(
        "INSERT INTO fee_plans (student_id, total_amount, count, interval_days, start_date) VALUES (%s, %s, %s, %s, %s) RETURNING id",
        (student_id, 1500, 3, 30, date(2026, 9, 1)),
    ).fetchone()
    plan_id = plan_row["id"]

    inst_row = db.execute(
        "INSERT INTO installments (fee_plan_id, seq_no, due_date, amount, paid_amount, status) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
        (plan_id, 1, date(2026, 9, 1), 500, 500, "paid"),
    ).fetchone()
    inst_id = inst_row["id"]

    # Payment (method in 'كاش', 'تحويل بنكي', 'تابي', 'تقسيط المركز', 'مدى', 'Apple Pay', 'بطاقة')
    payment_row = db.execute(
        "INSERT INTO payments (student_id, installment_id, amount, method, paid_on) VALUES (%s, %s, %s, %s, %s) RETURNING id",
        (student_id, inst_id, 500, "تحويل بنكي", date(2026, 9, 2)),
    ).fetchone()
    payment_id = payment_row["id"]

    receipt_row = db.execute(
        "INSERT INTO receipts (payment_id, issued_on) VALUES (%s, %s) RETURNING id, receipt_no",
        (payment_id, date(2026, 9, 2)),
    ).fetchone()
    receipt_no = receipt_row["receipt_no"]

    # Schedule (day in 'الأحد'..'الخميس', subject in 'القرآن', 'لغتي', 'الإنجليزي', 'الرياضيات')
    schedule_row = db.execute(
        "INSERT INTO schedules (room_id, day, start_time, end_time, subject, group_name) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
        (room_id, "الأحد", "08:00", "09:00", "القرآن", "الصباح"),
    ).fetchone()
    schedule_id = schedule_row["id"]

    # Attendance (status in 'حاضر', 'غائب', 'متأخر', 'مستأذن')
    db.execute(
        "INSERT INTO student_attendance (student_id, date, status) VALUES (%s, %s, %s)",
        (student_id, date(2026, 9, 15), "حاضر"),
    )

    # Evaluation (eval_type in 'daily', 'weekly', 'monthly', value numeric >= 0)
    db.execute(
        "INSERT INTO evaluations (student_id, subject, eval_type, date, value) VALUES (%s, %s, %s, %s, %s)",
        (student_id, "القرآن", "daily", date(2026, 9, 15), 95.00),
    )

    # Lesson log (status in 'تمت', 'مؤجلة', 'ملغاة')
    db.execute(
        "INSERT INTO lesson_logs (schedule_id, date, status, covered, homework) VALUES (%s, %s, %s, %s, %s)",
        (schedule_id, date(2026, 9, 15), "تمت", "سورة البقرة 1-20", "حفظ الآيات 21-25"),
    )

    # Certificate
    cert_row = db.execute(
        "INSERT INTO certificates (student_id, title, issued_on, reason) VALUES (%s, %s, %s, %s) RETURNING id",
        (student_id, "شهادة تفوق وتميز", date(2026, 9, 15), "التميز في حفظ القرآن"),
    ).fetchone()
    cert_id = cert_row["id"]

    # Optional expense if category exists
    cat = db.execute("SELECT id FROM expense_categories LIMIT 1").fetchone()
    if cat:
        db.execute(
            "INSERT INTO expenses (description, category_id, amount, paid_on, method) VALUES (%s, %s, %s, %s, %s)",
            ("أدوات تعليمية", cat["id"], 150, date(2026, 9, 10), "تحويل بنكي"),
        )

    db.commit()
    return {
        "room_id": str(room_id),
        "student_id": str(student_id),
        "payment_id": str(payment_id),
        "receipt_no": receipt_no,
        "schedule_id": str(schedule_id),
        "certificate_id": str(cert_id),
    }


def test_print_receipt_by_payment_uuid(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(f"/api/v1/print/receipt?payment={data['payment_id']}", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "receipt"
    assert body["student_name"] == "عمر إبراهيم الكاتب"
    assert body["amount"] == 500


def test_print_receipt_by_receipt_no(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(f"/api/v1/print/receipt?payment={data['receipt_no']}", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "receipt"
    assert body["student_name"] == "عمر إبراهيم الكاتب"
    assert body["receipt_no"] == data["receipt_no"]


def test_print_guardian_card(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(f"/api/v1/print/guardian-card?student={data['student_id']}", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "guardian_card"
    assert body["student_name"] == "عمر إبراهيم الكاتب"
    assert body["guardian_phone"] == "0501112233"


def test_print_excellence_certificate_on_the_fly(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(
        f"/api/v1/print/certificate?student_id={data['student_id']}&reason=التميز في حفظ القرآن الكريم",
        headers=manager,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "excellence_certificate"
    assert body["student_name"] == "عمر إبراهيم الكاتب"
    assert body["reason"] == "التميز في حفظ القرآن الكريم"


def test_print_excellence_certificate_existing(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(
        f"/api/v1/print/certificate?certificate_id={data['certificate_id']}",
        headers=manager,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "excellence_certificate"
    assert body["student_name"] == "عمر إبراهيم الكاتب"


def test_print_excellence_certificate_missing_404(client, db, manager):
    fake_id = str(uuid.uuid4())
    res = client.get(f"/api/v1/print/certificate?certificate_id={fake_id}", headers=manager)
    assert res.status_code == 404, res.text


def test_print_student_report(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(f"/api/v1/print/student-report?student={data['student_id']}", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "student_report"
    assert body["student_name"] == "عمر إبراهيم الكاتب"
    assert body["present_count"] >= 1
    assert len(body["evaluations"]) >= 1


def test_print_admin_report(client, db, manager):
    _seed_basic_data(db)
    res = client.get("/api/v1/print/admin-report?from=2026-09-01&to=2026-09-30", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "admin_report"
    assert body["students_count"] >= 1


def test_print_monthly_report(client, db, manager):
    _seed_basic_data(db)
    res = client.get("/api/v1/print/monthly-report?month=2026-09", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "monthly_report"
    assert body["month"] == "2026-09"
    assert body["total_income"] >= 500


def test_print_schedule(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(f"/api/v1/print/schedule?room={data['room_id']}", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "schedule"
    assert body["room"] == "قاعة ابن كثير"
    assert len(body["periods"]) >= 1


def test_print_attendance_report(client, db, manager):
    _seed_basic_data(db)
    res = client.get("/api/v1/print/attendance-report?from=2026-09-15&to=2026-09-15", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "attendance_report"
    assert len(body["rows"]) >= 1


def test_print_student_receipt(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(f"/api/v1/print/student-receipt?student={data['student_id']}", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "student_receipt"
    assert body["student_name"] == "عمر إبراهيم الكاتب"
    assert len(body["installments"]) >= 1


def test_print_lesson_log_by_room(client, db, manager):
    data = _seed_basic_data(db)
    res = client.get(
        f"/api/v1/print/lesson-log?room={data['room_id']}&from=2026-09-01&to=2026-09-30",
        headers=manager,
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "lesson_log"
    assert body["room"] == "قاعة ابن كثير"
    assert len(body["logs"]) >= 1


def test_print_statistics(client, db, manager):
    _seed_basic_data(db)
    res = client.get("/api/v1/print/statistics?from=2026-09-01&to=2026-09-30", headers=manager)
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["template"] == "statistics_report"
    assert body["students_count"] >= 1
