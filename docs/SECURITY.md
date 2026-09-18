# Security

This document describes the security posture of Data Analytics Hub, the trade-offs made for a portfolio/demo application, and what would change for a production deployment.

## Authentication & session management

- **JWT bearer tokens** are issued on login and returned in the JSON response body.
- The backend signs tokens with HMAC-SHA256 (`JwtService`). The signing key (`JWT_SECRET`) must be at least 32 bytes; known insecure defaults are rejected when the `prod` profile is active.
- Token lifetime is configurable via `JWT_EXPIRATION_MS` (default 1 hour). The frontend validates the `exp` claim client-side on startup and on every API call: expired tokens are cleared immediately.
- The frontend also handles `401` responses globally (`auth.interceptor.ts`): the session is cleared and the user is redirected to `/login`.

### Known limitation – token storage

Tokens are stored in `localStorage` (`da_token`). This is the standard choice for SPAs backed by a JSON API, but it is vulnerable to XSS: a script that can access `document` can exfiltrate the token.

For production, the recommended mitigation is to issue tokens as **`HttpOnly`, `Secure`, `SameSite=Strict` cookies** (or use a BFF / session proxy), eliminating JavaScript access to the credential entirely. The current API contract returns the token in the response body; switching to cookies requires a backend change.

## Passwords

Passwords are hashed with BCrypt (`PasswordEncoder`). The hash is never returned in API responses.

## Authorization

- All API endpoints except `/auth/login`, `/auth/register`, and `/actuator/health` require a valid JWT (`JwtAuthenticationFilter`).
- The frontend guards all protected routes with `authGuard`; expired or absent tokens redirect to `/login`.
- Ownership checks (`ProjectController`, `DatasetController`) verify the authenticated user owns the requested project/dataset before returning data.

## Secrets and environment variables

| Variable | Purpose | Notes |
|---|---|---|
| `JWT_SECRET` | HMAC signing key | **Must** be set to a high-entropy random value in production |
| `JWT_EXPIRATION_MS` | Token lifetime | Default 3600000 (1 hour) |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | DB credentials | |
| `OLLAMA_BASE_URL` | Ollama inference endpoint | Internal network only |

`.env` at the repository root is for local development convenience and is `.gitignore`-d. Docker Compose reads it via `env_file`; CI uses GitHub Actions secrets.

## Input validation

- Flyway applies a strict schema (`V1__init.sql`) and the database enforces constraints (unique emails, foreign keys, NOT NULL).
- The backend validates DTO constraints (`@NotBlank`, `@Size`, `@Email`) via Bean Validation; invalid payloads return `400` via `GlobalExceptionHandler`.
- `CsvSupport` sanitizes column names and file paths; DuckDB queries use parameterized placeholders where possible.
- The frontend uses Angular reactive forms with built-in validators.

## File handling

- `FileStorageService` saves uploads to a local directory; the original filename is stored in the database (`Dataset.originalFilename`).
- Allowed types: CSV only; a max size guard (`MAX_DATASET_SIZE_BYTES`) is enforced in the frontend upload form.
- No file content is served directly as an attachment — the download endpoint streams the stored blob.

## Analytics-service isolation

The analytics-service runs as a separate process (Docker or local). It is called only by the backend via HTTP; the frontend never talks to it directly. This limits blast radius: a vulnerability in the DuckDB/pandas layer does not expose the primary database or user credentials.

## LLM (Ollama)

- Ollama runs locally and is not exposed externally.
- Chat messages are project-scoped and persisted; the context window is bounded by the `ProjectContextBuilder` (recent turns + project metadata only).
- No sensitive data (passwords, tokens) is sent to the LLM prompt.

## Hardening already applied

- `JwtService` refuses to start with known insecure secrets unless running in `dev`/`test` profile.
- `JwtAuthenticationFilter` rejects expired or malformed tokens before they reach a controller.
- Frontend clears stale sessions on startup and on every API call.
- CI runs a secret scan as part of the GitHub Actions workflow.
- `CORS` is configured to allow only the frontend origin in non-local profiles.

## What would change for production

1. **Token storage**: switch to `HttpOnly` cookies or a BFF pattern.
2. **Rate limiting**: add rate limits to `/auth/login` and the chat endpoint.
3. **Audit logging**: record who created/deleted which datasets and projects.
4. **CSRF protection**: required if switching to cookie-based sessions.
5. **Container image scanning** in CI (Trivy/Snyk).
6. **Secrets management**: use Vault / cloud KMS rather than environment variables.
7. **Observability**: distributed tracing (OpenTelemetry) across backend → analytics-service.
