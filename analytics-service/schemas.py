"""Pydantic models describing the payloads exchanged between services."""

from enum import Enum
from typing import Any

from pydantic import BaseModel


class TopValue(BaseModel):
    value: str
    count: int


class OutlierSample(BaseModel):
    """An actual outlying value, traceable back to its row."""

    value: float
    rowIndex: int


class OutlierAnalysis(BaseModel):
    """IQR-based outlier statistics for a numeric column."""

    outlierCount: int
    mildCount: int
    extremeCount: int
    outlierPercentage: float
    lowerBound: float
    upperBound: float
    outlierSamples: list[OutlierSample] = []
    lowConfidence: bool = False


class SemanticRole(str, Enum):
    """Semantic meaning of a column, layered on top of its raw data type."""

    IDENTIFIER = "IDENTIFIER"
    BOOLEAN = "BOOLEAN"
    TEMPORAL = "TEMPORAL"
    FREE_TEXT = "FREE_TEXT"
    CATEGORICAL = "CATEGORICAL"
    NUMERIC_DISCRETE = "NUMERIC_DISCRETE"
    NUMERIC_CONTINUOUS = "NUMERIC_CONTINUOUS"
    CONSTANT = "CONSTANT"
    EMPTY = "EMPTY"
    INCONSISTENT = "INCONSISTENT"


class ColumnStats(BaseModel):
    name: str
    dataType: str
    semanticRole: SemanticRole
    confidence: float
    reasoning: str
    missingCount: int
    missingPercentage: float
    uniqueCount: int
    invalidValueCount: int = 0
    min: float | None = None
    max: float | None = None
    mean: float | None = None
    median: float | None = None
    stdDev: float | None = None
    topValues: list[TopValue] | None = None
    outlierAnalysis: OutlierAnalysis | None = None


class Summary(BaseModel):
    totalMissingValues: int
    totalDuplicateRows: int


class CorrelationStrength(str, Enum):
    MODERATE = "MODERATE"
    STRONG = "STRONG"
    VERY_STRONG = "VERY_STRONG"


class CorrelationDirection(str, Enum):
    POSITIVE = "POSITIVE"
    NEGATIVE = "NEGATIVE"


class ColumnCorrelation(BaseModel):
    """A significant pairwise Pearson correlation between two numeric columns."""

    columnA: str
    columnB: str
    correlation: float
    strength: CorrelationStrength
    direction: CorrelationDirection
    sampleSize: int
    lowConfidence: bool


class DataQualityGrade(str, Enum):
    A = "A"
    B = "B"
    C = "C"
    D = "D"
    F = "F"


class DataQualityBreakdown(BaseModel):
    """Per-component quality scores, each between 0.0 and 1.0."""

    completeness: float
    uniqueness: float
    consistency: float
    validity: float


class DataQuality(BaseModel):
    """Dataset-level composite score aggregated from existing analysis signals."""

    overallScore: int
    grade: DataQualityGrade
    breakdown: DataQualityBreakdown


class AnalysisResponse(BaseModel):
    rowCount: int
    columnCount: int
    duplicateRowCount: int
    columns: list[ColumnStats]
    summary: Summary
    correlations: list[ColumnCorrelation] = []
    dataQuality: DataQuality | None = None


# ===== Cleaning =====


class SuggestionType(str, Enum):
    MISSING_VALUES = "MISSING_VALUES"
    DUPLICATES = "DUPLICATES"
    DATA_TYPE_MISMATCH = "DATA_TYPE_MISMATCH"
    OUTLIERS = "OUTLIERS"
    VALIDATION_ISSUE = "VALIDATION_ISSUE"
    LOW_VARIANCE = "LOW_VARIANCE"


class CleaningAction(str, Enum):
    DROP_ROWS = "DROP_ROWS"
    DROP_DUPLICATES = "DROP_DUPLICATES"
    FILL_MEAN = "FILL_MEAN"
    FILL_MEDIAN = "FILL_MEDIAN"
    FILL_MODE = "FILL_MODE"
    FILL_ZERO = "FILL_ZERO"
    FILL_CUSTOM_VALUE = "FILL_CUSTOM_VALUE"
    COERCE_TYPE = "COERCE_TYPE"
    DROP_INVALID_ROWS = "DROP_INVALID_ROWS"
    CAP_TO_BOUNDS = "CAP_TO_BOUNDS"
    REMOVE_ROWS = "REMOVE_ROWS"
    SET_TO_ZERO = "SET_TO_ZERO"
    FLAG_ONLY = "FLAG_ONLY"
    NONE = "NONE"


class CleaningSuggestion(BaseModel):
    id: str
    type: SuggestionType
    columnName: str | None = None
    description: str
    suggestedAction: CleaningAction
    alternativeActions: list[CleaningAction] = []
    affectedRowCount: int
    reasoning: str | None = None


class CleaningSuggestionsResponse(BaseModel):
    suggestions: list[CleaningSuggestion]
    totalIssues: int


class SelectedAction(BaseModel):
    columnName: str | None = None
    actionType: CleaningAction
    customValue: str | None = None


class CleaningSummary(BaseModel):
    rowsBefore: int
    rowsAfter: int
    rowsRemoved: int
    valuesFilled: int


# ===== Insights =====


class TrendDirection(str, Enum):
    INCREASING = "INCREASING"
    DECREASING = "DECREASING"
    STABLE = "STABLE"


class TrendStrength(str, Enum):
    CLEAR = "CLEAR"
    MODERATE = "MODERATE"
    NOISY = "NOISY"


class TrendInsight(BaseModel):
    """A single detected trend for one numeric column over a temporal column."""

    column: str
    temporalColumn: str
    periodGrouping: str
    periodCount: int
    direction: TrendDirection
    strength: TrendStrength
    rSquared: float
    percentageChange: float | None = None
    firstPeriodLabel: str
    lastPeriodLabel: str
    points: list[float] = []
    pointLabels: list[str] = []
    summary: str


class TrendInsightsResponse(BaseModel):
    trends: list[TrendInsight] = []
    skippedReasons: list[str] = []


class ComparisonDriver(BaseModel):
    """A category whose value moved most between the two periods."""

    categoryColumn: str
    categoryValue: str
    previousValue: float
    currentValue: float
    delta: float


class ColumnComparison(BaseModel):
    """Recent-period vs previous-period comparison for one numeric column."""

    column: str
    previousValue: float
    currentValue: float
    delta: float
    percentChange: float | None = None
    lowConfidence: bool = False
    topDrivers: list[ComparisonDriver] = []
    summary: str


class PeriodComparisonResponse(BaseModel):
    periodType: str
    previousLabel: str
    currentLabel: str
    comparisons: list[ColumnComparison] = []
    skippedReasons: list[str] = []


class PartialAnalysis(BaseModel):
    """Only the columns block of an AnalysisResponse, reused across calls."""

    columns: list[ColumnStats]
    correlations: list[ColumnCorrelation] = []


class SiblingComparisonResponse(BaseModel):
    """Two schema-twin datasets compared like two periods of one report."""

    comparisonType: str = "sibling"
    datasetALabel: str
    datasetBLabel: str
    comparisons: list[ColumnComparison] = []
    skippedReasons: list[str] = []


class ReferentialCompleteness(BaseModel):
    """Share of child rows whose key value exists in the parent dataset."""

    matchPercentage: float
    mismatchCount: int
    mismatchPercentage: float
    summary: str


class JoinAggregate(BaseModel):
    """Average number of child entries per parent row (+ optional metric)."""

    avgChildCountPerParent: float
    childNumericColumn: str | None = None
    avgNumericSumPerParent: float | None = None
    summary: str


class RelationalInsightResponse(BaseModel):
    """Findings for one confirmed foreign-key relationship."""

    referentialCompleteness: ReferentialCompleteness
    joinAggregate: JoinAggregate | None = None


class AnomalyDriver(BaseModel):
    """A category that deviated most during an anomalous period."""

    categoryColumn: str
    categoryValue: str
    periodValue: float
    typicalValue: float


class PeriodAnomaly(BaseModel):
    """A whole time period whose aggregated metric deviates from the norm."""

    column: str
    temporalColumn: str
    periodGrouping: str
    periodLabel: str
    actualValue: float
    typicalValue: float
    zScore: float
    severity: str
    direction: str
    multiplier: float | None = None
    topDrivers: list[AnomalyDriver] = []
    summary: str


class AnomaliesResponse(BaseModel):
    anomalies: list[PeriodAnomaly] = []
    skippedReasons: list[str] = []


class PerformerEntry(BaseModel):
    """One ranked category within a top/bottom performers pair."""

    category: str
    sumValue: float
    avgValue: float
    rank: int


class PerformersPair(BaseModel):
    """Ranking of one categorical column by one numeric metric."""

    categoricalColumn: str
    numericColumn: str
    aggregation: str = "SUM"
    topPerformers: list[PerformerEntry] = []
    bottomPerformers: list[PerformerEntry] = []
    gap: float | None = None
    isDominant: bool = False
    dominantPercentage: float | None = None
    note: str | None = None
    summary: str


class PerformersResponse(BaseModel):
    performers: list[PerformersPair] = []
    skippedReasons: list[str] = []

class QueryResultResponse(BaseModel):
    """Outcome of a sandboxed DuckDB query execution."""

    success: bool
    columns: list[str] | None = None
    rows: list[list[Any]] | None = None
    rowCount: int | None = None
    error: str | None = None
