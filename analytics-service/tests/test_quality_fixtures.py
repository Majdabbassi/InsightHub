"""End-to-end quality-score fixtures over realistic-looking datasets.

Each fixture is a hand-crafted DataFrame with *known ground-truth* behaviour:
the number of missing cells, duplicate rows, invalid/parse failures, and
extreme outliers is fully controlled, so the expected breakdown values are
computed by hand from the documented weights in ``analysis.py``:

    completeness = 1 - missing_cells / total_cells
    uniqueness   = 1 - duplicate_rows / row_count
    consistency  = 1 - invalid_cells / total_cells - 0.1 * INCONSISTENT cols
    validity     = 1 - extreme_outliers / numeric_cells
    overall      = round((c*0.35 + u*0.20 + s*0.25 + v*0.20) * 100)

The expected numbers here are the *source of truth* the implementation must
reproduce; they are the figures published in ``docs/EVALUATION.md``.
"""

import pandas as pd
import pytest

from analysis import analyze_dataframe
from schemas import DataQualityGrade, SemanticRole


def _dirty() -> pd.DataFrame:
    """100 rows x 3 cols with: 30 missing cells, 10 duplicate rows, 2 extremes.

    - ``order_id``  : 100 unique strings (IDENTIFIER), none missing
    - ``amount``    : floats, rows 0-19 NaN (20 missing), two extreme outliers
    - ``region``    : 4 categories, rows 0-9 NaN (10 missing)
    - rows 90-99 are verbatim copies of clean rows 20-29 -> 10 duplicates
    """
    regions = ["EMEA", "AMERICAS", "APAC", "LATAM"]
    amount = [float(i * 7 + 13) for i in range(90)]
    amount[35], amount[62] = 100000.0, 100000.0            # extreme outliers
    amount = [None if i < 20 else amount[i] for i in range(90)]
    region = [regions[i % 4] for i in range(90)]
    region = [None if i < 10 else region[i] for i in range(90)]

    order_id = [f"ORD-{i:05d}" for i in range(90)]
    return pd.DataFrame(
        {
            "order_id": order_id + order_id[20:30],
            "amount": amount + amount[20:30],
            "region": region + region[20:30],
        }
    )


def _clean() -> pd.DataFrame:
    """100 rows x 3 cols, every column complete and consistent."""
    regions = ["EMEA", "AMERICAS", "APAC", "LATAM"]
    amount = [float(i * 7 + 13) for i in range(100)]        # uniform, no outliers
    return pd.DataFrame(
        {
            "order_id": [f"ORD-{i:05d}" for i in range(100)],
            "amount": amount,
            "region": [regions[i % 4] for i in range(100)],
        }
    )


def _inconsistent() -> pd.DataFrame:
    """A column that mixes numeric and text values -> INCONSISTENT role.

    The whole-column inconsistency applies the 0.1 consistency penalty even
    though no individual cell is unparseable (string type, invalid_count = 0).
    """
    regions = ["EMEA", "AMERICAS", "APAC", "LATAM"]
    messy = ["123", "45.6", "abc", "note", "789", "ooo", "12x", "1", "two", "3.14"]
    return pd.DataFrame(
        {
            "order_id": [f"ORD-{i:05d}" for i in range(100)],
            "region": [regions[i % 4] for i in range(100)],
            "messy": [messy[i % len(messy)] for i in range(100)],
        }
    )


class TestQualityFixtures:
    def _analyze(self, df: pd.DataFrame):
        return analyze_dataframe(df)

    # ── clean ──
    # 100 rows, 3 cols, 300 cells. Nothing missing/duplicated/invalid/extreme.
    #   completeness = 1 - 0/300          = 1.0
    #   uniqueness   = 1 - 0/100          = 1.0
    #   consistency  = 1 - 0/300 - 0      = 1.0
    #   validity     = 1 - 0/100          = 1.0
    #   overall      = round(100 * (0.35+0.20+0.25+0.20)) = 100  -> grade A

    def test_clean_dataset_scores_perfect(self):
        response = self._analyze(_clean())
        quality = response.dataQuality
        assert quality.breakdown.completeness == 1.0
        assert quality.breakdown.uniqueness == 1.0
        assert quality.breakdown.consistency == 1.0
        assert quality.breakdown.validity == 1.0
        assert quality.overallScore == 100
        assert quality.grade == DataQualityGrade.A
        assert response.summary.totalMissingValues == 0
        assert response.summary.totalDuplicateRows == 0

    # ── dirty ──
    # 100 rows, 3 cols, 300 cells. 30 missing, 10 duplicates, 2 extremes, 0 invalid.
    #   completeness = 1 - 30/300     = 0.90
    #   uniqueness   = 1 - 10/100     = 0.90
    #   consistency  = 1 - 0/300 - 0  = 1.0
    #   validity     = 1 - 2/100      = 0.98
    #   overall      = round(100 * (0.90*0.35 + 0.90*0.20 + 1.0*0.25 + 0.98*0.20))
    #                = round(94.1)    = 94  -> grade A

    def test_dirty_dataset_scores_expected(self):
        response = self._analyze(_dirty())
        quality = response.dataQuality
        assert response.summary.totalMissingValues == 30
        assert response.summary.totalDuplicateRows == 10
        assert quality.breakdown.completeness == pytest.approx(0.90, abs=0.01)
        assert quality.breakdown.uniqueness == pytest.approx(0.90, abs=0.01)
        assert quality.breakdown.consistency == 1.0
        assert quality.breakdown.validity == pytest.approx(0.98, abs=0.01)
        assert quality.overallScore == 94
        assert quality.grade == DataQualityGrade.A

    # ── inconsistent ──
    # 100 rows, 3 cols, 300 cells. No missing/duplicates/invalid/extreme, but one
    # column blends numeric + text -> INCONSISTENT (0.1 penalty).
    #   completeness = 1 - 0/300         = 1.0
    #   uniqueness   = 1 - 0/100         = 1.0
    #   consistency  = 1 - 0/300 - 0.1   = 0.90
    #   validity     = 1 - 0/0           = 1.0   (no numeric columns)
    #   overall      = round(100 * (0.35 + 0.20 + 0.90*0.25 + 0.20))
    #                = round(97.5)       = 98  -> grade A

    def test_inconsistent_column_penalises_consistency(self):
        response = self._analyze(_inconsistent())
        quality = response.dataQuality
        inconsistent_roles = [
            col.semanticRole for col in response.columns if col.name == "messy"
        ]
        assert inconsistent_roles == [SemanticRole.INCONSISTENT]
        assert quality.breakdown.completeness == 1.0
        assert quality.breakdown.uniqueness == 1.0
        assert quality.breakdown.consistency == pytest.approx(0.90, abs=0.01)
        assert quality.breakdown.validity == 1.0
        assert quality.overallScore == 98
        assert quality.grade == DataQualityGrade.A