# ADR-004: Dedicated analytics-service (FastAPI + DuckDB)

Status: Accepted

## Context

Statistical work (column quality, cleaning suggestions, trend/outlier detection, cross-dataset joins) is compute-heavy and CPU/GPU-bound. Bundling it into the Spring request thread would starve CRUD latency; keeping it separate also lets the heavy Python stack scale independently.

## Decision

- A separate Python service (`analytics-service/`): FastAPI + DuckDB + pandas.
- The analytics service is stateless and has no access to the upload volume: for each call, Spring streams the stored CSV to it as a multipart upload (`AnalyticsClient`), and only JSON results come back. For AI queries, DuckDB registers that uploaded CSV in an in-memory database under a sanitized table name (`SqlTableNames` in Spring, `sanitize_name` in Python).
- Spring calls it via `AnalyticsClient` with bounded retries and maps service failures to `AnalyticsServiceUnavailableException` (→ HTTP 503, friendly frontend message).
- The frontend never calls the analytics-service directly.

## Consequences

- Frontend/backend latency is protected from analytics work; the analytics service can be scaled out independently.
- Two deployment units plus Ollama → `docker-compose.yml` orchestrates all three.
- Failure modes are explicit: 503 surfaces in the UI as "analytics service temporarily unavailable".
- Requires keeping the table-name sanitizing (`SqlTableNames` / `sanitize_name`) in sync between backend and analytics-service.
- Every analysis re-sends the file; fine for the CSV sizes this app accepts (20 MB upload limit), but a shared object store would avoid the copy for large files.