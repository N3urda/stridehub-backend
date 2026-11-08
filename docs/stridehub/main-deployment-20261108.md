# Main integration and NAS deployment — 2026-11-08

`main` is the canonical branch for integration and deployment. Commit `3169b6e9b383d27a8b144cfb4c97911dddbdbaa7` includes the former master Mi Fitness integration and the deployed 0.4.0 chart, examination-report and automatic measurement-sync changes. The GitHub default branch and coverage workflow target main. Master was fast-forwarded for compatibility; future changes target main.

Conflict resolution preserves source-aware sleep reconciliation with interactive charts, the Mi Fitness controls, and body-composition/report sections.

Validation: 314 PHP tests / 1920 assertions, 8 Python parser tests, JavaScript syntax, Twig syntax and candidate service-container lint passed. PHP integration tests used an isolated empty Zepp credentials directory; the initial local-credentials-contaminated run was not accepted as validation.

NAS now runs `stridehub:main-3169b6e9b` (amd64 config `8f4e710b3a0cc812e0fa453be71a2ba96c49cd44343e1c7170f4103f72f34286`). Writers were stopped before backup to `backups/pre-main-20261108`. Cold and post-start checks confirmed existing business tables, records, environment and persistent files unchanged. All 21 read APIs preserved previous fields; reviewed additions contain Mi Fitness status and sleep provenance. Seventeen public checks, complete existing 0.4.0 APK checksum verification and MCP/TLS passed. The resumed daemon completed a mobile-workflow cycle.

No new Android APK was released. Existing signed 0.4.0 downloads remain available. No real-phone/BLE or newly configured Mi Fitness account acceptance is claimed.

Rollback: stop writers, restore the backed-up Compose image reference to `stridehub:android-0.4.0-d045181e3-compact`, restart and verify. Preserve current business data rather than restoring an older database over new records.

Private execution records are in the ignored `var/runtime/main-integration-20261108/` directory. Backup and maintenance payload are retained; temporary helper containers, Compose registrations and image transfer archive are removed after verification.
