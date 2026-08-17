# Prodamus Windows Client API — contract v1

Base content type: `application/json`.

Authenticated client endpoints use:

```http
Authorization: Bearer <accessToken>
X-Prodamus-Client-Version: 1.0.0
```

## POST /api/client/auth/login

```json
{
  "login": "ivan",
  "password": "manager-password",
  "deviceId": "stable-random-device-id",
  "deviceName": "OFFICE-PC-01 / Prodamus",
  "rememberMe": true
}
```

The backend returns opaque access/refresh tokens. The Windows client keeps the access token in memory only. A persistent refresh token is stored only when `rememberMe=true` and is protected locally with Windows DPAPI.

## POST /api/client/auth/refresh

```json
{
  "refreshToken": "...",
  "deviceId": "stable-random-device-id",
  "deviceName": "OFFICE-PC-01 / Prodamus"
}
```

Refresh token rotates on every successful refresh.

## POST /api/client/auth/logout

Revokes the supplied access/refresh tokens.

## GET /api/client/bootstrap

Header:

```http
X-Prodamus-Client-Version: 1.0.0
```

Returns only:

- current manager identity;
- enabled roles assigned to that manager (`id`, `name`, `description`);
- client feature flags;
- minimum/latest version policy and optional client download URL.

System prompts, role prompts, knowledge base, permanent Gemini credentials, encrypted credentials, model configuration internals and administrative settings are never returned by bootstrap.

## POST /api/client/live-sessions

```json
{
  "promptProfileId": 1,
  "deviceId": "stable-random-device-id",
  "clientVersion": "1.0.0",
  "clientContext": null
}
```

The backend validates the assigned role and version, reserves credential capacity, composes the hidden server-side instruction and returns only the connection descriptor:

- `sessionId`;
- constrained Gemini `ephemeralToken`;
- `tokenExpiresAt`;
- `newSessionExpiresAt`;
- constrained WebSocket URL;
- model required for setup;
- recommended heartbeat interval.

Only one active Live-session is allowed for one manager at a time. Active session operations are additionally bound to the authenticated `deviceId`; another logged-in device cannot heartbeat, renew or close that session.

## POST /api/client/live-sessions/{sessionId}/token

Reissues a short-lived constrained Gemini token for an already active Prodamus session. Used by the Windows client for long-running calls without exposing the permanent Gemini API key.

Optional body:

```json
{
  "clientContext": "Current manual client context"
}
```

The endpoint keeps the same Prodamus `sessionId`, extends its lease and returns a fresh connection descriptor.

## POST /api/client/live-sessions/{sessionId}/heartbeat

Extends the server lease for the active session.

## DELETE /api/client/live-sessions/{sessionId}?reason=Client%20stop

Gracefully closes the Live-session and releases its credential capacity.
