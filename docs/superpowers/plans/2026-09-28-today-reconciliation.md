# Today and workout reconciliation implementation plan

**Goal:** Deliver the user's selected mobile Today page and workout-to-session reconciliation, using the existing authenticated HTTP API and Skill.

**Architecture:** Keep the full training editor and add `/admin/training/today` for daily use. Generate reconciliation candidates on reads from imported runs; never mark a session completed until a user or authorized API client confirms its links. Store multiple linked activities atomically and retain legacy single-activity compatibility.

**Stack:** Symfony/PHP 8.5, Doctrine/SQLite, Twig, plain JavaScript/CSS, PHPUnit and real-browser integration tests.

## Product and API contract

- Today uses the runner profile's IANA timezone, not the browser timezone. It shows today's sessions, a selected session's weather/clothing briefing, partial daily check-in, applicable health constraints, recent unmatched sessions, and completed sessions awaiting feedback. Empty data means unknown. Weather failure does not prevent feedback or reconciliation.
- `GET /training/today` returns `date`, `timezone`, `generatedAt`, `sessions`, `checkIn`, `health: {version, constraints}`, `pendingFeedback`, `reconciliation` and `dataQuality`. `reconciliation` has the same shape as the endpoint below. Full paths use `/api/v1/training` for Bearer clients and `/admin/training/api` for administrator sessions.
- `GET /training/reconciliation?from=YYYY-MM-DD&to=YYYY-MM-DD` returns `{items:[comparison...]}`. Default window is today and previous seven local days. Comparisons retain existing `planned`, `actual`, `delta`, `candidates`, `suggestion`, `dataQuality`, and add `actualActivities`. Candidate entries contain activity summary plus `matchReasons` and `startOffsetMinutes`. Search is based on the session's local calendar day plus a bounded adjacent-time window, supports runs delayed by hours, excludes already linked activities and does not silently select candidates.
- Sessions expose `activityIds` (maximum 20) and keep `activityId` as the first linked activity for old clients. Explicit `activityId` writes replace links with zero/one activity; explicit `activityIds` replaces the full set. Contradictory simultaneous fields are rejected. Existing stored single links are migrated and normalized. Each activity may belong to only one session; duplicate claims, missing activities and stale versions reject the entire mutation. Unlinking does not invent a new completion state; callers can explicitly update `status`.
- Existing `POST /sessions/{id}/link` accepts `{version, activityIds:[...]}` as well as legacy `{version, activityId}`. Empty activityIds unlinks. Aggregate actual distance/moving duration and time-weighted measured heart rate only; explain that gaps between split files are not movement and aggregate comparison does not prove interval target completion.
- `PUT /sessions/{id}/feedback` accepts `{version, feedback:{...}}` and merges only supplied feedback fields. Feedback supports existing rpe/thermalFeeling/notes plus nullable pain and optional fuel `{carbsGrams, fluidMl, giComfort, notes}`. Fuel values can be unknown; this is a session summary, distinct from timed fuel logs. Saving feedback does not fabricate imported activity or mark completion.
- Daily check-ins allow null fatigue, soreness and pain. Old explicit values remain intact. The full editor and Today never default unknown fields to zero or false. Health constraints appear in all training briefings as dated source-backed information; the backend does not infer medical rules from prose.
- The mobile page preserves unsaved drafts during refresh and failed writes; prevents double submission; handles 401, 403, 409 and uncertain network outcomes without blind mutation retries. All browser writes use existing session auth and CSRF. No API key or health document enters HTML/localStorage.

## Implementation and validation

- [x] Add failing tests for late/split activity matching, exclusive atomic links, compatibility, unlink/delete and aggregate comparisons; implement repository link storage, migration and reconciliation service behavior.
- [x] Add failing tests for local-day Today selection and dated health constraints; implement Today aggregation and consistent briefing context.
- [x] Add failing tests for partial/unknown check-ins and feedback merging/validation; extend training API dispatch, input validation and feedback service.
- [x] Build accessible Today UI, quick forms, candidate selection/confirmation, links from existing navigation and legacy editor compatibility.
- [x] Run scoped PHP regression, PHPStan/style, migration rollback/reapply, JavaScript/Twig checks and real HTTP/browser scenarios against a disposable database. Cover a 390px viewport, late run, split records, occupied record, conflict, missing weather, partial feedback, refresh preservation and empty state. Keep personal data unchanged.
- [x] Review changes independently, update OpenAPI/Skill/user docs and validation evidence, commit and push to the existing feature PR.

## File ownership

- Reconciliation: `TrainingService.php`, `TrainingActivities.php`, `TrainingRepository.php`, new migration and focused reconciliation tests.
- Daily aggregation: new `TrainingToday.php`, `TrainingBriefing.php`, health-context presentation helpers if needed and focused daily/briefing tests.
- UI: new Today controller/template/JS/CSS; navigation and existing training editor adjustments.
- Integration: `TrainingInput.php`, training API controller, new `TrainingFeedback.php`, integration tests, OpenAPI/Skill/docs and disposable runtime verification.

The existing checkout and open feature PR are reused because both features extend the same ongoing work. No Zepp credentials are requested or data synchronization enabled by this task; imported data is used whenever available.
