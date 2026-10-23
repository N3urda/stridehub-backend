# Native Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace every Android WebView flow with native UI while preserving installed app data, BLE weighing and NAS synchronization.
**Architecture:** Kotlin/Compose UI with native API authentication, account-scoped Room cache and existing Java weighing core. Symfony JSON endpoints reuse existing domain services. Independent backend/auth, backend/workflows and Android UI work proceeds in parallel with fixed contracts and integration review.
**Tech Stack:** Kotlin, Compose, Material 3, Room, Android 26+, Java 17; PHP 8.5/Symfony/Doctrine.
**Spec:** docs/superpowers/specs/2026-10-23-native-android-design.md (approved by user 2026-10-23).

## Global Constraints

- No WebView, HTML login, CookieManager authentication, JavaScript charts or browser fallback for app features.
- Preserve applicationId io.stridehub.app, original signing identity, local SQLite history, measurement UUIDs, tombstones and queued uploads.
- Never use personal data, production databases or a release app installation as test fixtures.
- Retain existing Bearer API and web authentication compatibility; mobile tokens must not authorize MCP or arbitrary admin commands.
- Private caches and drafts are scoped by normalized server and account; logout clears them but retains local weighing records and pauses old queues.
- Missing health values remain missing, not zero; writes use existing version checks and preserve drafts on conflict.
- User authorized direct implementation; no additional design/plan permission gate.

## Review Focus

- Login to a subdirectory deployment; no cross-origin credential forwarding or browser redirects.
- Re-login, logout and account switching during in-flight requests cannot attach previous-account responses or uploads.
- Upgrading an installed app must preserve legacy scale history and every queue state.
- Interrupted writes and imports must not display success or silently duplicate data.
- Process recreation and offline navigation preserve meaningful screens and drafts without storing passwords.

## Task 1: Native server sessions and weighing authorization

**Files:** Create src/Domain/Mobile/MobileSessionService.php, src/Controller/Mobile/MobileSessionRequestHandler.php; modify owning API authenticator/security config; add tests/Controller/Mobile/MobileSessionApiTest.php and domain tests.
**Interfaces:** POST /mobile/v1/sessions JSON {username,password,deviceName} -> {token,expiresAt,username,apiVersion:1,capabilities}; GET/DELETE /mobile/v1/session; authenticated POST /mobile/v1/collection/devices {deviceId,name} -> existing collection device/token response. Mobile Bearer tokens authorize the app's /api/v1 health/training/running/activities/activity-upload/settings-weight endpoints only, and these mobile endpoints.
- [x] Test valid/invalid login, expiry/revocation/password changes, blocked MCP/admin access, credential redaction and subdirectory behavior.
- [x] Implement random hashed per-device session credentials with seven-day expiry and login throttling using existing admin provider/hasher; add native collection pairing through existing WeightCollection service.
- [x] Run isolated PHP tests and related existing auth/collection tests; commit only owned files.

## Task 2: Backend workflow parity

**Files:** Create src/Controller/Mobile/MobileWorkflowRequestHandler.php and src/Domain/Mobile/MobileWorkflowService.php plus focused tests; modify narrowly scoped upload/import/workout services where needed. Do not edit Task 1 auth files.
**Interfaces:** Authenticated /api/v1/mobile/workouts?page=1&limit=30&from=&to= -> existing overview plus paged items and pagination; /api/v1/mobile/activity-options and /api/v1/mobile/activities[/{id}] support JSON native create/edit/delete/enrich with capabilities; /api/v1/mobile/imports upload/list/detail/retry with durable job receipts; /api/v1/mobile/sync jobs start/status for daily/sleep/workouts. Send concrete field contracts to Android implementer as soon as established.
- [x] Test pagination stability, per-source edit capabilities, delete effects, malformed bodies and version conflicts.
- [x] Implement explicit domain-service adapters; never expose generic dispatchCommand.
- [x] Add durable import receipts and asynchronous sync jobs with deduplication, status and safe interruption handling; reuse existing queues/locks.
- [x] Run focused isolated PHP tests, commit owned files and document worker launch/operation requirements.

## Task 3: Native Android application

**Files:** android/build.gradle, android/app/build.gradle, new Kotlin io/stridehub/app/nativeui files and native MainActivity; replace BrowserLayout/LoginScript/NavigationSection/FileTransfers; tests for native navigation/cache/forms.
**Interfaces:** Use Task 1 mobile session endpoints and Task 2 workflow endpoints. Existing health/workspace, health/workouts, health/plans, health data, training, running and Zepp detail endpoints retain current schemas. Root-owned Java bridge io.stridehub.app.NativeSession exposes static token(Context), server(Context), signedIn(Context), clear(Context), save(Context,server,username,token,remember,expiresAt) for scale pairing and app lifecycle. Root owns Java scale/sync files and existing androidTest migration, do not edit those files.
- [x] Add compatible pinned Kotlin/Compose/Material/Room dependencies; preserve Android 26 and Java 17, bump release version.
- [x] Build native login/account switching and secure session bridge integration, including capability handshake and actionable old-backend errors.
- [x] Implement API repository, account-scoped Room cache, lifecycle cancellation, loading/error/offline states and saved per-tab navigation/scroll/drafts.
- [x] Build total native overview/body/exercise/plans, detail charts/route/segments, all existing record edit flows, feedback/association, nutrition/goals, import/export/activity management and sync/settings.
- [x] Keep weighing accessible from logged-out connection, body and a global shortcut via existing native WeighingActivity; root integrates its navigation and pairing.
- [x] Replace obsolete WebView tests with native equivalents, test cache isolation and conflict behavior; build/unit/lint and device UI tests.

## Task 4: Preserve and integrate native weighing

**Files:** android/app/src/main/java/io/stridehub/app/NativeSession.java; scale/WeighingActivity.java; sync/NasSync.java; affected androidTest files and tests/e2e native acceptance.
**Interfaces:** NativeSession bridge above uses existing connection preferences plus Android Keystore-protected mobile token. NasSync.pair(Context,Done) obtains native app token and POSTs /mobile/v1/collection/devices. Preserve existing device token and all queue identities.
- [x] Test original history/UUID/tombstone/queue states survive new app construction and native re-pair; preserve BLE protocol tests.
- [x] Remove WebView Cookie dependency and route weighing into the shared product flow without duplicate app navigation.
- [x] Preserve offline weighing and shortcut; verify live measurement/pending confirmation/CSV/export/queue retry behavior.
- [x] Add no-WebView source/APK gate and upgrade checks; commit owned files.

## Task 5: Integration and delivery

**Files:** android/README.md, docs/stridehub/native-android-validation.md, isolated acceptance fixtures/scripts and CI.
- [x] Run backend route/container checks and integration tests in an isolated database; initialize realistic synthetic fixtures.
- [x] Build and run native APK on the dedicated emulator; verify core scope-table flows, lifecycle/back/keyboard/theme and offline behavior. Full physical-device accessibility/BLE and production acceptance remain explicitly listed in the validation report.
- [x] Review all branch changes independently, resolve material defects, rerun impacted tests only.
- [x] Produce installable debug artifact and signed release if original signing configuration is available; explicitly distinguish simulator and physical BLE verification.
- [x] Record exact tests, limitations and artifact paths; do not deploy NAS or modify user data as a side effect of building.

## Delivery record

Application source is finalized at `9b04b4f8b`; independent task and final reviews are resolved. Backend related integration: 274 tests / 1,703 assertions. Android: 37 unit tests and 39 instrumentation tests passed, plus a repeated isolated CSV import test. Both APK native bytecode gates and original release certificate comparison passed. The exact signed APK, executed business flows and device/production limits are recorded in `docs/stridehub/native-android-validation.md`. Production NAS and personal app data were not changed.
