# Task 2 — native backend workflows

Implemented dedicated `/api/v1/mobile/*` workflow adapters. They dispatch only explicitly selected existing domain commands; clients cannot name commands or provide server paths. Auth/security files are owned by Task 1 and were not changed here.

## Contracts

All responses use `Cache-Control: private, no-store`; errors retain the existing `{error,message}` format. JSON writes require an object and `application/json`, reject unknown fields, and cap request bodies at 64 KiB.

- `GET workouts?page=1&limit=30&from=YYYY-MM-DD&to=YYYY-MM-DD`: existing `WorkoutCatalog` overview (`records,summary,pendingDuplicates,weeks,methodology`) with only `records` paged; `pagination:{page,limit,total,pages,hasMore}`. Limit 1–100. Default range is the latest 366 calendar days in the profile timezone. Ordering uses start timestamp descending, then ID ascending, including ties.
- `GET activity-options`: `{canCreate,importMode,sportTypes:[{value,label}],workoutTypes:[{value,label}],gears:[{id,name,imported}],units:{distance,elevation}}`.
- `GET activities/{id}`: `{id,version:string,source,fields,capabilities:{edit,delete,enrich,editableFields,deleteEffects}}`. Cloud Zepp details retain their existing source shape under `fields` and are read-only.
- `POST activities`: manual creation fields `{name,sportType,startDateTime:'YYYY-MM-DDTHH:mm',duration:{hours,minutes,seconds},distance,elevation,description?,gearId?,calories?,averageHeartRate?,maxHeartRate?,workoutType?,isCommute?,isGroupActivity?}`. Distance/elevation use the settings unit system, returned in options. Returns the created editor record, HTTP 201.
- `PUT activities/{id}`: partial editable fields plus current `version`. Imported records allow name, description, sportType, gearId, calories, deviceName, isCommute, isGroupActivity; manual records additionally allow start/duration/distance/elevation/heart rates/workoutType in file mode. Imported Strava gear is read-only. Invalid gear references are rejected. Version is a SHA256 of the current server row, so web/import changes also invalidate stale mobile drafts. Missing version is 428, stale version is 409.
- `DELETE activities/{id}?version=STRING`: existing domain deletion removes activity, streams/metrics, laps/splits, best efforts/routes/drivetrain, FileImport raw data, images and orphaned segments. Existing training/source links block deletion with 409. Native receipt retry payloads are deleted and receipts become `deleted` tombstones. Returns 204.
- `POST activities/{id}/enrich`: `{version:STRING}`, existing coordinate/weather enrichment, rejects records without coordinates.
- `GET activities/{id}/streams`: imported/manual streams for all sport types, `{streams:{[type]:[samples]},laps:[...],splits:[...]}`. Stream types are existing domain names: time, distance, latlng, altitude, velocity_smooth, heartrate, cadence, watts, temp, moving, grade_smooth when present. Units are seconds, meters, meters/second, bpm and degrees as applicable; no missing sensor samples are fabricated. Laps/splits expose existing domain export fields and numeric measurement values. Zepp timed sparse streams remain available through the existing Zepp detail API.
- Multipart `POST imports`, one field `file`: HTTP 202 receipt `{id,filename,state,activityId,error,attempt,version,updatedAt,fileImportId?}`. Cap 25 MiB. ID is content SHA256; repeated pending/completed content returns its original receipt without enqueueing again.
- `GET imports`: `{records:[receipts]}`; `GET imports/{id}`: receipt. States: queued, success, skipped, failed, interrupted, deleted. Queued means queued, not imported. FileImport supplies actual per-file final status/activity/error.
- `POST imports/{id}/retry {}`: HTTP 202 same receipt ID, requeues only failed/interrupted uploads, increments attempt, preserves durable source bytes. Repeated pending/success/skipped/deleted retries never enqueue duplicate files or resurrect deleted activity data. Interrupted staging writes recover without enqueueing an already-present file twice.
- `POST sync`: `{kind:'daily'|'sleep'|'workouts',days:1..90}` defaults days=30, returns HTTP 202 job `{id,kind,days,state,result,error,startedAt,finishedAt,version,updatedAt}`. Any in-flight submission for the same kind returns the existing job and its original days. States: queued, running, success, partial, no_data, failed, interrupted.
- `GET sync`: `{records:[jobs],sources:{daily,sleep,workouts}}`; `GET sync/{id}`: job. Polling a running job detects absent worker ownership and marks it interrupted, never successful and never automatically replayed.

## Operation and durability

`app:mobile:workflows:run --limit=10` processes persisted `TrainingRecord` mobile-sync jobs. Root integrates it into the existing minute daemon schedule. A global process lock makes redundant scheduled invocations return immediately, while per-job locks distinguish a live owner from a killed worker. Existing Zepp services, account ownership, source locks and source result semantics are reused.

Lifecycle and submission/upload locks use process-owned flock files in `watch/.uploads/mobile-locks`, shared by existing web and daemon watch mounts. There is no arbitrary expiry while a live remote sync is taking longer than expected. These locks assume the existing single-host NAS/container deployment; a distributed multi-host deployment would need shared reliable locking.

Import receipts use `TrainingRecord` kind mobile-import and retained retry payloads in `watch/.uploads/mobile-imports/{sha256}`. Hidden subdirectories are ignored by the existing watch importer. Both receipt metadata and payloads survive container recreation with existing database/watch mounts; no new migration or volume is needed. The ordinary file-import daemon processes the same watch queue with its existing import mutex and duplicate scanner. Missing queue files with no result are marked interrupted only when no fresh import mutex heartbeat exists.

No production database, existing application container or actual Zepp account was used. Source network success and NAS operation still need deployment/device verification.

## Verification

Isolated PHP 8.5.10 container: `stridehub-native-test`, `TEST_TOKEN=native_workflows`, `APP_DEBUG=1`. Tests use `tests/dreevenative_workflows.db` plus test-memory storage. Generated country geography assets were copied from the local main checkout into this ignored isolated worktree to enable the real TCX parser test.

- Focused native workflow controller tests: 10 tests, 99 assertions, passed. Covers manual CRUD, partial updates, changed web rows, missing/stale versions, linked deletion conflicts, no-coordinate enrich rejection, file/cloud/Strava capabilities, stable tied pagination and full-range summaries, malformed/oversized requests, no generic dispatch fields, sync deduplication, concurrent worker exclusion, real unconfigured-source result, lost-worker interruption, real TCX import, native streams/laps, deletion effects and retry tombstones, staging/missing-queue recovery, truthful parser failure receipts and repeated retries.
- Existing file-import pipeline and manual/imported activity command regressions passed together with native tests: 39 tests, 255 assertions.
- PHPStan level 8/type coverage checks for all three new production files: passed with no errors.
- Symfony container dependency lint: passed.
- PHP CS Fixer applied to owned files only.

No new frontend dependencies, auth behavior, production deployment, migrations or reviewer agents were introduced by this task.
