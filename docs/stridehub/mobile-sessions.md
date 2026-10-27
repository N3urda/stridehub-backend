# Native mobile session contract

`POST /mobile/v1/sessions` accepts JSON `{username, password, deviceName, persistent?}`. The optional `persistent` field must be a JSON boolean. Missing or `false` preserves the existing seven-day lifetime. `true` creates a revocable session without a scheduled expiry. Authentication, account/IP throttling and token scopes remain the same.

A successful login returns HTTP 201 with `{token, username, persistent, expiresAt, apiVersion: 1, capabilities}`. `persistent` is always a boolean. `expiresAt` is `null` only for a persistent session; otherwise it is the original absolute expiry timestamp. The capability `persistent-mobile-sessions` identifies support. The token remains an opaque `shmobile_` credential; only its SHA-256 lookup key is stored by the backend.

`GET /mobile/v1/session` returns the same metadata without `token`. `PATCH /mobile/v1/session`, authenticated with the current mobile bearer token, accepts exactly `{"persistent":true}`. It upgrades an unexpired finite session to persistent without changing the token or any health/training records. Repeating the request returns HTTP 200 with the same metadata and does not write another version. Missing/false/non-boolean values or extra fields return 422. The endpoint does not accept a global API token or weighing-device token.

Legacy rows without the flag keep their original finite expiry. Only an explicit stored `persistent: true` together with an explicit `expiresAt: null` is indefinite. Missing, invalid or inconsistent lifetime data is rejected with 401. Expired, revoked, or credential-invalidated sessions cannot be upgraded. Promotion uses the existing row version, so a concurrent deletion cannot reinsert a revoked session; concurrent successful promotion can be read back idempotently.

`DELETE /mobile/v1/session` revokes the current session and returns 204. Subsequent authentication returns 401. Changing the administrator username or password invalidates both finite and persistent sessions through the existing credential-version binding. The backend does not refresh expired tokens. All session responses are private and `no-store`; no browser cookie is issued.

Deployment order: upgrade the backend before an APK that requests persistent sessions. An existing APK that omits `persistent` continues to receive seven-day sessions. A new APK can promote a still-valid saved token after detecting the capability; an already expired or revoked token requires a fresh password login.
