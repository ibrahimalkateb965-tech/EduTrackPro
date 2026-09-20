# Print Templates — Mustache Field Reference

Field inventory for the 11 printable documents in `web/print/templates/`, for the Phase 2 server implementer of the `print/*` endpoints.

Shared chrome (all templates): `<!doctype html>`, `lang="ar"`, `dir="rtl"`, `../print.css`, `../gheras_logo.png`, header center name `{{center_name}}` (also the logo `alt`), tagline «من غراس تبدأ المعرفة وتصنع القمة» (static), footer `{{center_address}}` + «هاتف: `{{center_phone}}`» + «صفحة 1 من 1». Manager signature blocks (`excellence_certificate`, `monthly_report`, `student_receipt`, `student_report`) render `{{manager_name}}` over `{{manager_title}}`.

Shared scalars supplied under every `print/*` payload by `routers/print.py::_resp()` from `system_settings` (migration 006, constants fallback in `services/settings.py`): `center_name`, `center_phone`, `center_address`, `manager_title`, `manager_name`, `academic_year`. Endpoint-specific keys win on collision. `<title>` is not rendered by `print_engine.js` (body only), so it stays static. All user-facing numerals are Western digits (0-9). Dates and money amounts are pre-formatted strings filled by the server; the «ريال» unit lives in the template.

Page geometries (body class):

- A4 portrait (no body class): `student_report`, `admin_report`, `monthly_report`, `student_receipt`, `lesson_log`, `statistics_report`
- A4 landscape (`page-a4-landscape`): `excellence_certificate`, `schedule`, `attendance_report`
- A5 portrait (`page-a5`): `guardian_card`
- A5 landscape (`page-a5-landscape`): `receipt`

## 1. receipt.html — سند قبض

Scalars: `receipt_no`, `date`, `student_name`, `method`, `amount`, `installment_ref`, `remaining_balance`, `amount_words`

Loops: none

## 2. guardian_card.html — بطاقة ولي الأمر

Scalars: `card_no`, `date`, `student_name`, `group_name`, `room_name`, `father_phone`, `mother_phone`, `guardian_phone`, `pickup_type`, `pickup_name`, `pickup_relation`, `pickup_phone`

Loops: none

## 3. excellence_certificate.html — شهادة تفوق

Scalars: `title`, `certificate_no`, `date` (used twice: header meta + signature block), `student_name`, `reason`

Loops: none

## 4. student_report.html — تقرير الطالب

Scalars: `report_no`, `date`, `student_name`, `group_name`, `room_name`, `guardian_phone`, `present_count`, `absent_count`, `late_count`, `excused_count`, `attendance_pct`, `teacher_notes`

Loops:

- `evaluations[]`: `subject`, `eval_type`, `date`, `value`, `teacher`
- `skills[]`: `subject`, `skill`, `level`, `date`
- `plans[]`: `subject`, `skill`, `goal`, `start_date`, `end_date`

## 5. admin_report.html — التقرير الإداري

Scalars: `period`, `date`, `students_count`, `attendance_pct`, `collected`, `outstanding`, `expenses`, `net`

Loops:

- `by_group[]`: `group`, `students`, `attendance_pct`
- `top_outstanding[]`: `student`, `amount`
- `recent_expenses[]`: `description`, `category`, `amount`, `date`

## 6. monthly_report.html — التقرير الشهري

Scalars: `month`, `date`, `total_income`, `total_expenses`, `payroll_total`, `net`, `closed_by`, `closed_at`

Loops:

- `collections[]`: `student`, `amount`, `method`, `date`
- `expense_categories[]`: `category`, `amount`

## 7. schedule.html — الجدول الأسبوعي

Scalars: `room`, `group`

Loops:

- `periods[]`: `start_time`, `end_time`, `sun`, `mon`, `tue`, `wed`, `thu`

## 8. attendance_report.html — تقرير الحضور

Scalars: `from`, `to`, `date` (issue date)

Loops:

- `dates[]`: `date` (one column header per day; shadows the top-level `date` inside the loop)
- `rows[]`: `student_name`, `present_count`, `absent_count`, nested `cells[]`
  - `cells[]`: `symbol` — one of `✓` (حاضر), `✗` (غائب), `م` (متأخر), `ع` (مستأذن); one cell per `dates[]` entry, in the same order

## 9. student_receipt.html — كشف حساب الطالب

Scalars: `statement_no`, `date`, `student_name`, `group_name`, `room_name`, `guardian_phone`, `total_amount`, `installments_count`, `interval_days`, `total_paid`, `balance`

Loops:

- `installments[]`: `seq`, `due_date`, `amount`, `paid_amount`, `status`
- `payments[]`: `date`, `receipt_no`, `method`, `amount`

## 10. lesson_log.html — سجل الحصص

Scalars: `room`, `subject`, `teacher`, `from`, `to`, `date` (issue date)

Loops:

- `logs[]`: `date`, `status` (تمت / مؤجلة / ملغاة), `covered`, `homework`, `notes`

## 11. statistics_report.html — تقرير الإحصائيات

Scalars: `period`, `date`, `students_count`, `attendance_pct`, `collection_pct`, `outstanding`, `boys_count`, `boys_pct`, `girls_count`, `girls_pct`, `notes`

Loops:

- `by_group[]`: `group`, `students`, `pct` (group share of total students; drives the CSS bar width)

## Assumptions

- Mustache context shadowing: loop item fields may share names with top-level scalars (`dates[].date` vs `date`); the loop context wins inside the loop.
- `attendance_report`: the totals column renders `present_count / absent_count` per student; legend symbols are static text.
- `statistics_report`: bars are inline-styled divs (`width: {{pct}}%`) using the print.css palette variables; the server clamps every percentage field to 0-100 with Western digits.
- `student_receipt`: `balance` = `total_amount` minus `total_paid` (see `v_student_balance`); `interval_days` is one of 7, 14, 30; `installments[].status` is rendered as an Arabic label (غير مدفوع / جزئي / مدفوع), not the raw DB value.
- `lesson_log`: `teacher` is the display name resolved from the schedule's teacher user; `status` is the Arabic DB value (تمت / مؤجلة / ملغاة).
- All documents are single-page; the footer page counter is static.
