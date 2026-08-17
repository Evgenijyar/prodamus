# Prodamus Backend — architecture v0.1

## Boundaries

The backend is the control plane. It does not proxy realtime call audio.

- **Windows client**: captures local microphone + Windows loopback, renders overlay, connects directly to Gemini Live.
- **Prodamus Backend**: authenticates users/devices, serves bootstrap/configuration, manages prompt profiles, encrypts permanent AI credentials, allocates Live-session capacity and mints constrained ephemeral tokens.
- **PostgreSQL**: users, password hashes, token hashes, roles, encrypted AI credentials, technical session metadata and audit events.
- **Gemini Live**: realtime audio processing over a direct client-to-Gemini WebSocket. The client uses the same buffered-utterance/VAD flow as the original Sales Helper.

Once the backend has returned the constrained ephemeral token, it is not on the active-conversation path. The client sends no heartbeat, renew, audio, transcript, history or close request during that conversation.

## Security model

- User passwords: PBKDF2-HMAC-SHA256 with a random salt.
- Access and refresh tokens: 256-bit random opaque values; only SHA-256 hashes are stored in PostgreSQL.
- Remember-me: persistent refresh token expiry defaults to 7 days.
- AI API keys: AES-256-GCM at rest, protected by `PRODAMUS_MASTER_KEY`.
- Windows clients never receive permanent AI API keys.
- Each conversation gets a constrained Gemini ephemeral token.
- Back-office uses an HttpSession plus a per-session CSRF token. This deliberately remains a simple internal admin login for v0.1, without Spring Security.

## Live-session allocation

A START request is handled transactionally:

1. Lock the user row and close any older audit reservation for that user.
2. Validate that the requested prompt profile is assigned and enabled.
3. Pessimistically lock enabled AI credentials.
4. Select the first credential whose active leased-session count is below capacity.
5. Create a `PROVISIONING` lease.
6. Mint the constrained ephemeral token outside the reservation transaction.
7. Mark the issuance record `ACTIVE`; no client heartbeat is required.
8. A scheduler expires the short reservation automatically.

This avoids oversubscribing the key pool when multiple managers press START concurrently.

## Prompt composition

The effective system instruction is assembled server-side:

1. global company prompt;
2. role/scenario system prompt;
3. role knowledge base;
4. manager-specific instructions;
5. optional manually supplied client context (feature-flagged, off by default).

The native client receives only the role ID/name/description; prompt contents remain server-side.

## Future integrations

Bitrix24 is intentionally outside this release. It can later be added as a separate integration adapter that resolves CRM context and augments the same server-side prompt composition without changing the Windows authentication or Live-session contracts.
