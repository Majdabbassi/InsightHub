"""Data-cleaning engine: role-aware suggestion generation and pandas cleaning.

Suggestions are driven by the analysis engine's semantic signals — column
roles, content-based type detection and IQR outlier analysis — instead of raw
pandas dtypes alone, so advice always matches what the Analysis view shows.
"""

import json
import re

import pandas as pd
from analysis import _column_stats, _outlier_analysis
from fastapi import HTTPException

from schemas import (
    CleaningAction,
    CleaningSuggestion,
    CleaningSuggestionsResponse,
    CleaningSummary,
    SuggestionType,
    SelectedAction,
    SemanticRole,
)

NUMERIC_ROLES = (
    SemanticRole.NUMERIC_CONTINUOUS,
    SemanticRole.NUMERIC_DISCRETE,
)

# ===== Part 1: distribution-aware fill choice =====
RELATIVE_SKEW_THRESHOLD = 0.15  # |mean-median|/median above this => skewed
ABS_SKEW_STD_FACTOR = 0.5       # median==0 fallback: skewed if gap > 0.5*stdDev

# ===== Part 2: outlier severity =====
# Above this share of extreme outliers the IQR bounds themselves become
# unreliable (they drift toward the contamination), so values are only flagged.
EXTREME_CAP_MAX_FRACTION = 0.10

# ===== Part 3: lightweight validation checks =====
EMAIL_NAME_PATTERN = re.compile(r"email", re.IGNORECASE)
NON_NEGATIVE_NAME_PATTERN = re.compile(r"price|quantity|amount|count|total|qty", re.IGNORECASE)
EMAIL_VALUE_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

# Identity-like columns are never auto-filled whatever their classified role:
# inventing emails/phone numbers/usernames fabricates personal data.
# ('username' is listed explicitly even though /user_?name/i already matches
# it, so the supported names stay obvious at a glance.)
IDENTITY_LIKE_NAME_PATTERN = re.compile(
    r"email|phone|username|user_?name", re.IGNORECASE
)

# Mirrors analysis.MIXED_TYPE_RATIO: the minimum share of off-type content an
# INCONSISTENT column can carry when no countable dominant type exists.
MIXED_TYPE_RATIO_ESTIMATE = 0.4


# ===== Suggestions =====


def build_suggestions(frame: pd.DataFrame) -> CleaningSuggestionsResponse:
    """Role-aware suggestions built on top of the analysis engine's signals."""
    suggestions: list[CleaningSuggestion] = []

    for name, series in frame.items():
        stats = _column_stats(str(name), series)
        suggestions.extend(_column_suggestions(stats, series))

    duplicate_count = int(frame.duplicated().sum())
    if duplicate_count > 0:
        suggestions.append(
            CleaningSuggestion(
                id="duplicates",
                type=SuggestionType.DUPLICATES,
                columnName=None,
                description=f"Found {duplicate_count} fully duplicated row(s).",
                suggestedAction=CleaningAction.DROP_DUPLICATES,
                alternativeActions=[],
                affectedRowCount=duplicate_count,
            )
        )

    return CleaningSuggestionsResponse(
        suggestions=suggestions, totalIssues=len(suggestions)
    )


def _column_suggestions(
    stats, series: pd.Series
) -> list[CleaningSuggestion]:
    """Part 4: every suggestion type is scoped by semantic role."""
    role = stats.semanticRole

    # Identifiers are excluded from cleaning entirely, exactly like charts do.
    if role == SemanticRole.IDENTIFIER:
        return []

    # Constant/empty columns get a single informational removal hint.
    if role in (SemanticRole.CONSTANT, SemanticRole.EMPTY):
        return [_low_variance_note(stats)]

    out: list[CleaningSuggestion] = []

    missing = _missing_value_suggestion(stats)
    if missing:
        out.append(missing)

    outlier = _outlier_suggestion(stats, series)
    if outlier:
        out.append(outlier)

    out.extend(_validation_suggestions(stats, series))

    mismatch = _type_mismatch_suggestion(stats, series)
    if mismatch:
        out.append(mismatch)

    return out


def _low_variance_note(stats) -> CleaningSuggestion:
    if stats.semanticRole == SemanticRole.EMPTY:
        description = (
            f"Column '{stats.name}' is entirely empty "
            f"({stats.missingPercentage}% missing) — consider removing it."
        )
        reasoning = (
            "Every value is null, so nothing can be filled or validated; "
            "the column carries no information."
        )
    else:
        top_value = stats.topValues[0].value if stats.topValues else "?"
        description = (
            f"Column '{stats.name}' contains only one distinct value "
            f"('{top_value}') — consider removing it."
        )
        reasoning = (
            "This column has no variation and may not be useful — consider "
            "removing it. No automated fix applies to constant data."
        )
    return CleaningSuggestion(
        id=f"lowvariance:{stats.name}",
        type=SuggestionType.LOW_VARIANCE,
        columnName=stats.name,
        description=description,
        suggestedAction=CleaningAction.NONE,
        alternativeActions=[],
        affectedRowCount=0,
        reasoning=reasoning,
    )


def _missing_value_suggestion(stats) -> CleaningSuggestion | None:
    """Part 1: distribution-aware fills; Part 4: informational for odd roles."""
    if stats.missingCount == 0:
        return None

    role = stats.semanticRole
    name = stats.name
    description = (
        f"Column '{name}' has {stats.missingCount} missing value(s) "
        f"({stats.missingPercentage}%)."
    )

    # Identity-like columns (email/phone/username) are never auto-filled,
    # whatever the column's classified role turns out to be; this takes
    # priority over every role-based default below.
    if IDENTITY_LIKE_NAME_PATTERN.search(name):
        return _informational_missing(
            stats,
            description,
            f"Column '{name}' looks like a personal identifier — automatic "
            f"fill isn't appropriate; review these rows manually.",
        )

    if role in NUMERIC_ROLES:
        default, reasoning = _numeric_fill_choice(stats)
        other = (
            CleaningAction.FILL_MEAN
            if default == CleaningAction.FILL_MEDIAN
            else CleaningAction.FILL_MEDIAN
        )
        return CleaningSuggestion(
            id=f"missing:{name}",
            type=SuggestionType.MISSING_VALUES,
            columnName=name,
            description=description,
            suggestedAction=default,
            alternativeActions=[
                other,
                CleaningAction.FILL_CUSTOM_VALUE,
                CleaningAction.DROP_ROWS,
            ],
            affectedRowCount=stats.missingCount,
            reasoning=reasoning,
        )

    if role == SemanticRole.FREE_TEXT:
        return _informational_missing(
            stats,
            description,
            "Free-text values cannot be meaningfully averaged or mode-filled; "
            "review these rows manually.",
        )
    if role == SemanticRole.INCONSISTENT:
        return _informational_missing(
            stats,
            description,
            "This column mixes incompatible value types; resolve the type "
            "conflict before considering any fill.",
        )

    # CATEGORICAL / BOOLEAN / TEMPORAL: the most frequent value.
    detail = (
        "the most common date"
        if role == SemanticRole.TEMPORAL
        else "the most common value"
    )
    return CleaningSuggestion(
        id=f"missing:{name}",
        type=SuggestionType.MISSING_VALUES,
        columnName=name,
        description=description,
        suggestedAction=CleaningAction.FILL_MODE,
        alternativeActions=[
            CleaningAction.FILL_CUSTOM_VALUE,
            CleaningAction.DROP_ROWS,
        ],
        affectedRowCount=stats.missingCount,
        reasoning=(
            f"Fill with {detail}: averages make no sense for "
            f"{role.value.lower()} columns."
        ),
    )


def _informational_missing(
    stats, description: str, reason: str
) -> CleaningSuggestion:
    return CleaningSuggestion(
        id=f"missing:{stats.name}",
        type=SuggestionType.MISSING_VALUES,
        columnName=stats.name,
        description=description,
        suggestedAction=CleaningAction.NONE,
        alternativeActions=[],
        affectedRowCount=stats.missingCount,
        reasoning=reason,
    )


def _numeric_fill_choice(stats) -> tuple[CleaningAction, str]:
    """Chooses FILL_MEDIAN vs FILL_MEAN from existing distribution signals."""
    mean, median, std = stats.mean, stats.median, stats.stdDev
    extremes = stats.outlierAnalysis.extremeCount if stats.outlierAnalysis else 0

    if mean is None or median is None:
        return (
            CleaningAction.FILL_MODE,
            "Numeric summary unavailable for this column; falling back to the "
            "most frequent value.",
        )

    gap = abs(mean - median)
    skewed = False
    skew_detail = None
    if median != 0:
        relative_skew = gap / abs(median)
        skewed = relative_skew > RELATIVE_SKEW_THRESHOLD
        skew_detail = (
            f"relative mean-median gap {relative_skew:.2f} exceeds "
            f"{RELATIVE_SKEW_THRESHOLD}"
        )
    elif std:
        # Spec fallback for median == 0: compare the raw gap against spread.
        skewed = gap > ABS_SKEW_STD_FACTOR * std
        skew_detail = (
            f"mean-median gap {gap:.2f} exceeds "
            f"{ABS_SKEW_STD_FACTOR} x stdDev ({std:.2f})"
        )

    if extremes > 0:
        direction = "up" if mean >= median else "down"
        parts = [
            f"Median suggested: mean ({mean:.2f}) is pulled {direction} by "
            f"{extremes} extreme outlier(s)"
        ]
        if skewed:
            parts.append(f"distribution is also skewed ({skew_detail})")
        parts.append(f"median ({median:.2f}) better represents typical values")
        return CleaningAction.FILL_MEDIAN, "; ".join(parts) + "."

    if skewed:
        return (
            CleaningAction.FILL_MEDIAN,
            f"Median suggested: distribution is skewed ({skew_detail}); "
            f"mean ({mean:.2f}) vs median ({median:.2f}).",
        )

    return (
        CleaningAction.FILL_MEAN,
        f"Mean suggested: distribution is roughly symmetric (mean {mean:.2f}, "
        f"median {median:.2f}), no extreme outliers detected.",
    )


def _outlier_suggestion(stats, series) -> CleaningSuggestion | None:
    """Part 2: outliers as a first-class, severity-scoped action."""
    oa = stats.outlierAnalysis
    if oa is None or oa.outlierCount <= 0:
        return None

    name = stats.name
    non_null = max(1, len(series) - stats.missingCount)
    extreme_fraction = oa.extremeCount / non_null

    if oa.extremeCount > 0 and extreme_fraction < EXTREME_CAP_MAX_FRACTION:
        default = CleaningAction.CAP_TO_BOUNDS
        reasoning = (
            f"{oa.extremeCount} extreme outlier(s) "
            f"({extreme_fraction:.0%} of values) sit far outside the IQR "
            f"bounds; clipping them preserves row count while removing the "
            f"distortion."
        )
    elif oa.extremeCount > 0:
        default = CleaningAction.FLAG_ONLY
        reasoning = (
            f"{extreme_fraction:.0%} of values register as extreme outliers — "
            f"at this share the IQR bounds themselves are unreliable, so no "
            f"automatic modification is suggested."
        )
    else:
        default = CleaningAction.FLAG_ONLY
        reasoning = (
            f"All {oa.outlierCount} outliers are mild; mild outliers are often "
            f"legitimate data, so they are only flagged for review."
        )

    alternatives = [CleaningAction.REMOVE_ROWS]
    if default != CleaningAction.FLAG_ONLY:
        alternatives.append(CleaningAction.FLAG_ONLY)
    if default != CleaningAction.CAP_TO_BOUNDS:
        alternatives.append(CleaningAction.CAP_TO_BOUNDS)

    return CleaningSuggestion(
        id=f"outliers:{name}",
        type=SuggestionType.OUTLIERS,
        columnName=name,
        description=(
            f"Column '{name}' has {oa.outlierCount} outlier(s) "
            f"({oa.extremeCount} extreme, {oa.mildCount} mild) outside the "
            f"normal range ({oa.lowerBound:.2f}-{oa.upperBound:.2f})."
        ),
        suggestedAction=default,
        alternativeActions=alternatives,
        affectedRowCount=oa.outlierCount,
        reasoning=reasoning,
    )


def _validation_suggestions(stats, series) -> list[CleaningSuggestion]:
    """Part 3: three cheap checks for obvious data-entry errors."""
    out: list[CleaningSuggestion] = []
    name = stats.name
    role = stats.semanticRole

    # (c) Values that failed content-based type parsing. Fired for ANY role
    # with invalidValueCount > 0. INCONSISTENT columns are the one deliberate
    # exception: they are typed 'string' with every value parseable as such
    # (analysis._detect_data_type only assigns 'string' - with invalid_count 0 -
    # when no type reaches the 95% threshold), so their invalidValueCount is
    # structurally always 0 and nothing is suppressed in practice. Their
    # mixed-type root cause is owned by DATA_TYPE_MISMATCH below instead.
    if stats.invalidValueCount > 0 and role != SemanticRole.INCONSISTENT:
        out.append(
            CleaningSuggestion(
                id=f"validation:type:{name}",
                type=SuggestionType.VALIDATION_ISSUE,
                columnName=name,
                description=(
                    f"Column '{name}' has {stats.invalidValueCount} value(s) "
                    f"that do not fit its detected type ('{stats.dataType}')."
                ),
                suggestedAction=CleaningAction.FLAG_ONLY,
                alternativeActions=[CleaningAction.DROP_INVALID_ROWS],
                affectedRowCount=stats.invalidValueCount,
                reasoning=(
                    "These values failed parsing as the column's dominant "
                    "type; review them before dropping anything."
                ),
            )
        )

    # (a) Email format check on email-named columns (or identifier-like
    # columns whose values contain '@' — those never reach here because
    # identifiers are excluded above).
    non_null = series.dropna()
    if EMAIL_NAME_PATTERN.search(name) and len(non_null):
        as_strings = non_null.astype(str)
        invalid_emails = int((~as_strings.str.match(EMAIL_VALUE_PATTERN)).sum())
        if invalid_emails > 0:
            out.append(
                CleaningSuggestion(
                    id=f"validation:email:{name}",
                    type=SuggestionType.VALIDATION_ISSUE,
                    columnName=name,
                    description=(
                        f"Column '{name}' has {invalid_emails} value(s) that "
                        f"do not look like valid email addresses."
                    ),
                    suggestedAction=CleaningAction.FLAG_ONLY,
                    alternativeActions=[CleaningAction.DROP_INVALID_ROWS],
                    affectedRowCount=invalid_emails,
                    reasoning=(
                        "Malformed addresses usually need human judgment "
                        "(typos vs placeholders), so nothing is dropped "
                        "automatically."
                    ),
                )
            )

    # (b) Negative values in columns whose names imply non-negative amounts.
    if role in NUMERIC_ROLES and NON_NEGATIVE_NAME_PATTERN.search(name):
        parsed = pd.to_numeric(series, errors="coerce")
        negatives = int((parsed < 0).sum())
        if negatives > 0:
            out.append(
                CleaningSuggestion(
                    id=f"validation:negative:{name}",
                    type=SuggestionType.VALIDATION_ISSUE,
                    columnName=name,
                    description=(
                        f"Column '{name}' contains {negatives} negative "
                        f"value(s), which should be impossible for a "
                        f"{name}-style field."
                    ),
                    suggestedAction=CleaningAction.FLAG_ONLY,
                    alternativeActions=[
                        CleaningAction.SET_TO_ZERO,
                        CleaningAction.DROP_INVALID_ROWS,
                    ],
                    affectedRowCount=negatives,
                    reasoning=(
                        "Negative quantities/prices are usually entry errors; "
                        "decide between zeroing them out or dropping the rows."
                    ),
                )
            )

    return out


def _type_mismatch_suggestion(stats, series) -> CleaningSuggestion | None:
    """DATA_TYPE_MISMATCH now fires only for semantically INCONSISTENT columns."""
    if stats.semanticRole != SemanticRole.INCONSISTENT:
        return None

    invalid_count, target_kind = _detect_type_mismatch(series.dropna())
    affected = invalid_count
    if affected <= 0:
        # Heuristic found nothing countable; estimate from the mixed content.
        affected = int(len(series.dropna()) * MIXED_TYPE_RATIO_ESTIMATE)

    return CleaningSuggestion(
        id=f"type:{stats.name}",
        type=SuggestionType.DATA_TYPE_MISMATCH,
        columnName=stats.name,
        description=(
            f"Column '{stats.name}' mixes incompatible value types "
            f"(e.g. numbers and text); {affected} value(s) resist the "
            f"dominant type."
        ),
        suggestedAction=CleaningAction.COERCE_TYPE,
        alternativeActions=[CleaningAction.DROP_INVALID_ROWS],
        affectedRowCount=int(affected),
    )


def _detect_type_mismatch(values: pd.Series) -> tuple[int, str | None]:
    """Counts values that resist the column's dominant parseable type."""
    if values.empty:
        return 0, None
    numeric = pd.to_numeric(values, errors="coerce")
    numeric_ok = int(numeric.notna().sum())

    datetime = pd.to_datetime(values, errors="coerce", format="mixed", dayfirst=False)
    datetime_ok = int(pd.Series(datetime).notna().sum())

    total = len(values)

    # Numeric wins ties: dates like "2024" are ambiguous anyway.
    if numeric_ok / total >= 0.5 and numeric_ok < total:
        return total - numeric_ok, "numeric"
    if datetime_ok / total >= 0.5 and datetime_ok < total and datetime_ok > 0:
        return total - datetime_ok, "datetime"
    return 0, None


# ===== Apply =====


def apply_cleaning(
    frame: pd.DataFrame, actions: list[SelectedAction]
) -> tuple[pd.DataFrame, CleaningSummary]:
    """Applies selected actions sequentially; returns (cleaned, summary).

    FLAG_ONLY/NONE selections are acknowledgements and modify nothing; an
    otherwise-empty selection returns the frame unchanged so callers still get
    a transparent zeroed summary instead of an error.
    """
    rows_before = int(len(frame))
    values_changed = 0

    for action in actions:
        frame, changed = _apply_action(frame, action)
        values_changed += changed

    rows_after = int(len(frame))
    if rows_after == 0 and any(
        a.actionType
        in (
            CleaningAction.DROP_ROWS,
            CleaningAction.DROP_INVALID_ROWS,
            CleaningAction.REMOVE_ROWS,
        )
        for a in actions
    ):
        raise HTTPException(
            status_code=400,
            detail="The selected actions would remove every row of the dataset.",
        )

    summary = CleaningSummary(
        rowsBefore=rows_before,
        rowsAfter=rows_after,
        rowsRemoved=rows_before - rows_after,
        valuesFilled=values_changed,
    )
    return frame, summary


def _apply_action(
    frame: pd.DataFrame, action: SelectedAction
) -> tuple[pd.DataFrame, int]:
    kind = action.actionType

    # Informational acknowledgements: explicitly no-ops.
    if kind in (CleaningAction.FLAG_ONLY, CleaningAction.NONE):
        return frame, 0

    if kind == CleaningAction.DROP_DUPLICATES:
        return frame.drop_duplicates(), 0

    name = action.columnName
    if not name:
        raise HTTPException(status_code=400, detail=f"Action {kind.value} requires a column.")
    if name not in frame.columns:
        raise HTTPException(
            status_code=400, detail=f"Unknown column '{name}' for action {kind.value}."
        )
    series = frame[name]

    if kind == CleaningAction.DROP_ROWS:
        return frame[series.notna()], 0

    if kind == CleaningAction.FILL_MEAN:
        return _fill_from_parsed(frame, name, lambda parsed: parsed.mean())
    if kind == CleaningAction.FILL_MEDIAN:
        return _fill_median(frame, name, series)
    if kind == CleaningAction.FILL_ZERO:
        return _fill(frame, name, 0)
    if kind == CleaningAction.FILL_MODE:
        modes = series.dropna().mode()
        if modes.empty:
            return frame, 0
        return _fill(frame, name, modes.iloc[0])
    if kind == CleaningAction.FILL_CUSTOM_VALUE:
        if action.customValue is None or str(action.customValue) == "":
            raise HTTPException(
                status_code=400,
                detail=f"A custom value must be provided to fill column '{name}'.",
            )
        return _fill(frame, name, action.customValue)

    if kind == CleaningAction.CAP_TO_BOUNDS:
        return _cap_to_bounds(frame, name, series)
    if kind == CleaningAction.REMOVE_ROWS:
        return _remove_outlier_rows(frame, name, series)
    if kind == CleaningAction.SET_TO_ZERO:
        return _set_negatives_to_zero(frame, name, series)

    if kind in (CleaningAction.COERCE_TYPE, CleaningAction.DROP_INVALID_ROWS):
        return _handle_type_fixup(frame, name, series, kind)

    raise HTTPException(status_code=400, detail=f"Unsupported action: {kind.value}")


def _parsed_numeric(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce")


def _fill_from_parsed(frame: pd.DataFrame, name: str, aggregate) -> tuple[pd.DataFrame, int]:
    """Mean/median over successfully parsed values, even if stored as strings."""
    parsed = _parsed_numeric(frame[name]).dropna()
    if parsed.empty:
        raise HTTPException(
            status_code=400,
            detail=f"Column '{name}' has no numeric values to compute a fill from.",
        )
    return _fill(frame, name, float(aggregate(parsed)))


def _fill_median(frame: pd.DataFrame, name: str, series: pd.Series) -> tuple[pd.DataFrame, int]:
    parsed = _parsed_numeric(series).dropna()
    if parsed.empty:
        raise HTTPException(
            status_code=400,
            detail=f"Column '{name}' has no numeric values to compute a fill from.",
        )
    value = float(parsed.median())
    # Part 1: discrete (integer-valued) columns must not receive fractional fills.
    integer_valued = bool(len(parsed) and ((parsed % 1) == 0).all())
    if integer_valued:
        value = float(round(value))
    return _fill(frame, name, value)


def _iqr_bounds(series: pd.Series):
    """Recomputed live so caps always match detection exactly."""
    analysis, note = _outlier_analysis(series)
    if analysis is None:
        detail = f" ({note})" if note else ""
        raise HTTPException(
            status_code=400,
            detail=f"Column '{series.name}' has no usable IQR bounds{detail}.",
        )
    return analysis.lowerBound, analysis.upperBound


def _cap_to_bounds(
    frame: pd.DataFrame, name: str, series: pd.Series
) -> tuple[pd.DataFrame, int]:
    lower, upper = _iqr_bounds(series)
    parsed = _parsed_numeric(series)
    numeric_mask = parsed.notna()

    clipped = parsed.clip(lower=lower, upper=upper)
    changed = int((((clipped != parsed)) & numeric_mask).sum())

    frame = frame.copy()
    frame.loc[numeric_mask, name] = clipped[numeric_mask]
    return frame, changed


def _remove_outlier_rows(
    frame: pd.DataFrame, name: str, series: pd.Series
) -> tuple[pd.DataFrame, int]:
    lower, upper = _iqr_bounds(series)
    parsed = _parsed_numeric(series)
    outlier_mask = parsed.notna() & ((parsed < lower) | (parsed > upper))
    return frame[~outlier_mask], 0


def _set_negatives_to_zero(
    frame: pd.DataFrame, name: str, series: pd.Series
) -> tuple[pd.DataFrame, int]:
    parsed = _parsed_numeric(series)
    negative_mask = parsed < 0
    if not bool(negative_mask.any()):
        raise HTTPException(
            status_code=400, detail=f"Column '{name}' has no negative values."
        )
    frame = frame.copy()
    frame.loc[negative_mask, name] = 0
    return frame, int(negative_mask.sum())


def _fill(frame: pd.DataFrame, name: str, value) -> tuple[pd.DataFrame, int]:
    missing_before = int(frame[name].isna().sum())
    if missing_before == 0:
        return frame, 0
    frame = frame.copy()
    frame[name] = frame[name].fillna(value)
    return frame, missing_before


def _handle_type_fixup(
    frame: pd.DataFrame, name: str, series: pd.Series, kind: CleaningAction
) -> tuple[pd.DataFrame, int]:
    invalid_mask, target = _invalid_value_mask(series)

    # Part 3 semantics: DROP_INVALID_ROWS also honours the email/negative
    # checks when the column name implies them.
    if kind == CleaningAction.DROP_INVALID_ROWS:
        if EMAIL_NAME_PATTERN.search(name):
            strings = series.dropna().astype(str)
            bad_email = strings.index[~strings.str.match(EMAIL_VALUE_PATTERN)]
            invalid_mask = invalid_mask | pd.Series(
                series.index.isin(bad_email), index=series.index
            )
        if NON_NEGATIVE_NAME_PATTERN.search(name):
            invalid_mask = invalid_mask | (_parsed_numeric(series) < 0)

    invalid_mask = invalid_mask.fillna(False)
    if not bool(invalid_mask.any()):
        raise HTTPException(
            status_code=400,
            detail=f"Column '{name}' has no values that conflict with its detected type.",
        )

    if kind == CleaningAction.DROP_INVALID_ROWS:
        return frame[~invalid_mask], 0

    # COERCE_TYPE: convert to dominant type; unparseable become NaN.
    frame = frame.copy()
    if target == "numeric":
        frame[name] = pd.to_numeric(series, errors="coerce")
    else:
        coerced = pd.to_datetime(series, errors="coerce", format="mixed")
        frame[name] = coerced.values
    return frame, 0


def _invalid_value_mask(series: pd.Series):
    """Boolean mask marking values that fail the column's dominant type."""
    values = series.dropna()

    numeric = pd.to_numeric(values, errors="coerce")
    numeric_invalid = numeric.isna()

    datetime = pd.to_datetime(values, errors="coerce", format="mixed")
    datetime_invalid = pd.Series(datetime).isna()
    datetime_invalid.index = values.index

    numeric_ratio = 1 - (int(numeric_invalid.sum()) / len(values)) if len(values) else 0
    datetime_ratio = 1 - (int(datetime_invalid.sum()) / len(values)) if len(values) else 0

    if numeric_ratio >= datetime_ratio and numeric_ratio >= 0.5:
        mask = numeric_invalid.reindex(series.index, fill_value=False)
        return mask.fillna(False), "numeric"
    if datetime_ratio >= 0.5:
        mask = datetime_invalid.reindex(series.index, fill_value=False)
        return mask.fillna(False), "datetime"

    empty = pd.Series(False, index=series.index)
    return empty, "numeric"


def actions_from_json(raw: str) -> list[SelectedAction]:
    """Parses the JSON string sent alongside the file on /clean/apply."""
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail="Invalid actions JSON.") from exc

    if not isinstance(payload, list):
        raise HTTPException(status_code=400, detail="Actions must be a JSON array.")

    try:
        return [SelectedAction(**item) for item in payload]
    except Exception as exc:
        raise HTTPException(
            status_code=400, detail=f"Invalid cleaning action payload: {exc}"
        ) from exc
