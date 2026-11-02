# StrideHub 0.4.0: chart-first workspace

The user requested a broad Android/Web redesign, richer charts and less default prose, and directly authorized implementation and redeployment without further confirmation. Preserve all health records, reports, scale history, sync identities and credentials. The implementation starts from 8dccc67f1, the verified 0.3.4 production lineage, rather than the older checkout main/master.

## Design decisions

Use one Android navigation shell including weighing. Keep the existing local weighing storage and BLE capture lifecycle. Charts precede record lists; editing and data provenance remain reachable in disclosures. Use existing API facts, never inferred health/readiness scores. Prefer real dates and units, readable axes, touch inspection, keyboard equivalents, clear no-data states and explicit unknown values.

Palette: slate #183548, background #F3F6F8, activity teal #178B91, sleep violet #7763BC, weight orange #DB8639, heart rose #CA5C72. Use the platform system typeface and tabular numerals; emphasize chart titles and data. Desktop places complementary plots side by side; phone uses a single column with persistent navigation.

Alternative considered: skin existing cards only (does not improve information density); move all analysis into a single dashboard (makes task-specific flows harder). Chosen: shared chart vocabulary across focused overview, body, movement, weight, nutrition and plan workspaces, preserving their existing operations.

## Evidence and chart mapping

- [Strava Training Log](https://support.strava.com/en-us/articles/15402077-training-log): weekly visual activity overview and inspectable activities; adapt as activity calendar and weekly volume bars.
- [Strava Fitness & Freshness](https://support.strava.com/en-us/articles/15402032-how-fitness-freshness-is-calculated): range selection and focused time inspection. Reuse interaction, not proprietary/unsupported fitness scores.
- [Gadgetbridge activity and sleep](https://freeyourgadget.codeberg.page/gadgetbridge_org/basics/features/activities/): activity/HR time charts, sleep composition and configurable visibility. Use sleep stacked duration bars, heart trends and expandable underlying records.
- [Apple Fitness activity tracking](https://support.apple.com/en-euro/guide/watch/apd3bf6d85a6/watchos): goals and progress as compact graphics. Use explicit recorded-versus-target comparison bars, without inventing goals.
- [GoldenCheetah charts](https://github.com/GoldenCheetah/GoldenCheetah/wiki/UG_ChartTypes_Trends): different chart types for trends, distributions and composition. Use category duration rings and bars alongside retained detailed running analytics.

Weight: measured points and seven-day observed-data averages; target style separate. Sleep: stacked deep/light/REM with unattributed total remainder; durations are not chronological stages. Nutrition: paired actual/target bars, gram series, and 4/4/9 kcal composition only when all macro values are present. Plans: duration by date and state composition, actual and planned separated. Workout totals suppress ambiguous duplicate aggregates.

## Verification and deployment

Check arithmetic and missing/zero distinction, single observations, sparse dates, touch/keyboard selection, 390px and desktop layouts, loading/errors, existing editor and stale-version flows, weighing navigation and background capture pause. Run relevant PHP regression, Android unit/lint/build and actual emulator navigation. Build signed upgrade with existing key, monotonic versionCode, and no data clear. Publish APK/manifest and immutable web image after consistent backup with all writers stopped; verify persistent counts/files, services, public TLS and APK checksum. Physical BLE and the user's own phone are not presumed validated by emulator checks.
