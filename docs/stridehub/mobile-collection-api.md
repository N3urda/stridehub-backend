# Mobile weight collection v1

All requests use HTTPS in production. Pair through native HTTP using the authenticated WebView session Cookie: GET `/admin/health/api/collection/csrf` returns `{csrfToken}` (health CSRF); POST `/admin/health/api/collection/devices` JSON `{name:"My phone"}` with Cookie and `X-CSRF-Token` returns `{deviceId,name,token}`. Token is returned once. Store in Android encrypted storage. No admin API key on phone. GET same devices route returns `{devices:[{deviceId,name,revoked}]}`; DELETE `/admin/health/api/collection/devices/{deviceId}` revokes with CSRF.

Device routes use `Authorization: Bearer <token>` only:
- PUT `/device/v1/weight/{measurementId}` creates immutable measurement, or replays same canonical payload; response `{measurement, replayed, projection}`. UUID must match body's measurementId. 409 changed payload or deleted UUID; deletion cannot be undone by replay.
- GET `/device/v1/weight/{measurementId}` returns `{measurement}` for own device, 404 otherwise, including tombstone when deleted.
- GET `/device/v1/weight?offset=0&limit=50` returns `{records,total,offset,limit}` own records including tombstones, UTC measuredAt descending then measurementId descending.

JSON body (32 KiB maximum):
```json
{"measurementId":"a0000000-0000-4000-8000-000000000001","measuredAt":"2026-10-21T08:00:00+08:00","localDate":"2026-10-21","weightKg":75.2,"fatPercent":null,"bmi":null,"impedanceOhms":null,"profile":{"heightCm":175,"birthYear":1990,"sex":"male"},"derived":{"algorithmVersion":"yunmai-v1","values":{"bmi":24.55}}}
```
Required: measurementId, measuredAt (ISO with explicit offset), localDate, weightKg (2..300), profile object, derived object with nonempty algorithmVersion and values object. Missing optional numeric fields normalize to null, never zero. Profile is an immutable JSON object snapshot; derived values are a versioned JSON object snapshot. Nullable fatPercent 0..100, bmi 1..100, impedanceOhms 0..5000. Unknown top-level fields rejected. No schema migration or recalculation on replay. measuredAt identifies the instant; localDate independently preserves the original captured calendar day. Historical imports may have a different localDate from the supplied offset (original timezone may be unknown). New Android captures should persist their original offset. Never infer an original timezone for historical imports.

Admin Cookie+CSRF or existing admin API bearer: GET `/admin/health/api/collection/weight` or `/api/v1/health/collection/weight` paginated history; DELETE same `/{measurementId}?deviceId=...` creates tombstone. API key routes retain existing authorization. Device credentials cannot access these or other admin/API/MCP routes.

Projection response `{status:"projected"|"manual_conflict"|"unchanged",measurementId}`: latest surviving capture per local day projected to existing weight history in kg converted to configured units. Existing non-collection daily entries preserved and report manual_conflict. Deletion recomputes day. Manual edits thereafter remain protected. Capture immutable data is retained independently; manual projection conflict does not discard capture. A storage/concurrent-write failure rolls back the transaction and can be retried with the same measurementId. Errors use existing `{error:...}` envelope with HTTP 401/403/404/409/413/415/422. limit 1..100; offset 0..100000.
