-- EduTrack Pro - Demo Accounts Seed (Gheras Center)
-- File   : db/postgres/013_demo_accounts_seed.sql
-- Requires: 001 through 012 applied first.
--
-- Provisions 3 deterministic demo accounts for executive app evaluation:
--   1. Teacher  : username='teacher.demo'   (or phone='0550000001'), password='Password123!'
--   2. Guardian : username='guardian.demo'  (or phone='0550000002'), password='Password123!'
--   3. Student  : username='student.demo'   (or national_id='1100000001'), password='Password123!'
--
-- Properties:
--   * Idempotent (ON CONFLICT DO NOTHING / WHERE NOT EXISTS).
--   * Password hash verifies with Argon2id for 'Password123!'.
--   * Seeds relational links, evaluations, fee installments, and behavioral records.

BEGIN;

-- Fixed UUID anchors for reliable idempotency
DO $$
DECLARE
    v_branch uuid := '00000000-0000-0000-0000-000000000001';
    v_room uuid := '00000000-0000-0000-0000-000000000101';
    v_supervisor uuid := '00000000-0000-0000-0000-000000000200';
    v_staff uuid := '00000000-0000-0000-0000-000000000201';
    v_guardian uuid := '00000000-0000-0000-0000-000000000301';
    v_student uuid := '00000000-0000-0000-0000-000000000401';
    v_plan uuid := '00000000-0000-0000-0000-000000000501';
    v_inst1 uuid := '00000000-0000-0000-0000-000000000601';
    v_inst2 uuid := '00000000-0000-0000-0000-000000000602';
    v_inst3 uuid := '00000000-0000-0000-0000-000000000603';
    v_payment uuid := '00000000-0000-0000-0000-000000000701';
    v_pwd_hash text := '$argon2id$v=19$m=65536,t=3,p=4$SxQcjsBLLz7KTIblw7P/Ww$XmIWEFv950xwwG2PnZI/fSmxvhbsxsZ/7mRHWSkEDM8';
BEGIN

    -- 1. Demo Room
    INSERT INTO rooms (id, branch_id, name, group_name)
    VALUES (v_room, v_branch, 'قاعة الفاروق (تجريبية)', 'الصباح')
    ON CONFLICT (id) DO NOTHING;

    -- 2. Demo Supervisor Staff
    INSERT INTO staff (id, branch_id, name, role_title, base_salary, phone, status)
    VALUES (v_supervisor, v_branch, 'أ. سارة العتيبي (المشرفة التربوية)', 'مشرفة', 6000, '0550000000', 'active')
    ON CONFLICT (id) DO NOTHING;

    -- Demo Teacher Staff
    INSERT INTO staff (id, branch_id, name, role_title, base_salary, phone, status)
    VALUES (v_staff, v_branch, 'أ. عبد الله المنصور (معلم تجريبي)', 'معلم', 4500, '0550000001', 'active')
    ON CONFLICT (id) DO NOTHING;

    -- 3. Demo Guardian
    INSERT INTO guardians (id, branch_id, name, phone, relation)
    VALUES (v_guardian, v_branch, 'أبو فهد عبد العزيز (ولي أمر تجريبي)', '0550000002', 'الأب')
    ON CONFLICT (id) DO NOTHING;

    -- 4. Demo Student
    INSERT INTO students (id, branch_id, name, national_id, father_phone, guardian_phone, room_id, group_name, gender, status)
    VALUES (v_student, v_branch, 'فهد عبد العزيز (طالب تجريبي)', '1100000001', '0550000002', '0550000002', v_room, 'الصباح', 'بنين', 'active')
    ON CONFLICT (id) DO NOTHING;

    -- 5. Student-Guardian Link
    INSERT INTO student_guardians (branch_id, student_id, guardian_id, is_primary)
    VALUES (v_branch, v_student, v_guardian, true)
    ON CONFLICT (student_id, guardian_id) DO NOTHING;

    -- 6. User Accounts (Teacher, Guardian, Student)
    -- Teacher User
    INSERT INTO users (branch_id, username, password_hash, role, staff_id, room_id, is_active)
    VALUES (v_branch, 'teacher.demo', v_pwd_hash, 'teacher', v_staff, v_room, true)
    ON CONFLICT (username) DO UPDATE SET password_hash = v_pwd_hash, is_active = true, staff_id = v_staff, room_id = v_room;

    -- Guardian User
    INSERT INTO users (branch_id, username, password_hash, role, guardian_id, is_active)
    VALUES (v_branch, 'guardian.demo', v_pwd_hash, 'guardian', v_guardian, true)
    ON CONFLICT (username) DO UPDATE SET password_hash = v_pwd_hash, is_active = true, guardian_id = v_guardian;

    -- Student User
    INSERT INTO users (branch_id, username, password_hash, role, student_id, national_id, is_active)
    VALUES (v_branch, 'student.demo', v_pwd_hash, 'student', v_student, '1100000001', true)
    ON CONFLICT (username) DO UPDATE SET password_hash = v_pwd_hash, is_active = true, student_id = v_student, national_id = '1100000001';

    -- 7. Teacher Performance Evaluation Seed (Weekly Scorecard)
    INSERT INTO teacher_evaluations (
        branch_id, teacher_id, supervisor_id, period_type, evaluation_date,
        interaction_score, guardian_comm_score, discipline_score, curriculum_score,
        rating, supervisor_notes, improvement_plan
    ) VALUES (
        v_branch, v_staff, v_supervisor, 'weekly', CURRENT_DATE,
        9.5, 9.0, 10.0, 9.5,
        'ممتاز',
        'أداء متميز وتفاعل استثنائي مع الطلاب داخل القاعة وحرص عالٍ على إتقان المنهج.',
        'الاستمرار في استخدام استراتيجيات التعلم التفاعلي والتحفيز المستمر.'
    ) ON CONFLICT DO NOTHING;

    -- 8. Financial Seed: Fee Plan + 3 Installments
    INSERT INTO fee_plans (id, branch_id, student_id, total_amount, count, start_date, interval_days)
    VALUES (v_plan, v_branch, v_student, 1500.00, 3, CURRENT_DATE - INTERVAL '30 days', 30)
    ON CONFLICT (id) DO NOTHING;

    -- Installment 1 (Paid)
    INSERT INTO installments (id, branch_id, fee_plan_id, seq_no, due_date, amount, paid_amount, status)
    VALUES (v_inst1, v_branch, v_plan, 1, CURRENT_DATE - INTERVAL '30 days', 500.00, 500.00, 'paid')
    ON CONFLICT (fee_plan_id, seq_no) DO NOTHING;

    -- Installment 2 (Pending/Upcoming Due in 7 days)
    INSERT INTO installments (id, branch_id, fee_plan_id, seq_no, due_date, amount, paid_amount, status)
    VALUES (v_inst2, v_branch, v_plan, 2, CURRENT_DATE + INTERVAL '7 days', 500.00, 0.00, 'pending')
    ON CONFLICT (fee_plan_id, seq_no) DO NOTHING;

    -- Installment 3 (Pending/Due next month)
    INSERT INTO installments (id, branch_id, fee_plan_id, seq_no, due_date, amount, paid_amount, status)
    VALUES (v_inst3, v_branch, v_plan, 3, CURRENT_DATE + INTERVAL '37 days', 500.00, 0.00, 'pending')
    ON CONFLICT (fee_plan_id, seq_no) DO NOTHING;

    -- Payment and Receipt for Installment 1
    INSERT INTO payments (id, branch_id, student_id, installment_id, amount, method, paid_on, note)
    VALUES (v_payment, v_branch, v_student, v_inst1, 500.00, 'مدى', CURRENT_DATE - INTERVAL '30 days', 'سداد الدفعة الأولى - نقداً مدى')
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO receipts (branch_id, payment_id, issued_on)
    VALUES (v_branch, v_payment, CURRENT_TIMESTAMP - INTERVAL '30 days')
    ON CONFLICT (payment_id) DO NOTHING;

    -- 9. Behavioral Log Seed
    INSERT INTO student_behavioral_logs (
        branch_id, student_id, author_id, room_id, log_type, category, points, note, notified_guardian
    ) VALUES (
        v_branch, v_student, v_staff, v_room, 'positive', 'سلوكي', 5,
        'حفظ متقن ومشاركة نموذجية في الأنشطة الصفية لهذا الأسبوع.', true
    ) ON CONFLICT DO NOTHING;

END $$;

COMMIT;
