"""Tests verifying the retirement of legacy /me homework write routes and security guards (R3-1, M-6).

Covers:
1. Four retired legacy POST routes explicitly returning 404:
   - POST /api/v1/me/submissions -> 404
   - POST /api/v1/me/submissions/{id}/grade -> 404
   - POST /api/v1/me/assignments -> 404
   - POST /api/v1/me/uploads -> 404
2. Sole submit surface POST /api/v1/assignments/{id}/submit:
   - Non-enrolled student is rejected with 403 (no auto-linking).
   - Draft assignment submission is rejected with 404.
   - Using another user's staged_uploads file is rejected with 403.
"""

from __future__ import annotations

import uuid
import pytest

from tests.conftest import login, make_user

API = "/api/v1"


@pytest.fixture()
def retired_env(db, client):
    branch_id = uuid.UUID("00000000-0000-0000-0000-000000000001")
    t_id = make_user(db, "teacher_ret1", "teacher")
    g_id = make_user(db, "guardian_ret1", "guardian")

    room_id = db.execute("INSERT INTO rooms (name, group_name) VALUES ('Room Ret', 'G') RETURNING id").fetchone()["id"]
    db.execute("UPDATE users SET room_id = %s WHERE id = %s", (room_id, t_id))

    s_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('طالب متقاعد', '0507776655', 'بنين', %s) RETURNING id",
        (room_id,)
    ).fetchone()["id"]

    ge_id = db.execute("INSERT INTO guardians (name, phone) VALUES ('ولي متقاعد', '0507776655') RETURNING id").fetchone()["id"]
    db.execute("UPDATE users SET guardian_id = %s WHERE id = %s", (ge_id, g_id))
    db.execute("INSERT INTO student_guardians (student_id, guardian_id, relation) VALUES (%s, %s, 'أب')", (s_id, ge_id))

    db.commit()

    return {
        "t_id": t_id,
        "g_id": g_id,
        "s_id": s_id,
        "room_id": room_id,
        "t_headers": login(client, "teacher_ret1"),
        "g_headers": login(client, "guardian_ret1"),
    }


def test_retired_legacy_me_routes_return_404(client, retired_env):
    """The four retired /me POST routes must return 404 Not Found (R3-1)."""
    t_headers = retired_env["t_headers"]
    g_headers = retired_env["g_headers"]
    fake_id = str(uuid.uuid4())

    # 1. POST /me/submissions -> 404
    res_sub = client.post(f"{API}/me/submissions", json={"assignment_id": fake_id}, headers=g_headers)
    assert res_sub.status_code == 404

    # 2. POST /me/submissions/{id}/grade -> 404
    res_grade = client.post(f"{API}/me/submissions/{fake_id}/grade", json={"grade": 90}, headers=t_headers)
    assert res_grade.status_code == 404

    # 3. POST /me/assignments -> 404
    res_ass = client.post(f"{API}/me/assignments", json={"title": "Old"}, headers=t_headers)
    assert res_ass.status_code == 404

    # 4. POST /me/uploads -> 404
    res_up = client.post(f"{API}/me/uploads", headers=t_headers)
    assert res_up.status_code == 404


def test_sole_submit_surface_security_guards(db, client, retired_env):
    """Verify sole submit surface enforces enrollment, draft rejection, and staged file ownership."""
    env = retired_env

    # 1. Non-enrolled student cannot submit (no auto-linking H4)
    # Create another student not linked to assignment
    s2_id = db.execute(
        "INSERT INTO students (name, guardian_phone, gender, room_id) VALUES ('طالب أجنبي', '0507776655', 'بنين', %s) RETURNING id",
        (env["room_id"],)
    ).fetchone()["id"]
    db.commit()

    ass_res = client.post(
        f"{API}/assignments",
        json={"title": "واجب محمي", "room_id": str(env["room_id"]), "student_ids": [str(env["s_id"])], "is_draft": False},
        headers=env["t_headers"],
    )
    ass_id = ass_res.json()["id"]

    res_foreign = client.post(
        f"{API}/assignments/{ass_id}/submit",
        json={"student_id": str(s2_id), "files": []},
        headers=env["g_headers"],
    )
    assert res_foreign.status_code == 403
    assert res_foreign.json()["error"]["code"] == "not_enrolled"

    # 2. Draft assignment submission is rejected with 404
    draft_ass = client.post(
        f"{API}/assignments",
        json={"title": "واجب مسودة", "room_id": str(env["room_id"]), "student_ids": [str(env["s_id"])], "is_draft": True},
        headers=env["t_headers"],
    ).json()
    draft_id = draft_ass["id"]

    res_draft_sub = client.post(
        f"{API}/assignments/{draft_id}/submit",
        json={"student_id": str(env["s_id"]), "files": []},
        headers=env["g_headers"],
    )
    assert res_draft_sub.status_code == 404

    # 3. Attaching file staged by another user is blocked with 403
    other_key = f"staged_other_{uuid.uuid4().hex[:8]}.jpg"
    db.execute("""
        INSERT INTO staged_uploads (storage_key, uploaded_by_user_id, media_type, file_name, bytes)
        VALUES (%s, %s, 'image', 'other.jpg', 100)
    """, (other_key, env["t_id"]))
    db.commit()

    res_stolen_file = client.post(
        f"{API}/assignments/{ass_id}/submit",
        json={"student_id": str(env["s_id"]), "files": [{"storage_key": other_key}]},
        headers=env["g_headers"],
    )
    assert res_stolen_file.status_code == 403
    assert res_stolen_file.json()["error"]["code"] == "unauthorized_file"
