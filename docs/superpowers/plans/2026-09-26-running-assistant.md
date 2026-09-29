# StrideHub running assistant implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the assigned modules and test meaningful behavior before completion.

**Goal:** Deliver future running schedules with a Codex-friendly HTTP API, race goals, forecast-based clothing and timing advice, reminders, activity comparison, wellbeing check-ins, and fueling records.

**Architecture:** Extend the existing Symfony application and SQLite database. Reuse its bearer API authentication and admin session, and keep new domain code under `src/Domain/Training`. Preserve upstream activity weather behavior. Store validated training resources with optimistic versions and add an independent forecast provider and notification delivery ledger.

**Tech stack:** PHP 8.5, Symfony 8.1, Doctrine DBAL/ORM metadata, SQLite, Twig, vanilla JavaScript, Open-Meteo, Shoutrrr, PHPUnit.

## Approved scope and behavior

- The user explicitly requested all discussed capabilities and an external training schedule API. Develop in this directory on `feat/running-assistant`; no separate checkout is needed.
- Persist races, structured sessions, runner profile, daily wellbeing, session feedback and fueling practice. Support editing, rescheduling, skipping, deleting and comparing sessions to real imported activities.
- Suggestions explain their inputs and preserve saved plans. Applying a change is a separate explicit action. Missing weather/physiology must be shown as unavailable, not fabricated.
- New browser page lives at `/admin/training`, requires the existing admin login, and uses session-authenticated `/admin/training/api/*` endpoints with `X-CSRF-Token` for mutations. Never expose the API key in browser HTML.
- External base: `/api/v1/training`, protected by the existing `DREEVE_API_KEY` bearer token. JSON only, bounded payloads, explicit validation and no permissive CORS.

## Shared API contract

Resources use camelCase JSON. Every saved record has `id`, `version`, `updatedAt`. Lists return `{items: [...]}`; detail/create/update return the record itself. Errors return `{error, message}`. PUT is a partial update; omitted values are retained. Updating and deleting requires the current integer `version` (JSON for PUT, query for DELETE); stale versions return 409, missing versions 428. POST may provide an ID for client-managed identity; duplicate IDs return 409. IDs match `[a-zA-Z0-9_-]{1,80}`.

- `GET/PUT /profile`: singleton id `default`, version 0 when unconfigured. Fields: `timezone` (IANA, default Asia/Shanghai), `location` (null or `{label, latitude, longitude}`), `thermalPreference` (`cold|neutral|warm`, default neutral), `usualStartTime` (`HH:mm`, default 06:30), `usualDurationMinutes` (default 60), `runningDays` (ISO weekdays 1–7), `notificationsEnabled` (default false), `eveningReminderTime` (default 20:00), `preRunReminderMinutes` (default 60).
- `GET/POST /sessions`, `GET/PUT/DELETE /sessions/{id}`. Fields: `title`, `startAt` (RFC3339 with offset), `durationMinutes`, `distanceKm` (nullable), `type` (`easy|long|tempo|interval|recovery|race|rest`), `status` (`planned|completed|skipped|cancelled`), `raceId` (nullable), `location` (nullable override), `steps` (array of `{kind, minutes, distanceKm?, target?}`), `notes`, `fuelPlan` (array of `{minute, item, carbsGrams?, fluidMl?}`), `activityId` (nullable), `feedback` (null or `{rpe?, thermalFeeling?, notes?}`). List accepts `from`/`to` local dates and `status`.
- `PUT /sessions/batch`: `{sessions: [...]}`, at most 100, each with `id` and `version` (0 for creation); atomically validate and upsert, return `{items}`. A stale item rolls back the entire batch.
- `GET/POST /races`, `GET/PUT/DELETE /races/{id}`. Fields: `name`, `date` (`YYYY-MM-DD`), `distanceKm`, `targetTimeMinutes` (nullable), `notes`.
- `GET/POST /check-ins`, `GET/PUT/DELETE /check-ins/{id}`. Fields: `date`, `sleepHours` (nullable), `fatigue` and `soreness` (1–5), `pain` (boolean), `notes`. One per date.
- `GET/POST /fuel-logs`, `GET/PUT/DELETE /fuel-logs/{id}`. Fields: `sessionId` (nullable), `date`, `minute`, `item`, `carbsGrams`, `fluidMl`, `giComfort` (`good|mild|poor`), `notes`.
- `GET /activities`: candidate real running activities (id, name, startAt, distanceKm, durationMinutes, averageHeartRate); no invented activity records.
- `POST /sessions/{id}/link`: `{version, activityId}`, checks activity existence and running sport, prevents duplicate links, marks session completed. `GET /sessions/{id}/comparison` returns `planned`, `actual` (nullable), `delta` (nullable), `candidates`, and `suggestion`.
- `GET /briefing?sessionId=...`: session-specific advice or next planned session; fall back to configured weekly running habits, clearly marked as a habitual suggestion. Returns session, forecast, advice, checkIn, generatedAt, and comparison/coverage where available. No plan edits.
- `GET /openapi.json`: machine-readable OpenAPI documentation, authenticated like the API.

## Module contracts

`TrainingRepository` provides `list(string $kind): array`, `find(string $kind, string $id): ?array`, `save(string $kind, string $id, array $payload, int $expectedVersion): array`, `delete(string $kind, string $id, int $expectedVersion): void`, `transactional(callable $operation): mixed`. Only validated service calls can write domain resources. Record metadata is stripped from payload before save. Kind values: sessions, races, check-ins, fuel-logs, profile.

Weather module owns `ForecastProvider::forecast(float $latitude, float $longitude, string $timezone, DateTimeImmutable $start, int $durationMinutes): array` and `RunningAdvice::advise(array $session, array $profile, array $forecast, ?array $checkIn = null, array $feedbackHistory = []): array`. Forecast response: `status` (`available|unavailable|out_of_range`), `source`, `fetchedAt`, `timezone`, `hours` (time, temperature, apparentTemperature, humidity, precipitationProbability, precipitation, windSpeed, windGusts, weatherCode, uvIndex, isDay), `message`. Advice response: `summary`, `clothing` (string list), `reasons` (string list), `warnings` (string list), `training` (`keep|consider_easier|indoor_or_reschedule|unknown`), `alternatives` (array), `personalization`, `dataQuality`. Actual HTTP forecast failures yield unavailable data; no fallback synthetic forecast.

## Tasks and acceptance

- [x] Runtime: provision PHP8.5/dependencies without changing the lockfile; confirm a baseline test and container boot.
- [x] Core API: add ORM metadata/migration, repository, validation, resources, atomic batch, activity comparison, admin and bearer routes. Tests cover 401, CSRF, validation, persistence, date ranges, stale versions, atomic rollback, cross-resource references and linking.
- [x] Forecast and advice: independent future endpoint, timestamp matching across midnight, cache and unavailable handling; rules use entire session, intensity, preference, personal feedback, fatigue and precipitation severity. Tests cover timezone/midnight, distant date, missing data, storm, hot/cold, preference and comfort calibration.
- [x] Runner interface: responsive Chinese admin page for profile, race, dated session CRUD/structured steps/fuel plan, wellbeing, comparison, feedback, fueling log, briefing. Preserve unsaved edits on API conflict/failure and distinguish saved changes. Add navigation entry.
- [x] Briefing/reminders: compose real resources and weather, use habitual fallback only when explicitly configured. Scheduled evening and pre-run checks, changed-advice follow-up and post-run feedback prompt. Reuse Shoutrrr, track per-channel delivery, retry failures without repeating successes, suppress duplicates and expired messages. Add daemon catalog entry and command with dry-run preview.
- [x] API documentation: complete OpenAPI, curl examples and a Codex workflow demonstrating read version → batch create/update → verify; document setup, timezone, API credential rotation and notification opt-in.
- [x] Integration: run targeted suites, existing auth/scheduler regression, PHP lint, container compilation/Twig lint, migration up/down/up on a disposable DB, and actual HTTP/browser create-edit-refresh-conflict-compare-briefing flow at desktop and narrow width.
- [x] Review: inspect requirements and security/data-loss concerns, fix findings, rerun affected checks; preserve upstream license and clearly report any unverified external delivery.
