"""Rule-based top/bottom performer ranking for the Insights engine.

Ranks categories by a numeric metric and surfaces the best/worst
performers, reusing the cross-column aggregation approach already used
by the Dashboard's cross-column charts.
"""

import pandas as pd

from insights import NUMERIC_ROLES, _top_relevant_numeric
from period_comparison import _fmt, _top_categorical
from schemas import (
    PerformerEntry,
    PerformersPair,
    PerformersResponse,
    SemanticRole,
)

MIN_DISTINCT_CATEGORIES = 3
SIDES_WHEN_ENOUGH = 3
DOMINANCE_THRESHOLD = 0.5


def detect_performers(frame, analysis) -> PerformersResponse:
    """Ranks categories by each eligible (categorical, numeric) pair."""
    skipped: list[str] = []

    categorical_stats = [
        c for c in analysis.columns if c.semanticRole == SemanticRole.CATEGORICAL
    ]
    if not categorical_stats:
        skipped.append("No categorical columns found")
    else:
        categorical_stats = [
            c for c in categorical_stats
            if c.uniqueCount >= MIN_DISTINCT_CATEGORIES
        ]
        if not categorical_stats:
            skipped.append(
                "No categorical column has at least "
                f"{MIN_DISTINCT_CATEGORIES} distinct values"
            )

    numeric_stats = {
        c.name: c for c in analysis.columns if c.semanticRole in NUMERIC_ROLES
    }
    if not numeric_stats:
        skipped.append("No numeric columns to rank")

    if skipped:
        return PerformersResponse(performers=[], skippedReasons=skipped)

    category_columns = _top_categorical(categorical_stats)[:2]
    numeric_columns = _top_relevant_numeric(numeric_stats, analysis.correlations)[:2]

    pairs: list[PerformersPair] = []
    for category_column in category_columns:
        for numeric_column in numeric_columns:
            pairs.append(_pair_result(frame, category_column, numeric_column))

    return PerformersResponse(performers=pairs, skippedReasons=skipped)


def _pair_result(
    frame, category_column: str, numeric_column: str
) -> PerformersPair:
    cats = frame[category_column].astype(str)
    values = pd.to_numeric(frame[numeric_column], errors="coerce")
    combined = values.notna()

    table = pd.DataFrame({
        "cat": cats[combined],
        "value": values[combined],
    })
    agg = (
        table.groupby("cat")["value"]
        .agg(["sum", "mean"])
        .sort_values("sum", ascending=False)
    )
    total_categories = len(agg)

    per_side = SIDES_WHEN_ENOUGH
    note = None
    if total_categories < 2 * SIDES_WHEN_ENOUGH:
        per_side = max(1, total_categories // 2)
        note = (
            f"Only {total_categories} categories - showing {per_side} on "
            f"each side so top and bottom don't overlap."
        )

    top_frame = agg.head(per_side)
    bottom_frame = agg.tail(per_side).iloc[::-1]

    top_entries = [
        PerformerEntry(
            category=str(name),
            sumValue=round(float(row["sum"]), 2),
            avgValue=round(float(row["mean"]), 2),
            rank=i + 1,
        )
        for i, (name, row) in enumerate(top_frame.iterrows())
    ]
    bottom_entries = [
        PerformerEntry(
            category=str(name),
            sumValue=round(float(row["sum"]), 2),
            avgValue=round(float(row["mean"]), 2),
            rank=int(agg.index.get_loc(name)) + 1,
        )
        for name, row in bottom_frame.iterrows()
    ]

    top_name = str(agg.index[0])
    bottom_name = str(agg.index[-1])
    top_value = round(float(agg["sum"].iloc[0]), 2)
    bottom_value = round(float(agg["sum"].iloc[-1]), 2)

    total_sum = float(agg["sum"].sum())
    share = top_value / total_sum if total_sum > 0 else 0.0
    is_dominant = share >= DOMINANCE_THRESHOLD
    dominant_percentage = round(share * 100, 1) if is_dominant else None

    gap = None if bottom_value == 0 else round(top_value / bottom_value, 1)

    summary = _build_summary(
        top_name, bottom_name, numeric_column,
        top_value, bottom_value, gap, is_dominant, dominant_percentage,
    )

    return PerformersPair(
        categoricalColumn=category_column,
        numericColumn=numeric_column,
        aggregation="SUM",
        topPerformers=top_entries,
        bottomPerformers=bottom_entries,
        gap=gap,
        isDominant=is_dominant,
        dominantPercentage=dominant_percentage,
        note=note,
        summary=summary,
    )


def _build_summary(
    top_name: str,
    bottom_name: str,
    numeric_column: str,
    top_value: float,
    bottom_value: float,
    gap: float | None,
    is_dominant: bool,
    dominant_percentage: float | None,
) -> str:
    if gap is None:
        summary = (
            f"{top_name} leads by {numeric_column}, but {bottom_name} "
            f"has no recorded value for comparison."
        )
    else:
        summary = (
            f"{top_name} is the top performer by SUM of {numeric_column} "
            f"({_fmt(top_value)}), {gap}x higher than the lowest, "
            f"{bottom_name} ({_fmt(bottom_value)})."
        )

    if is_dominant and dominant_percentage is not None:
        summary += (
            f" {top_name} alone accounts for {dominant_percentage}% "
            f"of total {numeric_column}."
        )
    return summary
