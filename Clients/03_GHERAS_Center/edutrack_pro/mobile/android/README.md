# EduTrack Pro — Native Android App (Phase 5c)

EduTrack Pro is the native Android application for **Gheras Center (`مركز غراس للتعليم والتأهيل`)**, built natively with **Kotlin 2.1**, **Jetpack Compose (Material 3)**, **Room (Offline-first Cache & Write Outbox)**, and **WorkManager**.

---

## 🏛️ Architectural Overview

- **Layering**: Clean Architecture (Manual DI via `AppContainer`, zero Hilt overhead).
- **Offline Strategy (Level 2)**:
  - Room acts as durable local read cache + write outbox (`pending_writes`).
  - Writes (`ATTENDANCE`, `DAILY_EVAL`, `LESSON_LOG`, `NOTIFICATION_READ`) are optimistic, idempotent upserts enqueued locally and flushed by `OutboxWorker` via WorkManager.
  - Strict reverse-dependency topological order on scope replacement (`replaceScope`).
- **Session & Auth**:
  - JWT HS256 with 30-day mobile TTL (`MOBILE_JWT_TTL_MINUTES=43200`).
  - Seamless 401 handling, persistent expiry banners, background pause without data loss.
- **RTL & Localization**:
  - Enforced Arabic layout direction globally (`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`).
  - Strict Rule 50 compliance: Western Arabic numerals (`0-9`) exclusively for all monetary values, dates, and counters via `Num.kt`.

---

## 🛠️ Build & Test Instructions

### Prerequisites
- JDK 17 (`JAVA_HOME` pointing to JDK 17)
- Android SDK (API 35, Build-Tools 35.0.0)

### Running Unit Tests Locally
```bash
# Core logic tests (JVM)
./gradlew :homework-core:test

# App Unit & Database Tests
./gradlew :app:testDebugUnitTest
```

### Assembling Debug APK
```bash
./gradlew :app:assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## ⚙️ Configuration (`local.properties`)

You can override API endpoints on local workstations or emulators:
```properties
# Override server base URL (default: https://gheras.autovem.tech/api/v1/)
edutrack.baseUrl=http://10.0.2.2:8000/api/v1/
sdk.dir=C:\\Users\\<Username>\\AppData\\Local\\Android\\Sdk
```

---

## 📱 Demo Phone Installation

1. Download the latest debug APK from GitHub Actions artifact `gheras-edutrack-debug-apk`.
2. Transfer to target device (or download directly via browser on phone).
3. Enable "Install from unknown sources" in Android settings.
4. Open the APK and verify initial login with teacher or guardian credentials.
