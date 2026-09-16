# Dashboard Notes

Scope of this pass: authored `js/views/rooms.js` and `js/views/reports.js` following the existing view pattern (`js/ui.js` helpers + `js/views/accounts.js` structure). No other files were read or modified.

## View contract (confirmed from accounts.js / ui.js)

- Every view exports `async function render(container, api)`.
- `api` exposes `get / post / patch / del`, all returning parsed JSON.
- Responses may be a bare array or `{ items: [...] }` — handled by a local `toList()`.
- Shared UI: `el()`, `toast()`, `modal()`, `fmtSAR()`, `fmtDate()` from `js/ui.js`; form classes `form-grid`, `form-actions`, `button`, `button-outline`, `muted`.

## Assumptions (unverified, needs backend check)

1. **Enum values are the Arabic labels themselves**: `group_name` (الصباح / المساء / الإنجليزي / القدرات), `day` (الأحد … الخميس), `subject` (القرآن / لغتي / الإنجليزي / الرياضيات). If the backend stores keys (e.g. `morning`), the select option values must be remapped.
2. **`POST schedules` body**: `{ room_id, day, start_time, end_time, subject, teacher_user_id }`; times are `HH:MM` 24-hour strings from `<input type="time">`.
3. **`GET users?role=teacher`** returns user objects with `id` plus `name` / `full_name` / `username` (first present wins for display). Schedule rows may carry `teacher_name` already — used when present.
4. **`GET reports/daily`** returns a flat object of counts/totals; unknown keys fall back to the raw key name. Keys matching `total|amount|net|balance|revenue` are rendered via `fmtSAR`.
5. **`GET audit-log`** rows: `at` (ISO timestamp), `actor`, `action`, `entity`.
6. **Print query param names**: only `room` is confirmed (`schedule.html?room=ID`). Assumed for the rest: `student`, `reason`, `payment`, `from`, `to`, `month`.
7. **Print base path** `../print/templates/` relative to the dashboard URL; all print links open in a new tab (`target="_blank" rel="noopener"`). The receipt card takes a raw payment id number (no dependency on a payments list endpoint).
8. **Structural CSS classes** used by the new views (`panel`, `panel-head`, `panel-tools`, `table`, `cards-grid`, `card`, `stats`, `stat`) are assumptions — only the classes listed in the view contract above are confirmed from existing code.
9. **Schedule section behavior**: room select defaults to the first room; schedules sort by weekday order (الأحد → الخميس) then start time; «طباعة الجدول» is disabled until a room exists.
10. **Digits**: Western digits only (Rule 50) — counts via `String()`, dates via `Intl` `en-CA`, times via `en-GB`, money via `fmtSAR`.
11. No frameworks, no CDN, plain ES2020 modules. `receipt.html` card uses a number input for the payment id.

## API endpoints by view (9 views + login)

| View | Endpoints | Status |
| --- | --- | --- |
| login | `POST auth/login` (name unknown) | Assumption |
| home.js | `GET reports/daily` or a dashboard summary endpoint | Assumption (file not read) |
| students.js | `GET students`, `POST students`, `PATCH students/:id`, `DEL students/:id` | Assumption (file not read) |
| teachers.js | `GET users?role=teacher`, `POST users`, `PATCH users/:id`, `DEL users/:id` | Assumption (file not read) |
| attendance.js | `GET attendance?date=`, `POST attendance` | Assumption (file not read) |
| payments.js | `GET payments`, `POST payments`, `PATCH payments/:id` | Assumption (file not read) |
| expenses.js | `GET expenses`, `POST expenses`, `PATCH expenses/:id` | Assumption (file not read) |
| accounts.js | `GET ledger-accounts`, `GET ledger-entries` (verified); `POST ledger-accounts`, `POST ledger-entries` (inferred, beyond line 80) | Partially verified |
| rooms.js (new) | `GET rooms`, `POST rooms`, `PATCH rooms/:id`, `GET schedules?room_id=:id`, `POST schedules`, `GET users?role=teacher` | Authored this pass |
| reports.js (new) | `GET students`, `GET rooms`, `GET reports/daily`, `GET audit-log` | Authored this pass |

## Print documents surfaced in reports.js (11)

| Template | Params |
| --- | --- |
| receipt.html | payment |
| guardian_card.html | student |
| excellence_certificate.html | student, reason |
| student_report.html | student |
| admin_report.html | from, to |
| monthly_report.html | month (defaults to current month) |
| schedule.html | room |
| attendance_report.html | from, to |
| student_receipt.html | student |
| lesson_log.html | room, from, to |
| statistics_report.html | from, to |

Param names other than `room` are assumptions — align them with the query strings the print templates actually parse before shipping.
