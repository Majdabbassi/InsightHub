"""Rule-based period comparison for the Insights engine.

Compares a recent time period against the one immediately before it,
reusing the Trend Detection temporal grouping rules, and explains the
change through per-category drivers where categorical columns exist.
"""

import calendar
from datetime import date, timedelta

import pandas as pd

from insights import (
    NUMERIC_ROLES,
    _parse_dates,
    _period_for_span,
    _top_relevant_numeric,
)
from schemas import (
    ColumnComparison,
    ComparisonDriver,
    PeriodComparisonResponse,
    SemanticRole,
)

MIN_ROWS_FOR_CONFIDENCE = 5
MAX_CATEGORY_COLUMNS = 2
TOP_DRIVERS = 3
DRIVER_DOMINANCE_RATIO = 0.3

VALID_PERIOD_TYPES = ("day", "week", "month", "quarter")


def compare_periods(
    frame,
    analysis,
    period_type: str | None = None,
    custom_current_start: str | None = None,
    custom_current_end: str | None = None,
    custom_previous_start: str | None = None,
    custom_previous_end: str | None = None,
) -> PeriodComparisonResponse:
    """Compares the two most recent complete periods (or custom ranges)."""
    skipped: list[str] = []
    temporal_columns = [
        c.name for c in analysis.columns if c.semanticRole == SemanticRole.TEMPORAL
    ]
    numeric_stats = {
        c.name: c for c in analysis.columns if c.semanticRole in NUMERIC_ROLES
    }

    if not temporal_columns or not numeric_stats:
        if not temporal_columns:
            skipped.append("No temporal column found")
        if not numeric_stats:
            skipped.append("No numeric columns to track")
        return PeriodComparisonResponse(
            periodType="custom", previousLabel="", currentLabel="",
            comparisons=[], skippedReasons=skipped,
        )

    temporal = temporal_columns[0]
    chosen = _top_relevant_numeric(numeric_stats, analysis.correlations)
    dates = _parse_dates(frame[temporal])
    valid_mask = dates.notna()

    custom_ranges = [
        custom_current_start, custom_current_end,
        custom_previous_start, custom_previous_end,
    ]
    any_custom = any(custom_ranges)
    if any_custom and not all(custom_ranges):
        raise ValueError(
            "All four custom dates (customCurrentStart, customCurrentEnd, "
            "customPreviousStart, customPreviousEnd) must be provided together."
        )

    extra_reasons: list[str] = []

    if any_custom:
        cur_start, cur_end, prev_start, prev_end = (
            pd.to_datetime(value).date() for value in custom_ranges
        )
        if cur_start > cur_end or prev_start > prev_end:
            raise ValueError("Custom range start dates must not be after their end dates.")
        resolved_type = "custom"
        previous_label = f"{prev_start.isoformat()} – {prev_end.isoformat()}"
        current_label = f"{cur_start.isoformat()} – {cur_end.isoformat()}"
        current_mask = (dates >= pd.Timestamp(cur_start)) & (dates <= pd.Timestamp(cur_end))
        previous_mask = (dates >= pd.Timestamp(prev_start)) & (dates <= pd.Timestamp(prev_end))
    else:
        resolved_type = (period_type or "").strip().lower()
        if resolved_type and resolved_type not in VALID_PERIOD_TYPES:
            raise ValueError(
                f"Unsupported periodType '{period_type}'. "
                f"Use one of {', '.join(VALID_PERIOD_TYPES)}."
            )

        usable = dates[valid_mask]
        if usable.empty:
            skipped.append("No parsable dates found in the temporal column")
            return _empty_response(resolved_type or "day", skipped)

        if not resolved_type:
            span_days = int((usable.max() - usable.min()).days) + 1
            resolved_type = _period_for_span(span_days)

        keys = _bucket_keys(usable, resolved_type)
        ordered = sorted(set(keys))
        if len(ordered) < 2:
            skipped.append(
                f"Only {len(ordered)} {resolved_type} period(s) available; need at least 2"
            )
            return _empty_response(resolved_type, skipped)

        current_key = ordered[-1]
        natural_end = _natural_period_end(usable.max().date(), resolved_type)
        if usable.max().date() < natural_end:
            ordered = ordered[:-1]
            extra_reasons.append(
                f"The most recent {resolved_type} ({current_key}) was excluded "
                f"because its last data point ({usable.max().date().isoformat()}) "
                f"does not reach the end of the period."
            )
            if len(ordered) < 2:
                skipped.append(
                    f"Not enough complete {resolved_type} periods after excluding "
                    f"the incomplete final one"
                )
                return _empty_response(resolved_type, skipped)

        current_key = ordered[-1]
        previous_key = ordered[-2]
        previous_label = str(previous_key)
        current_label = str(current_key)

        all_keys = pd.Series(None, index=dates.index, dtype="object")
        all_keys.loc[usable.index] = keys
        current_mask = all_keys == current_key
        previous_mask = all_keys == previous_key

    category_columns = _top_categorical(analysis.columns)
    primary = chosen[0]

    driver_lists: list[list[ComparisonDriver]] = []
    if category_columns:
        for cat in category_columns:
            driver_lists.append(_drivers_for(
                frame, valid_mask, primary, cat, current_mask, previous_mask,
            ))

    comparisons: list[ColumnComparison] = []
    for column in chosen:
        series_num = pd.to_numeric(frame[column], errors="coerce")
        cur_values = series_num[valid_mask & current_mask].dropna()
        prev_values = series_num[valid_mask & previous_mask].dropna()

        current_value = round(float(cur_values.sum()), 2) if len(cur_values) else 0.0
        previous_value = round(float(prev_values.sum()), 2) if len(prev_values) else 0.0
        delta = round(current_value - previous_value, 2)

        percent_change = (
            round(delta / previous_value * 100, 1) if previous_value != 0 else None
        )
        low_confidence = (
            len(cur_values) < MIN_ROWS_FOR_CONFIDENCE
            or len(prev_values) < MIN_ROWS_FOR_CONFIDENCE
        )

        drivers: list[ComparisonDriver] = []
        if column == primary:
            for cat_drivers in driver_lists:
                drivers.extend(cat_drivers)
            drivers.sort(key=lambda d: abs(d.delta), reverse=True)
            drivers = drivers[:TOP_DRIVERS]

        summary = _build_summary(
            column, previous_value, current_value, delta,
            percent_change, previous_label, current_label, drivers,
        )
        comparisons.append(ColumnComparison(
            column=column,
            previousValue=previous_value,
            currentValue=current_value,
            delta=delta,
            percentChange=percent_change,
            lowConfidence=low_confidence,
            topDrivers=drivers,
            summary=summary,
        ))

    return PeriodComparisonResponse(
        periodType=resolved_type,
        previousLabel=str(previous_label),
        currentLabel=str(current_label),
        comparisons=comparisons,
        skippedReasons=skipped + extra_reasons,
    )


def _empty_response(period_type: str, reasons: list[str]) -> PeriodComparisonResponse:
    return PeriodComparisonResponse(
        periodType=period_type, previousLabel="", currentLabel="",
        comparisons=[], skippedReasons=reasons,
    )


def _bucket_keys(dates: pd.Series, period: str) -> list[str]:
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
    return keys.tolist()


def _natural_period_end(max_date: date, period: str) -> date:
    if period == "day":
        return max_date
    if period == "week":
        return max_date + timedelta(days=(6 - max_date.weekday()))
    if period == "month":
        return max_date.replace(day=calendar.monthrange(max_date.year, max_date.month)[1])
    last_month = ((max_date.month - 1) // 3 + 1) * 3
    return max_date.replace(
        month=last_month, day=calendar.monthrange(max_date.year, last_month)[1]
    )


def _top_categorical(columns) -> list[str]:
    scored: list[tuple[int, str]] = []
    for col in columns:
        if col.semanticRole != SemanticRole.CATEGORICAL:
            continue
        score = 0
        if 2 <= col.uniqueCount <= 20:
            score += 2
        elif col.uniqueCount <= 50:
            score += 1
        else:
            continue
        if col.missingPercentage <= 5:
            score += 1
        scored.append((-score, col.name))
    return [name for _, name in sorted(scored)[:MAX_CATEGORY_COLUMNS]]


def _drivers_for(
    frame,
    valid_mask,
    numeric_column: str,
    category_column: str,
    current_mask,
    previous_mask,
) -> list[ComparisonDriver]:
    """Per-category sums for both periods; only nonzero deltas are kept."""
    series_num = pd.to_numeric(frame[numeric_column], errors="coerce")
    cats = frame[category_column].astype(str)
    base = valid_mask & series_num.notna() & cats.notna()

    def sums(mask) -> dict[str, float]:
        combined = base & mask.fillna(False)
        subset = series_num[combined].groupby(cats[combined]).sum()
        return {str(k): float(v) for k, v in subset.items()}

    prev = sums(previous_mask)
    cur = sums(current_mask)

    drivers = []
    for value in sorted(set(prev) | set(cur)):
        pv = round(prev.get(value, 0.0), 2)
        cv = round(cur.get(value, 0.0), 2)
        dv = round(cv - pv, 2)
        if dv != 0:
            drivers.append(ComparisonDriver(
                categoryColumn=category_column,
                categoryValue=value,
                previousValue=pv,
                currentValue=cv,
                delta=dv,
            ))
    return drivers


def _fmt(value: float) -> str:
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return text if text else "0"


def _build_summary(
    column: str,
    previous_value: float,
    current_value: float,
    delta: float,
    percent_change: float | None,
    previous_label: str,
    current_label: str,
    drivers: list[ComparisonDriver],
) -> str:
    if percent_change is None:
        return (
            f"{column} went from 0 to {_fmt(current_value)} in {current_label} "
            f"(no prior baseline to compare against)."
        )
    if percent_change == 0:
        return (
            f"{column} stayed flat at {_fmt(current_value)} between "
            f"{previous_label} and {current_label}."
        )

    verb = "increased" if delta > 0 else "decreased"
    base = (
        f"{column} {verb} {abs(percent_change)}% from {previous_label} to "
        f"{current_label} ({_fmt(previous_value)} -> {_fmt(current_value)})."
    )

    dominant_threshold = DRIVER_DOMINANCE_RATIO * abs(delta)
    if dominant_threshold > 0 and drivers and abs(drivers[0].delta) >= dominant_threshold:
        top = drivers[0]
        sign = "+" if top.delta > 0 else ""
        return base[:-1] + (
            f", driven primarily by {top.categoryColumn} '{top.categoryValue}' "
            f"({sign}{_fmt(top.delta)})."
        )
    return base


def _build_sibling_summary(
    column: str,
    baseline_label: str,
    other_label: str,
    previous_value: float,
    current_value: float,
    delta: float,
    percent_change: float | None,
    drivers: list[ComparisonDriver],
) -> str:
    """Sibling flavour of the comparison summary: dataset names, not periods."""
    if percent_change is None:
        return (
            f"{column} in {other_label} totals {_fmt(current_value)} — no "
            f"comparable non-zero baseline exists in {baseline_label}."
        )
    if percent_change == 0:
        return (
            f"{column} totals match between {baseline_label} and "
            f"{other_label} ({_fmt(current_value)})."
        )

    verb = "higher" if delta > 0 else "lower"
    base = (
        f"{column} in {other_label} is {abs(percent_change)}% {verb} than in "
        f"{baseline_label} ({_fmt(previous_value)} -> {_fmt(current_value)})."
    )

    dominant_threshold = DRIVER_DOMINANCE_RATIO * abs(delta)
    if dominant_threshold > 0 and drivers and abs(drivers[0].delta) >= dominant_threshold:
        top = drivers[0]
        sign = "+" if top.delta > 0 else ""
        return base[:-1] + (
            f", driven primarily by {top.categoryColumn} '{top.categoryValue}' "
            f"({sign}{_fmt(top.delta)})."
        )
    return base
