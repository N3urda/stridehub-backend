# Blood pressure and Zepp sleep

The user requested blood-pressure entry and Zepp sleep integration for the shared Web/Android backend, exposed through HTTP API and Skill.

## Delivered behavior

- `/admin/health` is available from Today, the training editor, running dashboard and admin navigation. The existing APK renders it through its current backend connection; no APK binary change is needed.
- Blood pressure keeps individual timestamped measurements, optional pulse, arm, posture and notes. The form and API support create, edit and explicit deletion. Version checks prevent accidental overwrites; a stable create ID permits recovery from a lost response. Drafts survive refresh, conflict, network failure and re-login in a second tab.
- Zepp reads sleep summaries using an existing private server authorization. It preserves source dates, actual wake dates and offsets, stage durations, available scores and resting pulse. Unknown fields remain null, empty placeholders are skipped, and inconsistent stage totals do not become a measured sleep duration. Date queries include a buffer because source date and actual wake date can differ.
- Sleep synchronization is idempotent and account-bound. Failed or empty responses retain prior records. A database version claim also prevents simultaneous app/daemon jobs across separate container lock stores. A terminated attempt becomes retryable after five minutes.
- The standard daemon schedules a configured account every hour at minute 15. Its command is also available for initial 90-day import. The local development daemon is running with restart recovery; no credential or private health value is included in source control.
- Today/Briefing return separate `observations` for their requested date. Sleep is matched by actual wake time in the training profile timezone. Future and uncertain wake times are excluded. Existing manual sleep, pain, fatigue and plans are never rewritten by a read or sync.

## Validation

- PHP: 181 tests / 946 assertions across health and training domain and authenticated API tests, isolated databases.
- Python Skill client: 27 tests, including blood-pressure versions/offsets/null and sleep provider failure status.
- Eight real HTTP/mobile-browser acceptance groups, including 320px/390px/desktop layouts, actual saves/read-back, multiple readings, conflict review, re-login recovery, deletion and Today integration. One group injects a transport failure **after** a real successful write; other requests use the real disposable backend. Browser sleep records are labelled synthetic fixtures.
- Real authorized Zepp account: seven nonempty sleep records imported. Repeated synchronization returned zero additions/updates and seven unchanged records. Raw provider payloads and personal measurements were not printed or added to the repository.
- Source syntax, Twig templates, scoped PHPStan and Skill/OpenAPI validation are checked separately. The scoped PHPStan configuration disables only unmatched project-wide ignore reporting.

## Boundaries

Zepp's endpoint is unofficial and can change; an expired authorization requires renewing the private connection. The integration keeps one selected summary per source day and does not claim complete nap-segment support. Device sleep measurements are observations, not diagnoses or an automatic clearance to train. Blood pressure is manual/API entry, not a Bluetooth blood-pressure monitor integration. Original examination PDFs/photos remain outside this feature.

No new APK was built or released for this backend-only update. Android WebView inherits the new page from the updated server; acceptance in this change uses a mobile browser, not a new physical-phone or emulator run.
