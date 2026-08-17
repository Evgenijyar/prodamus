# Prodamus Client API — draft contract v0.1

Base content type: `application/json`.

Authenticated client endpoints use:

```http
Authorization: Bearer <accessToken>
```

## POST /api/client/auth/login

```json
{
  "login": "ivan",
  "password": "secret password",
  "deviceId": "random-stable-device-id",
  "deviceName": "OFFICE-PC-01",
  "rememberMe": true
}
```

## POST /api/client/auth/refresh

```json
{
  "refreshToken": "...",
  "deviceId": "random-stable-device-id",
  "deviceName": "OFFICE-PC-01"
}
```

Refresh token rotates on every successful refresh.

## GET /api/client/bootstrap

Header:

```http
X-Prodamus-Client-Version: 0.1.0
```

Prompts/knowledge base/AI keys are not returned.

## POST /api/client/live-sessions

```json
{
  "promptProfileId": 1,
  "deviceId": "random-stable-device-id",
  "clientVersion": "0.1.0",
  "clientContext": null
}
```

Response contains `sessionId`, constrained Gemini `ephemeralToken`, token expiry, WebSocket URL, model, and recommended heartbeat interval.

## POST /api/client/live-sessions/{sessionId}/heartbeat

Extends the server lease.

## DELETE /api/client/live-sessions/{sessionId}

Gracefully releases the session capacity.
