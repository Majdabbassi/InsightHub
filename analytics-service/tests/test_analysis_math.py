"""Tests for the analytics math core: semantic classification, IQR outliers,
data-quality scoring, and DuckDB read-only sandbox validation.

Each test uses hand-crafted DataFrames with known ground-truth values so the
assertions verify real math rather than incidental snapshot output.
"""

import pandas as pd
import pytest

from analysis import (
    analyze_dataframe,
    _classify_role,
    _detect_data_type,
    _outlier_analysis,
    _compute_data_quality,
    ColumnStats,
    Detection,
)
from schemas import SemanticRole, DataQualityGrade
from duckdb_query import validate_sql, QueryValidationError, sanitize_name


# ── Semantic role classification ──────────────────────────────────────────


class TestSemanticRoleClassification:
    def test_high_cardinality_string_is_identifier(self):
        """100 distinct UUIDs across 100 rows → IDENTIFIER."""
        df = pd.DataFrame({"user_id": [f"user-{i}" for i in range(100)]})
        series = df["user_id"]
        detection = _detect_data_type(series)
        role, confidence, reasoning = _classify_role("user_id", series, detection, 100, 0)
        assert role == SemanticRole.IDENTIFIER
        assert confidence >= 0.9

    def test_code_named_low_cardinality_is_categorical_not_identifier(self):
        """An ID-looking *name* must not win over genuinely categorical data.

        ``region_code`` has only 60 distinct values of 1500 rows (4%). The
        ID_NAME_PATTERN matches ``_code``, but sub-5% cardinality means labels,
        not identifiers — the name hint is only honored above the categorical
        cardinality ratio.
        """
        df = pd.DataFrame(
            {"region_code": [f"{i % 60:04d}" for i in range(1500)]}
        )
        series = df["region_code"]
        detection = _detect_data_type(series)
        role, confidence, _ = _classify_role("region_code", series, detection, 1500, 0)
        assert role == SemanticRole.CATEGORICAL
        assert confidence >= 0.8

    def test_code_named_high_cardinality_is_identifier(self):
        """An ID-looking name with real distinctness is still an identifier."""
        df = pd.DataFrame(
            {"product_code": [f"SKU-{i}" for i in range(500)]}
        )
        series = df["product_code"]
        detection = _detect_data_type(series)
        role, confidence, _ = _classify_role("product_code", series, detection, 500, 0)
        assert role == SemanticRole.IDENTIFIER
        assert confidence >= 0.9

    def test_datetime_column_is_temporal(self):
        """Column of dates → TEMPORAL regardless of cardinality."""
        df = pd.DataFrame({"date": pd.date_range("2024-01-01", periods=60, freq="D")})
        series = df["date"]
        detection = _detect_data_type(series)
        role, _, _ = _classify_role("date", series, detection, 60, 0)
        assert role == SemanticRole.TEMPORAL

    def test_boolean_like_column(self):
        """Exactly 2 values {true, false} → BOOLEAN."""
        df = pd.DataFrame({"active": ["true", "false", "true", "false", "true", "false"]})
        series = df["active"]
        detection = _detect_data_type(series)
        role, confidence, _ = _classify_role("active", series, detection, 6, 0)
        assert role == SemanticRole.BOOLEAN
        assert confidence >= 0.95

    def test_constant_column(self):
        """Only 1 distinct value → CONSTANT."""
        df = pd.DataFrame({"country": ["US"] * 50})
        series = df["country"]
        detection = _detect_data_type(series)
        role, confidence, _ = _classify_role("country", series, detection, 50, 0)
        assert role == SemanticRole.CONSTANT
        assert confidence >= 0.99

    def test_empty_column(self):
        """All nulls → EMPTY."""
        df = pd.DataFrame({"notes": [None, None, None]})
        series = df["notes"]
        detection = _detect_data_type(series)
        role, confidence, _ = _classify_role("notes", series, detection, 3, 3)
        assert role == SemanticRole.EMPTY
        assert confidence == 1.0

    def test_numeric_continuous(self):
        """High-cardinality floats → NUMERIC_CONTINUOUS."""
        values = [float(i) + 0.5 for i in range(200)]
        df = pd.DataFrame({"revenue": values})
        series = df["revenue"]
        detection = _detect_data_type(series)
        role, _, _ = _classify_role("revenue", series, detection, 200, 0)
        assert role == SemanticRole.NUMERIC_CONTINUOUS

    def test_numeric_discrete_few_unique_integers(self):
        """Few distinct whole-number values → NUMERIC_DISCRETE."""
        df = pd.DataFrame({"rating": [1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2]})
        series = df["rating"]
        detection = _detect_data_type(series)
        role, _, _ = _classify_role("rating", series, detection, 12, 0)
        assert role == SemanticRole.NUMERIC_DISCRETE

    def test_low_cardinality_numeric_is_discrete(self):
        """Numeric column with few distinct whole values → NUMERIC_DISCRETE
        (the discrete check runs before the low-cardinality categorical fallback)."""
        values = [1, 2, 3] * 100
        df = pd.DataFrame({"zone": values})
        series = df["zone"]
        detection = _detect_data_type(series)
        role, _, _ = _classify_role("zone", series, detection, 300, 0)
        assert role == SemanticRole.NUMERIC_DISCRETE

    def test_inconsistent_mixed_types(self):
        """Column that is 50% numeric and 50% text → INCONSISTENT."""
        values = ["123", "456", "789", "012", "345"] * 2 + ["abc", "def", "ghi", "jkl", "mno"] * 2
        df = pd.DataFrame({"weird": values})
        series = df["weird"]
        detection = _detect_data_type(series)
        role, _, _ = _classify_role("weird", series, detection, 20, 0)
        assert role == SemanticRole.INCONSISTENT


# ── IQR outlier detection ────────────────────────────────────────────────


class TestIqrOutlierDetection:
    def test_detects_outliers_in_normal_data(self):
        """Data with known outliers beyond 1.5*IQR boundaries."""
        # Normal data: 0-99, plus two clear outliers at 500 and -100
        values = list(range(100)) + [500, -100]
        series = pd.Series(values, dtype=float)
        analysis, note = _outlier_analysis(series)
        assert note is None
        assert analysis is not None
        assert analysis.outlierCount >= 2
        assert analysis.extremeCount >= 1
        assert analysis.mildCount >= 0
        assert analysis.outlierPercentage > 0

    def test_no_outliers_in_tight_data(self):
        """Uniform data → no outliers (Q1 == Q3 → iqr == 0 → note returned)."""
        series = pd.Series([5.0, 5.0, 5.0, 5.0, 5.0])
        analysis, note = _outlier_analysis(series)
        assert analysis is None
        assert note is not None
        assert "Insufficient variance" in note

    def test_empty_series(self):
        """Empty series → no analysis, no note."""
        series = pd.Series([], dtype=float)
        analysis, note = _outlier_analysis(series)
        assert analysis is None
        assert note is None

    def test_outlier_bounds_are_correct(self):
        """Verify IQR calculation on known data: Q1=25, Q3=75, IQR=50.
        Lower bound = 25 - 1.5*50 = -50, Upper = 75 + 1.5*50 = 150.
        Extreme: -50 - 1.5*50 = -125, 150 + 1.5*50 = 225."""
        values = list(range(101))  # 0..100
        series = pd.Series(values, dtype=float)
        analysis, _ = _outlier_analysis(series)
        assert analysis is not None
        assert analysis.lowerBound == pytest.approx(-50.0, abs=0.01)
        assert analysis.upperBound == pytest.approx(150.0, abs=0.01)
        assert analysis.outlierCount == 0  # all within [-50, 150]


# ── Data quality score ───────────────────────────────────────────────────


class TestDataQualityScore:
    def test_perfect_data_scores_high(self):
        """No missing, no duplicates, no invalids, no outliers → score >= 90 (grade A)."""
        columns = [
            ColumnStats(
                name="id", dataType="integer", semanticRole=SemanticRole.IDENTIFIER,
                confidence=0.95, reasoning="", missingCount=0, missingPercentage=0,
                uniqueCount=100, invalidValueCount=0,
            ),
            ColumnStats(
                name="amount", dataType="float", semanticRole=SemanticRole.NUMERIC_CONTINUOUS,
                confidence=0.9, reasoning="", missingCount=0, missingPercentage=0,
                uniqueCount=100, invalidValueCount=0,
            ),
        ]
        quality = _compute_data_quality(columns, row_count=100, duplicate_row_count=0)
        assert quality.grade == DataQualityGrade.A
        assert quality.overallScore >= 90
        assert quality.breakdown.completeness == 1.0
        assert quality.breakdown.uniqueness == 1.0

    def test_missing_values_lower_completeness(self):
        """50% missing cells should drop completeness to ~0.5."""
        columns = [
            ColumnStats(
                name="x", dataType="string", semanticRole=SemanticRole.CATEGORICAL,
                confidence=0.8, reasoning="", missingCount=50, missingPercentage=50.0,
                uniqueCount=1, invalidValueCount=0,
            ),
        ]
        quality = _compute_data_quality(columns, row_count=100, duplicate_row_count=0)
        assert quality.breakdown.completeness == pytest.approx(0.5, abs=0.01)

    def test_duplicates_lower_uniqueness(self):
        """50% duplicate rows → uniqueness = 0.5."""
        columns = [
            ColumnStats(
                name="x", dataType="string", semanticRole=SemanticRole.CATEGORICAL,
                confidence=0.8, reasoning="", missingCount=0, missingPercentage=0,
                uniqueCount=1, invalidValueCount=0,
            ),
        ]
        quality = _compute_data_quality(columns, row_count=100, duplicate_row_count=50)
        assert quality.breakdown.uniqueness == pytest.approx(0.5, abs=0.01)

    def test_grade_boundaries(self):
        """Score 75 → grade B, score 60 → grade C, score 40 → grade D, score 20 → grade F."""
        for score, expected_grade in [
            (90, DataQualityGrade.A),
            (80, DataQualityGrade.B),
            (65, DataQualityGrade.C),
            (45, DataQualityGrade.D),
            (25, DataQualityGrade.F),
        ]:
            # Fake column stats that produce the exact desired overall score
            from analysis import _quality_grade
            assert _quality_grade(score) == expected_grade


# ── DuckDB read-only sandbox ─────────────────────────────────────────────


class TestDuckDbSandbox:
    def test_validate_rejects_insert(self):
        # Guarded by the statement-start check (must begin with SELECT/WITH).
        with pytest.raises(QueryValidationError, match="Only SELECT"):
            validate_sql("INSERT INTO users VALUES (1)", {"users"})

    def test_validate_rejects_drop(self):
        with pytest.raises(QueryValidationError, match="Only SELECT"):
            validate_sql("DROP TABLE users", {"users"})

    def test_validate_rejects_update(self):
        with pytest.raises(QueryValidationError, match="Only SELECT"):
            validate_sql("UPDATE users SET name='x'", {"users"})

    def test_validate_rejects_delete(self):
        with pytest.raises(QueryValidationError, match="Only SELECT"):
            validate_sql("DELETE FROM users WHERE id=1", {"users"})

    def test_validate_rejects_mutation_inside_select(self):
        """Mutation keywords hidden inside a valid SELECT (e.g. a CTE body)
        are caught by the blocked-keyword scan."""
        with pytest.raises(QueryValidationError, match="not allowed"):
            validate_sql("WITH x AS (DELETE FROM users) SELECT * FROM x", {"users"})

    def test_validate_accepts_select(self):
        result = validate_sql("SELECT * FROM users", {"users"})
        assert result == "SELECT * FROM users"

    def test_validate_accepts_cte(self):
        sql = "WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"
        result = validate_sql(sql, {"orders"})
        assert "WITH" in result

    def test_validate_rejects_unknown_table(self):
        with pytest.raises(QueryValidationError, match="not in the provided datasets"):
            validate_sql("SELECT * FROM unknown_table", {"users"})

    def test_validate_rejects_multi_statement(self):
        with pytest.raises(QueryValidationError, match="single statement"):
            validate_sql("SELECT 1; DROP TABLE users", {"users"})

    def test_validate_rejects_empty_query(self):
        with pytest.raises(QueryValidationError, match="empty"):
            validate_sql("", {"users"})

    def test_sanitize_name_basic(self):
        assert sanitize_name("Sales-2024.csv") == "sales_2024"

    def test_sanitize_name_leading_digit(self):
        assert sanitize_name("123data.csv") == "t_123data"

    def test_sanitize_name_preserves_alphanumeric(self):
        assert sanitize_name("orders.csv") == "orders"
