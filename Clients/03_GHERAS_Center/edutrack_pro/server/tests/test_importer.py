"""Importer tests: MVP `gheras_simple_v1` backup -> PostgreSQL (reviewer-only).

The sample payload mirrors the exact camelCase keys the MVP writes (see PHASE2_SPEC section 7).
"""

from __future__ import annotations

import copy
import json
import re
import uuid

import pytest

from edutrack_api.auth import verify_password
from edutrack_api.importer import NAMESPACE_GHERAS, import_backup, legacy_uuid
from edutrack_api.importer.__main__ import main as importer_main
from tests.conftest import MAIN_BRANCH

SAMPLE = {
    "rooms": [{"id": "1700000000001", "name": "حلقة الفجر", "group": "الصباح"}, {"id": "1700000000002", "name": "حلقة العصر", "group": "غير معروف"}],
    "staff": [{"id": "1700000000010", "name": "أستاذة هند", "role": "معلمة", "salary": 3500}, {"id": "1700000000011", "name": "المدير"}],
    "users": [
        {"id": "1700000000020", "name": "المدير", "username": "admin", "password": "Admin12345", "role": "مدير/مديرة", "staffId": "1700000000011", "active": True},
        {"id": "1700000000021", "name": "هند", "username": "hind", "password": "", "role": "معلم/معلمة", "staffId": "1700000000010", "roomId": "1700000000001", "active": False},
    ],
    "permissions": [{"staffId": "1700000000010", "role": "معلم/معلمة", "roomId": "1700000000001", "attendance": True, "dailyEvaluation": True, "monthlyEvaluation": False, "students": False, "finance": False}],
    "students": [
        {
            "id": "1700000000100", "name": "محمد سعد", "nationalId": "1234567890", "birthDate": "2019-03-05", "nationality": "سعودي",
            "hasDifficulties": "لا", "difficulty": "", "childNotes": "", "fatherName": "سعد", "fatherPhone": "0501111111",
            "motherName": "منى", "motherPhone": "0502222222", "guardianPhone": "0501111111", "guardianRelation": "الأب",
            "pickupType": "ولي الأمر", "pickupName": "سعد", "pickupRelation": "الأب", "pickupPhone": "0501111111",
            "previousStudy": "نعم", "previousSchool": "روضة النور", "previousLevel": "تمهيدي", "educationNotes": "",
            "group": "الصباح", "roomId": "1700000000001", "roomName": "حلقة الفجر", "status": "نشط",
            "fees": 4000, "booksFee": 200, "busFee": 0, "totalDue": 4200, "installments": 2, "paid": 1200,
        },
        {"id": "1700000000101", "name": "فاطمة علي", "group": "المساء", "roomName": "حلقة العصر", "status": "مفصول", "totalDue": 0},
    ],
    "installments": [
        {"id": "1700000000200", "studentId": "1700000000100", "number": 1, "amount": 2100, "paid": 1200, "dueDate": "2026-09-01"},
        {"id": "1700000000201", "studentId": "1700000000100", "number": 2, "amount": 2100, "paid": 0, "dueDate": "2026-10-01"},
    ],
    "payments": [
        {"id": "1700000000300", "studentId": "1700000000100", "studentName": "محمد سعد", "amount": 1200, "method": "جهاز نقاط بيع", "date": "2026-09-01", "note": "دفعة عند التسجيل"},
        {"id": "1700000000301", "studentId": "1700000000100", "amount": 0, "method": "كاش", "date": "2026-09-02"},
    ],
    "receipts": [{"id": "1700000000400", "paymentId": "1700000000300", "no": 57, "date": "2026-09-01", "studentId": "1700000000100", "student": "محمد سعد", "amount": 1200, "method": "جهاز نقاط بيع"}],
    "expenses": [{"id": "1700000000500", "description": "فاتورة كهرباء", "category": "كهرباء", "amount": 850.5, "method": "تحويل بنكي", "date": "2026-09-03", "vendor": "الشركة السعودية للكهرباء", "invoiceNo": "INV-9", "note": ""},
                 {"id": "1700000000501", "description": "لافتة", "category": "فئة غير موجودة", "amount": 300, "method": "", "date": "2026-09-04"}],
    "attendance": [
        {"id": "1700000000600", "studentId": "1700000000100", "date": "2026-09-06", "status": "حضور", "note": "", "teacherId": "1700000000021"},
        {"id": "1700000000601", "studentId": "1700000000100", "date": "2026-09-06", "status": "غياب"},
        {"id": "1700000000602", "studentId": "9999999999999", "date": "2026-09-06", "status": "غياب"},
    ],
    "accounts": [{"id": "1700000000700", "name": "حساب الراجحي", "type": "بنك", "opening": 5000}],
    "accountMoves": [{"id": "1700000000800", "accountId": "1700000000700", "accountName": "حساب الراجحي", "type": "إيداع", "amount": 1200, "date": "2026-09-01", "description": "دفعة", "sourceType": "payments", "sourceId": "1700000000300"}],
    "evaluations": [{"id": "1700000000900", "studentId": "1700000000100", "type": "يومي", "subject": "القرآن", "date": "2026-09-06", "value": "متميز", "teacherId": "1700000000021"}],
    "auditLog": [{"id": "1700000001000", "date": "2026-09-01T10:00:00.000Z", "user": "المدير", "action": "حفظ بيانات", "entity": "النظام", "details": "تم"}],
    "futureThing": [{"id": "1"}],
}


def _run(conn, payload, **kw):
    return import_backup(conn, payload, branch_id=MAIN_BRANCH, **kw)


def test_legacy_uuid_is_deterministic():
    assert NAMESPACE_GHERAS == uuid.uuid5(uuid.NAMESPACE_DNS, "edutrack.gheras.sa")
    assert legacy_uuid("students", "1700000000100") == uuid.uuid5(NAMESPACE_GHERAS, "students:1700000000100")
    assert legacy_uuid("students", "1") != legacy_uuid("rooms", "1")


def test_import_raw_payload_maps_every_collection(db):
    report = _run(db, copy.deepcopy(SAMPLE))
    db.commit()
    by = {r.collection: r for r in report.rows}

    assert by["rooms"].inserted == 2
    assert db.execute("SELECT group_name FROM rooms WHERE name='حلقة العصر'").fetchone()["group_name"] == "الصباح"

    assert by["staff"].inserted == 2
    assert db.execute("SELECT role_title, base_salary FROM staff WHERE name='المدير'").fetchone() == {"role_title": "موظف", "base_salary": 0}

    users = {u["username"]: u for u in db.execute("SELECT username, role, is_active, password_hash, staff_id, room_id FROM users").fetchall()}
    assert users["admin"]["role"] == "manager" and users["admin"]["is_active"] is True
    assert verify_password(users["admin"]["password_hash"], "Admin12345")
    assert users["hind"]["role"] == "teacher" and users["hind"]["is_active"] is False
    assert verify_password(users["hind"]["password_hash"], "hind"), "empty password defaults to the username"
    assert users["hind"]["room_id"] == legacy_uuid("rooms", "1700000000001")
    assert any("hind" in w for w in by["users"].warnings), "defaulted password must be reported"
    perms = db.execute("SELECT attendance, daily_evaluation, finance FROM user_permissions WHERE user_id=%s", (users["hind"]["staff_id"] and legacy_uuid("users", "1700000000021"),)).fetchone()
    assert perms == {"attendance": True, "daily_evaluation": True, "finance": False}

    assert by["students"].inserted == 2
    s = db.execute("SELECT * FROM students WHERE id=%s", (legacy_uuid("students", "1700000000100"),)).fetchone()
    assert s["national_id"] == "1234567890" and s["has_difficulties"] is False and s["previous_study"] is True
    assert s["room_id"] == legacy_uuid("rooms", "1700000000001") and s["group_name"] == "الصباح" and s["status"] == "active"
    s2 = db.execute("SELECT room_id, status FROM students WHERE id=%s", (legacy_uuid("students", "1700000000101"),)).fetchone()
    assert s2["room_id"] == legacy_uuid("rooms", "1700000000002") and s2["status"] == "dismissed"
    guardians = db.execute("SELECT g.name, g.phone, sg.is_primary FROM student_guardians sg JOIN guardians g ON g.id=sg.guardian_id WHERE sg.student_id=%s ORDER BY g.name", (s["id"],)).fetchall()
    assert [(g["name"], g["is_primary"]) for g in guardians] == [("سعد", True), ("منى", False)]

    plans = db.execute("SELECT student_id, total_amount, count, start_date, interval_days FROM fee_plans").fetchall()
    assert len(plans) == 1 and plans[0]["total_amount"] == 4200 and plans[0]["count"] == 2 and str(plans[0]["start_date"]) == "2026-09-01"
    inst = db.execute("SELECT seq_no, amount, paid_amount, status FROM installments ORDER BY seq_no").fetchall()
    assert [(i["seq_no"], float(i["paid_amount"]), i["status"]) for i in inst] == [(1, 1200.0, "partial"), (2, 0.0, "pending")]

    pays = db.execute("SELECT id, amount, method, paid_on FROM payments").fetchall()
    assert len(pays) == 1 and pays[0]["method"] == "مدى" and float(pays[0]["amount"]) == 1200
    assert by["payments"].read == 2 and by["payments"].skipped == 1 and by["payments"].warnings
    rec = db.execute("SELECT receipt_no, payment_id FROM receipts").fetchone()
    assert rec["receipt_no"] == 57 and rec["payment_id"] == pays[0]["id"]
    assert db.execute("SELECT nextval('receipts_receipt_no_seq') AS n").fetchone()["n"] == 58

    exp = {e["description"]: e for e in db.execute("SELECT e.description, e.method, e.note, c.name AS category FROM expenses e JOIN expense_categories c ON c.id=e.category_id").fetchall()}
    assert exp["فاتورة كهرباء"]["category"] == "كهرباء" and exp["فاتورة كهرباء"]["method"] == "تحويل بنكي"
    assert "INV-9" in exp["فاتورة كهرباء"]["note"] and "الشركة السعودية للكهرباء" in exp["فاتورة كهرباء"]["note"]
    assert exp["لافتة"]["category"] == "فئة غير موجودة" and exp["لافتة"]["method"] is None

    att = db.execute("SELECT status, recorded_by_user_id FROM student_attendance").fetchall()
    assert len(att) == 1 and att[0]["status"] == "حاضر"
    assert by["attendance"].read == 3 and by["attendance"].inserted == 1 and by["attendance"].skipped == 2

    acc = db.execute("SELECT kind, opening_balance FROM ledger_accounts WHERE name='حساب الراجحي'").fetchone()
    assert acc["kind"] == "bank" and float(acc["opening_balance"]) == 5000
    move = db.execute("SELECT entry_type, ref_table, ref_id, amount FROM ledger_entries").fetchone()
    assert move["entry_type"] == "in" and move["ref_table"] == "payments" and move["ref_id"] == legacy_uuid("payments", "1700000000300")

    ev = db.execute("SELECT eval_type, teacher_user_id FROM evaluations").fetchone()
    assert ev["eval_type"] == "daily" and ev["teacher_user_id"] == legacy_uuid("users", "1700000000021")

    audit = db.execute("SELECT actor_user_id, action, entity, details_json FROM audit_log").fetchone()
    assert audit["actor_user_id"] is None and audit["action"] == "حفظ بيانات"

    assert by["futureThing"].skipped == 1 and "unknown" in by["futureThing"].warnings[0]
    table = report.format_table()
    assert "students" in table and "inserted" in table
    assert not re.search("[" + chr(0x660) + "-" + chr(0x669) + "]", table), "report must use Western digits"


def test_import_is_idempotent_and_accepts_wrapper(db):
    _run(db, copy.deepcopy(SAMPLE))
    db.commit()
    before = db.execute("SELECT count(*) AS n FROM students").fetchone()["n"]
    wrapped = {"version": "GHERAS-BACKUP-1", "createdAt": "2026-09-16T00:00:00.000Z", "data": copy.deepcopy(SAMPLE)}
    report = _run(db, wrapped)
    db.commit()
    assert sum(r.inserted for r in report.rows) == 0
    assert db.execute("SELECT count(*) AS n FROM students").fetchone()["n"] == before
    assert db.execute("SELECT count(*) AS n FROM guardians").fetchone()["n"] == 2
    assert db.execute("SELECT count(*) AS n FROM fee_plans").fetchone()["n"] == 1


def test_dry_run_commits_nothing(db):
    report = _run(db, copy.deepcopy(SAMPLE), dry_run=True)
    db.commit()
    assert {r.collection: r.inserted for r in report.rows}["students"] == 2
    assert db.execute("SELECT count(*) AS n FROM students").fetchone()["n"] == 0
    assert db.execute("SELECT count(*) AS n FROM users").fetchone()["n"] == 0


def test_invalid_payloads_are_rejected(db):
    with pytest.raises(ValueError):
        _run(db, {"version": "GHERAS-BACKUP-2"})
    with pytest.raises(ValueError):
        _run(db, [1, 2])


def test_cli_dry_run_and_bad_file(db, tmp_path, capsys, monkeypatch):
    path = tmp_path / "GHERAS_Backup.json"
    path.write_text(json.dumps({"version": "GHERAS-BACKUP-1", "data": SAMPLE}, ensure_ascii=False), encoding="utf-8")
    assert importer_main([str(path), "--dry-run"]) == 0
    out = capsys.readouterr().out
    assert "DRY RUN" in out and "students" in out
    assert db.execute("SELECT count(*) AS n FROM students").fetchone()["n"] == 0

    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    assert importer_main([str(bad)]) == 1
    assert importer_main([str(tmp_path / "missing.json")]) == 1
    assert importer_main([]) == 1

    assert importer_main([str(path)]) == 0
    assert db.execute("SELECT count(*) AS n FROM students").fetchone()["n"] == 2
