"""Rule-based period-level anomaly detection for the Insights engine.

Flags whole time periods whose aggregated metric deviates unusually from
the norm (z-score >= 2), distinct from row-level outlier detection.
Grouping, relevance capping and category-driver logic are shared with
Trend Detection and Period Comparison.
"""

import numpy as np
import pandas as pd

from insights import (
    NUMERIC_ROLES,
    _parse_dates,
    _period_for_span,
    _top_relevant_numeric,
)
from period_comparison import (
    TOP_DRIVERS,
    _bucket_keys,
    _fmt,
    _natural_period_end,
    _top_categorical,
)
from schemas import (
    AnomaliesResponse,
    AnomalyDriver,
    PeriodAnomaly,
    SemanticRole,
)

MIN_PERIODS_FOR_ANOMALY = 5
ANOMALY_Z_THRESHOLD = 2.0
SEVERE_Z_THRESHOLD = 3.0
MAX_ANOMALIES = 10


def detect_anomalies(frame, analysis) -> AnomaliesResponse:
    """Detects anomalous periods for every eligible (temporal, numeric) pair."""
    skipped: list[str] = []
    temporal_columns = [
        c.name for c in analysis.columns if c.semanticRole == SemanticRole.TEMPORAL
    ]
    numeric_stats = {
        c.name: c for c in analysis.columns if c.semanticRole in NUMERIC_ROLES
    }

    if not temporal_columns:
        skipped.append("No temporal column found")
    if not numeric_stats:
        skipped.append("No numeric columns to track")
    if not temporal_columns or not numeric_stats:
        return AnomaliesResponse(anomalies=[], skippedReasons=skipped)

    chosen = _top_relevant_numeric(numeric_stats, analysis.correlations)
    category_columns = _top_categorical(analysis.columns)

    anomalies: list[PeriodAnomaly] = []
    for temporal in temporal_columns:
        dates = _parse_dates(frame[temporal])
        valid_mask = dates.notna()
        usable = dates[valid_mask]
        if usable.empty:
            skipped.append("No parsable dates found in the temporal column")
            continue

        span_days = int((usable.max() - usable.min()).days) + 1
        period = _period_for_span(span_days)

        keys = _bucket_keys(usable, period)
        all_keys = pd.Series(None, index=dates.index, dtype="object")
        all_keys.loc[usable.index] = keys
        usable_max_date = usable.max().date()

        for column in chosen:
            result = _anomalies_for_pair(
                frame, valid_mask, all_keys, period,
                temporal, column, category_columns, usable_max_date,
            )
            if isinstance(result, str):
                skipped.append(result)
            else:
                anomalies.extend(result)

    anomalies.sort(key=lambda a: (-_severity_rank(a.severity), -abs(a.zScore)))
    return AnomaliesResponse(
        anomalies=anomalies[:MAX_ANOMALIES],
        skippedReasons=skipped,
    )


def _severity_rank(severity: str) -> int:
    return 1 if severity == "SEVERE" else 0


def _anomalies_for_pair(
    frame,
    valid_mask,
    all_keys,
    period: str,
    temporal: str,
    column: str,
    category_columns: list[str],
    usable_max_date,
):
    series_num = pd.to_numeric(frame[column], errors="coerce")
    combined = valid_mask & series_num.notna()

    grouped = pd.DataFrame({
        "key": all_keys[combined].astype(str),
        "value": series_num[combined],
    })
    sums = grouped.groupby("key")["value"].sum().sort_index()
    labels = [str(label) for label in sums.index.tolist()]
    values = np.asarray([float(v) for v in sums.tolist()], dtype=float)

    if len(values) < MIN_PERIODS_FOR_ANOMALY:
        return (
            f"'{column}' grouped by {period} over '{temporal}' yields only "
            f"{len(values)} period(s); minimum is {MIN_PERIODS_FOR_ANOMALY}"
        )

    mean = float(values.mean())
    std_dev = float(values.std())
    if std_dev == 0.0:
        return (
            f"'{column}' has zero variance across its {len(values)} {period} "
            f"periods; no anomalies can be detected"
        )

    z_scores = (values - mean) / std_dev
    flagged_indices = [
        i for i, z in enumerate(z_scores) if abs(z) >= ANOMALY_Z_THRESHOLD
    ]
    if not flagged_indices:
        return []

    incomplete_note = ""
    natural_end = _natural_period_end(usable_max_date, period)
    last_is_incomplete = usable_max_date < natural_end
    if last_is_incomplete:
        incomplete_note = (
            f"Note: the most recent {period} ({labels[-1]}) may be incomplete "
                f"(last data point {usable_max_date.isoformat()})."
        )

    category_sums = {}
    if category_columns:
        for cat in category_columns:
            category_sums[cat] = _category_period_matrix(
                frame, combined, column, cat, all_keys
            )

    found: list[PeriodAnomaly] = []
    for i in flagged_indices:
        actual_value = round(float(values[i]), 2)
        others = np.delete(values, i)
        typical_value = round(float(others.mean()), 2)
        z_score = round(float(z_scores[i]), 2)
        severity = "SEVERE" if abs(z_score) >= SEVERE_Z_THRESHOLD else "MODERATE"
        direction = "SPIKE" if z_scores[i] > 0 else "DROP"
        raw_multiplier = actual_value / typical_value if typical_value != 0 else None
        multiplier = (
            round(raw_multiplier, 2) if raw_multiplier is not None and raw_multiplier < 1
            else round(raw_multiplier, 1) if raw_multiplier is not None
            else None
        )

        drivers = _anomaly_drivers(labels[i], category_sums)
        summary = _build_summary(
            labels[i], column, direction, severity,
            actual_value, typical_value, multiplier, drivers,
        )
        if incomplete_note and i == len(labels) - 1:
            summary += f" {incomplete_note}"

        found.append(PeriodAnomaly(
            column=column,
            temporalColumn=temporal,
            periodGrouping=period,
            periodLabel=labels[i],
            actualValue=actual_value,
            typicalValue=typical_value,
            zScore=z_score,
            severity=severity,
            direction=direction,
            multiplier=multiplier,
            topDrivers=drivers,
            summary=summary,
        ))
    return found


def _category_period_matrix(
    frame, base_mask, numeric_column: str, category_column: str, all_keys
) -> dict[str, dict[str, float]]:
    """Per-period, per-category sums: {category: {periodKey: sum}}."""
    series_num = pd.to_numeric(frame[numeric_column], errors="coerce")
    cats = frame[category_column].astype(str)
    combined = base_mask & series_num.notna()

    table = pd.DataFrame({
        "key": all_keys[combined].astype(str),
        "cat": cats[combined],
        "value": series_num[combined],
    })
    pivot = table.groupby(["cat", "key"])["value"].sum().unstack(fill_value=0.0)
    return {
        str(cat): {str(k): float(v) for k, v in row.items()}
        for cat, row in pivot.iterrows()
    }


def _anomaly_drivers(
    anomalous_key: str,
    category_matrices: dict[str, dict[str, dict[str, float]]],
) -> list[AnomalyDriver]:
    candidates: list[tuple[float, AnomalyDriver]] = []
    for category_column, matrix in category_matrices.items():
        for value, periods in matrix.items():
            period_value = round(periods.get(anomalous_key, 0.0), 2)
            other_values = [v for k, v in periods.items() if k != anomalous_key]
            typical_value = (
                round(sum(other_values) / len(other_values), 2) if other_values else 0.0
            )
            deviation = abs(period_value - typical_value)
            if deviation > 0:
                candidates.append((deviation, AnomalyDriver(
                    categoryColumn=category_column,
                    categoryValue=value,
                    periodValue=period_value,
                    typicalValue=typical_value,
                )))

    candidates.sort(key=lambda pair: pair[0], reverse=True)
    return [driver for _, driver in candidates[:TOP_DRIVERS]]


def _build_summary(
    period_label: str,
    column: str,
    direction: str,
    severity: str,
    actual_value: float,
    typical_value: float,
    multiplier: float | None,
    drivers: list[AnomalyDriver],
) -> str:
    multiplier_text = f"{multiplier}x normal" if multiplier is not None else None
    driver_clause = ""
    if drivers:
        top = drivers[0]
        driver_clause = (
            f", driven primarily by {top.categoryColumn} "
            f"'{top.categoryValue}' ({_fmt(top.periodValue)} vs a typical "
            f"{_fmt(top.typicalValue)})"
        )

    if direction == "SPIKE":
        if severity == "SEVERE":
            base = (
                f"{period_label} had an unusual spike in {column} — "
                f"{_fmt(actual_value)} vs a typical {_fmt(typical_value)}"
            )
            if multiplier_text:
                base += f" ({multiplier_text})"
            return base + driver_clause + "."
        base = (
            f"{period_label} was somewhat higher than usual for {column} — "
            f"{_fmt(actual_value)} vs a typical {_fmt(typical_value)}"
        )
        if multiplier_text:
            base += f" ({multiplier_text})"
        return base + driver_clause + "."

    if severity == "SEVERE":
        base = (
            f"{period_label} had an unusual drop in {column} — "
            f"{_fmt(actual_value)} vs a typical {_fmt(typical_value)}"
        )
        if multiplier_text:
            base += f" (only {multiplier_text})"
        elif typical_value == 0:
            base += " (no comparable baseline)"
        return base + driver_clause + "."
    base = (
        f"{period_label} was somewhat lower than usual for {column} — "
        f"{_fmt(actual_value)} vs a typical {_fmt(typical_value)}"
    )
    if multiplier_text:
        base += f" (only {multiplier_text})"
    elif typical_value == 0:
        base += " (no comparable baseline)"
    return base + driver_clause + "."
