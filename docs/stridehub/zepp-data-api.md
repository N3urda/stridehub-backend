# Zepp detailed health data

StrideHub keeps daily records, minute samples and original Zepp source records separately. The storage is local to the existing application database. No data is sent to an AI provider by synchronization; an authorized AI client can read the APIs below.

## Authentication and routes

All routes require the existing Bearer API key under `/api/v1/health/zepp`, or an authenticated administrator session under `/admin/health/api/zepp`. Responses, including errors, use `Cache-Control: no-store, private`. Admin writes additionally require the health page's `X-CSRF-Token`. Credentials never travel through these APIs.

| Method and suffix | Result |
| --- | --- |
| GET `/status` | Configuration, last attempt/success, counts, date range and separate source statuses. |
| GET `/days?from=YYYY-MM-DD&to=YYYY-MM-DD` | `{records: [...]}` ordered newest first. Compact daily measurements; no minute arrays or raw payloads. |
| GET `/days/YYYY-MM-DD` | One daily record, with `samples`, `sleepStages` and `activityStages`. |
| GET `/raw/{id}` | A referenced immutable original source record, decoded summary if available and fetch time. This may contain sensitive device identifiers; request only when needed. |
| POST `/sync` | JSON `{ "days": 30 }`, range 1–90. Returns synchronization status; partial success is HTTP 200 with `state: partial`. |

The command `app:health:zepp:sync --days=90` performs an initial backfill. The existing daemon refreshes configured daily connections at minute 15 of every hour and workouts at minute 25 using `app:health:zepp:workouts:sync`. `--if-configured` skips absent connections. Source time windows are fetched in small chunks. The legacy sleep read API receives the same normalized sleep records, preserving existing consumers.

### Legacy and events account identifiers

The legacy band/workout `userId` can differ from the account ID returned by an authenticated `/v2/users/me/events` response. Using the legacy ID for `/users/{id}/events` then returns HTTP 403 with code `-2005`, even while sleep and workout synchronization succeeds. The private credentials file accepts an optional `eventsUserId`, verified from a response for the same authenticated account; only events use this ID, on the configured regional host. Do not replace the legacy `userId` or reset stored account ownership. Without the optional field, existing same-ID accounts retain their behavior. Credential refresh preserves the optional field for the same legacy account.

Blood-oxygen `extra` may be a JSON string. Only `click` spot readings contribute to the oxygen sample chart; ODI/OSA events remain intact in raw source records. PAI and readiness records are also preserved in raw sources; their successful import does not imply separate dashboard cards exist.

## Daily record

Each record includes `date`, `sourceDate`, `source: zepp`, `timeZone`, `lastSyncedAt`, `rawRecordIds`, `sourceFields`, `retainedFields`, and the groups below. A missing measurement is **null**, never a synthetic zero. A measured zero, such as zero steps, remains zero. `coverage` marks each group `available` or `missing`; source failures live in `/status.sources` independently of historical availability. A step goal alone does not count as observed activity. `retainedFields` identifies values or series preserved from earlier source versions when the latest response omitted them; AI consumers must not treat them as newly measured.

| Group | Fields and units |
| --- | --- |
| `sleep` (nullable) | `date`, `sourceDate`, `startedAt`, `endedAt`, `totalMinutes`, `deepMinutes`, `lightMinutes`, `remMinutes`, `awakeMinutes`, `score`, `restingHeartRate`, `wakeCount`, `rawRecordIds`. Sleep is assigned to its actual wake date when the source offset is known. Unsupported REM is null. |
| `activity` | `steps`, `distanceMeters`, `caloriesKcal`, `activeMinutes`, `walkingMinutes`, `runningMinutes`, `stepGoal`. Active minutes are the sum of available walking and running minutes; calories are the source's activity value, not an estimated total daily energy expenditure. |
| `heartRate` | `restingBpm`, `averageBpm`, `minBpm`, `maxBpm`, `sampleCount`. Average/min/max describe observed minute readings, not a complete day or medical interpretation. Resting HR is only a source-provided resting metric. |
| `oxygen` | `averagePercent`, `minPercent`, `sampleCount`, calculated from returned spot readings. |
| `stress` | `average`, `max`, `sampleCount`; source summary and returned point count. |
| `body` | `weightKg`, `bodyFatPercent`; currently null because the verified APIs do not provide them. Use the separate weight history API. |

Day detail adds `samples.heartRate: [{at, bpm}]`, `samples.oxygen: [{at, percent}]`, and `samples.stress: [{at, value}]`. Heart rate is decoded only for the verified 1,440-byte, one-reading-per-minute format with a known source offset. Zero, 254, 255 and invalid values are excluded; gaps remain gaps. Unknown binary formats are retained in raw data for future decoding. Sleep/activity stage arrays preserve the upstream `start`, `stop`, `mode` schema; mode codes are device/protocol dependent and must not be guessed or presented as verified sleep labels.

Daily activity and heart rate use the source's calendar date. Sleep can belong to the previous source day, so it is joined using its own local wake date. Overlapping devices are not summed; the row with the most minute HR samples and then most steps is preferred, while its missing fields are filled from other same-day rows. `fieldSources` maps each measured field or stream to its original raw record IDs. All original rows are retained. Provider corrections create new content-addressed source versions. Repeated identical rows are deduplicated. A transactionally claimed account owner is shared by daily, legacy sleep and workout importers, preventing mixing accounts even across independent containers. Daily and legacy sleep synchronization also share a versioned database lease. Network/authorization failures preserve existing records, and missing fields/series do not erase previously measured values.

## Availability and evidence

The authenticated regional `band_data.json?query_type=detail` endpoint has been verified against this connection. It returns daily activity, sleep summaries/stages when present, minute heart rate and original binary activity data. The original provider summary and binary fields are persisted, including fields not yet interpreted.

The global events endpoint is queried separately for `all_day_stress`, `blood_oxygen`, `PaiHealthInfo` and `readiness`. The current connection returned HTTP 403 for these four sources during live validation. This is displayed as source-specific `auth_required`, with overall `partial` status; it does not mean the successfully verified band data failed. PAI/readiness and oxygen ODI/OSA event shapes are retained raw if returned; they are not mislabeled as measurements that the parser has not verified. No body-composition endpoint or detailed stage-code mapping is claimed as supported.

Protocol references: [band-detail parser](https://github.com/weddle/zepp-cloud-sdk/blob/main/src/zepp_cloud/parsers/band_detail.py), [events resource](https://github.com/weddle/zepp-cloud-sdk/blob/main/src/zepp_cloud/resources/events.py), and [original event extraction implementation](https://github.com/bentasker/zepp_to_influxdb/blob/main/app/mifit_to_influxdb.py). These describe an unofficial cloud interface, so source-specific errors and original payload retention are part of the data contract.
