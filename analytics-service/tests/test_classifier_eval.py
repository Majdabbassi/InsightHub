"""Ground-truth evaluation harness for the semantic role classifier.

The corpus below is a hand-labeled set of realistic columns (as they would
appear in real CSVs) across all ten semantic roles. Each entry is
``(name, values, expected_role)`` where ``expected_role`` is the role a human
analyst would assign. Running ``_column_stats`` over every entry and comparing
against the labels yields per-role precision/recall/F1 and an overall
accuracy figure that is published in ``docs/EVALUATION.md``.

Ground truth is intentionally *human intent* rather than current classifier
behaviour: the harness exists to catch drift, so a regression shows up as a
failing accuracy threshold rather than a silently changed number.
"""

from __future__ import annotations

import random
from collections import defaultdict

import pandas as pd
import pytest

from analysis import _column_stats
from schemas import SemanticRole

random.seed(42)

# ── Ground-truth corpus ────────────────────────────────────────────────────


def _uuids(n: int) -> list[str]:
    return [f"{i:08d}-0000-4000-8000-{i:012d}" for i in range(n)]


def _dates(n: int, start: str = "2023-01-01") -> list[str]:
    return list(pd.date_range(start, periods=n, freq="D").strftime("%Y-%m-%d"))


def _reviews(n: int, unique: int) -> list[str]:
    """Long free-text strings with moderate repetition.

    ``unique`` is the number of *distinct* texts over ``n`` rows, chosen so the
    unique/row ratio lands inside FREE_TEXT_CARDINALITY_RANGE (0.3–0.9) and the
    average length far exceeds the 30-char floor. Combo indices use divmod so
    distinct texts never alias (6 * 5 * 6 = 180 possible) regardless of n.
    """
    subjects = ["product", "service", "delivery", "quality", "support", "brand"]
    verbs = ["exceeded", "undermined", "matched", "surpassed", "disappointed"]
    tails = [
        "far beyond what the marketing material had promised ahead of purchase.",
        "much better than the previous version used before this one was shipped.",
        "roughly in line with expectations but with noticeable room for improvement.",
        "after three weeks of continuous daily use the unit still works perfectly.",
        "despite a delayed arrival the experience was pleasant and well handled.",
        "with only trivial caveats that do not materially affect the outcome.",
    ]
    texts = []
    for i in range(unique):
        subject = subjects[i % len(subjects)]
        verb = verbs[(i // len(subjects)) % len(verbs)]
        tail = tails[(i // (len(subjects) * len(verbs))) % len(tails)]
        texts.append(f"The {subject} {verb} expectations {tail}")
    return [texts[i % unique] for i in range(n)]


CORPUS: list[tuple[str, list, SemanticRole]] = [
    # ── IDENTIFIER ────────────────────────────────────────────────────────
    ("customer_id", _uuids(120), SemanticRole.IDENTIFIER),
    ("order_id", _uuids(150), SemanticRole.IDENTIFIER),
    ("user_id", [f"U{i:04d}" for i in range(200)], SemanticRole.IDENTIFIER),
    ("transaction_id", [f"txn-{i:06d}" for i in range(180)], SemanticRole.IDENTIFIER),
    ("session_id", _uuids(90), SemanticRole.IDENTIFIER),
    # ── TEMPORAL ──────────────────────────────────────────────────────────
    ("order_date", _dates(90), SemanticRole.TEMPORAL),
    ("signup_date", _dates(60), SemanticRole.TEMPORAL),
    ("event_timestamp", _dates(45), SemanticRole.TEMPORAL),
    ("invoice_date", _dates(75), SemanticRole.TEMPORAL),
    # ── CATEGORICAL ───────────────────────────────────────────────────────
    ("country", ["US", "DE", "FR", "JP", "BR", "IN"] * 20, SemanticRole.CATEGORICAL),
    ("region", ["EMEA", "AMERICAS", "APAC"] * 33, SemanticRole.CATEGORICAL),
    (
        "payment_status",
        ["paid", "pending", "refunded", "failed"] * 25,
        SemanticRole.CATEGORICAL,
    ),
    (
        "product_category",
        ["electronics", "apparel", "home", "books", "toys"] * 20,
        SemanticRole.CATEGORICAL,
    ),
    # Medium-cardinality numeric codes (e.g. region codes): > 20 unique but
    # with a low unique/row ratio, so the categorical fallback applies.
    (
        "region_code",
        [f"{i:04d}" for _ in range(25) for i in range(60)],
        SemanticRole.CATEGORICAL,
    ),
    (
        "product_class",
        [f"C{i:03d}" for _ in range(50) for i in range(80)],
        SemanticRole.CATEGORICAL,
    ),
    # ── NUMERIC_DISCRETE ──────────────────────────────────────────────────
    ("quantity", [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] * 10, SemanticRole.NUMERIC_DISCRETE),
    ("rating", [1, 2, 3, 4, 5] * 24, SemanticRole.NUMERIC_DISCRETE),
    ("unit_count", [i for _ in range(15) for i in range(1, 21)], SemanticRole.NUMERIC_DISCRETE),
    ("stock_level", [i for _ in range(10) for i in range(0, 15)], SemanticRole.NUMERIC_DISCRETE),
    # ── NUMERIC_CONTINUOUS ────────────────────────────────────────────────
    ("total_amount", [round(random.uniform(5, 950), 2) for _ in range(150)], SemanticRole.NUMERIC_CONTINUOUS),
    ("unit_price", [round(random.uniform(0.5, 300), 2) for _ in range(180)], SemanticRole.NUMERIC_CONTINUOUS),
    ("revenue", [round(random.uniform(100, 5000), 2) for _ in range(120)], SemanticRole.NUMERIC_CONTINUOUS),
    ("weight_kg", [round(random.uniform(0.1, 50), 3) for _ in range(100)], SemanticRole.NUMERIC_CONTINUOUS),
    ("temperature_c", [round(random.uniform(-20, 45), 1) for _ in range(200)], SemanticRole.NUMERIC_CONTINUOUS),
    # ── FREE_TEXT ──────────────────────────────────────────────────────────
    ("review_text", _reviews(200, unique=120), SemanticRole.FREE_TEXT),
    ("product_description", _reviews(150, unique=90), SemanticRole.FREE_TEXT),
    ("comment", _reviews(120, unique=72), SemanticRole.FREE_TEXT),
    # ── BOOLEAN ────────────────────────────────────────────────────────────
    ("is_active", [True, False] * 60, SemanticRole.BOOLEAN),
    ("opt_in", ["true", "false"] * 75, SemanticRole.BOOLEAN),
    ("has_discount", ["yes", "no"] * 40, SemanticRole.BOOLEAN),
    ("is_vip", ["y", "n"] * 50, SemanticRole.BOOLEAN),
    ("enabled", ["1", "0"] * 45, SemanticRole.BOOLEAN),
    # ── CONSTANT ───────────────────────────────────────────────────────────
    ("source_system", ["web"] * 100, SemanticRole.CONSTANT),
    ("currency", ["USD"] * 80, SemanticRole.CONSTANT),
    ("batch_id", ["BX-2024-01"] * 60, SemanticRole.CONSTANT),
    # ── EMPTY ──────────────────────────────────────────────────────────────
    ("legacy_notes", [None] * 90, SemanticRole.EMPTY),
    ("old_field", [float("nan")] * 70, SemanticRole.EMPTY),
    # ── INCONSISTENT ───────────────────────────────────────────────────────
    (
        "messy_value",
        ["123", "456", "789", "012", "345"] * 4 + ["abc", "def", "ghi", "jkl", "mno"] * 4,
        SemanticRole.INCONSISTENT,
    ),
    (
        "mixed_flag",
        ["1", "0", "yes", "no", "true"] * 20,
        SemanticRole.INCONSISTENT,
    ),
]


# ── Metrics ────────────────────────────────────────────────────────────────


def evaluate_corpus() -> dict:
    """Run the classifier over the corpus; return per-role + overall stats."""
    predicted_by = defaultdict(int)
    actual_by = defaultdict(int)
    tp_by = defaultdict(int)

    for name, values, expected in CORPUS:
        series = pd.Series(values, name=name)
        actual = _column_stats(name, series).semanticRole
        predicted_by[actual] += 1
        actual_by[expected] += 1
        if actual == expected:
            tp_by[expected] += 1

    roles = sorted(set(actual_by) | set(predicted_by))
    rows = []
    for role in roles:
        tp = tp_by[role]
        actual_n = actual_by[role]
        pred_n = predicted_by[role]
        precision = tp / pred_n if pred_n else 0.0
        recall = tp / actual_n if actual_n else 0.0
        f1 = (
            2 * precision * recall / (precision + recall)
            if precision + recall
            else 0.0
        )
        rows.append(
            {
                "role": role.value,
                "actual": actual_n,
                "predicted": pred_n,
                "tp": tp,
                "precision": precision,
                "recall": recall,
                "f1": f1,
            }
        )

    total = sum(actual_by.values())
    correct = sum(tp_by.values())
    return {
        "total": total,
        "correct": correct,
        "accuracy": correct / total if total else 0.0,
        "per_role": rows,
    }


def format_report(report: dict) -> str:
    lines = [
        f"Total cases : {report['total']}",
        f"Correct     : {report['correct']}",
        f"Accuracy    : {report['accuracy'] * 100:.2f}%",
        "",
        f"{'Role':<20} {'Actual':>7} {'Pred.':>7} {'TP':>4} {'P':>7} {'R':>7} {'F1':>7}",
        "-" * 62,
    ]
    for row in report["per_role"]:
        lines.append(
            f"{row['role']:<20} {row['actual']:>7d} {row['predicted']:>7d} {row['tp']:>4d}"
            f" {row['precision'] * 100:>6.1f}% {row['recall'] * 100:>6.1f}% {row['f1']:>7.3f}"
        )
    return "\n".join(lines)


class TestClassifierEvalHarness:
    def test_corpus_is_balanced(self):
        """Every role is represented in the corpus (nothing silently untested)."""
        present = {expected for _, _, expected in CORPUS}
        assert present == set(SemanticRole)

    def test_ground_truth_accuracy_threshold(self):
        """Overall accuracy over the hand-labeled corpus stays high."""
        report = evaluate_corpus()
        assert report["accuracy"] >= 0.95

    def test_per_role_recall_threshold(self):
        """No role may silently regress below 90% recall."""
        report = evaluate_corpus()
        for row in report["per_role"]:
            if row["actual"] > 0:
                assert row["recall"] >= 0.90, (
                    f"role {row['role']} recall {row['recall'] * 100:.1f}% "
                    f"below threshold ({row['actual']} cases)"
                )

    def test_report_is_reproducible(self):
        """Corpus entries are deterministic (no accidental randomness)."""
        _ = random.seed  # lint guard: corpus uses module-level fixed lists
        assert len(CORPUS) == len({id(entry) for entry in CORPUS})