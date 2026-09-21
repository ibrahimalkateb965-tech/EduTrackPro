# EduTrack Pro — Phase 5 (c) Design: Android App (teacher / guardian)

- **Owner**: Autovem Master Architect (Claude Code CLI)
- **Client**: Gheras Center (`Clients/03_GHERAS_Center`)
- **Status**: **[APPROVED — Sections 1–8 approved by Fleet Lead 2026-09-21; design complete, execution starts with §8 Wave 0]**
- **Baseline**: `7eeeada` on `main` (production = Phase 5 (b) live: `/api/v1/me/*` 15 routes, web v=3.0)
- **Process**: superpowers brainstorming, architectural path. All sections approved (chat, 2026-09-21). Next: `superpowers:writing-plans` → fleet batches (§8), Wave 0 (S1) first.
- **Binding API contract**: `docs/PHASE5_SPEC.md` §2 (routes, envelope, role matrix), §3.1 (student projections), §4.1 (Arabic error table).

---

## 0. Decisions already taken (chat, 2026-09-21 ~03:30–05:15)

| # | Question | Decision |
| :--- | :--- | :--- |
| D1 | Feature tier | **(B)** Read-only app for both roles **+ the four teacher writes that already exist server-side**: `POST /me/lesson-logs` (upsert), `POST /attendance/students`, `POST /evaluations/daily`, `POST /me/notifications/{id}/read`. Homework camera upload (`POST /me/submissions` + storage) deferred — needs its own server spec first. |
| D2 | Offline depth | **Level (2)**: Room as read cache + durable write outbox (`pending_writes` + WorkManager flush + optimistic UI). Safe because all four writes are idempotent upserts on natural keys. Full bidirectional delta sync rejected (server has no `since=`). |
| D3 | Build & test loop | **(2)**: JVM unit tests locally under a small Gradle daemon; `assembleDebug` + lint on GitHub Actions (`ubuntu-latest`, workflow at repo root `.github/workflows/android.yml`, `paths:` scoped to `Clients/03_GHERAS_Center/edutrack_pro/mobile/**`), debug APK as artifact for the demo phones. Reason: workstation had ~2 GB RAM free; a Compose+KSP build wants ~3–4 GB. |
| D4 | Session lifetime | **(2)**: per-role JWT TTL on the server — `JWT_TTL_MINUTES` (720) stays for manager/supervisor; new `MOBILE_JWT_TTL_MINUTES` (default 30 days) for teacher/guardian. ~10 lines in `auth.py`/`config.py` + 2 tests, deployed with `push.sh`. Existing revocation (`revoked_tokens`, `/auth/logout`) covers lost phones. App still handles 401 gracefully (Section 4). Refresh tokens rejected for now (migration + rotation semantics). |
| D5 | Structure | **(A)** single `:app` module + existing `:homework-core`, layered by package, **manual DI** (`AppContainer`, no Hilt — Room is the only KSP consumer). Multi-module + Hilt rejected (7 modules × KSP on a memory-tight machine); network-only ViewModels rejected (throws away the 15 entities). |
| D6 | Fixed engineering choices | `minSdk 26` (java.time without desugaring), `targetSdk 35`, `compileSdk 35`, Kotlin 2.1.20, AGP 8.7.x, Compose BOM + Material 3, Navigation Compose, WorkManager, `EncryptedSharedPreferences` for the token, Arabic-only RTL UI, Rule 50 numerals via `Locale.ROOT`, package `sa.gheras.edutrack`. |

### Code facts that motivated the design (verified 2026-09-21)

- `mobile/android/app/.../data/` holds 15 Room entities + 15 DAOs + `Converters` (`Instant`/`LocalDate`/`LocalTime` ↔ text) + `GherasDatabase` v1 (`exportSchema = false`). **No Gradle build, manifest, UI, networking or auth exist.** Room/KSP has never compiled.
- `mobile/android/homework-core/` is a standalone Gradle project (Kotlin JVM 2.1.20, JUnit 5, toolchain 17), 13/13 tests green; `app/.../homework/ImageCompressor.kt` is its Android half (static review only).
- Auth: `POST /api/v1/auth/login {username, password}` → `{token, user{id, username, role, name}}`; JWT HS256, `exp = now + jwt_ttl_minutes` (720), `jti` revocation via `revoked_tokens`.
- Only `/me/students` is a projection (§3.1: no timestamps, no `branch_id`; adds `gender`, `room_name`); every other list returns `table.*` plus join columns (`student_name`, `room_name`, `teacher_name`, `assignment_title`, `files[]`, `student_ids[]`, `plan_total`, `plan_count`). Guardian `/me/lesson-logs` omits `notes`.
- All 15 entities have **non-null** `createdAt`/`updatedAt`. No `installments`/`receipts` entities exist. `UserEntity` carries `passwordHash`. `StudentEntity` lacks `gender`.
- Teacher writes are idempotent: `student_attendance` `ON CONFLICT (student_id, date)`, daily evaluations SELECT-then-write on `(student_id, date, subject, eval_type='daily')`, lesson logs SELECT-then-write on `(schedule_id, date)`.
- Environment: Android SDK present (platforms 34–36.1, build-tools ≤ 37), JDK 17, Gradle 9.4.1; `ANDROID_HOME` unset; 16 GB RAM, ~2 GB free at design time.
- Commercial target (Grand Slam Package 2): Android app for teachers + guardians with Gheras branding, attendance + grades, demo APK on management/teacher phones (day 25), Google Play later.

---

## 1. Module layout & build — **[APPROVED 2026-09-21]**

```
mobile/android/
├── settings.gradle.kts          rootProject "gheras-edutrack"; include(":app", ":homework-core")
├── build.gradle.kts             plugins { agp, kotlin-android, kotlin-compose, ksp, kotlinx-serialization } apply false
├── gradle.properties            -Xmx2g daemon, org.gradle.workers.max=2, build cache + configuration cache on, no parallel
├── gradle/libs.versions.toml    single version catalog (AGP 8.7.3, Kotlin 2.1.20, KSP 2.1.20-x, Compose BOM 2025.x,
│                                Room 2.7, Retrofit 2.11, OkHttp 4.12, kotlinx-serialization 1.8, WorkManager 2.10,
│                                Navigation Compose 2.8, security-crypto 1.1, JUnit 4 + Turbine + coroutines-test + MockWebServer)
├── gradlew / gradle/wrapper     wrapper 8.11 (AGP 8.7 ceiling; system Gradle 9.4.1 no longer used for homework-core)
├── homework-core/               unchanged except: own settings.gradle.kts deleted (becomes a subproject),
│                                plugin version moves to the catalog; .gradle/ and replay_pid*.log deleted + gitignored
└── app/
    ├── build.gradle.kts         namespace sa.gheras.edutrack, minSdk 26, target/compile 35, Room schema export → app/schemas/
    ├── schemas/                 Room exported JSON (committed; migration tests read it)
    └── src/main/
        ├── AndroidManifest.xml  INTERNET, supportsRtl, single Activity, WorkManager on-demand init
        ├── res/                 Arabic strings as default, Gheras colours, adaptive icon placeholder,
        │                        debug-only network_security_config.xml (cleartext for emulator)
        └── java/sa/gheras/edutrack/
            ├── GherasApp.kt           Application: builds AppContainer, schedules OutboxWorker
            ├── di/AppContainer.kt     manual graph: OkHttp(auth interceptor) → Retrofit → MeApi/AuthApi; Room db; repos; SessionStore
            ├── data/local/            existing entity/ + dao/ + db/ (+ PendingWriteEntity, SyncStateEntity, Installment/Receipt entities + DAOs)
            ├── data/remote/           AuthApi, MeApi, dto/*.kt, Envelope<T>, ApiErrorBody, ErrorMapper, AuthInterceptor
            ├── data/repo/             SessionRepository, StudentsRepository, ScheduleRepository, AttendanceRepository,
            │                          EvaluationsRepository, AssignmentsRepository, LessonLogsRepository, FeesRepository,
            │                          NotificationsRepository, OutboxRepository
            ├── sync/                  PullSync (13 GETs → Room), Outbox (enqueue/flush), OutboxWorker (WorkManager)
            ├── homework/              existing ImageCompressor.kt (compiles, unused this phase)
            └── ui/                    theme/, nav/, login/, teacher/, guardian/, common/ (Section 5)
```

- Two builds become one: `:homework-core:test` runs from the root; its 13 tests must stay green.
- Room `exportSchema = true`, **version stays 1** — all schema edits in Section 2 land before the first APK; no migration.
- Debug-only this phase: single application id (no `.debug` suffix, so demo phones do not collect duplicates), no release signing; Play Console signing is a later phase.
- `BuildConfig.BASE_URL` from `local.properties` / CI env; default `https://gheras.autovem.tech/api/v1/`, emulator `http://10.0.2.2:8000/api/v1/`.

---

## 2. Data layer — **[APPROVED 2026-09-21, with Ibrahim's 3 integrity constraints]**

### 2.1 Remote (`data/remote/`)

- `AuthApi`: `login`, `logout`, `changePassword`. `MeApi`: one function per §2 route; list calls return `Envelope<T> = {items, total, limit, offset}`. Retrofit + kotlinx.serialization converter, `Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = false }`.
- One `@Serializable` DTO per route (`StudentDto`, `ScheduleDto`, `AttendanceDto`, `EvaluationDto`, `AssignmentDto`, `SubmissionDto`, `LessonLogDto`, `SkillProgressDto`, `InstallmentDto`, `ReceiptDto`, `NotificationDto`, `ProfileDto`; bodies `LoginBody`, `LessonLogBody`, `AttendanceItemBody`, `DailyEvalItemBody`). Dates/times are `String` in DTOs, parsed once in mappers. Numbers `Double` (Rule 9).
- `ApiErrorBody {code, message}` mirrors `ApiError`; `ErrorMapper` → sealed `AppError { Unauthorized, Forbidden(msg), NotFound, Validation(msg), Offline, Server(msg) }`. Server Arabic `message` shown verbatim.
- `AuthInterceptor` adds `Authorization: Bearer <token>`; any `401` → `Unauthorized` → `SessionState.Expired` (Section 4).

### 2.2 Local (`data/local/`) — edits to the Muse-generated layer, all before Room v1 ships

- **Remove** `UserEntity`/`UserDao` (no credential persistence on device), `GuardianEntity`/`GuardianDao`, `StudyPlanEntity`/`StudyPlanDao` (no `/me/*` source). Git history keeps them.
- **Add** `InstallmentEntity` + `ReceiptEntity` + DAOs; columns copied from `db/postgres/001_schema.sql` (`installments`, `receipts`, `payments`) during the plan; monetary values `Double`.
- **Add** `PendingWriteEntity` (`id TEXT PK uuid, kind {ATTENDANCE, DAILY_EVAL, LESSON_LOG, NOTIFICATION_READ}, payload_json, created_at, attempts, last_error?`) and `SyncStateEntity` (`resource TEXT PK, last_pull_at, last_total`) + DAOs. `pending_writes` has **no FK** to synced tables.
- **Amend** `StudentEntity`: add nullable `gender`. Join-only display columns (`room_name`, `student_name`, `teacher_name`, `assignment_title`) are **not stored**; DAO queries JOIN the cached `rooms`/`students`/`schedules`/`assignments`. `submissions.files[]` → `SubmissionFileEntity` rows.
- `exportSchema = true`, version **1**.

### 2.3 Mappers & repositories (`data/repo/`)

- `toEntity(dto, pulledAt)`: server timestamps when present; for `/me/students` (projected) `createdAt = updatedAt = pulledAt` (never displayed).
- **Room synthesis for guardians**: no `/me/rooms` for guardians, so the mapper upserts a minimal `RoomEntity(id, name, groupName = "")` for every distinct `(room_id, room_name)` seen in student/schedule rows **before** inserting students (FK `RESTRICT` holds). Teacher pulls overwrite with real rows.
- Repositories expose `Flow<List<…>>` from DAOs + `suspend fun refresh()` (→ `PullSync`) + for the four writes `suspend fun submit(…)` (→ `Outbox`). Only `PullSync` touches Retrofit and Room in one function.
- **Scope replacement** (Ibrahim's constraints 1–2): each pull replaces a resource's cache by `clear()` + `upsertAll()` inside **one `@Transaction` DAO method** (`replaceScope`) so Flow observers emit once (no flicker). Clears run in reverse-dependency order: `SubmissionFileEntity → SubmissionEntity → AssignmentStudentEntity → AssignmentEntity → StudentAttendanceEntity / EvaluationEntity / SkillProgressEntity / LessonLogEntity → ReceiptEntity → InstallmentEntity → StudentEntity → ScheduleEntity → RoomEntity` (notifications are FK-free). **`pending_writes` is never cleared by a pull** — rows leave it only after a `2xx` or a terminal 4xx (Section 3). Immediately after `upsertAll`, still-pending outbox rows are re-applied to the cache inside the same transaction so offline ticks never vanish.

---

## 3. Pull sync & outbox — **[APPROVED 2026-09-21]**

### 3.1 `PullSync` (`sync/PullSync.kt`)

- Triggers: app foreground (skipped if `sync_state.last_pull_at` < 5 min ago), pull-to-refresh (unthrottled), after every successful outbox flush.
- Order — teacher: `profile → rooms → students → schedule → attendance → evaluations → assignments → submissions → lesson-logs → skill-progress → notifications`; guardian: same minus `rooms`, plus `installments → receipts`. Profile first: an empty teacher scope shows «لم تُسند لك حلقة بعد — راجع الإدارة» instead of empty lists.
- Paging: `limit=500`, loop `offset` until `offset ≥ total`. Cache window: `attendance`, `evaluations`, `lesson-logs`, `skill-progress` = last 60 days (`date_from`); `assignments` due within ±60 days; `submissions`, `notifications` unbounded (small).
- All-or-nothing **per resource**: a failure on page N leaves that resource's previous cache intact (its `replaceScope` never commits); other resources still refresh. `sync_state` records `last_pull_at` per resource; UI shows the oldest as «آخر تحديث: قبل N دقيقة».
- Errors: `Offline` → silent; `Unauthorized` → `SessionState.Expired`; `Forbidden` on `/me/profile` (manager on the phone app / unlinked guardian) → login screen with the server message.

### 3.2 `Outbox` (`sync/Outbox.kt`, `sync/OutboxWorker.kt`)

- `enqueue(kind, payload)` in one transaction: insert `PendingWriteEntity`, then apply the optimistic projection:
  - `ATTENDANCE` (batch `{student_id, date, status, note}`) → upsert `StudentAttendanceEntity` with `id = "local:<student_id>:<date>"`, `recordedByUserId = me`.
  - `DAILY_EVAL` (batch `{student_id, date, subject, value}`) → upsert `EvaluationEntity` with `id = "local:<student_id>:<date>:<subject>"`, `evalType = "daily"`.
  - `LESSON_LOG` (`{schedule_id, date, status, covered, homework, notes}`) → upsert `LessonLogEntity` with `id = "local:<schedule_id>:<date>"` or the existing server id (`getByScheduleAndDate`).
  - `NOTIFICATION_READ` (`{id}`) → set `readAt = now`.
  `local:` ids are internal; the next pull replaces them with server rows (same natural key) and `replaceScope` re-applies anything still pending.
- Coalescing: a new enqueue for the same natural key **replaces** the pending row (last write wins locally, one request server-side).
- `OutboxWorker`: WorkManager unique work `"outbox-flush"`, `ExistingWorkPolicy.KEEP`, `NetworkType.CONNECTED`, exponential backoff from 30 s; enqueued on every `enqueue()` and on app start. Flush FIFO by `created_at`, one request per row: `POST /attendance/students`, `POST /evaluations/daily`, `POST /me/lesson-logs`, `POST /me/notifications/{id}/read`.
  - `2xx` → delete the row; response rows upserted into the cache (server ids replace `local:`).
  - `401` → stop, `SessionState.Expired`, keep all rows, worker `retry`; resumes after re-login.
  - `403` / `404` / `422` (terminal) → `attempts = -1`, `last_error = server message`, **revert** the optimistic projection, surface in a «تعذّر الحفظ» list on the home screen with a dismiss action. Never auto-retried.
  - `5xx` / IO → `retry` with backoff; after 10 attempts the row stays queued, badge reads «بانتظار الاتصال منذ N ساعة». Nothing is dropped.
- UI contract: `OutboxRepository.pendingCount: Flow<Int>` and `failed: Flow<List<FailedWrite>>` drive an app-bar badge (`⟳ N`) and the failure list. The four teacher forms save instantly (enqueue + navigate back), no network spinner.

---

## 4. Session management, auth lifecycle & navigation guards — **[APPROVED 2026-09-21 — D4-a purge included; password-change revocation deferred to §9; renewal banner at 3 days]**

### Code facts behind this section (verified 2026-09-21 against `7eeeada`)

- `server/edutrack_api/auth.py`: `issue_token(user_row, settings)` uses `settings.jwt_ttl_minutes` for **every** role; claims `sub, role, jti, iat, exp`. `current_user` answers 401 when the `jti` is in `revoked_tokens`, the user is missing/soft-deleted, **or `users.is_active = false`** → a manager deactivating a user is an immediate kill switch for a lost phone, independent of token TTL.
- `server/edutrack_api/routers/auth.py`: `POST /auth/login` → 401 `unauthorized` «بيانات الدخول غير صحيحة»; `POST /auth/logout` → 204, inserts `(jti, expires_at = exp)` into `revoked_tokens`; `POST /auth/change-password {current_password, new_password, confirm_password?}` → `{status: "ok", message: "تم تغيير كلمة المرور بنجاح"}`; its four error cases are **HTTP 400** (`validation_error` ×3, `invalid_credentials`), not 422. **Changing the password revokes nothing** — the caller's token and every other device's token stay valid until `exp`.
- `db/postgres/003_phase2.sql`: `revoked_tokens (jti PK, expires_at)` with `idx_revoked_tokens_expires_at`; **no statement anywhere deletes expired rows**.
- `server/edutrack_api/config.py`: frozen `Settings` dataclass, `@lru_cache get_settings()`, env parsed once; `tests/conftest.py` pins `JWT_TTL_MINUTES=60`; `PyJWT>=2.9` is a runtime dependency (tests can decode claims).
- `GET /me/profile` → `{user{id, username, role, name}, scope{room_ids, student_ids}, center{name, phone}}`; 403 «هذه الواجهة مخصصة لتطبيق المعلم وولي الأمر» for manager/supervisor, 403 «حساب ولي الأمر غير مرتبط بطالب — راجع إدارة المركز» for an unlinked guardian (PHASE5_SPEC §4.1).

### 4.1 `SessionStore` (`data/local/session/`)

```kotlin
interface SessionStore {
    val token: String?
    val tokenExp: Instant?          // parsed from the JWT payload, display / pre-check only
    val user: SessionUser?          // {id, username, role, name} from the login response
    val profile: ProfileDto?        // GET /me/profile body, verbatim
    val lastUserId: String?
    fun saveLogin(token: String, user: SessionUser)   // one synchronous commit(): token + token_exp + user_json + last_user_id
    fun saveProfile(profile: ProfileDto)
    fun clearToken()                                   // Expired: removes token + token_exp only
    fun clear()                                        // Logout: removes everything
}
class EncryptedPrefsSessionStore(context: Context) : SessionStore
```

- One implementation on `EncryptedSharedPreferences` (file `gheras_session`, `MasterKey` `AES256_GCM`, keys `AES256_SIV`, values `AES256_GCM`). The interface exists so `AppContainer` can swap in a Keystore+DataStore store later (`security-crypto` is in maintenance mode) and so tests use an in-memory fake — callers never see the prefs API.
- Keys: `token` (String), `token_exp` (Long, epoch seconds — read from the JWT's base64 payload **without signature verification**; the server stays authoritative, the phone only uses it to avoid sending a doomed request and to show the renewal banner), `user_json`, `profile_json`, `last_user_id`.
- Never stored: password, `password_hash`, anything from other users. `profile_json` carries only ids, names and the centre phone.
- Built once in `AppContainer` on the main thread inside `GherasApp.onCreate()` (first run ≈ 100 ms for Keystore key generation, afterwards a few ms). This makes the initial `SessionState` known synchronously before `setContent` — no splash screen, no `Loading` state, no login-screen flash on cold start.
- `saveLogin` uses `commit()` (synchronous) so the `AuthInterceptor` can never observe a half-written session; `saveProfile`/`clearToken` may `apply()`; `clear()` uses `commit()`.
- Self-heal: opening the prefs throws (`GeneralSecurityException` / `IOException` / `AEADBadTagException`) when the Keystore master key is gone or the file came from another device → delete `gheras_session` and start `SignedOut` instead of crash-looping. Manifest: `android:allowBackup="false"` and `dataExtractionRules` exclude `gheras_session` and the Room database (session material and another user's cache must never ride a device backup).

### 4.2 `SessionState` and `SessionRepository` (`data/repo/SessionRepository.kt`)

```kotlin
enum class Role { TEACHER, GUARDIAN }                          // manager/supervisor never reach Active
data class SessionUser(val id: String, val username: String, val role: Role, val name: String)

sealed interface SessionState {
    data object SignedOut : SessionState                        // no token, no user
    data class Active(val role: Role, val user: SessionUser) : SessionState
    data class Expired(val username: String) : SessionState     // user known, token gone; cache + outbox intact
}
```

- `SessionRepository.state: StateFlow<SessionState>`; initial value from the store: `token != null` → `Active`; `token == null && user != null` → `Expired(user.username)`; otherwise `SignedOut`. If `tokenExp < now` at init or on every foreground → `onUnauthorized()` locally (no round-trip).
- Pending-write count is **not** duplicated into the state — the login screen combines `state` with `OutboxRepository.pendingCount`.
- The only transitions (everything else is a read):

| From → To | Trigger | Effects |
| :--- | :--- | :--- |
| `SignedOut` / `Expired` → `Active` | `login()` success (4.3) | store written, outbox resumed, full pull requested |
| `Active` → `Active` | `renew()` = `login()` while still valid (4.4) | same user id → token replaced, nothing else touched |
| `Active` → `Expired` | `onUnauthorized()` — 401 on a bearer request, local `exp` check, or role mismatch on `/me/profile` | `clearToken()` only; user, profile, Room cache, outbox rows all kept; outbox pauses (4.4) |
| `Active` / `Expired` → `SignedOut` | `logout()` (4.5) | server revoke best-effort → Room wipe → prefs clear |
| `Expired` → `SignedOut` | «تسجيل الدخول بحساب آخر» after the discard confirmation | local wipe + clear only (no token to revoke) |

- `onUnauthorized()` is idempotent: `_state.update { if (it is Active) Expired(it.user.username) else it }` — concurrent 401s from `PullSync` and `OutboxWorker` collapse into one transition, and a 401 that arrives during logout is a no-op.

### 4.3 Login flow (`ui/login/LoginViewModel` → `SessionRepository.login()`)

1. Local checks: both fields non-empty. `AuthApi.login(LoginBody)` — no bearer header.
2. Errors: 401 → the server message «بيانات الدخول غير صحيحة» inline, no state change; `Offline` → «لا يوجد اتصال بالإنترنت — تسجيل الدخول يتطلب الاتصال»; 5xx/IO → «تعذّر الاتصال بالخادم — حاول لاحقًا».
3. **Role gate** (before anything is stored): `user.role ∉ {teacher, guardian}` → best-effort `POST /auth/logout` with the fresh token (revokes it), show «هذا التطبيق مخصص للمعلمين وأولياء الأمور — استخدم لوحة التحكم على الويب», remain `SignedOut`.
4. **Identity gate**: Room is wiped (`clearAllTables()`) unless `lastUserId == user.id`. A `null` `lastUserId` with a non-empty database (crash between the wipe and the clear in 4.5) is treated as "different user". From `Expired` the user can only reach a different account through the discard confirmation in 4.4, so this wipe never surprises anyone.
5. `store.saveLogin(token, user)` — **no state emission yet** (the `StateFlow`, not the store, drives navigation).
6. `MeApi.profile()` (bearer now attached): 200 → `saveProfile`; also if `profile.user.role != user.role` → treat as 403. 403 → `store.clear()` + wipe → stay `SignedOut`, show the server's Arabic message (unlinked guardian). `Offline`/5xx → ignore; `PullSync` fetches the profile first thing anyway (§3.1).
7. `_state.value = Active(role, user)` → root graph swaps (4.7). Then `Outbox.schedule(ExistingWorkPolicy.REPLACE)` if `pendingCount > 0`, and `PullSync.requestFull()`.

Login screen states (`LoginMode`):
- **Fresh** (`SignedOut`): logo, username (`KeyboardType.Text`, no autocorrect), password (visibility toggle, `KeyboardType.Password`, `ImeAction.Done` submits), button, inline error, `BuildConfig.BASE_URL` host in small text on **debug builds only**.
- **Expired** (`Expired(username)`): username prefilled and locked, banner «انتهت صلاحية الجلسة — أدخل كلمة المرور للمتابعة»; when `pendingCount > 0`: «لديك N تغييرات لم تُرفع بعد — سجّل الدخول بالحساب نفسه لرفعها»; link «تسجيل الدخول بحساب آخر» → if `pendingCount > 0` a dialog «سيتم حذف N تغييرات غير مرفوعة. هل تريد المتابعة؟» → local wipe → **Fresh**.
- **Renew** (`Active`, opened from the renewal banner in 4.4): same as Expired minus the expiry wording; success replaces the token, no navigation.
- Back press on the login screen exits the app (no screen behind it).

### 4.4 401 recovery — pause, notify, resume without data loss

- **Detection, one place**: `AuthInterceptor` (OkHttp `Interceptor`, not `Authenticator`) attaches `Authorization: Bearer` when a token exists, then inspects the response: `401` **and** the request carried a bearer **and** the request is not tagged `SkipExpiry` (used only by logout) → `sessionRepository.onUnauthorized()`. The response still flows to the caller, whose `ErrorMapper` yields `AppError.Unauthorized`. Consequences: a login 401 (wrong password, no bearer) can never be mistaken for expiry; every other route — pulls, flushes, change-password — funnels into the same transition.
- **Local pre-check**: on foreground and before each `PullSync`/flush, `tokenExp < now` → `onUnauthorized()` with no request (no doomed round-trip, works offline). Skew policy: a fast device clock forces an early re-login (safe, mildly annoying); a slow one is caught by the server 401.
- **Outbox pause**: `OutboxWorker.doWork()` starts with `if (session.state.value !is Active) return Result.retry()` — no network, no `attempts++`, rows untouched, WorkManager backs off (30 s → … → 5 h cap). A 401 mid-flush leaves the failing row unchanged (attempts not incremented) and returns `Result.retry()` (§3.2). Optimistic projections stay in the cache — the teacher keeps seeing their ticks.
- **PullSync**: the resource in flight aborts before its `replaceScope` commits; remaining resources are skipped; every cache stays intact. Local reads and the four forms keep working while `Expired` (enqueue needs no token).
- **In-app notice, never interrupting a form**: `Expired` shows a persistent top banner on every screen «انتهت صلاحية الجلسة — اضغط لتسجيل الدخول». The nav guard (4.7) moves to the login screen automatically only when the current destination is not a form (`isForm = false`), on the next cold start, or when the banner is tapped; on a form it waits until the form pops. A background 401 while the teacher is filling an attendance sheet therefore costs nothing.
- **Pre-emptive renewal**: `tokenExp - now ≤ 3 days` → banner «ستنتهي جلستك خلال N يوم — سجّل الدخول مجددًا الآن حتى لا تتوقف المزامنة» → login screen in **Renew** mode. Prevents the trap "token expired while offline, writes stuck until a password is remembered". The old `jti` simply ages out (no extra revoke call).
- **OS notification** from the background worker («انتهت جلستك في تطبيق غراس») is **deferred** — it needs the `POST_NOTIFICATIONS` runtime permission flow on API 33+; the banner + `⟳ N` badge cover the case at the next app open.
- **Resume**: `login()` with the same user id → 4.3 steps 5–7 → `Outbox.schedule(REPLACE)` cancels the backed-off retry and flushes immediately → `PullSync` after the flush (§3.1). No row is dropped, `local:` ids reconcile through the normal path. Cache and `sync_state` are untouched, so the home screen is populated the instant the login succeeds.

### 4.5 Logout (`SessionRepository.logout()`) — account sheet action «تسجيل الخروج»

1. **Pending gate**: `pendingCount > 0` → dialog «لديك N تغييرات لم تُرفع بعد» with «رفع ثم خروج» (runs one inline flush with a 20 s timeout; if rows remain, back to the dialog with «تعذّر الرفع الآن»), «خروج وحذف التغييرات», «إلغاء». Rows in the «تعذّر الحفظ» list are terminal already and are dropped silently.
2. **Freeze writers**: `WorkManager.cancelUniqueWork("outbox-flush").result.await()`; cancel the sync scope's children and `join()` — no flush or pull may insert into Room after the wipe.
3. `POST /auth/logout` (request tagged `SkipExpiry`, 5 s timeout): 204 → `jti` revoked; 401 → already revoked/expired, treated as success; `Offline`/5xx/timeout → **proceed anyway**. Local logout is never blocked by the network; the token then ages out (D4) and the manager's `is_active` toggle remains the immediate kill switch.
4. **Atomic Room wipe**: `withContext(Dispatchers.IO) { db.clearAllTables() }` — Room generates this as a single transaction under `PRAGMA defer_foreign_keys = TRUE` followed by `VACUUM`, so FK order is irrelevant; `pending_writes` and `sync_state` are tables and go with it.
5. `store.clear()` (synchronous `commit()`).
6. `_state.value = SignedOut` → the root graph swaps to **Fresh** login with an empty back stack.

Ordering rationale: wipe before clear. A crash between 4 and 5 leaves a valid session with an empty cache (harmless — the next pull refills); the reverse could leave one user's data on disk for the next login. The identity gate (4.3 step 4) is the second line of defence.

### 4.6 Change-password screen (`ui/account/ChangePasswordScreen`, route `account/change-password`)

- Reached from the account sheet (Section 5). Three fields — current, new, confirm — all `PasswordVisualTransformation` with an eye toggle, `KeyboardType.Password`, `ImeAction.Next` / `Done`.
- **Online-only**: the submit button is disabled with «يتطلب الاتصال بالإنترنت» while `ConnectivityManager` reports no validated network. A password change is never queued in the outbox — it carries a secret and is not an idempotent upsert.
- Client pre-validation mirrors the server so most errors never leave the phone, using the **same strings** as `routers/auth.py`: `new.length < 8` → «كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف»; `new != confirm` → «كلمة المرور وتأكيدها غير متطابقين»; `new == current` → «كلمة المرور الجديدة يجب أن تكون مختلفة عن الحالية».
- Request `POST /auth/change-password {current_password, new_password, confirm_password}` (bearer). Responses:
  - 200 → snackbar with the server `message` («تم تغيير كلمة المرور بنجاح») → pop back.
  - **400** `validation_error` → inline under the new-password field; **400** `invalid_credentials` → inline under the current-password field, both verbatim. Therefore `ErrorMapper` maps **400 and 422** → `AppError.Validation(msg)` (amends the §2.1 wording, which listed 422 only), and `AppError.Validation` gains a `code` so the screen can pick the field.
  - 401 → the interceptor already moved the session to `Expired`; the guard closes the screen.
  - 5xx / IO → «تعذّر الاتصال بالخادم — حاول لاحقًا»; the fields keep their values.
- After success the current token stays valid (the server revokes nothing) → no re-login, the outbox is unaffected.
- **Security gap, flagged, not fixed here**: other devices' tokens also survive a password change; with 30-day TTLs a stolen phone keeps access after the owner changes the password from another device. The fix is server-side (`users.password_changed_at` + reject tokens with `iat < password_changed_at` in `current_user` → migration 007 + ~3 lines) and is listed in §9; until then the manager's `is_active` toggle is the documented response to a lost phone.

### 4.7 Navigation guards (`ui/nav/RootNavHost.kt`)

- Three disjoint graphs — `auth/*`, `teacher/*`, `guardian/*` — under one `NavHost`; routes never overlap, so a stale back stack can never expose the other role's screens. Route definitions are `@Serializable` objects/classes (Navigation 2.8 type-safe API).
- `RootNavHost` collects `SessionRepository.state` and reacts:

| State | Root destination | Action |
| :--- | :--- | :--- |
| `SignedOut` | `auth/login` (Fresh) | `navigate { popUpTo(0) { inclusive = true } }` |
| `Active(TEACHER)` | `teacher/home` | same, only when the current graph is not already `teacher/*` |
| `Active(GUARDIAN)` | `guardian/home` | same, only when not already in `guardian/*` |
| `Expired` | current screen + banner | navigate to `auth/login` (Expired) when `currentDestination.isForm == false`, else after the form pops (observed via `currentBackStackEntryFlow`) |

- Role-change detection: every `PullSync` compares `profile.user.role` with the stored role; a mismatch (manager changed the account's role) → `onUnauthorized()` with the banner text «تغيّرت صلاحيات حسابك — سجّل الدخول مجددًا»; the next login re-runs the gates in 4.3.
- No deep links, no exported activities this phase; `MainActivity` handles `onNewIntent` only for the default launcher intent.

### 4.8 Server change D4 — `MOBILE_JWT_TTL_MINUTES` (Claude-only, TDD, independent of the app)

**`config.py`** (+6 lines):

```python
@dataclass(frozen=True)
class Settings:
    ...
    jwt_ttl_minutes: int = 720
    mobile_jwt_ttl_minutes: int = 43200   # 30 days — teacher/guardian tokens (Phase 5 (c) D4)
    ...

# in get_settings(), after the JWT_TTL_MINUTES block:
    mobile_ttl_raw = os.environ.get("MOBILE_JWT_TTL_MINUTES", "43200")
    try:
        mobile_jwt_ttl_minutes = int(mobile_ttl_raw)
    except ValueError:
        raise RuntimeError("MOBILE_JWT_TTL_MINUTES is not a valid integer") from None
    return Settings(..., mobile_jwt_ttl_minutes=mobile_jwt_ttl_minutes, ...)
```

**`auth.py`** (+5 lines, 1 changed):

```python
MOBILE_ROLES = frozenset({"teacher", "guardian"})


def token_ttl_minutes(role: str, settings) -> int:
    return settings.mobile_jwt_ttl_minutes if role in MOBILE_ROLES else settings.jwt_ttl_minutes


def issue_token(user_row: dict, settings) -> str:
    ...
        "exp": now + timedelta(minutes=token_ttl_minutes(user_row["role"], settings)),
```

Units for the record: `JWT_TTL_MINUTES=720` = 12 h (manager/supervisor, unchanged); `MOBILE_JWT_TTL_MINUTES=43200` = 30 days (teacher/guardian). No range check, matching the existing parser.

**Env documentation** (one identical line each, no VPS change required — the default applies when the variable is absent): `server/.env.example`, `deploy/.env.prod.example`, and the generated block at `deploy/deploy.sh:46`: `MOBILE_JWT_TTL_MINUTES=43200`.

**Hygiene — D4-a, APPROVED 2026-09-21 (+1 statement in `logout`)**: `DELETE FROM revoked_tokens WHERE expires_at < now()` before the `INSERT`. With 30-day tokens, revoked rows now live up to 30 days and nothing purges them today. Lookups are by PK `jti`, so this is housekeeping, not a correctness fix. Covered by the third test in §7.6. **Implementation finding (2026-09-21)**: `gheras_app` holds `SELECT/INSERT/UPDATE` only (`003_phase2.sql:33`), so the purge 500s without `db/postgres/007_revoked_tokens_purge.sql` (`GRANT DELETE ON revoked_tokens TO gheras_app`, idempotent, registered in `deploy/deploy.sh`'s three migration lists). Least privilege is unchanged for every other table.

**Tests — `server/tests/test_auth_ttl.py` (2 TTL tests below + the purge test in §7.6 = 3, conftest fixtures only, no scope setup)**:

```python
"""D4: per-role JWT TTL — mobile roles get MOBILE_JWT_TTL_MINUTES, dashboard roles keep JWT_TTL_MINUTES."""

from __future__ import annotations

import os

import jwt

from tests.conftest import login, make_user


def _ttl_seconds(headers: dict) -> int:
    token = headers["Authorization"].removeprefix("Bearer ")
    claims = jwt.decode(token, os.environ["JWT_SECRET"], algorithms=["HS256"])
    return claims["exp"] - claims["iat"]


def test_mobile_roles_get_mobile_ttl(db, client):
    for username, role in (("t1", "teacher"), ("g1", "guardian")):
        make_user(db, username, role)
        assert _ttl_seconds(login(client, username)) == 43200 * 60  # default, conftest does not override


def test_dashboard_roles_keep_jwt_ttl(db, client, manager, supervisor):
    for headers in (manager, supervisor):
        assert _ttl_seconds(headers) == 60 * 60  # conftest pins JWT_TTL_MINUTES=60
```

- TDD order: write both tests → run → test 1 **RED** (teacher/guardian currently get 3 600 s), test 2 already green (regression guard) → apply the two edits → both green → full suite green under the embedded PG booter (105 on 2026-09-21) → `[APPROVED]`.
- Deploy: `deploy/push.sh` → API rebuild + `deploy.sh` applies idempotent migration 007 (grant only); no web `v=` bump. Post-deploy probe (Ibrahim, locally, token never pasted into chat): `curl` login as a teacher → `python -c` / `jq` decode of the payload → `exp - iat == 2592000`; a manager login must still give `43200`.
- Existing tokens are unaffected (TTL is fixed at issue time); teachers who are already logged in on the web keep their 12 h tokens.

---

## 5. Screens & navigation — **[APPROVED 2026-09-21]**

### 5.1 Shell

- One `MainActivity` (`ComponentActivity`, `enableEdgeToEdge`), `GherasTheme` = Material 3 with the Gheras palette from `res/values/colors.xml` (dynamic colour **off**, so demo phones all look the same), `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)` at the root — the UI is RTL regardless of device locale. System fonts (no bundled font this phase).
- Numbers: a single `Num` helper (`String.format(Locale.ROOT, …)`, `NumberFormat.getInstance(Locale.ROOT)`) used by every formatter — Rule 50 (`0-9`) everywhere, including dates and money («ر.س»). Dates are Gregorian (the API and the dashboard are Gregorian ISO); display `d MMMM yyyy` with Arabic month names, times `HH:mm`.
- ViewModels: one per screen, created through `viewModel(factory = container.vmFactory)`; each exposes a `StateFlow<UiState>` built from DAO flows combined with `OutboxRepository.pendingCount/failed` and `SyncState`. ViewModels never touch Retrofit — only repositories.
- Bottom navigation — teacher: `الرئيسية · الطلاب · الواجبات · التنبيهات · حسابي`; guardian: `الرئيسية · الرسوم · التنبيهات · حسابي`. App bar on every screen carries the `⟳ N` outbox badge (teacher) and the sync stamp «آخر تحديث: قبل N دقيقة» (both roles).

### 5.2 Route table

| Route | Role | Screen | Data source (cache) | Writes |
| :--- | :--- | :--- | :--- | :--- |
| `auth/login?mode` | — | Login (Fresh / Expired / Renew, 4.3) | — | `POST /auth/login` |
| `teacher/home` | T | Today's slots for `LocalDate.now()` (weekday mapped to the schedule `day` vocabulary; Friday/Saturday → «لا حصص اليوم» + date picker for another day). One card per slot: room, subject, time, and three actions حضور / تقييم / سجل الحصة; ✓ marks when attendance / evaluations / a lesson log already exist for that room+date. Below: «تعذّر الحفظ» list (§3.2) with dismiss + «إعادة المحاولة» (re-enqueues a fresh row from the stored payload). | `schedules ⋈ rooms`, `student_attendance`, `evaluations`, `lesson_logs`, `pending_writes` | — |
| `teacher/students?roomId` | T | List, search by name, room chips; row → detail | `students ⋈ rooms` | — |
| `teacher/students/{id}` | T | Name, room, group, guardian name + phone with **call** (`ACTION_DIAL tel:` — no `CALL_PHONE` permission) and **WhatsApp** (`ACTION_VIEW https://wa.me/966…`, `05xxxxxxxx` → `+9665xxxxxxxx`) buttons; 60-day attendance summary, latest evaluations by subject, skill progress | `students`, `student_attendance`, `evaluations`, `skill_progress` | — |
| `teacher/attendance/{roomId}/{date}` | T | Sheet: one row per active student, 4-state segmented control «حاضر / غائب / متأخر / مستأذن» (default حاضر), optional note; prefilled from cache; «حفظ» = one `ATTENDANCE` batch → pop | `students`, `student_attendance` | `POST /attendance/students` |
| `teacher/evaluations/{roomId}/{date}/{subject}` | T | Sheet: subject chosen from the room's schedule subjects («القرآن / لغتي / الإنجليزي / الرياضيات»), one stepper per student, scale 0–10, default 10, half points (dashboard parity); «حفظ» = one `DAILY_EVAL` batch → pop | `students`, `schedules`, `evaluations` | `POST /evaluations/daily` |
| `teacher/lesson-log/{scheduleId}/{date}` | T | Form: status chips «تمت / مؤجلة / ملغاة», `covered`, `homework`, `notes` (multi-line); prefilled from cache; «حفظ» = `LESSON_LOG` → pop | `schedules`, `lesson_logs` | `POST /me/lesson-logs` |
| `teacher/assignments` → `teacher/assignments/{id}` | T | Due-sorted list (±60 d); detail shows the linked students and each one's submission status + file count (no file download route this phase — thumbnails deferred with the upload feature) | `assignments`, `assignment_students`, `submissions`, `submission_files` | — |
| `teacher/notifications`, `guardian/notifications` | T, G | Unread first; opening a row = `NOTIFICATION_READ` | `notifications` | `POST /me/notifications/{id}/read` |
| `account` | T, G | Name, role label, centre name + phone (call), «آخر تحديث», «تغيير كلمة المرور» → `account/change-password` (4.6), «تسجيل الخروج» (4.5), app version | `profile_json`, `sync_state` | — |
| `guardian/home` | G | Children switcher (chips, selection remembered in `SavedStateHandle`); child dashboard cards: today's attendance, latest evaluation per subject, homework due count, next unpaid installment; cards open the sections below | all child-scoped tables | — |
| `guardian/child/{id}/attendance` | G | 60-day list grouped by week, status colour-coded | `student_attendance` | — |
| `guardian/child/{id}/evaluations` | G | Tabs per subject, list newest first | `evaluations` | — |
| `guardian/child/{id}/homework` | G | Assignments due ±60 d with the child's submission status | `assignments`, `submissions` | — |
| `guardian/child/{id}/lessons` | G | Lesson logs newest first — `covered` + `homework` only (`notes` never arrives, §2) | `lesson_logs ⋈ schedules` | — |
| `guardian/child/{id}/skills` | G | Skill progress by subject | `skill_progress` | — |
| `guardian/fees?studentId` | G | Installments (status chips «pending / partial / paid» rendered as «مستحق / جزئي / مسدَّد», amount / paid / due date) + receipts list; totals in «ر.س» | `installments`, `receipts` | — |

- Forms (`isForm = true` for the nav guard, 4.7): attendance sheet, evaluation sheet, lesson-log form, change-password. All keep drafts in `SavedStateHandle` (survive rotation and process death) and ask «تجاهل التغييرات؟» on back press when dirty.
- Every list screen has pull-to-refresh (`PullSync` unthrottled, §3.1). Empty scope on the teacher home shows «لم تُسند لك حلقة بعد — راجع الإدارة» (§3.1).

---

## 6. Error handling & UX states — **[APPROVED 2026-09-21]**

### 6.1 Cache-first `UiState`

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>                      // only when the cache is empty AND a pull is in flight
    data class Empty(val reason: EmptyReason) : UiState<Nothing> // NoScope, NoDataInWindow, NoChildren
    data class Content<T>(val data: T, val stale: Boolean) : UiState<T>
    data class Error(val error: AppError, val cached: Any?) : UiState<Nothing> // full-screen only when cached == null
}
```

- Rule: whatever Room holds is shown immediately; network problems are **non-modal** (banner/snackbar) whenever content exists and full-screen only when the cache is empty. `stale = oldest sync_state.last_pull_at > 24 h`.

### 6.2 `AppError` → UI

| `AppError` | Where it surfaces | Text / behaviour |
| :--- | :--- | :--- |
| `Offline` | top banner on lists, inline on login / change-password | «لا يوجد اتصال — تُعرض بيانات محفوظة» + sync stamp; forms still save locally with «تم الحفظ — سيُرفع عند توفّر الاتصال» |
| `Unauthorized` | never shown directly | Section 4.4 (banner + guarded navigation) |
| `Forbidden(msg)` | login (profile 403), outbox terminal failures | server message verbatim; outbox → «تعذّر الحفظ» list |
| `NotFound` | outbox terminal failures, detail screens | «العنصر لم يعد موجودًا» — the next pull removes it from the cache |
| `Validation(code, msg)` | inline on forms / change-password | server message verbatim, placed on the field by `code` |
| `Server(msg)` | snackbar with «إعادة المحاولة» | «تعذّر الاتصال بالخادم — حاول لاحقًا» (server message when present) |

### 6.3 Sync & outbox feedback

- App bar: `⟳ N` badge while `pendingCount > 0`; after 10 failed attempts the badge tooltip reads «بانتظار الاتصال منذ N ساعة» (§3.2).
- Home «تعذّر الحفظ» list: one card per terminal failure with the server message, «تجاهل» (deletes the row) and «إعادة المحاولة» (new row, same payload — never automatic).
- Saving a form: local enqueue + immediate pop; snackbar «تم الحفظ» online / «تم الحفظ — سيُرفع عند توفّر الاتصال» offline. No network spinner on any form.
- Sync stamp on every list: «آخر تحديث: قبل N دقيقة / ساعة / يوم» from the oldest `sync_state` row; «لم تتم المزامنة بعد» before the first successful pull.

### 6.4 Empty states

| Case | Text |
| :--- | :--- |
| Teacher with `scope.room_ids = []` | «لم تُسند لك حلقة بعد — راجع الإدارة» |
| Any 60-day window list empty | «لا توجد بيانات في آخر 60 يومًا» |
| No assignments / notifications / receipts | «لا توجد واجبات حاليًا» / «لا توجد تنبيهات» / «لا توجد إيصالات» |
| Guardian with no children | unreachable — the profile 403 is handled at login (4.3) |

### 6.5 Robustness & accessibility

- Process death: form drafts and the guardian's selected child live in `SavedStateHandle`; the session comes from `SessionStore`; nothing else needs restoring because Room is the source of truth.
- Logging: OkHttp `HttpLoggingInterceptor` at `BASIC` in debug builds only (never `BODY` — tokens and personal data), no logging in release; no crash-reporting SDK this phase.
- Accessibility: every icon-only action has a `contentDescription`; 48 dp minimum touch targets; text in `sp` (dynamic font scale honoured); segmented controls and chips expose `Role.RadioButton` semantics; colour is never the only status carrier (attendance and installment statuses always show text).
- Clock: all "today" logic uses the device's `ZoneId.systemDefault()`; the server timestamps are `timestamptz` → `Instant` → local time for display only.

---

## 7. Testing & CI — **[APPROVED 2026-09-21 — contract-first tests confirmed]**

### 7.1 Strategy in one paragraph

Everything that carries risk in this app is deterministic Kotlin with no screen: mappers, the outbox state machine, `replaceScope`, `PullSync` paging, the session transitions. All of it is tested on the JVM — no emulator, no instrumented tests, no Compose UI tests this phase. Two tiers: **pure JVM** (fakes, Turbine, MockWebServer) for logic and contracts, and **Robolectric** for the few classes whose behaviour *is* SQLite — Room `@Transaction` methods, FK order, the `OutboxWorker`. Compose screens are covered by the manual demo checklist (7.6) and by the fact that their ViewModels are tested. Claude Code runs every command in this section; workers never run Gradle, tests or lint (AGENTS.md §4 monopoly — and the machine cannot afford two Gradle daemons).

### 7.2 Tooling (all in `gradle/libs.versions.toml`, `testImplementation` only)

| Library | Version | Used for |
| :--- | :--- | :--- |
| JUnit 4 | 4.13.2 | AGP unit-test runner (JUnit 5 stays in `:homework-core` only) |
| kotlinx-coroutines-test | 1.10.x | `runTest`, `StandardTestDispatcher`, `Dispatchers.setMain` |
| Turbine | 1.2.x | `Flow` / `StateFlow` assertions (one-emission checks) |
| MockWebServer | 4.12.x (= OkHttp) | `PullSync`, `AuthInterceptor`, `ErrorMapper` against real HTTP |
| Robolectric | 4.14.x, `@Config(sdk = [34])` | in-memory Room, `TestListenableWorkerBuilder`, `ApplicationProvider` |
| androidx.test:core | 1.6.x | `ApplicationProvider.getApplicationContext()` |
| androidx.work:work-testing | 2.10.x | `OutboxWorker` under Robolectric |
| androidx.room:room-testing | 2.7.x | reserved for `MigrationTestHelper` from v2 onward (unused at v1) |

- `app/build.gradle.kts`: `testOptions.unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true; all { it.maxHeapSize = "1g"; it.jvmArgs("-XX:MaxMetaspaceSize=384m") } }`.
- Robolectric tests are tagged `@Category(RoomTests::class)` (marker interface in `src/test/.../RoomTests.kt`). `-PskipRobolectric` → `useJUnit { excludeCategories("sa.gheras.edutrack.RoomTests") }`, so the pure-JVM tier runs alone on a memory-tight afternoon; CI always runs both. Robolectric fetches `android-all-instrumented` (~110 MB) into `~/.m2/repository/org/robolectric` on first run — cached locally once, cached on CI (7.4).
- `EncryptedSharedPreferences` is never exercised in tests (no Keystore on the JVM): every test injects `FakeSessionStore` (in-memory `MutableMap`), which is exactly why `SessionStore` is an interface (4.1).
- Fakes live in `src/test/.../fakes/`: `FakeSessionStore`, `FakeAuthApi`, `FakeMeApi` (scripted responses + call log), `FakeClock` (`Clock.fixed`, injected into `SessionRepository`, `PullSync`, `Outbox` — no `Instant.now()` in production code, always `clock.instant()`), `FakeConnectivity`.

### 7.3 Test inventory (file → cases; these names are the plan's task list)

**Pure JVM — `data/remote`**

- `ErrorMapperTest`: 400 `validation_error` → `Validation(code, msg)`; 400 `invalid_credentials` → `Validation("invalid_credentials", msg)`; 422 → `Validation`; 401 → `Unauthorized`; 403 → `Forbidden(msg)`; 404 → `NotFound`; 500 with body / without body → `Server(msg)` / `Server(default)`; `IOException` / `UnknownHostException` / `SocketTimeoutException` → `Offline`; non-JSON error body → `Server`.
- `EnvelopeJsonTest`: `{items,total,limit,offset}` parses; unknown keys ignored; `"amount": 500` and `500.0` both → `Double`; `null` for a non-null `String` field → `SerializationException` (we do not coerce nulls into empty strings); a guardian `LessonLogDto` without `notes` → `notes == null`.
- `AuthInterceptorTest` (MockWebServer): token present → `Authorization: Bearer` header on the recorded request; token absent → no header; `401` + bearer → `onUnauthorized()` called once; `401` without bearer (login) → not called; `401` + `SkipExpiry` tag → not called; `200` → not called; response body still reaches the caller after a `401`.

**Pure JVM — `data/repo` mappers**

- `StudentMapperTest`: projected `/me/students` row → `createdAt == updatedAt == pulledAt`, `gender` copied, `branchId` absent; guardian row set → distinct `(room_id, room_name)` pairs become `RoomEntity(id, name, groupName = "")` **before** students (order asserted on the returned list); teacher rows never synthesise rooms.
- `AcademicMapperTest`: ISO `date` → `LocalDate`; `timestamptz` with offset → `Instant`; `HH:MM:SS` → `LocalTime`; `evaluations.value` `Double` → entity `Double`; `student_name`/`room_name`/`teacher_name` are **dropped** (not stored, 2.2).
- `AssignmentMapperTest`: `student_ids[]` → `AssignmentStudentEntity` rows; `files[]` → `SubmissionFileEntity` rows with the parent `submissionId`; `plan_total`/`plan_count` dropped.
- `FeesMapperTest`: installment `amount`/`paid` `Double`, status string kept raw (`pending|partial|paid`); receipt links to installment by id.
- `NumTest` (Rule 50): `Num.int(1234)` == `"1234"` and `Num.money(1500.5)` == `"1500.50 ر.س"` **while `Locale.setDefault(Locale("ar","SA"))`**; date `2026-09-21` → `"21 سبتمبر 2026"`; time → `"14:05"`.
- `PhoneTest`: `"0551234567"` → `tel:0551234567` and `https://wa.me/966551234567`; `"+966551234567"` unchanged; `"966551234567"` → prefixed `+`; empty/garbage → both actions disabled.

**Pure JVM — session (`SessionRepositoryTest`, fakes + `FakeClock`)**

- Initial state: token → `Active`; no token + user → `Expired(username)`; nothing → `SignedOut`; token with `exp < now` → `Expired` without any API call.
- `login()`: 401 → error, state unchanged, store untouched; role `manager` → `logout()` called with the fresh token, message shown, `SignedOut`, store empty; `lastUserId != user.id` → `clearAllTables` invoked (fake db hook) **before** `saveLogin`; `lastUserId == null` + non-empty db flag → wipe; profile 403 → `store.clear()` + wipe + `SignedOut`; profile `Offline` → `Active` anyway; profile role mismatch → treated as 403; success → `saveLogin` (commit) precedes `saveProfile` precedes the `Active` emission (Turbine: exactly one emission); `pendingCount > 0` → `Outbox.schedule(REPLACE)` called, else not; `PullSync.requestFull()` called last.
- `renew()`: same user → token replaced, `lastUserId`, profile and wipe-hook untouched, no emission (state already `Active`).
- `onUnauthorized()`: `Active` → `Expired(username)` with `clearToken()` only (user/profile keys still present); called twice → one emission; from `SignedOut` → no-op.
- `logout()`: order of effects recorded by fakes = `cancelUniqueWork → join sync scope → POST /auth/logout (SkipExpiry tag, 5 s) → clearAllTables → store.clear → SignedOut`; server 401 / `Offline` / timeout → sequence continues; `pendingCount > 0` → returns `NeedsConfirmation(n)` without side effects; «رفع ثم خروج» path → flush called with 20 s budget, rows remaining → `FlushIncomplete`.
- Renewal banner: `tokenExp - now == 3 d` → `renewalDue = true`; `3 d + 1 s` → `false`; `Expired` → `false` (banner replaced by the expiry banner).
- `JwtPayloadTest`: `exp` read from a hand-built base64url JWT (no signature check), malformed token → `null` (treated as "unknown", not "expired").

**Robolectric — `data/local` (`@Category(RoomTests::class)`, in-memory `GherasDatabase`)**

- `ReplaceScopeTest`: for `students`, `schedules`, `attendance`, `evaluations`, `assignments (+students +submissions +files)` — Turbine collects the DAO `Flow`, `replaceScope(newRows)` is called, **exactly one** new emission with the new rows; FK reverse order in 2.3 is executed by a single `GherasDatabase.replaceAll(teacherPayload)` without `SQLiteConstraintException`; a pending `ATTENDANCE` row survives `replaceScope` on `student_attendance` and its `local:` projection is re-applied (present after the transaction); `pending_writes` row count unchanged by any pull; a failing `upsertAll` (forced constraint error) rolls the whole `replaceScope` back — previous rows still there.
- `SchemaExportTest` (pure JVM, reads `app/schemas/sa.gheras.edutrack.data.db.GherasDatabase/1.json`): `version == 1`; entity table names == the exact set {`rooms, students, schedules, student_attendance, evaluations, assignments, assignment_students, submissions, submission_files, lesson_logs, skill_progress, notifications, installments, receipts, pending_writes, sync_state`} (16 — no `users`, `guardians`, `study_plans`); `students` has `gender` nullable; `pending_writes` has zero foreign keys. CI additionally fails on schema drift (7.4).

**Robolectric — `sync`**

- `OutboxTest` (in-memory Room + `FakeMeApi` + `FakeClock`): `enqueue` for each of the 4 kinds inserts one `pending_writes` row **and** the optimistic projection (`local:` id / `readAt`) in one transaction; same natural key twice → one row, latest payload; flush order == `created_at` FIFO; `2xx` → row deleted, server rows upserted, `local:` id gone, same natural key present once; `401` → `Result.retry`, no `attempts++`, rows untouched, `onUnauthorized()` called once; `403`/`404`/`422` → `attempts == -1`, `last_error == server message`, projection reverted (attendance row removed / lesson log restored to the pre-enqueue server row / `readAt` back to `null`), row appears in `failed` Flow; `5xx`/`IOException` → `attempts++`, `Result.retry`, projection kept; `attempts == 10` → still queued, `pendingCount` unchanged; `retryFailed(id)` → new row with the same payload, old row deleted; `dismissFailed(id)` → row deleted, projection stays reverted.
- `OutboxWorkerTest` (`TestListenableWorkerBuilder`): session `Expired` → `Result.retry()` with **zero** API calls; `Active` + empty outbox → `Result.success()`; `Active` + one `5xx` row → `Result.retry()`.
- `PullSyncTest` (MockWebServer + in-memory Room): teacher request order == 3.1 list (recorded paths); guardian order skips `/me/rooms`, ends with `installments, receipts`; `total = 1200` → three requests with `offset = 0, 500, 1000`, then stop; `date_from` query == `today - 60 d` for the four windowed resources, `due_from/due_to` ±60 d for assignments, none for submissions/notifications; page 2 of `students` returns 500 → students cache keeps the previous rows, `schedule` still refreshed, `sync_state.students` untouched, `sync_state.schedule` updated; `Offline` on `profile` → returns silently, nothing changed; `401` on any page → `onUnauthorized()`, remaining resources not requested; profile 403 → `ProfileForbidden(msg)` result; profile role ≠ stored role → `onUnauthorized()`; `last_pull_at < 5 min` + foreground trigger → no request; pull-to-refresh → request regardless; `requestFull()` while a pull is running → coalesced (one pull, one follow-up).

**Pure JVM — ViewModels (fakes + Turbine)**

- `LoginViewModelTest`: mode derivation Fresh / Expired(username locked) / Renew; empty fields → local error, no call; 401 → server message; `Offline` → offline string; discard dialog appears only when `pendingCount > 0`.
- `TeacherHomeViewModelTest`: `LocalDate` → schedule `day` vocabulary for all 7 weekdays (Friday/Saturday → `NoLessons`); ✓ flags true when attendance/evaluations/lesson log rows exist for `(room, date)`; `failed` list surfaces `FailedWrite`s; empty `scope.room_ids` → `Empty(NoScope)`.
- `AttendanceSheetViewModelTest`: prefill from cache (default `present` when no row); `save()` builds **one** `ATTENDANCE` batch with every active student; dirty flag; draft restored from `SavedStateHandle`.
- `EvaluationSheetViewModelTest`: subjects from the room's schedules; stepper bounds 0–10 step 0.5; one `DAILY_EVAL` batch.
- `LessonLogViewModelTest`: prefill from the existing server row; `save()` → `LESSON_LOG` payload with `schedule_id`/`date`.
- `GuardianHomeViewModelTest`: selected child persisted in `SavedStateHandle`; cards computed from the 5 child-scoped flows; next unpaid installment = earliest `due_date` with status ≠ `paid`.
- `ChangePasswordViewModelTest`: the three local validations produce the exact server strings; submit disabled while offline; 400 `validation_error` → new-password field, 400 `invalid_credentials` → current-password field; 200 → `Done(message)`.
- `UiStateTest`: cache empty + pull running → `Loading`; cache empty + error → `Error(cached = null)`; cache non-empty + error → `Content(stale)` plus banner flag; `last_pull_at > 24 h` → `stale = true`.

**Unchanged**: `:homework-core` 13 tests (JUnit 5) must stay green after the settings/catalog merge (§1).

Rough count: ≈ 24 test classes, ≈ 130 cases; pure-JVM tier ≈ 20 s, Robolectric tier ≈ 60 s on this machine.

### 7.4 Local loop (Claude Code only)

```bash
# from mobile/android — wrapper 8.11, daemon -Xmx2g (gradle.properties)
./gradlew :homework-core:test                                     # 13 green, must stay
./gradlew :app:testDebugUnitTest -PskipRobolectric                 # pure-JVM tier, the cheap loop (≈ 20 s warm)
./gradlew :app:testDebugUnitTest --tests 'sa.gheras.edutrack.sync.*'   # one package while iterating
./gradlew :app:testDebugUnitTest                                   # both tiers before any [APPROVED]
./gradlew :app:lintDebug                                           # abortOnError = true, warnings reported not fatal
./gradlew :app:assembleDebug && git status --short app/schemas     # schema export must be clean or committed
./gradlew --stop                                                   # always, before dispatching a worker
```

- RAM discipline: one Gradle daemon at a time; stop it before OpenCode/Cline runs; never alongside the pgserver booter + pytest. If free RAM < 3 GB, run only the `-PskipRobolectric` tier locally and let CI run the Robolectric tier.
- TDD order per Claude-owned class: test file first (RED, may not even compile until the worker's DTO/DAO lands) → implementation → GREEN → next. Tests for worker-owned files (mappers, DAOs, DTOs) are written by Claude **before** the batch is dispatched — they are the acceptance contract in the batch order (§8.2).
- Gate before `[APPROVED]` of the Android feature: both tiers green, `:homework-core` green, `lintDebug` 0 errors, `app/schemas/.../1.json` committed and equal to the freshly exported file, `assembleDebug` produces `app-debug.apk`, CI green on the pushed commit.

### 7.5 GitHub Actions — `.github/workflows/android.yml` (repo root `F:\AI PROJECTS\Autovemtech\.github\`, new directory)

```yaml
name: android

on:
  push:
    branches: [main]
    paths:
      - "Clients/03_GHERAS_Center/edutrack_pro/mobile/**"
      - ".github/workflows/android.yml"
  pull_request:
    paths:
      - "Clients/03_GHERAS_Center/edutrack_pro/mobile/**"
      - ".github/workflows/android.yml"
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: android-${{ github.ref }}
  cancel-in-progress: true

defaults:
  run:
    working-directory: Clients/03_GHERAS_Center/edutrack_pro/mobile/android

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    env:
      EDUTRACK_BASE_URL: https://gheras.autovem.tech/api/v1/
      GRADLE_OPTS: -Dorg.gradle.daemon=false
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - uses: gradle/actions/wrapper-validation@v4

      - uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}

      - name: Cache Robolectric android-all jars
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository/org/robolectric
          key: robolectric-${{ hashFiles('**/libs.versions.toml') }}

      - name: Unit tests (homework-core + app, both tiers)
        run: ./gradlew :homework-core:test :app:testDebugUnitTest --stacktrace

      - name: Lint
        run: ./gradlew :app:lintDebug

      - name: Assemble debug APK
        run: ./gradlew :app:assembleDebug

      - name: Room schema must be committed
        run: git diff --exit-code -- app/schemas

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: reports
          path: |
            Clients/03_GHERAS_Center/edutrack_pro/mobile/android/app/build/reports/tests/testDebugUnitTest
            Clients/03_GHERAS_Center/edutrack_pro/mobile/android/app/build/reports/lint-results-debug.html
            Clients/03_GHERAS_Center/edutrack_pro/mobile/android/homework-core/build/reports/tests/test
          retention-days: 14

      - uses: actions/upload-artifact@v4
        with:
          name: gheras-edutrack-debug-apk
          path: Clients/03_GHERAS_Center/edutrack_pro/mobile/android/app/build/outputs/apk/debug/app-debug.apk
          retention-days: 30
```

- `ubuntu-latest` ships the Android SDK with licences accepted (`ANDROID_HOME` preset); AGP downloads `platforms;android-35` / `build-tools;35.0.0` on demand. If that ever breaks, add `android-actions/setup-android@v3` before the test step — not included by default to keep the job at one moving part.
- `app/build.gradle.kts` resolves `BASE_URL` as `System.getenv("EDUTRACK_BASE_URL") ?: localProperties["edutrack.baseUrl"] ?: "https://gheras.autovem.tech/api/v1/"` → the CI APK points at production; the emulator URL is a `local.properties` override on the workstation only.
- The APK artifact is what goes on the demo phones (day 25): download from the run page → «install from unknown sources» → `adb`-free. `git push` and the Actions run are Ibrahim's (auto-mode holds `push`); Claude verifies the run status through the public run URL.
- No secrets in the workflow (no signing, no tokens) — `permissions: contents: read` is the whole surface.

### 7.6 Server tests for D4 — `server/tests/test_auth_ttl.py` (3 tests)

The two TTL tests are in 4.8 verbatim. The purge line (D4-a, approved) gets a third test, same fixtures:

```python
def test_logout_purges_expired_revoked_tokens(db, client, manager):
    db.execute(
        "INSERT INTO revoked_tokens (jti, expires_at) VALUES (%s, now() - interval '1 day')",
        ("stale-jti",),
    )
    db.commit()
    res = client.post("/api/v1/auth/logout", headers=manager)
    assert res.status_code == 204
    rows = db.execute("SELECT jti FROM revoked_tokens").fetchall()
    jtis = {r["jti"] for r in rows}
    assert "stale-jti" not in jtis      # purged
    assert len(jtis) == 1               # the manager's own jti, just revoked
```

- `routers/auth.py` `logout`: add `conn.execute("DELETE FROM revoked_tokens WHERE expires_at < now()")` immediately before the existing `INSERT` (same transaction, `idx_revoked_tokens_expires_at` makes it a range scan).
- Gate: 3 new tests RED (1 + 3 fail, test 2 green as a regression guard) → apply `config.py` + `auth.py` + `routers/auth.py` edits → GREEN → full suite **103** green under the embedded PG booter (harness commands as in the Phase 5 (b) plan "Test harness" section) → `uvx ruff check server/` delta = 0 new findings → `[APPROVED]` → commit → Ibrahim: `deploy/push.sh` → post-deploy probe from 4.8 (`exp - iat == 2592000` for a teacher, `43200` for a manager).

### 7.7 Manual demo acceptance (Ibrahim, one teacher phone + one guardian phone, ~20 min)

| # | Step | Expected |
| :--- | :--- | :--- |
| 1 | Install CI APK, open | login screen, RTL, Arabic, `0-9` numerals in the debug host line |
| 2 | Login as a manager | «هذا التطبيق مخصص للمعلمين وأولياء الأمور…», stays on login |
| 3 | Login as a teacher | home shows today's slots within ~3 s (first pull) |
| 4 | Airplane mode → attendance sheet → save | «تم الحفظ — سيُرفع عند توفّر الاتصال», badge `⟳ 1`, ticks visible on home |
| 5 | Airplane off | badge clears within ~30 s; web dashboard shows the attendance |
| 6 | Manager deactivates the teacher on the web → teacher pulls to refresh | expiry banner, forms still open, login (Expired) shows the username locked |
| 7 | Manager re-activates → teacher logs in again | home populated instantly, nothing lost |
| 8 | Change password → wrong current password | inline «بيانات الدخول غير صحيحة» under the current field |
| 9 | Login as a guardian with 2 children | chips switch children; fees show «ر.س» totals with `0-9` |
| 10 | Logout with a pending write | dialog with the three choices; «خروج وحذف» → fresh login, cache empty |
| 11 | Kill the app mid-form (developer options → «Don't keep activities») | draft restored on return |
| 12 | Device language = English | app still RTL and Arabic |

Any red row → Claude opens a fix task; the checklist re-runs only on the rows touched.

---

## 8. Fleet delegation batches — **[APPROVED 2026-09-21 — Worker B owns all data-layer batches]**

### 8.1 Rules that apply to every batch (ADR 22/23, `fleet_orders/_common_rules.md` pattern)

- One order file per batch in `fleet_orders/phase5c/batchNN_<worker>.md` (git-ignored), generated from the implementation plan's task text (`task-brief PLAN N`), never from this spec. Each order carries: the closed **edit list (≤ 4 files)**, a closed **read list** (this spec's section, the sibling files it must mirror, the Claude-written test file that is its acceptance contract), the exact code block from the plan when one exists, and the output contract («one line per file + `DONE`»).
- Workers **never** run Gradle, tests, lint or git; never create, rename or delete files outside their edit list; never touch `.github/`, `gradle.properties`, tests, or any Claude-owned file (8.3). Deletions (`UserEntity` etc., `homework-core/settings.gradle.kts`) are Claude's `git rm`.
- Concurrency: at most **one** GLM 5.3 Flash instance on OpenCode (Worker A). Safe parallel sets, confirmed in Phase 4e/5b: **A ∥ B ∥ C** (OpenCode Flash, OpenCode Muse, Cline Flash) plus Claude's own edits. Worker D (Cline Muse) is unassigned — its model id on the Cline provider is still unverified; its work is folded into B. Gradle daemon stopped before every dispatch (7.4).
- Review per batch, by Claude only: `git diff --stat` matches the edit list exactly (any extra file → reject) → line-by-line against the plan block / spec section → `[APPROVED]` or a patch (small fixes applied directly, large ones re-dispatched). Nothing compiles until the wave gate, so review is what catches mistakes early.
- Invocation, for the record: `opencode run -m opencode-go/glm-5.3-flash` / `opencode run -m opencode-go/muse-spark-1.3-contributor` with `timeout 540`, prompt = `"$(cat order.md)"`; Cline: `cline --auto-approve true -c "$(pwd -W)" -m z-ai/glm-5.3-flash --thinking medium -t 500 --json "$(cat order.md)" < /dev/null`.

### 8.2 Ownership map

| Owner | Owns | Why |
| :--- | :--- | :--- |
| **Claude Code** | `AuthInterceptor`, `ErrorMapper`, `SessionStore` + `EncryptedPrefsSessionStore`, `SessionRepository`, `Outbox`, `OutboxWorker`, `OutboxRepository`, `PullSync`, `RootNavHost` (guards), `AppContainer`, `GherasApp`, `MainActivity`, `GherasDatabase.replaceAll`, Gradle wrapper, `.github/workflows/android.yml`, **every test file and fake**, the server D4 change, all deletions, all reviews | Security- and consistency-critical; each has a state machine or a transaction whose bugs are silent |
| **Worker B — OpenCode Muse Spark** | Room entities + DAOs (new and amended), DTOs, `AuthApi`/`MeApi`, mappers, the 8 read repositories | Data shapes with a spec to copy from (`001_schema.sql`, PHASE5_SPEC §2/§3.1); Muse delivered 3/3 byte-exact data batches in Phase 5 (b) |
| **Worker A — OpenCode GLM Flash** | Gradle scaffolding, manifest, resources, theme, `Num`, common composables, login + account + guardian screens & ViewModels | Boilerplate-heavy, pattern-following; fast |
| **Worker C — Cline GLM Flash** | Teacher screens & ViewModels (parallel to A) | Independent file set, doubles Compose throughput |
| **Ibrahim** | `git push`, `deploy/push.sh` (D4), CI run, APK install, 7.7 checklist | External publication + production + physical devices |

### 8.3 Batches by wave

Legend: **A/B/C** = worker, **CC** = Claude Code. «needs» = batches that must be `[APPROVED]` first. Each row is one dispatch (≤ 4 files).

**Wave 0 — server D4 (independent; runs whenever the booter is up)**

| ID | Owner | Files | Needs |
| :--- | :--- | :--- | :--- |
| S1 | CC (TDD) | `server/tests/test_auth_ttl.py`, `server/edutrack_api/config.py`, `server/edutrack_api/auth.py`, `server/edutrack_api/routers/auth.py`, `server/.env.example`, `deploy/.env.prod.example`, `deploy/deploy.sh` (env line) | — |

**Wave 1 — build foundation ∥ data layer**

| ID | Owner | Files (`mobile/android/…`) | Needs |
| :--- | :--- | :--- | :--- |
| A1 | A | `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` | — |
| A2 | A | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/colors.xml` | A1 |
| A3 | A | `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/debug/res/xml/network_security_config.xml`, `homework-core/build.gradle.kts` (catalog aliases, JUnit 5 kept) | A1 |
| B1 | B | `data/entity/PendingWriteEntity.kt`, `data/entity/SyncStateEntity.kt`, `data/dao/PendingWriteDao.kt`, `data/dao/SyncStateDao.kt` | — |
| B2 | B | `data/entity/InstallmentEntity.kt`, `data/entity/ReceiptEntity.kt`, `data/dao/InstallmentDao.kt`, `data/dao/ReceiptDao.kt` (columns from `db/postgres/001_schema.sql`) | — |
| B3 | B | `data/entity/StudentEntity.kt` (+`gender`), `data/db/GherasDatabase.kt` (−3 +4 entities, `exportSchema = true`, v1), `data/dao/StudentDao.kt` (`replaceScope`, joins), `data/dao/RoomDao.kt` (`replaceScope`, `upsertMinimal`) | B1, B2 |
| B4 | B | `replaceScope` + window queries on `ScheduleDao.kt`, `StudentAttendanceDao.kt`, `EvaluationDao.kt`, `LessonLogDao.kt` | — |
| B5 | B | same on `SkillProgressDao.kt`, `AssignmentDao.kt`, `AssignmentStudentDao.kt`, `SubmissionDao.kt` | — |
| B6 | B | same on `SubmissionFileDao.kt`, `NotificationDao.kt` (+`markRead`) ; `data/entity/SubmissionFileEntity.kt` (parent FK check), `data/entity/NotificationEntity.kt` (`readAt` nullable check) | — |
| CC1 | CC | `git rm` `UserEntity/UserDao/GuardianEntity/GuardianDao/StudyPlanEntity/StudyPlanDao`, `homework-core/settings.gradle.kts`, `replay_pid*.log`; `.gitignore` (`**/.gradle/`, `local.properties`, `*.log`); `gradle wrapper --gradle-version 8.11`; `GherasDatabase.replaceAll` + `RoomTests` marker; `src/test/.../SchemaExportTest.kt`, `ReplaceScopeTest.kt` | A1–A3, B1–B6 |

**Gate G1 (CC)**: `./gradlew :homework-core:test :app:assembleDebug` — first Room/KSP compile ever; `app/schemas/.../1.json` appears; `SchemaExportTest` + `ReplaceScopeTest` green. Expect one fix round on DAO SQL — Claude patches inline, no re-dispatch unless > 20 lines.

**Wave 2 — remote layer ∥ Claude core**

| ID | Owner | Files (`app/src/main/java/sa/gheras/edutrack/…`) | Needs |
| :--- | :--- | :--- | :--- |
| B7 | B | `data/remote/dto/Envelope.kt` (+`ApiErrorBody`), `dto/StudentDto.kt`, `dto/ScheduleDto.kt`, `dto/AttendanceDto.kt` | G1 |
| B8 | B | `dto/EvaluationDto.kt`, `dto/AssignmentDto.kt`, `dto/SubmissionDto.kt`, `dto/LessonLogDto.kt` | G1 |
| B9 | B | `dto/SkillProgressDto.kt`, `dto/InstallmentDto.kt`, `dto/ReceiptDto.kt`, `dto/NotificationDto.kt` | G1 |
| B10 | B | `dto/ProfileDto.kt`, `dto/AuthDtos.kt` (`LoginBody`, `LoginResponse`, `ChangePasswordBody`, `StatusResponse`), `dto/WriteBodies.kt` (`LessonLogBody`, `AttendanceItemBody`, `DailyEvalItemBody`), `data/remote/AuthApi.kt` | — |
| B11 | B | `data/remote/MeApi.kt`, `data/repo/mappers/DateParsers.kt`, `data/repo/mappers/StudentMappers.kt` (incl. guardian room synthesis), `data/repo/mappers/ScheduleMappers.kt` | B7–B10 |
| B12 | B | `mappers/AcademicMappers.kt` (attendance, evaluation, lesson log, skill), `mappers/AssignmentMappers.kt`, `mappers/FeesMappers.kt`, `mappers/NotificationMappers.kt` | B7–B10 |
| CC2 | CC (TDD) | `data/remote/ErrorMapper.kt`, `data/remote/AuthInterceptor.kt`, `data/local/session/SessionStore.kt`, `data/local/session/EncryptedPrefsSessionStore.kt`, `data/local/session/JwtPayload.kt` + tests `ErrorMapperTest`, `AuthInterceptorTest`, `JwtPayloadTest`, `fakes/*` | B10 |
| CC3 | CC (TDD) | `data/repo/SessionRepository.kt` (+`SessionState`, `Role`, `SessionUser`), `data/repo/OutboxRepository.kt`, `sync/Outbox.kt`, `sync/OutboxWorker.kt` + `SessionRepositoryTest`, `OutboxTest`, `OutboxWorkerTest` | CC2, B11 |
| CC4 | CC (TDD) | `sync/PullSync.kt`, `sync/SyncState.kt`, `di/AppContainer.kt`, `GherasApp.kt` + `PullSyncTest`, `EnvelopeJsonTest`, mapper tests (`StudentMapperTest`, `AcademicMapperTest`, `AssignmentMapperTest`, `FeesMapperTest`) | CC3, B12 |

Mapper tests in CC4 are written **before** B11/B12 are dispatched and referenced in their read lists — the worker sees the expected output.

**Gate G2 (CC)**: `:app:testDebugUnitTest` both tiers green (everything but ViewModel tests), `assembleDebug` green.

**Wave 3 — UI, three workers in parallel ∥ Claude nav**

| ID | Owner | Files (`ui/…` unless noted) | Needs |
| :--- | :--- | :--- | :--- |
| A4 | A | `theme/Color.kt`, `theme/Theme.kt` (RTL provider, dynamic colour off), `theme/Type.kt`, `common/Num.kt` | A2 |
| A5 | A | `nav/Routes.kt` (`@Serializable` routes, `isForm`), `common/AppScaffold.kt` (app bar + `⟳ N` + sync stamp + expiry/renewal banners slot), `common/BottomBar.kt`, `common/StateViews.kt` (Loading/Empty/Error/Offline banner) | A4 |
| A6 | A | `login/LoginScreen.kt`, `login/LoginViewModel.kt`, `account/AccountScreen.kt`, `account/AccountViewModel.kt` | A5, G2 |
| A7 | A | `account/ChangePasswordScreen.kt`, `account/ChangePasswordViewModel.kt`, `common/FailedWritesList.kt`, `common/Phone.kt` (dial / wa.me intents) | A5, G2 |
| A8 | A | `guardian/GuardianHomeScreen.kt`, `guardian/GuardianHomeViewModel.kt`, `guardian/ChildSwitcher.kt`, `guardian/ChildAttendanceScreen.kt` (+VM in file) | A5, G2 |
| A9 | A | `guardian/ChildEvaluationsScreen.kt`, `guardian/ChildHomeworkScreen.kt`, `guardian/ChildLessonsScreen.kt`, `guardian/ChildSkillsScreen.kt` (each with its VM) | A5, G2 |
| A10 | A | `guardian/FeesScreen.kt`, `guardian/FeesViewModel.kt`, `notifications/NotificationsScreen.kt`, `notifications/NotificationsViewModel.kt` (shared by both roles) | A5, G2 |
| B13 | B | `data/repo/StudentsRepository.kt`, `ScheduleRepository.kt`, `AttendanceRepository.kt`, `EvaluationsRepository.kt` | G2 |
| B14 | B | `data/repo/AssignmentsRepository.kt`, `LessonLogsRepository.kt`, `FeesRepository.kt`, `NotificationsRepository.kt` | G2 |
| C1 | C | `teacher/TeacherHomeScreen.kt`, `teacher/TeacherHomeViewModel.kt`, `teacher/StudentsScreen.kt`, `teacher/StudentsViewModel.kt` | A5, B13 |
| C2 | C | `teacher/StudentDetailScreen.kt`, `teacher/StudentDetailViewModel.kt`, `teacher/AttendanceSheetScreen.kt`, `teacher/AttendanceSheetViewModel.kt` | A5, B13 |
| C3 | C | `teacher/EvaluationSheetScreen.kt`, `teacher/EvaluationSheetViewModel.kt`, `teacher/LessonLogScreen.kt`, `teacher/LessonLogViewModel.kt` | A5, B13 |
| C4 | C | `teacher/AssignmentsScreen.kt`, `teacher/AssignmentsViewModel.kt`, `teacher/AssignmentDetailScreen.kt`, `teacher/AssignmentDetailViewModel.kt` | A5, B14 |
| CC5 | CC (TDD) | `nav/RootNavHost.kt` (state → graph table 4.7, `isForm` deferral), `MainActivity.kt`, `common/UiState.kt` + `UiStateTest`, `NumTest`, `PhoneTest`, all ViewModel tests (`LoginViewModelTest` … `ChangePasswordViewModelTest`) | A5 |

ViewModel tests in CC5 are written before A6–A10 / C1–C4 dispatch and named in each order's read list (same contract trick as the mappers). Dispatch order inside the wave: A4 → A5 → then A6–A10 (A), B13–B14 (B) and C1–C4 (C) in parallel — three harnesses, one GLM per harness.

**Gate G3 (CC)**: full `7.4` sequence green (both tiers, `homework-core`, `lintDebug`, `assembleDebug`, schema clean). Compose compile errors from workers are fixed inline by Claude when < 20 lines per file; larger → re-dispatch with the compiler output in the order.

**Wave 4 — CI and hand-off**

| ID | Owner | Files | Needs |
| :--- | :--- | :--- | :--- |
| CC6 | CC | `.github/workflows/android.yml` (7.5), `mobile/android/README.md` (build, `local.properties` keys, demo install steps), `CURRENT_STATE.md`, ADR in `.agents/MEMORY_STORE.md` | G3 |
| I1 | Ibrahim | `git push` → Actions run → APK artifact → demo phones → 7.7 checklist; `deploy/push.sh` for S1 (any time after S1 is `[APPROVED]`) | CC6, S1 |

**Gate G4**: CI green on `main`, APK installed, 7.7 all green → Phase 5 (c) `[APPROVED]`, `CURRENT_STATE.md` frozen.

### 8.4 Totals & critical path

- Worker batches: A 10, B 14, C 4 → **28 dispatches** at ~2–3 min each plus review (~5 min each) ≈ 3.5 h of fleet time, largely parallel. Claude-owned: ~20 production files + ~24 test files across CC1–CC6 — this is where the calendar goes.
- Critical path: A1 → B3 → G1 → B10 → CC2 → CC3 → CC4 → G2 → A5 → C1–C4 → G3 → CC6 → I1. Wave 0 (S1) and B1/B2/B4–B6 float freely; Muse batches can start the moment this spec is approved and the plan's briefs exist.
- Highest-risk moment: **G1**, the first Room/KSP compile on this machine. Mitigation: A1–A3 are reviewed against the exact versions in §1, the daemon runs at `-Xmx2g` with everything else closed, and the compile happens before any DTO/mapper work so a Gradle problem cannot block Wave 2 design.
- Every batch order includes the sentence «Do NOT run any shell commands, tests, linters or git commands» (Phase 4e wording) — the testing monopoly is enforced by the order text, not by trust.

### 8.5 What the implementation plan must add (next step: `superpowers:writing-plans`)

1. One task per batch ID above, in wave order, each with the full file contents (workers copy; Claude reviews by `diff`).
2. The `FakeMeApi`/`FakeSessionStore` sources early (they gate every test).
3. Exact `libs.versions.toml` pins (resolved once against Maven Central at plan time, not guessed at dispatch time).
4. The G1–G4 gate commands verbatim from 7.4 / 7.6.
5. The Ibrahim hand-off block (push, deploy, install, checklist) as the last task.

---

## 9. Out of scope (Phase 5 (d)+ backlog)

- Homework camera upload (`POST /me/submissions` multipart + storage design) — server spec first, then wire `ImageCompressor`.
- Push notifications / WhatsApp hooks — messaging provider decision; OS-level session-expiry notification (`POST_NOTIFICATIONS`) rides with it.
- **Revoke all sessions on password change** — `users.password_changed_at` + `iat` check in `current_user` (migration 007 + ~3 lines); until then the manager's `is_active` toggle is the lost-phone response (4.6).
- Refresh tokens; Play Console release signing and listing; iOS (Package 3).
- Delta sync (`since=` + tombstones) if the cache window ever becomes insufficient.
- `SessionStore` migration from `security-crypto` to Keystore + DataStore (interface already isolates it, 4.1).
