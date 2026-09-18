"""Safe read-only SQL execution over uploaded dataset files using DuckDB.

The Spring backend forwards an LLM-proposed SELECT query plus the CSV bytes
of the datasets it references. Everything here is defense in depth: the query
must start with SELECT/WITH, must not contain mutation keywords, must be a
single statement, may only reference registered (sanitized) table names, and
is always wrapped with a hard row limit and wall-clock timeout.
"""

import re
import threading
from typing import Any

import duckdb
import pandas as pd

ROW_LIMIT = 1000
TIMEOUT_SECONDS = 5.0

# Any occurrence of these words anywhere in the statement is rejected — even
# inside a CTE — because DuckDB treats them as statement starters.
BLOCKED_KEYWORDS = re.compile(
    r"(?is)\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|ATTACH|COPY|PRAGMA)\b"
)
START_PATTERN = re.compile(r"(?is)^\s*(SELECT|WITH)\b")
# Bare identifiers referenced in FROM/JOIN clauses, including comma-
# separated lists ("FROM a, b"). Quoted identifiers and subqueries never
# match, which is exactly the surface we scope.
TABLE_REFERENCE = re.compile(
    r"(?i)\b(?:FROM|JOIN)\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*)"
)
# CTE aliases ("WITH recent AS (...)", optionally with a column list as in
# "WITH inf(n) AS (...)" for recursive queries) are defined by the query.
CTE_ALIAS = re.compile(
    r"(?i)\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^)]*\))?\s*AS\s*\(")

_IDENTIFIER_CLEANUP = re.compile(r"[^A-Za-z0-9_]")


def sanitize_name(original: str) -> str:
    """Turns a dataset filename into a SQL-safe table name.

    Mirrors the Java-side sanitizer exactly so both services agree on the
    mapping: non-alphanumerics collapse to underscores, a leading digit gets
    a letter prefix.
    """
    stem = original.rsplit(".", 1)[0] if "." in original else original
    cleaned = _IDENTIFIER_CLEANUP.sub("_", stem).strip("_").lower()
    if not cleaned:
        cleaned = "table"
    if cleaned[0].isdigit():
        cleaned = "t_" + cleaned
    return cleaned


def build_table_map(filenames: list[str]) -> dict[str, str]:
    """Maps sanitized names to original filenames, de-duplicating collisions
    with numeric suffixes (_2, _3, ...) deterministically by input order."""
    tables: dict[str, str] = {}
    for filename in filenames:
        base = sanitize_name(filename)
        candidate = base
        suffix = 2
        while candidate in tables:
            candidate = f"{base}_{suffix}"
            suffix += 1
        tables[candidate] = filename
    return tables


class QueryValidationError(ValueError):
    """The proposed SQL violates one of the safety rules."""


def validate_sql(sql: str, allowed_tables: set[str]) -> str:
    """Returns the single-statement query ready for wrapping, or raises."""
    if sql is None or not sql.strip():
        raise QueryValidationError("Query is empty.")
    normalized = sql.strip()
    if normalized.endswith(";"):
        normalized = normalized[:-1].rstrip()
    if ";" in normalized:
        raise QueryValidationError(
            "Only a single statement can be executed.")
    if not START_PATTERN.match(normalized):
        raise QueryValidationError(
            "Only SELECT queries (optionally starting WITH) are allowed.")
    blocked = BLOCKED_KEYWORDS.search(normalized)
    if blocked:
        raise QueryValidationError(
            f"Keyword '{blocked.group(1).upper()}' is not allowed; queries "
            + "are strictly read-only.")

    referenced: set[str] = set()
    for match in TABLE_REFERENCE.finditer(normalized):
        for name in match.group(1).split(","):
            referenced.add(name.strip().lower())
    # Names the query defines itself (CTEs) are legitimate targets.
    cte_aliases = {alias.lower() for alias in CTE_ALIAS.findall(normalized)}
    unknown = sorted(referenced - cte_aliases - allowed_tables)
    if unknown:
        raise QueryValidationError(
            "Query references table(s) not in the provided datasets: "
            + ", ".join(unknown) + ". Allowed: " + ", ".join(sorted(allowed_tables)) + ".")
    return normalized


def _json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    return str(value)


def execute_query(sql: str, tables: dict[str, bytes]) -> dict[str, Any]:
    """Registers the CSVs, runs the validated query, returns a JSON-ready
    dict. Never raises for semantic issues — those come back as
    success=False with an error message."""
    try:
        validated = validate_sql(sql, set(tables.keys()))
        frames = {}
        for name, content in tables.items():
            # Type inference stays ON so numeric aggregates work; DuckDB
            # handles the mixed-type edge cases pandas cannot.
            frames[name] = pd.read_csv(pd.io.common.BytesIO(content))
        wrapped = (
            f"SELECT * FROM ({validated}) AS __limited LIMIT {ROW_LIMIT}"
        )

        connection = duckdb.connect(database=":memory:")
        result: dict[str, Any] = {}

        def run() -> None:
            try:
                for name, frame in frames.items():
                    connection.register(name, frame)
                cursor = connection.execute(wrapped)
                columns = [description[0] for description in cursor.description]
                rows = [
                    [_json_safe(cell) for cell in row]
                    for row in cursor.fetchall()
                ]
                result.update(
                    success=True, columns=columns,
                    rows=rows, rowCount=len(rows))
            except Exception as exc:  # noqa: BLE001 - reported verbatim
                result.update(success=False, error=f"SQL error: {exc}")

        worker = threading.Thread(target=run, daemon=True)
        worker.start()
        worker.join(timeout=TIMEOUT_SECONDS)
        if worker.is_alive():
            try:
                connection.interrupt()
                worker.join(timeout=1.0)
            except Exception:  # noqa: BLE001 - best-effort interrupt
                pass
            result.update(
                success=False,
                error=("Query exceeded the 5 second timeout and was "
                       + "cancelled."))

        try:
            connection.close()
        except Exception:  # noqa: BLE001 - already closing
            pass
        if "success" not in result:
            result.update(success=False, error="Query did not complete.")
        return result
    except QueryValidationError as exc:
        return {"success": False, "error": str(exc)}
    except Exception as exc:  # noqa: BLE001 - last-resort guard
        return {"success": False, "error": f"Unexpected error: {exc}"}
