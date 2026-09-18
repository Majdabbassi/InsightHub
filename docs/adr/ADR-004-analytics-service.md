# ADR-004: Dedicated analytics-service (FastAPI + DuckDB)

Status: Accepted

## Context

Statistical work (column quality, cleaning suggestions, trend/outlier detection, cross-dataset joins) is compute-heavy and CPU/GPU-bound. Bundling it into the Spring request thread would starve CRUD latency; keeping it separate also lets the heavy Python stack scale independently.

## Decision

- A separate Python service (`analytics-service/`): FastAPI + DuckDB + pandas.
- DuckDB reads CSV files directly off disk (`CsvSupport`/`SqlTableNames`); no data is copied to the analytics service; only JSON results cross the wire.
- Spring calls it via `AnalyticsClient` with bounded retries and maps service failures to `AnalyticsServiceUnavailableException` (→ HTTP 503, friendly frontend message).
- The frontend never calls the analytics-service directly.

## Consequences

- Frontend/backend latency is protected from analytics work; the analytics service can be scaled out independently.
- Two deployment units plus Ollama → `docker-compose.yml` orchestrates all three.
- Failure modes are explicit: 503 surfaces in the UI as "analytics service temporarily unavailable".
- Requires keeping `CsvSupport` (column-name sanitization, file lookup) in sync between backend and analytics-service.