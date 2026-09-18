"""Project-level insights spanning two datasets.

Sibling comparison reuses the Period Comparison machinery (delta,
percent change, category drivers, low-confidence rules) but treats the
baseline dataset as "previous" and its schema twin as "current".

Relational insights reframe an existing foreign-key relationship as two
concrete findings: referential completeness and one predefined join
aggregate (child rows per parent, plus an optional per-parent metric).
"""

import pandas as pd

from insights import (
    NUMERIC_ROLES,
    _parse_dates,
    _top_relevant_numeric,
)
from period_comparison import (
    MAX_CATEGORY_COLUMNS,
    MIN_ROWS_FOR_CONFIDENCE,
    TOP_DRIVERS,
    _build_sibling_summary,
    _top_categorical,
)
from schemas import (
    ColumnComparison,
    ComparisonDriver,
    JoinAggregate,
    PartialAnalysis,
    ReferentialCompleteness,
    RelationalInsightResponse,
    SemanticRole,
    SiblingComparisonResponse,
)

MAX_SIBLING_NUMERIC_COLUMNS = 4

# A sibling pair this lopsided in row count is too different to compare.
ROW_RATIO_FLOOR = 0.1


def columns_by_lower_name(analysis: PartialAnalysis) -> dict:
    """Column stats keyed by lowercase column name."""
    return {c.name.lower(): c for c in analysis.columns}


def shared_columns(
    analysis_a: PartialAnalysis | None,
    analysis_b: PartialAnalysis | None,
    roles: tuple,
) -> list[tuple[str, str]]:
    """(nameInA, nameInB) pairs sharing a name and one of the given roles."""
    if analysis_a is None or analysis_b is None:
        return []
    by_b = {c.name.lower(): c.name for c in analysis_b.columns}
    pairs: list[tuple[str, str]] = []
    seen: set[str] = set()
    for col in analysis_a.columns:
        if col.semanticRole not in roles:
            continue
        key = col.name.lower()
        if key in seen or key not in by_b:
            continue
        seen.add(key)
        pairs.append((col.name, by_b[key]))
    return pairs


def resolve_order(
    frame_a: pd.DataFrame,
    frame_b: pd.DataFrame,
    analysis_a: PartialAnalysis | None,
    analysis_b: PartialAnalysis | None,
):
    """Orders the pair oldest-first when a shared temporal column exists.

    Returns (frameBaseline, frameCurrent, analysisA, analysisB) — the
    analyses follow their frames so labels stay attached correctly.
    """
    temporal_pairs = shared_columns(analysis_a, analysis_b, (SemanticRole.TEMPORAL,))
    if not temporal_pairs:
        return frame_a, frame_b, analysis_a, analysis_b
    try:
        dates_a = _parse_dates(frame_a[temporal_pairs[0][0]])
        dates_b = _parse_dates(frame_b[temporal_pairs[0][1]])
        mean_a = dates_a.mean()
        mean_b = dates_b.mean()
        if pd.notna(mean_a) and pd.notna(mean_b) and mean_b < mean_a:
            return frame_b, frame_a, analysis_b, analysis_a
    except Exception:
        pass
    return frame_a, frame_b, analysis_a, analysis_b


def choose_numeric_columns(
    analysis_baseline: PartialAnalysis | None,
    analysis_other: PartialAnalysis | None,
) -> list[str]:
    """Up to MAX_SIBLING_NUMERIC_COLUMNS shared numeric column names."""
    numeric_pairs = shared_columns(
        analysis_baseline, analysis_other, NUMERIC_ROLES
    )
    if not numeric_pairs:
        return []
    names_in_order = [pair[0] for pair in numeric_pairs]
    stats = {
        pair[0]: next(
            c for c in analysis_baseline.columns if c.name == pair[0]
        )
        for pair in numeric_pairs
    }
    chosen = _top_relevant_numeric(stats, analysis_baseline.correlations or [])
    # _top_relevant_numeric caps at its own maximum; keep our tighter cap and
    # preserve the shared-name ordering for any it dropped.
    extra = [n for n in names_in_order if n not in chosen]
    return (chosen + extra)[:MAX_SIBLING_NUMERIC_COLUMNS]


def sibling_low_confidence(
    rows_baseline: int, rows_other: int, overlap_percentage: float | None
) -> bool:
    """Too few rows on either side, wildly different sizes, or weak overlap."""
    if rows_baseline < MIN_ROWS_FOR_CONFIDENCE or rows_other < MIN_ROWS_FOR_CONFIDENCE:
        return True
    bigger = max(rows_baseline, rows_other)
    smaller = min(rows_baseline, rows_other)
    if bigger > 0 and smaller / bigger < ROW_RATIO_FLOOR:
        return True
    return overlap_percentage is not None and overlap_percentage < 50.0


def compare_siblings(
    frame_a: pd.DataFrame,
    frame_b: pd.DataFrame,
    label_a: str,
    label_b: str,
    analysis_a: PartialAnalysis | None,
    analysis_b: PartialAnalysis | None,
    overlap_percentage: float | None = None,
) -> SiblingComparisonResponse:
    """Compares two datasets with matching structure like two periods."""
    skipped: list[str] = []
    frame_baseline, frame_other, analysis_base, analysis_other = resolve_order(
        frame_a, frame_b, analysis_a, analysis_b
    )
    baseline_label, other_label = (
        (label_a, label_b) if frame_baseline is frame_a else (label_b, label_a)
    )

    chosen = choose_numeric_columns(analysis_base, analysis_other)
    if not chosen:
        return SiblingComparisonResponse(
            datasetALabel=baseline_label,
            datasetBLabel=other_label,
            comparisons=[],
            skippedReasons=["No shared numeric columns between the two datasets"],
        )

    category_pairs = [
        pair for pair in shared_columns(
            analysis_base, analysis_other, (SemanticRole.CATEGORICAL,)
        )
    ][:MAX_CATEGORY_COLUMNS]

    low_confidence_pair = sibling_low_confidence(
        len(frame_baseline), len(frame_other), overlap_percentage
    )

    comparisons: list[ColumnComparison] = []
    for index, column in enumerate(chosen):
        base_values = pd.to_numeric(frame_baseline[column], errors="coerce").dropna()
        other_values = pd.to_numeric(frame_other[column], errors="coerce").dropna()

        previous_value = round(float(base_values.sum()), 2) if len(base_values) else 0.0
        current_value = round(float(other_values.sum()), 2) if len(other_values) else 0.0
        delta = round(current_value - previous_value, 2)
        percent_change = (
            round(delta / previous_value * 100, 1) if previous_value != 0 else None
        )

        drivers: list[ComparisonDriver] = []
        if index == 0:
            drivers = top_drivers(
                frame_baseline, frame_other, column, category_pairs
            )

        summary = _build_sibling_summary(
            column, baseline_label, other_label, previous_value,
            current_value, delta, percent_change, drivers,
        )
        comparisons.append(ColumnComparison(
            column=column,
            previousValue=previous_value,
            currentValue=current_value,
            delta=delta,
            percentChange=percent_change,
            lowConfidence=low_confidence_pair,
            topDrivers=drivers,
            summary=summary,
        ))

    return SiblingComparisonResponse(
        datasetALabel=baseline_label,
        datasetBLabel=other_label,
        comparisons=comparisons,
        skippedReasons=skipped,
    )


def top_drivers(
    frame_baseline: pd.DataFrame,
    frame_other: pd.DataFrame,
    numeric_column: str,
    category_pairs: list[tuple[str, str]],
) -> list[ComparisonDriver]:
    """Per-category deltas across the pair; largest absolute movement first."""
    drivers: list[ComparisonDriver] = []
    for cat_base, cat_other in category_pairs:
        base_sums = _category_sums(frame_baseline, numeric_column, cat_base)
        other_sums = _category_sums(frame_other, numeric_column, cat_other)
        for value in sorted(set(base_sums) | set(other_sums)):
            pv = round(base_sums.get(value, 0.0), 2)
            cv = round(other_sums.get(value, 0.0), 2)
            dv = round(cv - pv, 2)
            if dv != 0:
                drivers.append(ComparisonDriver(
                    categoryColumn=cat_base,
                    categoryValue=value,
                    previousValue=pv,
                    currentValue=cv,
                    delta=dv,
                ))
    drivers.sort(key=lambda d: abs(d.delta), reverse=True)
    return drivers[:TOP_DRIVERS]


def _category_sums(
    frame: pd.DataFrame, numeric_column: str, category_column: str
) -> dict[str, float]:
    series_num = pd.to_numeric(frame[numeric_column], errors="coerce")
    cats = frame[category_column].astype(str)
    mask = series_num.notna() & cats.notna()
    grouped = series_num[mask].groupby(cats[mask]).sum()
    return {str(k): float(v) for k, v in grouped.items()}


# ===== Relational insights =====


def relational_insights(
    frame_parent: pd.DataFrame,
    frame_child: pd.DataFrame,
    parent_column: str,
    child_column: str,
    parent_label: str,
    child_label: str,
    analysis_child: PartialAnalysis | None,
    stored_match_percentage: float | None = None,
) -> RelationalInsightResponse:
    """Referential completeness + join aggregate for a confirmed FK link.

    The headline matchPercentage reuses what relationship detection already
    computed (passed in by Spring) so it stays stable across calls; only when
    no stored value exists is it recomputed here. Detection measures DISTINCT
    key values, while the mismatch counts are row-based — a repeated orphaned
    key counts once for detection but once per row here, which is the number
    users need for cleanup.
    """
    parent_keys = _normalised_keys(frame_parent[parent_column])
    child_keys = _normalised_keys(frame_child[child_column])

    valid = child_keys.dropna()
    matched_count = int(valid.isin(set(parent_keys)).sum())
    total_valid = int(len(valid))
    live_match_percentage = (
        round(matched_count / total_valid * 100, 1) if total_valid else 100.0
    )
    match_percentage = (
        stored_match_percentage if stored_match_percentage is not None
        else live_match_percentage
    )
    mismatch_count = total_valid - matched_count
    mismatch_percentage = (
        round(mismatch_count / total_valid * 100, 1) if total_valid else 0.0
    )

    completeness = ReferentialCompleteness(
        matchPercentage=float(match_percentage),
        mismatchCount=mismatch_count,
        mismatchPercentage=mismatch_percentage,
        summary=_completeness_summary(
            float(match_percentage), mismatch_count, mismatch_percentage,
            child_label, child_column, parent_label,
        ),
    )

    join_aggregate = _join_aggregate(
        frame_parent, frame_child, parent_column, child_column,
        parent_keys, child_keys, parent_label, analysis_child,
    )
    return RelationalInsightResponse(
        referentialCompleteness=completeness,
        joinAggregate=join_aggregate,
    )


def _completeness_summary(
    match_percentage: float,
    mismatch_count: int,
    mismatch_percentage: float,
    child_label: str,
    child_column: str,
    parent_label: str,
) -> str:
    summary = (
        f"{match_percentage}% of {child_label} rows reference a valid "
        f"{child_column} in {parent_label}."
    )
    if match_percentage < 100:
        summary += (
            f" {mismatch_count} row(s) ({mismatch_percentage}%) don't match "
            "any record and may be orphaned or entered incorrectly."
        )
    return summary


def _join_aggregate(
    frame_parent: pd.DataFrame,
    frame_child: pd.DataFrame,
    parent_column: str,
    child_column: str,
    parent_keys: pd.Series,
    child_keys: pd.Series,
    parent_label: str,
    analysis_child: PartialAnalysis | None,
):
    numeric_column = pick_child_metric_column(child_column, analysis_child)

    child_frame = frame_child.assign(__key=child_keys)
    parent_keys_present = set(parent_keys.dropna())
    joined = child_frame.loc[
        child_frame["__key"].notna() & child_frame["__key"].isin(parent_keys_present)
    ]

    counts_per_parent = joined.groupby("__key").size()
    avg_child_count = (
        round(float(counts_per_parent.mean()), 2) if len(counts_per_parent) else 0.0
    )

    if numeric_column is None:
        return JoinAggregate(
            avgChildCountPerParent=avg_child_count,
            childNumericColumn=None,
            avgNumericSumPerParent=None,
            summary=(
                f"{parent_label} has an average of {avg_child_count} "
                "matching entries each."
            ),
        )

    sums_per_parent = (
        pd.to_numeric(joined[numeric_column], errors="coerce")
        .groupby(joined["__key"])
        .sum()
        .dropna()
    )
    avg_sum = round(float(sums_per_parent.mean()), 2) if len(sums_per_parent) else None
    summary = f"{parent_label} has an average of {avg_child_count} matching entries each."
    if avg_sum is not None:
        summary += (
            f" On average, each {parent_label} entry totals "
            f"{_fmt(avg_sum)} in {numeric_column}."
        )
    return JoinAggregate(
        avgChildCountPerParent=avg_child_count,
        childNumericColumn=numeric_column,
        avgNumericSumPerParent=avg_sum,
        summary=summary,
    )


def pick_child_metric_column(
    key_column: str, analysis_child: PartialAnalysis | None
) -> str | None:
    """Most relevant non-key numeric column on the child side, if any."""
    if analysis_child is None:
        return None
    numeric_stats = {
        c.name: c for c in analysis_child.columns if c.semanticRole in NUMERIC_ROLES
    }
    candidates = [
        name for name in _top_relevant_numeric(
            numeric_stats, analysis_child.correlations or []
        )
        if name != key_column
    ]
    return candidates[0] if candidates else None


def _normalised_keys(series: pd.Series) -> pd.Series:
    keys = series.astype("string").str.strip()
    return keys.replace({"": pd.NA})


def _fmt(value: float) -> str:
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return text if text else "0"
