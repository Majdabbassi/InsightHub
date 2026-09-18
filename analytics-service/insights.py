"""Rule-based trend detection over (temporal column, numeric column) pairs.

Deterministic, no AI involved: temporal grouping mirrors the Dashboard
line-chart rules (day/week/month/quarter chosen by date span), then a
degree-1 numpy.polyfit drives direction/strength classification and a
templated plain-English summary.
"""

from collections import Counter

import numpy as np
import pandas as pd

from analysis import DATETIME_FORMATS
from schemas import (
    SemanticRole,
    TrendDirection,
    TrendInsight,
    TrendInsightsResponse,
    TrendStrength,
)

NUMERIC_ROLES = (SemanticRole.NUMERIC_CONTINUOUS, SemanticRole.NUMERIC_DISCRETE)
MIN_PERIODS = 4
MAX_NUMERIC_COLUMNS = 5

OUTLIER_WEIGHT = 2
CORRELATION_WEIGHT = 1

STABLE_SLOPE_FRACTION = 0.05
CLEAR_R2 = 0.7
MODERATE_R2 = 0.3


def detect_trends(frame: pd.DataFrame, analysis) -> TrendInsightsResponse:
    """Detects trends for every eligible (temporal, numeric) pair."""
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
        return TrendInsightsResponse(trends=[], skippedReasons=skipped)

    chosen = _top_relevant_numeric(numeric_stats, analysis.correlations)

    trends: list[TrendInsight] = []
    for temporal in temporal_columns:
        for column in chosen:
            result = _trend_for_pair(frame, temporal, column)
            if isinstance(result, TrendInsight):
                trends.append(result)
            elif result is not None:
                skipped.append(result)

    return TrendInsightsResponse(trends=trends, skippedReasons=skipped)


def _top_relevant_numeric(numeric_stats: dict, correlations) -> list[str]:
    """Keeps only the most relevant numeric columns when there are too many."""
    correlation_counts = Counter()
    for corr in correlations:
        correlation_counts[corr.columnA] += CORRELATION_WEIGHT
        correlation_counts[corr.columnB] += CORRELATION_WEIGHT

    def relevance(name: str) -> int:
        score = correlation_counts.get(name, 0)
        stats = numeric_stats[name]
        if stats.outlierAnalysis and stats.outlierAnalysis.outlierCount > 0:
            score += OUTLIER_WEIGHT
        return score

    names = sorted(
        numeric_stats.keys(),
        key=lambda name: (-relevance(name), name),
    )
    return names[:MAX_NUMERIC_COLUMNS]


def _trend_for_pair(frame: pd.DataFrame, temporal: str, column: str):
    """Returns a TrendInsight, or a skip-reason string when data is insufficient."""
    dates = _parse_dates(frame[temporal])
    values = pd.to_numeric(frame[column], errors="coerce")
    mask = dates.notna() & values.notna()

    if not bool(mask.any()):
        return (
            f"Not enough usable rows for '{column}' over '{temporal}' "
            f"(minimum {MIN_PERIODS} periods required)"
        )

    usable_dates = dates[mask]
    usable_values = values[mask]

    period = _period_for_span(int((usable_dates.max() - usable_dates.min()).days) + 1)
    sums = _group_sums(usable_dates, usable_values, period)
    labels = [str(label) for label in sums.index.tolist()]
    points = [round(float(value), 2) for value in sums.tolist()]

    if len(points) < MIN_PERIODS:
        return (
            f"'{column}' grouped by {period} over '{temporal}' yields only "
            f"{len(points)} period(s); minimum is {MIN_PERIODS}"
        )

    x = np.arange(len(points), dtype=float)
    y = np.asarray(points, dtype=float)
    slope, intercept = np.polyfit(x, y, 1)
    fitted = slope * x + intercept
    ss_res = float(np.sum((y - fitted) ** 2))
    ss_tot = float(np.sum((y - y.mean()) ** 2))
    r_squared = round(min(1.0, max(0.0, 1.0 - ss_res / ss_tot)), 2) if ss_tot else 0.0

    mean_y = float(y.mean())
    threshold = STABLE_SLOPE_FRACTION * abs(mean_y)
    if mean_y == 0.0 or abs(slope) < threshold:
        direction = TrendDirection.STABLE
    elif slope > 0:
        direction = TrendDirection.INCREASING
    else:
        direction = TrendDirection.DECREASING

    if r_squared >= CLEAR_R2:
        strength = TrendStrength.CLEAR
    elif r_squared >= MODERATE_R2:
        strength = TrendStrength.MODERATE
    else:
        strength = TrendStrength.NOISY

    half = len(points) // 2
    first_avg = float(y[:half].mean())
    second_avg = float(y[len(points) - half :].mean())
    percentage_change = (
        round(((second_avg - first_avg) / first_avg) * 100, 1)
        if first_avg != 0.0
        else None
    )

    summary = _build_summary(
        column, direction, strength, percentage_change, labels[0], labels[-1]
    )

    return TrendInsight(
        column=column,
        temporalColumn=temporal,
        periodGrouping=period,
        periodCount=len(points),
        direction=direction,
        strength=strength,
        rSquared=r_squared,
        percentageChange=percentage_change,
        firstPeriodLabel=labels[0],
        lastPeriodLabel=labels[-1],
        points=points,
        pointLabels=labels,
        summary=summary,
    )


def _parse_dates(series: pd.Series) -> pd.Series:
    """Parses a temporal column using the same candidate formats as analysis."""
    if pd.api.types.is_datetime64_any_dtype(series):
        return pd.Series(pd.to_datetime(series), index=series.index)

    as_strings = series.astype(str)
    best_ratio = 0.0
    best_parsed = pd.Series(pd.NaT, index=series.index, dtype="datetime64[ns]")
    for fmt in DATETIME_FORMATS:
        try:
            parsed = pd.Series(
                pd.to_datetime(as_strings, errors="coerce", format=fmt),
                index=series.index,
            )
        except (TypeError, ValueError):
            continue
        ratio = float(parsed.notna().mean()) if len(parsed) else 0.0
        if ratio > best_ratio:
            best_ratio = ratio
            best_parsed = parsed
        if ratio == 1.0:
            break
    return best_parsed


def _period_for_span(span_days: int) -> str:
    """Temporal grouping rule: <=31 days by day; <=90 by week; <=730 by month;
    longer spans by quarter (mirrors ChartDataService.periodForSpan)."""
    if span_days <= 31:
        return "day"
    if span_days <= 90:
        return "week"
    if span_days <= 730:
        return "month"
    return "quarter"


def _group_sums(dates: pd.Series, values: pd.Series, period: str) -> pd.Series:
    """Buckets row values by period key (lexicographically sortable) and SUMs."""
    if period == "week":
        iso = dates.dt.isocalendar()
        keys = iso["year"].map("{:04d}".format) + "-W" + iso["week"].map("{:02d}".format)
    elif period == "month":
        keys = dates.dt.strftime("%Y-%m")
    elif period == "quarter":
        keys = (
            dates.dt.year.map("{:04d}".format)
            + "-Q"
            + (((dates.dt.month - 1) // 3) + 1).map(str)
        )
    else:
        keys = dates.dt.strftime("%Y-%m-%d")

    grouped = pd.DataFrame({"key": keys, "value": values})
    return grouped.groupby("key")["value"].sum().sort_index()


def _build_summary(
    column: str,
    direction: TrendDirection,
    strength: TrendStrength,
    percentage_change: float | None,
    first_label: str,
    last_label: str,
) -> str:
    """Templated plain-English summary, varied by direction and strength."""
    zero_baseline_note = (
        " Percentage change could not be computed because the baseline "
        "period average was zero."
    ) if percentage_change is None else ""

    if direction == TrendDirection.STABLE:
        return (
            f"{column} has remained relatively stable over time, with no "
            f"significant upward or downward trend."
        )
    if strength == TrendStrength.NOISY:
        return (
            f"{column} doesn't show a reliable trend — values fluctuate too "
            f"much to draw a clear conclusion."
        )

    word = "upward" if direction == TrendDirection.INCREASING else "downward"
    verb = "increasing" if direction == TrendDirection.INCREASING else "decreasing"
    amount = (
        abs(percentage_change)
        if direction == TrendDirection.DECREASING and percentage_change is not None
        else percentage_change
    )

    if strength == TrendStrength.CLEAR:
        if amount is None:
            base = (
                f"{column} shows a clear {word} trend from {first_label} "
                f"to {last_label}."
            )
        else:
            base = (
                f"{column} shows a clear {word} trend, {verb} {amount}% from "
                f"{first_label} to {last_label}."
            )
    else:
        if amount is None:
            base = (
                f"{column} shows a moderate {word} trend, though with some "
                f"fluctuation over the period."
            )
        else:
            base = (
                f"{column} shows a moderate {word} trend, though with some "
                f"fluctuation, changing {amount}% over the period."
            )
    return base + zero_baseline_note
