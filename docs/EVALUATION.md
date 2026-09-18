# Classifier Evaluation

This document records the measured accuracy of the semantic role classifier and
the dataset-level quality scorer in `analytics-service`. Both are evaluated
against hand-labeled ground truth so that regressions surface as failing tests
rather than silently changed behaviour.

## Semantic role classifier

The classifier assigns one of ten semantic roles to every column. Three layers
decide the outcome:

1. **Type detection** — boolean type, datetime, or numeric (ratio ≥ 0.95 of
   values parse) with integer-vs-float discrimination; otherwise string.
2. **Role decision** — an ordered decision tree (`_classify_role`):
   EMPTY → CONSTANT → TEMPORAL → IDENTIFIER → BOOLEAN → numeric handling
   (DISCRETE / CATEGORICAL / CONTINUOUS) → FREE_TEXT → INCONSISTENT →
   CATEGORICAL fallback.
3. **Quality scoring** — per-dataset aggregation of completeness, uniqueness,
   consistency, and validity (see below).

### Corpus

The ground-truth corpus in `tests/test_classifier_eval.py` contains 39
hand-labeled columns covering all ten roles with realistic shapes: UUID and
slug identifiers, ISO dates, true/false pairs (including `yes/no`, `y/n`,
`0/1`), discrete counts, continuous measures, free text, constants, empty
columns, and mixed-type columns. Labels encode *human intent* rather than the
classifier's behaviour, so the harness measures real accuracy.

### Per-role results

| Role | Cases | Precision | Recall | F1 |
| --- | ---: | ---: | ---: | ---: |
| BOOLEAN | 5 | 100.0% | 100.0% | 1.000 |
| CATEGORICAL | 6 | 100.0% | 100.0% | 1.000 |
| CONSTANT | 3 | 100.0% | 100.0% | 1.000 |
| EMPTY | 2 | 100.0% | 100.0% | 1.000 |
| FREE_TEXT | 3 | 100.0% | 100.0% | 1.000 |
| IDENTIFIER | 5 | 100.0% | 100.0% | 1.000 |
| INCONSISTENT | 2 | 100.0% | 100.0% | 1.000 |
| NUMERIC_CONTINUOUS | 5 | 100.0% | 100.0% | 1.000 |
| NUMERIC_DISCRETE | 4 | 100.0% | 100.0% | 1.000 |
| TEMPORAL | 4 | 100.0% | 100.0% | 1.000 |

## Overall accuracy: 39/39 (100.0%)

### Thresholds enforced by tests

- Overall accuracy ≥ 95%.
- Per-role recall ≥ 90% for every role present in the corpus.

These are deliberately strict: a missed role in a new or edited rule drops the
score and fails CI instead of drifting silently.

### Findings surfaced by the harness

Building the harness exposed one genuine classifier bug, now fixed and locked
in by regression tests:

- An ID-looking **column name** (`*_id`, `*_code`, `*_key`) previously forced
  `IDENTIFIER` regardless of cardinality. A `region_code` with only 4% distinct
  values was tagged an identifier instead of a category. The name hint is now
  only honoured above the categorical cardinality ratio; genuinely distinct
  code columns (e.g. 500 unique `SKU-*` values) remain identifiers.
  See `tests/test_analysis_math.py::TestSemanticRoleClassification`.

### Known limitations

- Free text is recognised by length + moderate repetition. Very short free-text
  fields (under the 30-char average floor) fall back to CATEGORICAL.
- ISO timestamps beat ID-looking names, so a timestamp column named
  `*_id` classifies as TEMPORAL.
- Digits-only phone/postal values parse as numeric and may be seen as
  `NUMERIC_DISCRETE`; this mirrors the content-based type detection decision
  and is documented rather than special-cased.

## Data quality scorer

`_compute_data_quality` aggregates four signals into a 0–100 score and an A–F
grade, using the weights below. Extreme (not mild) outliers are the only
outlier class penalised, since mild outliers are often legitimate data.

| Signal | Formula | Weight |
| --- | --- | ---: |
| Completeness | `1 - missing_cells / total_cells` | 0.35 |
| Uniqueness | `1 - duplicate_rows / row_count` | 0.20 |
| Consistency | `1 - invalid_cells / total_cells - 0.1 * inconsistent_columns` | 0.25 |
| Validity | `1 - extreme_outliers / numeric_cells` | 0.20 |

`overall = round(100 * weighted sum)`; grade A ≥ 90, B ≥ 75, C ≥ 60, D ≥ 40,
F otherwise.

### Fixture results

Expected values in `tests/test_quality_fixtures.py` are computed by hand from
the formulas above and cross-checked by the tests:

| Fixture | Missing | Duplicates | Extremes | Inconsistent cols | Completeness | Uniqueness | Consistency | Validity | Overall | Grade |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| clean | 0 / 300 | 0 / 100 | 0 | 0 | 1.00 | 1.00 | 1.00 | 1.00 | 100 | A |
| dirty | 30 / 300 | 10 / 100 | 2 / 100 | 0 | 0.90 | 0.90 | 1.00 | 0.98 | 94 | A |
| inconsistent | 0 / 300 | 0 / 100 | n/a | 1 | 1.00 | 1.00 | 0.90 | 1.00 | 98 | A |

## Running the evaluation

```bash
cd analytics-service
python -m pytest tests/test_classifier_eval.py tests/test_quality_fixtures.py -v
```

The same tests run in CI via `pytest` in `.github/workflows/ci.yml`.
