# Android chart coverage and presentation refinement

## Agreed direction

The user delegated visual and implementation choices on 2026-11-08: prioritize readable data and charts, fix omissions and inconsistent presentation, and continue the existing native Compose app. Keep five main destinations, offline weighing, editing, authentication and data provenance intact.

## Implementation scope

- Compact shared page hierarchy and consistent cool neutral surfaces; specific detail-page navigation titles.
- Labeled chart scales, formatted dates/time/units, visible selection and accessible previous/next controls; preserve missing values and real sampling gaps.
- Body metric selectors and previously omitted blood pressure, circumference/body composition, available daily-health and Xiaomi observations, keeping source boundaries explicit.
- Plans: real week/day overview and status, instead of a menu-only landing screen. Nutrition: date navigation, targets and actual recorded coverage. Workout time/pace/distance formatting corrections.
- Validate with model tests and isolated native instrumentation fixtures; inspect light and dark/large-font screenshots. Never use synthetic fixture data as evidence of a real account or BLE device.

## Existing gaps confirmed

The previous body first screen spent most of its space on headers, date controls and entry buttons. Only weight, sleep, steps and resting heart rate had top-level charts. Plans were predominantly destination cards. Line charts had grid lines without numeric Y-axis labels and showed raw ISO timestamps or raw seconds in some details. Backend support for more body and Xiaomi data did not imply corresponding Android presentation.

Execution evidence and screenshots are stored under the ignored `var/runtime/android-polish-20261108/` directory.

## Verification and delivery

Android 0.4.1 (versionCode 10) was built using the existing release signing certificate. Debug and release each passed 76 unit tests, both lint variants passed, and the final native emulator runs passed 8 light-mode and 6 dark-mode/150%-font tests (360dp width). Screenshots were visually reviewed for body, composition and week-calendar layout. These UI fixtures are synthetic offline Room data; no real phone/BLE acceptance is claimed.

Additional regression coverage checks missing dates/weeks, source separation, repeated same-day measurements, precise units, multi-day axes, accessible point navigation and linked nutrition selection. Forms and report text remain readable records; unavailable/unnormalized provider values are not fabricated for charts.

The signed APK is in `var/runtime/android-polish-20261108/downloads/StrideHub-0.4.1.apk`. This client-only release uses the existing backend APIs. The NAS backend and its download-page metadata were not changed in this iteration.

APK SHA-256: `2a4b7c97d36434bc0c2984241aa653269db832e93f33be23789117bdaeec0cd5`.

## NAS publication — 2026-11-08

The signed 0.4.1 APK, checksum and release manifest are now published in NAS image `stridehub:android-0.4.1-dad5d710a` (config ID `8a8811dd1be4e055f38772056d083a48fe557a3286173de6e7725498fb398a7a`). This image extends the deployed main backend and changes only download assets and image labels. Existing APKs remain available.

Backup: `backups/pre-android-041-20261108`. All original business tables/records and persistent files were preserved. Twenty-one authenticated read APIs and seventeen public checks passed, including the complete public APK hash and MCP/TLS. The public manifest has versionCode 10 and `no-cache, no-store, must-revalidate` headers. No real phone installation is claimed; users can install this APK over the previous version without uninstalling.

Download: https://n3urdachengdu.xyz:1999/downloads/StrideHub-0.4.1.apk

The authenticated download page remains `/admin/app`. Rollback uses the previous `stridehub:main-3169b6e9b` image while retaining current data. Private execution evidence is under `var/runtime/android-041-deploy-20261108/`.
