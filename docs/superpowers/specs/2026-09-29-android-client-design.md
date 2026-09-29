# StrideHub Android client

The user requested an installable APK in this repository, connecting to the existing backend by URL, username and password. This explicitly authorizes implementation. The first release uses native connection controls and an embedded Android WebView for the existing mobile management interface. A separate native rewrite would duplicate the current training/reconciliation forms and their concurrency handling; a PWA alone would not deliver the requested APK.

## User flow

1. Install and open StrideHub (Android 8.0 or later).
2. Enter an HTTPS installation URL and the existing administrator username/password. A deliberate HTTP opt-in supports trusted local development. Explain that phone localhost means the phone.
3. Load the server's own login form, validate its origin, path, POST action and CSRF field, then submit the entered credentials once. Reuse the existing session/remember-me mechanism; never distribute an API key or store the password.
4. Open Today; native shortcuts also reach training, running analysis and file import. Keep all server-side management features available through the embedded interface. Support the Android back button, page refresh, uploads through the system file picker, controlled downloads, and server/account changes.
5. Remember only the server, username and preference for the backend's seven-day login cookie. Expired sessions return to the native connection screen. Logout clears this app's cookies, cache and web storage.

## Boundaries

There is no backend change, offline editing, native health-device integration or background AI in this release. Data stays on the selected backend; the APK hosts the existing forms and charts. Read/write conflict handling remains the existing implementation. HTTP requires explicit consent; invalid TLS certificates are rejected. Credential submission is restricted to the exact configured login path and same origin. Other web links require deliberate opening in the system browser. Do not expose a JavaScript/native bridge, arbitrary file access, third-party cookies or exported app links. Disable application backup/transfer of session data.

## Delivery and checks

Add `android/` with pinned Gradle/AGP versions, a reproducible wrapper, native launcher and signed local release APK. Signing material stays outside version control. CI builds a debug APK and checks tests/lint. Validate URL/script security with JVM tests, real server login with generated script in a browser, and the actual APK with an Android emulator when available. Report precisely which checks completed and any deployment/device limitations. The existing personal database is not a test fixture.
