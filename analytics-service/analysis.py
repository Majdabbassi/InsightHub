"""Pandas-powered CSV analysis engine with semantic column classification.

Each column receives two independent labels:
- ``dataType``: content-based storage type (boolean/integer/float/datetime/string),
  inferred by parsing the values themselves rather than trusting pandas dtypes.
- ``semanticRole``: what the column *means* (identifier, measure, category, ...),
  derived from cardinality, value patterns and name heuristics.
"""

import io
import re
from collections import Counter
from dataclasses import dataclass

import numpy as np
import pandas as pd
from fastapi import HTTPException

from schemas import (
    AnalysisResponse,
    ColumnCorrelation,
    ColumnStats,
    CorrelationDirection,
    CorrelationStrength,
    DataQuality,
    DataQualityBreakdown,
    DataQualityGrade,
    OutlierAnalysis,
    OutlierSample,
    SemanticRole,
    Summary,
    TopValue,
)

CANDIDATE_DELIMITERS = [",", ";", "\t", "|"]
SNIFF_SAMPLE_BYTES = 8192

# ===== Type detection =====
TYPE_PARSE_THRESHOLD = 0.95  # >=95% parse success => typed column
DATETIME_FORMATS = ["ISO8601", "%Y-%m-%d", "%m/%d/%Y", "%d/%m/%Y"]
BOOLEAN_VALUE_PAIRS = [{"true", "false"}, {"yes", "no"}, {"y", "n"}, {"0", "1"}]

# ===== Semantic roles =====
IDENTIFIER_CARDINALITY_RATIO = 0.9  # uniqueCount/nonNullCount >= 0.9 => identifier
FREE_TEXT_MIN_AVG_LENGTH = 30  # avg string length above this => free text
FREE_TEXT_CARDINALITY_RANGE = (0.3, 0.9)
CATEGORICAL_MAX_UNIQUE = 20
CATEGORICAL_CARDINALITY_RATIO = 0.05
MIXED_TYPE_RATIO = 0.4  # significant share of off-type parseable values => inconsistent

# ===== Outliers (IQR method, numeric roles only) =====
OUTLIER_MILD_FACTOR = 1.5  # values beyond Q1/Q3 +/- 1.5*IQR are outliers
OUTLIER_EXTREME_FACTOR = 3.0  # ... and beyond 3*IQR are extreme
OUTLIER_SAMPLE_LIMIT = 5  # sample outlying values reported per column
LOW_CONFIDENCE_MIN_VALUES = 10  # quartiles are unstable below this

# ===== Correlations (Pearson, numeric roles only) =====
CORRELATION_MIN_ABS = 0.5  # weaker pairs are noise and not reported
CORRELATION_STRONG_ABS = 0.7  # >= 0.7 => STRONG
CORRELATION_VERY_STRONG_ABS = 0.9  # >= 0.9 => VERY_STRONG
CORRELATION_LOW_CONFIDENCE_ROWS = 30  # pairwise stats unstable below this

# ===== Data quality score (aggregates already-computed signals only) =====
QUALITY_WEIGHT_COMPLETENESS = 0.35
QUALITY_WEIGHT_UNIQUENESS = 0.20
QUALITY_WEIGHT_CONSISTENCY = 0.25
QUALITY_WEIGHT_VALIDITY = 0.20
CONSISTENCY_INCONSISTENT_COLUMN_PENALTY = 0.1  # per column flagged INCONSISTENT

# Matches names that ARE "id"/"uuid"/"guid" or end in "_id", "_code", "_key".
ID_NAME_PATTERN = re.compile(r"^(?:id|uuid|guid|(?:.+_)?(?:id|code|key))$", re.IGNORECASE)


@dataclass
class Detection:
    """Outcome of content-based type detection for a single column."""

    data_type: str  # boolean | integer | float | datetime | string
    invalid_count: int  # non-null values that do not fit data_type
    numeric_ratio: float  # share of non-null values parsing as numbers
    datetime_ratio: float  # share of non-null values parsing as dates (best format)
    boolean_like: bool  # exactly 2 distinct values matching a known boolean pair
    integer_valued: bool = False  # numeric column whose values are all whole numbers


def read_csv(content: bytes) -> pd.DataFrame:
    """Decodes and parses CSV content, tolerating common encoding/delimiter issues."""
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        try:
            text = content.decode("latin-1")
        except Exception as exc:
            raise HTTPException(
                status_code=422, detail="Could not decode the file (unsupported encoding)."
            ) from exc

    delimiter = _sniff_delimiter(text)

    try:
        frame = pd.read_csv(
            io.StringIO(text),
            sep=delimiter,
            engine="python",
            on_bad_lines="skip",
        )
    except pd.errors.EmptyDataError as exc:
        raise HTTPException(status_code=422, detail="The CSV file contains no data.") from exc
    except Exception as exc:
        raise HTTPException(
            status_code=422, detail=f"Could not parse the file as CSV: {exc}"
        ) from exc

    if frame.empty:
        raise HTTPException(status_code=422, detail="The CSV file contains no data rows.")

    return frame


def analyze_dataframe(frame: pd.DataFrame) -> AnalysisResponse:
    """Computes summary + per-column statistics for the given dataframe."""
    row_count = int(len(frame))
    duplicate_row_count = int(frame.duplicated().sum())
    total_missing = int(frame.isna().sum().sum())

    columns: list[ColumnStats] = []
    numeric_column_names: list[str] = []
    for name, series in frame.items():
        stats = _column_stats(name, series)
        columns.append(stats)
        if stats.semanticRole in (
            SemanticRole.NUMERIC_DISCRETE,
            SemanticRole.NUMERIC_CONTINUOUS,
        ):
            numeric_column_names.append(stats.name)

    return AnalysisResponse(
        rowCount=row_count,
        columnCount=int(len(frame.columns)),
        duplicateRowCount=duplicate_row_count,
        columns=columns,
        summary=Summary(
            totalMissingValues=total_missing,
            totalDuplicateRows=duplicate_row_count,
        ),
        correlations=_detect_correlations(frame, numeric_column_names, row_count),
        dataQuality=_compute_data_quality(columns, row_count, duplicate_row_count),
    )


# ===== Per-column statistics =====


def _column_stats(name: str, series: pd.Series) -> ColumnStats:
    detection = _detect_data_type(series)

    missing_count = int(series.isna().sum())
    missing_percentage = round((missing_count / len(series) * 100), 2) if len(series) else 0.0
    unique_count = int(series.nunique(dropna=True))
    row_count = int(len(series))

    role, confidence, reasoning = _classify_role(
        str(name), series, detection, row_count, missing_count
    )

    stats = dict(
        name=str(name),
        dataType=detection.data_type,
        semanticRole=role,
        confidence=confidence,
        reasoning=reasoning,
        missingCount=missing_count,
        missingPercentage=missing_percentage,
        uniqueCount=unique_count,
        invalidValueCount=detection.invalid_count,
    )

    if role in (SemanticRole.NUMERIC_DISCRETE, SemanticRole.NUMERIC_CONTINUOUS):
        stats.update(_numeric_summary(series))
        outlier_analysis, outlier_note = _outlier_analysis(series)
        if outlier_note:
            # Zero-variance columns: detection is skipped, explain why instead.
            stats["reasoning"] = f"{reasoning} {outlier_note}"
        elif outlier_analysis is not None:
            stats["outlierAnalysis"] = outlier_analysis
    if role in (
        SemanticRole.BOOLEAN,
        SemanticRole.CATEGORICAL,
        SemanticRole.CONSTANT,
        SemanticRole.FREE_TEXT,
        SemanticRole.INCONSISTENT,
    ):
        top_values = _top_values(series)
        if top_values:
            stats["topValues"] = top_values

    return ColumnStats(**stats)


def _numeric_summary(series: pd.Series) -> dict:
    """min/max/mean/median/stdDev over successfully parsed numeric values."""
    numeric = pd.to_numeric(series.dropna(), errors="coerce").dropna()
    if numeric.empty:
        return {}
    return dict(
        min=_num(numeric.min()),
        max=_num(numeric.max()),
        mean=_num(numeric.mean()),
        median=_num(numeric.median()),
        stdDev=_num(numeric.std(ddof=1)) if len(numeric) > 1 else None,
    )


def _top_values(series: pd.Series, limit: int = 5) -> list[TopValue]:
    counts = series.dropna().astype(str).value_counts()
    top = counts.head(limit)
    return [TopValue(value=value, count=int(count)) for value, count in top.items()]


# ===== Outlier detection (Part: IQR method) =====


def _outlier_analysis(series: pd.Series) -> tuple[OutlierAnalysis | None, str | None]:
    """IQR-based outlier statistics; exactly one of (analysis, note) is returned.

    The note explains why detection was skipped (zero-variance column).
    """
    parsed = pd.to_numeric(series, errors="coerce")
    numeric = parsed.dropna()
    total_non_null = int(len(numeric))
    if total_non_null == 0:
        return None, None

    q1 = float(numeric.quantile(0.25))
    q3 = float(numeric.quantile(0.75))
    iqr = q3 - q1

    if iqr == 0:
        return None, (
            f"Insufficient variance for outlier detection "
            f"(Q1 and Q3 both equal {_num(q1)})."
        )

    lower_bound = q1 - OUTLIER_MILD_FACTOR * iqr
    upper_bound = q3 + OUTLIER_MILD_FACTOR * iqr
    extreme_lower = q1 - OUTLIER_EXTREME_FACTOR * iqr
    extreme_upper = q3 + OUTLIER_EXTREME_FACTOR * iqr

    outlier_mask = parsed.notna() & ((parsed < lower_bound) | (parsed > upper_bound))
    extreme_mask = outlier_mask & ((parsed < extreme_lower) | (parsed > extreme_upper))

    outlier_count = int(outlier_mask.sum())
    extreme_count = int(extreme_mask.sum())

    positions = np.flatnonzero(outlier_mask.to_numpy())
    samples: list[OutlierSample] = []
    if len(positions):
        values_at = parsed.to_numpy(dtype=float)[positions]
        distances = np.maximum(values_at - upper_bound, lower_bound - values_at)
        furthest_first = np.argsort(distances)[::-1][:OUTLIER_SAMPLE_LIMIT]
        samples = [
            OutlierSample(value=_num(float(values_at[i])), rowIndex=int(positions[i]))
            for i in furthest_first
        ]

    analysis = OutlierAnalysis(
        outlierCount=outlier_count,
        mildCount=outlier_count - extreme_count,
        extremeCount=extreme_count,
        outlierPercentage=round(outlier_count / total_non_null * 100, 2),
        lowerBound=_num(lower_bound),
        upperBound=_num(upper_bound),
        outlierSamples=samples,
        lowConfidence=total_non_null < LOW_CONFIDENCE_MIN_VALUES,
    )
    return analysis, None


# ===== Correlation detection (Pearson, numeric roles only) =====


def _detect_correlations(
    frame: pd.DataFrame, column_names: list[str], row_count: int
) -> list[ColumnCorrelation]:
    """Pairwise Pearson correlations between qualifying numeric columns.

    Columns are coerced to numeric first (content-detected numeric columns may
    be stored as strings); pandas .corr() then handles pairwise-complete
    observations. Pairs below the absolute threshold are not reported.
    """
    if len(column_names) < 2:
        return []

    coerced = pd.DataFrame(
        {name: pd.to_numeric(frame[name], errors="coerce") for name in column_names}
    )
    matrix = coerced.corr(method="pearson")

    correlations: list[ColumnCorrelation] = []
    for i, name_a in enumerate(column_names):
        for name_b in column_names[i + 1:]:
            value = matrix.loc[name_a, name_b]
            # NaN (no overlapping data / zero variance) or weak => skip.
            if pd.isna(value) or abs(value) < CORRELATION_MIN_ABS:
                continue

            sample_size = int(coerced[[name_a, name_b]].dropna().shape[0])
            correlations.append(
                ColumnCorrelation(
                    columnA=name_a,
                    columnB=name_b,
                    correlation=round(float(value), 2),
                    strength=_correlation_strength(abs(value)),
                    direction=(
                        CorrelationDirection.POSITIVE
                        if value > 0
                        else CorrelationDirection.NEGATIVE
                    ),
                    sampleSize=sample_size,
                    lowConfidence=row_count < CORRELATION_LOW_CONFIDENCE_ROWS,
                )
            )

    return correlations


def _correlation_strength(abs_value: float) -> CorrelationStrength:
    if abs_value >= CORRELATION_VERY_STRONG_ABS:
        return CorrelationStrength.VERY_STRONG
    if abs_value >= CORRELATION_STRONG_ABS:
        return CorrelationStrength.STRONG
    return CorrelationStrength.MODERATE


# ===== Data quality score =====


def _compute_data_quality(
    columns: list[ColumnStats], row_count: int, duplicate_row_count: int
) -> DataQuality:
    """Aggregates already-computed signals into a dataset-level quality score."""
    total_cells = row_count * len(columns)

    # Completeness: share of cells that hold values.
    if total_cells == 0:
        completeness = 1.0
    else:
        total_missing = sum(column.missingCount for column in columns)
        completeness = 1 - total_missing / total_cells

    # Uniqueness: share of rows that are not exact duplicates of another row.
    uniqueness = 1.0 if row_count == 0 else 1 - duplicate_row_count / row_count

    # Consistency: parse failures plus whole columns flagged INCONSISTENT.
    total_invalid = sum(column.invalidValueCount for column in columns)
    inconsistent_column_count = sum(
        1 for column in columns if column.semanticRole == SemanticRole.INCONSISTENT
    )
    consistency = (
        1.0
        if total_cells == 0
        else 1
        - total_invalid / total_cells
        - CONSISTENCY_INCONSISTENT_COLUMN_PENALTY * inconsistent_column_count
    )
    consistency = min(1.0, max(0.0, consistency))

    # Validity: extreme outliers among numeric cells only; mild outliers are
    # often legitimate data, not quality problems, so they are not penalized.
    numeric_column_count = sum(
        1
        for column in columns
        if column.semanticRole
        in (SemanticRole.NUMERIC_CONTINUOUS, SemanticRole.NUMERIC_DISCRETE)
    )
    total_numeric_cells = row_count * numeric_column_count
    if total_numeric_cells == 0:
        validity = 1.0
    else:
        total_extreme = sum(
            column.outlierAnalysis.extremeCount
            for column in columns
            if column.outlierAnalysis is not None
        )
        validity = 1 - total_extreme / total_numeric_cells

    overall_score = round(
        (
            completeness * QUALITY_WEIGHT_COMPLETENESS
            + uniqueness * QUALITY_WEIGHT_UNIQUENESS
            + consistency * QUALITY_WEIGHT_CONSISTENCY
            + validity * QUALITY_WEIGHT_VALIDITY
        )
        * 100
    )

    return DataQuality(
        overallScore=overall_score,
        grade=_quality_grade(overall_score),
        breakdown=DataQualityBreakdown(
            completeness=round(completeness, 2),
            uniqueness=round(uniqueness, 2),
            consistency=round(consistency, 2),
            validity=round(validity, 2),
        ),
    )


def _quality_grade(score: int) -> DataQualityGrade:
    if score >= 90:
        return DataQualityGrade.A
    if score >= 75:
        return DataQualityGrade.B
    if score >= 60:
        return DataQualityGrade.C
    if score >= 40:
        return DataQualityGrade.D
    return DataQualityGrade.F


# ===== Content-based type detection (Part 1) =====


def _detect_data_type(series: pd.Series) -> Detection:
    """Infers the storage type from the actual values, tracking unparseable ones."""
    if pd.api.types.is_bool_dtype(series):
        return Detection("boolean", 0, 0.0, 0.0, True)

    non_null = series.dropna()
    total = int(len(non_null))
    if total == 0:
        return Detection("string", 0, 0.0, 0.0, False)

    if pd.api.types.is_datetime64_any_dtype(series):
        return Detection("datetime", 0, 0.0, 1.0, False, integer_valued=False)

    boolean_like = _is_boolean_like(non_null)

    numeric_ratio, numeric_parsed = _numeric_parse(non_null)
    if numeric_ratio >= TYPE_PARSE_THRESHOLD:
        invalid_count = total - int(numeric_parsed.notna().sum())
        finite = numeric_parsed[np.isfinite(numeric_parsed)]
        integer_valued = bool(((finite % 1) == 0).all()) if len(finite) else True
        data_type = "integer" if integer_valued else "float"
        return Detection(
            data_type,
            invalid_count,
            float(numeric_ratio),
            0.0,
            boolean_like,
            integer_valued=integer_valued,
        )

    datetime_ratio, datetime_ok = _datetime_parse(non_null)
    if datetime_ratio >= TYPE_PARSE_THRESHOLD:
        return Detection(
            "datetime",
            total - int(datetime_ok),
            float(numeric_ratio),
            float(datetime_ratio),
            boolean_like,
        )

    # Nothing reached the threshold: treat as string; every value is a valid string.
    return Detection("string", 0, float(numeric_ratio), float(datetime_ratio), boolean_like)


def _numeric_parse(values: pd.Series) -> tuple[float, pd.Series]:
    parsed = pd.to_numeric(values, errors="coerce")
    ratio = float(parsed.notna().sum()) / len(values) if len(values) else 0.0
    return ratio, parsed


def _datetime_parse(values: pd.Series) -> tuple[float, int]:
    """Tries each candidate format; keeps the one that parses the most values."""
    as_strings = values.astype(str)
    best_ratio, best_ok = 0.0, 0
    for fmt in DATETIME_FORMATS:
        try:
            parsed = pd.to_datetime(as_strings, errors="coerce", format=fmt)
        except (TypeError, ValueError):
            continue
        ok = int(pd.Series(parsed).notna().sum())
        ratio = ok / len(values) if len(values) else 0.0
        if ratio > best_ratio:
            best_ratio, best_ok = ratio, ok
        if ratio == 1.0:
            break
    return best_ratio, best_ok


def _is_boolean_like(non_null: pd.Series) -> bool:
    tokens = {_normalize_token(value) for value in non_null}
    if len(tokens) != 2:
        return False
    return any(tokens == pair for pair in BOOLEAN_VALUE_PAIRS)


def _normalize_token(value) -> str:
    text = str(value).strip().lower()
    try:
        number = float(text)
        if number.is_integer():
            text = str(int(number))  # "1.0" and "1" collapse to the same token
    except ValueError:
        pass
    return text


# ===== Semantic role classification (Part 2) =====


def _classify_role(
    name: str,
    series: pd.Series,
    detection: Detection,
    row_count: int,
    missing_count: int,
) -> tuple[SemanticRole, float, str]:
    """Assigns (semanticRole, confidence, reasoning); first matching rule wins."""
    non_null = series.dropna()
    non_null_count = int(len(non_null))
    unique_count = int(non_null.nunique())

    if non_null_count == 0:
        return (
            SemanticRole.EMPTY,
            1.0,
            f"All {row_count} value(s) are null/missing.",
        )

    if unique_count <= 1:
        value = str(non_null.iloc[0])[:40]
        return (
            SemanticRole.CONSTANT,
            0.99,
            f"Only 1 distinct value ('{value}') across {non_null_count} row(s) - zero variance.",
        )

    # TEMPORAL wins over IDENTIFIER: high cardinality is normal for dates
    # (one row per day/timestamp), so a datetime column must stay temporal
    # no matter how unique its values are.
    if detection.data_type == "datetime":
        pct = round(detection.datetime_ratio * 100)
        return (
            SemanticRole.TEMPORAL,
            round(min(0.99, max(0.8, detection.datetime_ratio)), 2),
            f"{pct}% of values parse as dates/timestamps.",
        )

    # Cardinality ratios are computed over the full row count (nulls included),
    # so columns that are mostly empty do not qualify as identifiers.
    unique_ratio = unique_count / row_count if row_count else 0.0
    name_suggests_id = bool(ID_NAME_PATTERN.match(re.sub(r"\s+", "_", name.strip())))

    # Numeric columns are measures, not identifiers: continuous values
    # (prices, totals, sensor readings) are frequently near-unique, so bare
    # cardinality must never demote them. Only an explicit ID-like name
    # (checked below) marks a numeric column as an identifier.
    is_numeric = detection.data_type in ("integer", "float")

    if unique_ratio >= IDENTIFIER_CARDINALITY_RATIO and not is_numeric:
        confidence = min(0.99, 0.95 + (0.02 if name_suggests_id else 0.0))
        reasoning = (
            f"{round(unique_ratio * 100)}% of values are distinct "
            f"({unique_count} unique across {row_count} rows)"
        )
        if name_suggests_id:
            reasoning += "; column name also matches an ID pattern"
        return SemanticRole.IDENTIFIER, round(confidence, 2), reasoning + "."

    if name_suggests_id and unique_ratio > CATEGORICAL_CARDINALITY_RATIO:
        return (
            SemanticRole.IDENTIFIER,
            0.7,
            f"Column name matches an ID pattern; {unique_count} unique across "
            f"{row_count} rows ({round(unique_ratio * 100)}% distinct).",
        )

    if detection.boolean_like or detection.data_type == "boolean":
        tokens = ", ".join(sorted({_normalize_token(v) for v in non_null}))
        confidence = 0.98 if detection.data_type == "boolean" else 0.95
        return (
            SemanticRole.BOOLEAN,
            confidence,
            f"Exactly 2 distinct values ({tokens}) matching a true/false-style pattern.",
        )

    if detection.data_type in ("integer", "float"):
        if detection.integer_valued and unique_count <= CATEGORICAL_MAX_UNIQUE:
            return (
                SemanticRole.NUMERIC_DISCRETE,
                0.9,
                f"{unique_count} distinct whole-number value(s) across {non_null_count} rows - "
                f"a discrete count-like measure.",
            )
        if unique_ratio <= CATEGORICAL_CARDINALITY_RATIO:
            return (
                SemanticRole.CATEGORICAL,
                0.8,
                f"Only {unique_count} distinct value(s) across {row_count} rows "
                f"({round(unique_ratio * 100)}% distinct) - low-cardinality codes treated as categories.",
            )
        return (
            SemanticRole.NUMERIC_CONTINUOUS,
            0.9,
            _numeric_reasoning(detection, unique_count, row_count, unique_ratio),
        )

    # String columns from here on.
    average_length = float(non_null.astype(str).str.len().mean())
    in_free_text_range = (
        FREE_TEXT_CARDINALITY_RANGE[0] <= unique_ratio <= FREE_TEXT_CARDINALITY_RANGE[1]
    )
    if in_free_text_range and average_length > FREE_TEXT_MIN_AVG_LENGTH:
        return (
            SemanticRole.FREE_TEXT,
            0.85,
            f"Text-heavy values (avg {average_length:.0f} chars) with moderate repetition "
            f"({unique_count} unique across {row_count} rows) - descriptive text, "
            f"not chartable.",
        )

    # Mixed-type detection must run BEFORE the categorical fallback: a column
    # that blends numeric and text content is genuinely inconsistent even when
    # its cardinality happens to be low enough to look like repeating labels
    # (e.g. 50% numbers + 50% text across very few distinct values).
    strongest = max(detection.numeric_ratio, detection.datetime_ratio)
    if strongest >= MIXED_TYPE_RATIO:
        kinds = []
        if detection.numeric_ratio >= MIXED_TYPE_RATIO:
            kinds.append(f"{round(detection.numeric_ratio * 100)}% parse as numeric")
        if detection.datetime_ratio >= MIXED_TYPE_RATIO:
            kinds.append(f"{round(detection.datetime_ratio * 100)}% parse as dates")
        detail = " and ".join(kinds)
        return (
            SemanticRole.INCONSISTENT,
            0.6,
            f"Mixed content: {detail}, but neither reaches the 95% typing threshold - "
            f"the column mixes values of different types.",
        )

    if unique_count <= CATEGORICAL_MAX_UNIQUE or unique_ratio <= CATEGORICAL_CARDINALITY_RATIO:
        return (
            SemanticRole.CATEGORICAL,
            0.85,
            f"{unique_count} distinct value(s) across {row_count} rows "
            f"({round(unique_ratio * 100)}% distinct) - small set of repeating labels.",
        )

    # Remaining high-cardinality short strings that matched nothing specific.
    return (
        SemanticRole.CATEGORICAL,
        0.5,
        f"No stronger signal matched ({unique_count} unique across {row_count} rows, "
        f"avg length {average_length:.0f}); kept as categorical fallback.",
    )


def _numeric_reasoning(
    detection: Detection, unique_count: int, non_null_count: int, unique_ratio: float
) -> str:
    kind = "integers" if detection.data_type == "integer" else "floats"
    pct = round(detection.numeric_ratio * 100)
    return (
        f"{pct}% of values parse as {kind}; {unique_count} distinct values across "
        f"{non_null_count} rows ({round(unique_ratio * 100)}% distinct) - continuous measure."
    )


def _infer_type(series: pd.Series) -> str:
    """Dtype-only inference, retained for the cleaning engine until it migrates
    to the content-based/semantic classification above."""
    if pd.api.types.is_bool_dtype(series):
        return "boolean"
    if pd.api.types.is_integer_dtype(series):
        return "integer"
    if pd.api.types.is_float_dtype(series):
        return "float"
    if pd.api.types.is_datetime64_any_dtype(series):
        return "datetime"
    return "string"


def _num(value) -> float | None:
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    if np.isnan(result):
        return None
    return round(result, 6)


def _sniff_delimiter(text: str) -> str:
    """Picks the delimiter whose per-line occurrence count is most consistent."""
    lines = [line for line in text[:SNIFF_SAMPLE_BYTES].splitlines() if line.strip()][:100]
    if not lines:
        return ","

    best_delimiter = ","
    best_consistency = 0.0
    best_frequency = 0

    for delimiter in CANDIDATE_DELIMITERS:
        counts = [line.count(delimiter) for line in lines]
        if not any(counts):
            continue
        modal_count, frequency = Counter(counts).most_common(1)[0]
        if modal_count == 0:
            continue
        consistency = frequency / len(lines)
        if consistency > best_consistency or (
            consistency == best_consistency and frequency > best_frequency
        ):
            best_delimiter = delimiter
            best_consistency = consistency
            best_frequency = frequency

    return best_delimiter
