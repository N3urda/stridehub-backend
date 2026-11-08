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
