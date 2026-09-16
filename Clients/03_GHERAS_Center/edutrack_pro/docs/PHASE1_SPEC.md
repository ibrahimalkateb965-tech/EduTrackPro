# EduTrack Pro — Phase 1 Architecture Spec (Gheras Center)

Owner: Autovem Staff Architect. Status: BINDING for all Phase 1 workers.
Source of truth for fields: `Clients/03_GHERAS_Center/تطبيق غراس.html` (MVP, 2,682 lines). Read it ONLY in chunked line ranges (max 80 lines per read). Never read the whole file.

## 0. Non-negotiables
- This is a bespoke system for **Gheras Center** (مركز غراس, حوطة بني تميم). Naming: package `sa.gheras.edutrack`, DB name `gheras_edutrack`.
- FORBIDDEN words anywhere in code, comments, SQL, or docs: `tenant`, `multi-tenant`, `SaaS`, `agency`, `Autovem`, `lease`. Future expansion is modelled ONLY as `branches` (فروع), which the client's own offer includes.
- Language: code/SQL/comments in English. Arabic ONLY in user-facing strings and seed/reference data (enum labels the MVP already uses).
- Numerals in any user-facing text: Western `0-9` only.
- Do NOT run tests, linters, `gradle`, `flutter`, `npm test`, or `psql`. Claude Code is the sole testing authority. Write code and stop.
- Web dashboard = management (manager/supervisor) only. Teachers and guardians use the mobile app. Do not build teacher screens in the web dashboard.
- Brand palette (from MVP CSS): `--g-teal:#0f8b8d`, `--g-teal-dark:#075f62`, `--g-orange:#f57c00`, `--g-text:#18343b`, `--g-muted:#6d7f84`, `--g-bg:#f4f8f8`, `--g-border:#e4ecee`, `--g-red:#d94b4b`, white `#fff`. Font: `Cairo` (Google Fonts) with `Tahoma` fallback. RTL everywhere (`dir="rtl"`).
- Logo asset: `edutrack_pro/assets/gheras_logo.png` (812×831 RGBA). Print templates reference `../gheras_logo.png` relative to `web/print/templates/`.

## 1. Folder layout
```
edutrack_pro/
  docs/PHASE1_SPEC.md              (this file)
  db/postgres/001_schema.sql       (Muse) full DDL, idempotent, one transaction
  db/postgres/002_reference.sql    (Muse) reference/enum seed rows (Arabic labels)
  db/sqlite/schema.sql             (Muse) mobile offline cache DDL (SQLite dialect)
  mobile/android/app/src/main/java/sa/gheras/edutrack/data/
      entity/*.kt                  (Muse) Room @Entity classes (one file per table)
      dao/*.kt                     (Muse) Room @Dao interfaces
      db/GherasDatabase.kt         (Muse) @Database + converters
      db/Converters.kt
  mobile/android/app/src/main/java/sa/gheras/edutrack/homework/
      ImageCompressor.kt           (Codex) pure-Kotlin planning + Android impl split (see §5)
      HomeworkEvaluator.kt         (Codex) pure Kotlin, no Android imports
      models.kt                    (Codex)
  mobile/android/homework-core/    (Codex) pure-JVM Gradle module holding the pure logic + unit tests
  web/dashboard/index.html         (GLM) shell: sidebar + topbar + view container
  web/dashboard/css/gheras.css     (GLM) tokens + components
  web/dashboard/js/api.js          (GLM) fetch client for /api/v1 (see §4)
  web/dashboard/js/app.js          (GLM) router + auth gate + view mounting
  web/dashboard/js/views/*.js      (GLM) one file per module (§3)
  web/print/print.css              (GLM) shared A4/A5 print stylesheet
  web/print/templates/*.html       (GLM) the 11 documents (§6)
  assets/gheras_logo.png
```

## 2. Data model (from MVP `data.*` collections → tables)
IDs: PostgreSQL `uuid` PK (`gen_random_uuid()`); SQLite/Room `TEXT` PK (uuid string). Every table: `id`, `branch_id` (FK → branches, default main branch), `created_at`, `updated_at`, `deleted_at NULL` (soft delete). Money: `numeric(12,2)` (PG) / `REAL` (SQLite) in SAR. Dates: `date`; timestamps `timestamptz`.

| MVP collection | Table(s) | Key columns (from MVP forms) |
| --- | --- | --- |
| `rooms` | `rooms` | name, group_name ∈ {الصباح, المساء, الإنجليزي, القدرات} |
| `students` | `students` | name, national_id, birth_date, nationality, has_difficulties bool, difficulty_notes, child_notes, father_name, father_phone, mother_name, mother_phone, guardian_phone, guardian_relation ∈ {الأب, الأم, ولي الأمر, شخص آخر}, pickup_type, pickup_name, pickup_relation, pickup_phone, previous_study bool, previous_school, previous_level, education_notes, room_id, group_name, status ∈ {active, dismissed, archived} |
| (new) | `guardians`, `student_guardians` | name, phone (unique), relation; link table with `is_primary` |
| `staff` | `staff` | name, role_title, base_salary, phone, hire_date, status |
| `users`, `permissions` | `users`, `user_permissions` | username (unique), password_hash (argon2id/bcrypt string, NEVER plaintext), role ∈ {manager, supervisor, teacher, guardian}, staff_id FK, guardian_id FK, room_id FK, is_active; permissions flags: attendance, daily_evaluation, monthly_evaluation, students, finance |
| `schedule` | `schedules` | room_id, teacher_user_id, day ∈ {الأحد..الخميس}, start_time, end_time, subject ∈ {القرآن, لغتي, الإنجليزي, الرياضيات}, group_name |
| `attendance` | `student_attendance` | student_id, date, status ∈ {حاضر, غائب, متأخر, مستأذن}, note, recorded_by_user_id; UNIQUE(student_id, date) |
| `staffAttendance` | `staff_attendance` | staff_id, date, status, note; UNIQUE(staff_id, date) |
| `payments`, `installments`, `receipts` | `fee_plans`, `installments`, `payments`, `receipts` | fee_plans: student_id, total_amount, count, start_date, interval_days ∈ {7,14,30}; installments: fee_plan_id, seq_no, due_date, amount, paid_amount, status; payments: student_id, installment_id NULL, amount, method ∈ {كاش, تحويل بنكي, تابي, تقسيط المركز, مدى, Apple Pay, بطاقة}, paid_on, note, gateway_ref NULL; receipts: payment_id, receipt_no (sequential integer, UNIQUE), issued_on |
| `expenses` | `expenses`, `expense_categories` | description, category_id, amount, paid_on, method, account_id, note; categories seeded from MVP list (رواتب وأجور, بدلات ومكافآت, إيجار, كهرباء, مياه, إنترنت واتصالات, هاتف وجوال, نظافة وتعقيم, صيانة وإصلاحات, أثاث وتجهيزات, أدوات ومستلزمات, قرطاسية وطباعة, كتب ومناهج, أنشطة وفعاليات, ضيافة, باص ونقل, وقود, أخرى) |
| `payroll` | `payroll_runs` | staff_id, month (YYYY-MM), base, allowances, bonus, deductions, advance_deducted, net (generated), note |
| `staffAdvances`, `staffAssets`, `staffMovements` | `staff_advances`, `staff_assets`, `staff_movements` | staff_id, amount/asset_name, date, note, settled bool |
| `accounts`, `accountMoves`, `reconciliation`, `monthClosures` | `ledger_accounts`, `ledger_entries`, `reconciliations`, `month_closures` | accounts: name, kind ∈ {cash, bank}, opening_balance; entries: account_id, entry_type ∈ {in, out, transfer_in, transfer_out}, amount, ref_table, ref_id, occurred_on; reconciliations: account_id, period, expected, actual, approved_by, approved_at; month_closures: month, closed_by, closed_at, totals_json |
| `assignments` | `assignments`, `assignment_students` | title, subject, kind, due_date, teacher_user_id, instructions, page_ref (e.g. "ص 12 تمرين 3") |
| (new) | `submissions`, `submission_files` | assignment_id, student_id, submitted_at, status ∈ {submitted, graded, returned}, grade numeric NULL, feedback; files: submission_id, storage_key, width, height, bytes, sha256 |
| `lessonLogs` | `lesson_logs` | schedule_id, date, status ∈ {تمت, مؤجلة, ملغاة}, covered, homework, notes, teacher_user_id |
| `studentPlans`, `skillProgress` | `study_plans`, `skill_progress` | plans: student_id, subject, skill, goal, start_date, end_date, activities, notes; progress: student_id, subject, skill, level ∈ {متميز, متقن, جيد, يحتاج متابعة, يحتاج دعم}, date, note |
| `evaluations` | `evaluations` | student_id, subject, eval_type ∈ {daily, weekly, monthly}, date, value, teacher_user_id |
| `followups`, `collectionFollowups` | `student_followups`, `collection_followups` | student_id, date, note, by_user_id; collection: installment_id, contacted_on, channel, outcome |
| `tasks` | `tasks` | title, student_id NULL, assignee_user_id NULL, priority ∈ {high, medium, low}, due_date, description, status |
| `parentMessages`, `communication` | `messages`, `message_templates` | student_id, channel ∈ {whatsapp, sms, app}, template_key, body, sent_at, status |
| `certificates` | `certificates` | student_id, title, issued_on, reason |
| `dismissals` | `dismissals` | student_id, date, method ∈ {استلام من ولي الأمر, استلام من شخص مصرح, الباص, استلام إداري بإذن}, receiver_name, receiver_relation, receiver_phone, receiver_id_no, note, recorded_by_user_id |
| `monthlyReports` | `monthly_reports` | month, generated_at, payload_json |
| `auditLog` | `audit_log` | actor_user_id, action, entity, entity_id, details_json, at — append-only (no UPDATE/DELETE grants) |
| (new) | `branches` | name, city, is_main |
| (new) | `notifications` | user_id, kind, title, body, read_at, sent_at |

Postgres extras required: `pgcrypto` extension; `receipts.receipt_no` from a sequence; check constraints on all enums above; indexes on every FK, on `(student_id, date)`, `(due_date)`, `(paid_on)`; a `v_student_balance` view (total planned − total paid per student); trigger `set_updated_at`.

## 3. Web dashboard modules (management only)
Sidebar order and route keys exactly as MVP: `home`, `students`, `attendance`, `payments`, `expenses`, `accounts`, `staff`, `rooms`, `reports`. Each view exports `render(container, api)` and uses semantic HTML, no framework, ES2020 modules. Auth gate: `POST /api/v1/auth/login` → JWT stored in memory + `sessionStorage`; roles allowed on web: `manager`, `supervisor`.

## 4. API contract (paths only; server is Phase 2)
Base `/api/v1`. JSON. Bearer JWT. Errors `{error:{code,message}}`.
`auth/login`, `auth/logout`, `me`;
CRUD (`GET list`, `GET :id`, `POST`, `PATCH :id`, `DELETE :id`) for: `students`, `guardians`, `staff`, `users`, `rooms`, `schedules`, `fee-plans`, `installments`, `payments`, `receipts`, `expenses`, `expense-categories`, `payroll-runs`, `staff-advances`, `staff-assets`, `ledger-accounts`, `ledger-entries`, `assignments`, `submissions`, `lesson-logs`, `study-plans`, `skill-progress`, `evaluations`, `tasks`, `messages`, `certificates`, `dismissals`, `branches`;
Reports: `reports/daily`, `reports/monthly?month=YYYY-MM`, `reports/student/:id`, `reports/attendance?from&to`, `reports/finance?from&to`, `audit-log`;
Attendance bulk: `POST attendance/students` (array), `POST attendance/staff`;
Print data: `print/receipt/:paymentId`, `print/guardian-card/:studentId`, `print/certificate/:certificateId`, `print/student-report/:studentId`, `print/admin-report`, `print/monthly-report?month`, `print/schedule?room`, `print/attendance-report?from&to`, `print/student-receipt/:studentId`, `print/lesson-log?schedule&from&to`, `print/statistics`.

## 5. Homework engine (Codex) — mobile
Pure-JVM Gradle module `mobile/android/homework-core` (Kotlin JVM, no Android dependencies) containing:
- `CompressionPlanner`: given source width/height/bytes and target constraints (max long edge 1600 px, max 600 KB, JPEG quality ladder 85→50), returns a deterministic `CompressionPlan` (scale factor, target dims, quality steps). Android `ImageCompressor` (in `app/.../homework/`) applies the plan with `BitmapFactory`/`Bitmap.compress` and EXIF orientation fix — that file may import Android classes and is NOT unit-tested here.
- `HomeworkEvaluator`: rubric-based scoring. Inputs: `Rubric(criteria: List<Criterion(key, weight 0..1, maxScore)>)`, `TeacherMarks(map criterionKey → score)`, `Submission(submittedAt, dueDate, pageCount)`. Output `EvaluationResult(total 0..100, level ∈ {متميز, متقن, جيد, يحتاج متابعة, يحتاج دعم}, latePenaltyApplied, breakdown)`. Level thresholds: ≥90 متميز, ≥80 متقن, ≥70 جيد, ≥50 يحتاج متابعة, else يحتاج دعم. Late penalty: 5 points per day late, cap 20, never below 0. Weights must sum to 1.0 ± 0.001 else `IllegalArgumentException`.
- Unit tests in `homework-core/src/test/kotlin` using kotlin-test/JUnit5 covering: weight validation, boundary thresholds (49.99/50/69.99/70/79.99/80/89.99/90), late penalty cap, zero-page submission rejection, compression plan for portrait/landscape/tiny/huge inputs.
- Gradle: `settings.gradle.kts` + `build.gradle.kts` with `kotlin("jvm") version "2.1.20"`, JUnit5, JVM toolchain 17. No Android plugin in this module.

## 6. The 11 printable documents (GLM)
Every template: A4 portrait unless noted, `dir="rtl"`, header with logo (left in RTL = `float:inline-start`), center name «مركز غراس», document title, document number/date; footer with «حوطة بني تميم» and page counter. Colors: headings `--g-teal-dark`, accents `--g-orange`, table header bg `--g-teal` white text, borders `--g-border`. Fields are Mustache-style placeholders `{{field}}` and row loops `{{#rows}}…{{/rows}}` so the server can fill them.

| # | File | MVP function | Notes |
| --- | --- | --- | --- |
| 1 | `receipt.html` | printGherasReceipt/printReceipt | A5 landscape, receipt_no, student, amount in digits + words, method, remaining balance, two signature lines |
| 2 | `guardian_card.html` | printParentCard | A5, student photo placeholder, group, room, guardian phones, pickup authorization, QR placeholder |
| 3 | `excellence_certificate.html` | printExcellenceCertificate/printCertificate | A4 landscape, ornamental border in teal/orange, student name large, reason, date, manager signature |
| 4 | `student_report.html` | printStudentReport | attendance summary, evaluations per subject, skill progress table, plans, teacher notes |
| 5 | `admin_report.html` | printAdminReport | KPI tiles (students, attendance %, collected, outstanding, expenses), tables |
| 6 | `monthly_report.html` | printMonthlyReport | income vs expenses, payroll total, collections, month closure summary |
| 7 | `schedule.html` | printSchedule | weekly grid الأحد..الخميس × periods per room |
| 8 | `attendance_report.html` | openAttendanceReport (print) | date range, per-student presence matrix |
| 9 | `student_receipt.html` | printStudentReceipt | student account statement: plan, installments, payments, balance |
| 10 | `lesson_log.html` | openLessonLogManager (print) | per schedule: date, status, covered, homework, notes |
| 11 | `statistics_report.html` | printCurrentReport / buildAdminReportsHTML | general statistics: counts by group, gender split (بنين/بنات), collection rate charts as CSS bars |

## 7. Definition of done for each worker
- Files exist at the exact paths above, UTF-8, no BOM.
- No forbidden words (§0). No secrets. No external CDN except Google Fonts (Cairo).
- SQL parses; Kotlin has no unresolved references you can see; JS is valid ES modules.
- Leave a short `NOTES.md` next to your output listing assumptions.
