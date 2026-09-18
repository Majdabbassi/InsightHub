# ADR-001: JWT authentication with local LLM role isolation

Status: Accepted

## Context

The app needs user accounts (register/login) plus protection of project, dataset, and chat APIs. The AI services (analytics, chat) should never see primary credentials. We need an auth mechanism simple to operate locally and in Docker.

## Decision

- Spring Security with a stateless JWT filter (`JwtAuthenticationFilter + JwtService`).
- Tokens signed with HMAC-SHA256; signing key comes from `JWT_SECRET` (validated, minimum 32 bytes, insecure defaults rejected outside `dev`/`test`).
- Only `/auth/login`, `/auth/register`, and the health endpoint are public.
- Ownership checks in controllers isolate user data; the analytics service and Ollama are only reachable through backend services.

## Consequences

- No server-side session store; horizontal scaling is trivial.
- Token revocation requires `JWT_EXPIRATION_MS` to stay short (1h default).
- The frontend must handle `401` → redirect to `/login` (implemented in the auth interceptor).