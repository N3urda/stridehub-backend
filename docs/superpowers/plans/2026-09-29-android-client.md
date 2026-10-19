# Android Client Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent core, toolchain and acceptance tasks.

**Goal:** Deliver an installable APK that connects to the same StrideHub backend with URL and administrator credentials.

**Architecture:** Native connection UI and lifecycle/navigation wrap the existing mobile management pages. A tested URL policy and guarded login script reuse Symfony's CSRF/session authentication without storing a password or introducing another API authentication scheme.

**Tech Stack:** Java 17, Android SDK 36 (minimum 26), AGP 8.13.2, Gradle 8.13, platform WebView, JUnit 4, Playwright, Android emulator.

---

- [x] Add tests for `ServerAddress` and `LoginScript` before implementing those security boundaries. Reject userinfo, ambiguous paths, wrong origins/actions and HTTP without opt-in; preserve quoted/unicode credentials without executing them.
- [x] Implement native connection form, session restoration, Today/training/running/import navigation, page errors, TLS cancellation, file picker and bounded download handling in `android/app/src/main/java/io/stridehub/app/`.
- [x] Add Gradle wrapper, manifest/resources, build workflow and reproducible signing instructions. Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` with signing environment supplied privately for release.
- [x] Run `tests/e2e/android-login.cjs` against the disposable backend at port 8082, using the real Java-generated script; verify success, failure, special characters and untrusted action rejection.
- [x] Install the APK on the emulator; validate connection, remember-me, native navigation, uploads, logout and error recovery; retain screenshots and logs under ignored `var/runtime/`.
- [x] Independently review security/functional changes, fix findings, verify signed APK metadata, record SHA-256 and update docs/PR. Preserve signing key for install-over updates; never commit credentials or test health data.
