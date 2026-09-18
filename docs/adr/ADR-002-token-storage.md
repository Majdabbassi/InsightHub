# ADR-002: Token storage in localStorage (with expiration checks)

Status: Accepted — portfolio trade-off, documented for a production follow-up

## Context

The API returns the JWT in the response body. The SPA must persist the session across reloads. Options: `localStorage`, `sessionStorage`, in-memory, or switching the API to `HttpOnly` cookies.

## Decision

- Store the token in `localStorage` under `da_token`.
- Store user profile under `da_user`.
- Validate the JWT `exp` claim on startup and on every `token` read; immediately clear stale sessions (never treat an expired token as authenticated, and skip useless API calls).
- On any `401` from a non-auth endpoint, clear the session and redirect to `/login`.

## Consequences

- **Risk**: `localStorage` is readable by any script → XSS can exfiltrate the token. Acceptable for a portfolio/demo; see `docs/SECURITY.md` for the production path (`HttpOnly` cookies or a BFF).
- **Benefit**: stateless refresh across tabs, zero extra backend work, simple demo story.
- Mitigations in place: expiry checks, strict Content-Security-Policy-friendly code (no external scripts), minimal surface for JS-injected HTML (Angular text interpolation).