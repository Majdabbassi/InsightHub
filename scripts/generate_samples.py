#!/usr/bin/env python3
"""Deterministic generator for the demo datasets shipped in `samples/`.

Run (from the repo root or anywhere):

    python scripts/generate_samples.py

Writes customers.csv, orders.csv and order_items.csv into `samples/`.
The output is fully reproducible (fixed random seed) so the sample files
match commit-for-commit, and reviewers can regenerate or tweak them.

The three datasets form a small e-commerce star: customers <- orders
-via `customer_id`- and orders <- order_items -via `order_id`-. They are
engineered to exercise the platform:

  * Semantic column roles: IDENTIFIER (ids), TEMPORAL (dates),
    CATEGORICAL (category/region/country), NUMERIC_DISCRETE (quantity),
    NUMERIC_CONTINUOUS (prices) — with high-confidence classification.
  * Relationship detection: shared `customer_id` and `order_id` keys.
  * Trend detection + period comparison: a 6-month order window.
  * Anomaly detection: a $99999 outlier total in `orders.csv`.
  * Cleaning suggestions: a missing `total`, an outlier, malformed emails.
  * Duplicate detection: one duplicated order row.
"""

from __future__ import annotations

import csv
import datetime as dt
import os
import random
from pathlib import Path

SEED = 42

CUSTOMER_HEADER = ["customer_id", "full_name", "email", "signup_date", "country"]
ORDERS_HEADER = [
    "order_id",
    "order_date",
    "customer_id",
    "product_category",
    "region",
    "quantity",
    "unit_price",
    "total",
]
ORDER_ITEMS_HEADER = [
    "order_item_id",
    "order_id",
    "product_name",
    "product_category",
    "unit_price",
    "quantity",
]

FIRST_NAMES = [
    "Anna", "Ben", "Chloe", "David", "Emma", "Felix", "Grace", "Hugo",
    "Iris", "Jack", "Kira", "Leo", "Maya", "Noah", "Olive", "Pia",
    "Quinn", "Rosa", "Sam", "Tina", "Uma", "Victor", "Wendy", "Xander",
    "Yara", "Zoe", "Aria", "Liam", "Nora", "Owen",
]
LAST_NAMES = [
    "Smith", "Johnson", "Brown", "Garcia", "Miller", "Davis", "Wilson",
    "Moore", "Taylor", "Anderson", "Thomas", "Jackson", "White", "Harris",
    "Martin", "Thompson", "Young", "King", "Wright", "Scott",
]
COUNTRIES = ["US", "DE", "FR", "UK", "CA", "JP"]
CATEGORIES = ["Electronics", "Clothing", "Home", "Sports", "Beauty", "Books"]
REGIONS = ["North", "South", "East", "West"]

PRODUCTS = {
    "Electronics": ["Wireless Mouse", "USB-C Hub", "4K Monitor", "Bluetooth Speaker", "Mechanical Keyboard"],
    "Clothing": ["Cotton Tee", "Denim Jacket", "Hoodie", "Running Shorts", "Canvas Belt"],
    "Home": ["Desk Lamp", "Ceramic Mug", "Throw Pillow", "Candles", "Photo Frame"],
    "Sports": ["Yoga Mat", "Dumbbell Set", "Resistance Bands", "Water Bottle", "Jump Rope"],
    "Beauty": ["Moisturizer", "Face Serum", "Shampoo", "Lip Balm", "Sunscreen"],
    "Books": ["Sci-Fi Novel", "Cookbook", "Biography", "Self-Help", "Mystery"],
}
PRICES = {
    "Electronics": [25.0, 49.0, 299.0, 89.0, 120.0],
    "Clothing": [19.0, 65.0, 42.0, 28.0, 15.0],
    "Home": [35.0, 12.0, 24.0, 18.0, 9.0],
    "Sports": [22.0, 55.0, 15.0, 8.0, 11.0],
    "Beauty": [18.0, 34.0, 12.0, 6.0, 14.0],
    "Books": [16.0, 29.0, 19.0, 21.0, 13.0],
}

MISSING_TOTAL_ROW = 55   # 1-indexed order row -> cleaning suggestion (missing value)
OUTLIER_TOTAL_ROW = 112  # 1-indexed order row -> anomaly detection ($99999)
DUPLICATE_SOURCE_ROW = 29  # 1-indexed order row that gets an exact duplicate
BAD_EMAIL_ROWS = (7, 19)  # customer rows 1-indexed -> malformed emails

CUSTOMER_COUNT = 30
ORDER_COUNT = 180
MIN_LINE_ITEMS = 1
MAX_LINE_ITEMS = 3


def _customers(rng: random.Random) -> list[list[str | None]]:
    rows: list[list[str | None]] = []
    for i in range(1, CUSTOMER_COUNT + 1):
        cid = f"C{i:03d}"
        name = f"{rng.choice(FIRST_NAMES)} {rng.choice(LAST_NAMES)}"
        base = name.lower().replace(" ", ".")
        if i in BAD_EMAIL_ROWS:
            email = "bad-address@" if i == 7 else f"{base}@@example.com"
        else:
            email = f"{base}{i}@example.com"
        signup = dt.date(2023, 1, 1) + dt.timedelta(days=rng.randint(0, 330))
        rows.append([cid, name, email, signup.isoformat(), rng.choice(COUNTRIES)])
    return rows


def _orders(rng: random.Random) -> list[list[str | None]]:
    rows: list[list[str | None]] = []
    ord_counter = 10001
    for i in range(1, ORDER_COUNT + 1):
        oid = f"ORD-{ord_counter}"
        ord_counter += 1
        order_date = dt.date(2024, rng.randint(1, 6), rng.randint(1, 28))
        total = None if i == MISSING_TOTAL_ROW else None
        qty = rng.randint(1, 5)
        price = round(rng.uniform(5.0, 450.0), 2)
        total_value = round(price * qty, 2)
        if i == MISSING_TOTAL_ROW:
            total_value = None
        if i == OUTLIER_TOTAL_ROW:
            total_value = 99999.0
        rows.append([
            oid,
            order_date.isoformat(),
            f"C{rng.randint(1, 30):03d}",
            rng.choice(CATEGORIES),
            rng.choice(REGIONS),
            qty,
            price,
            total_value,
        ])
    dup = list(rows[DUPLICATE_SOURCE_ROW - 1])
    rows.append(dup)
    return rows


def _order_items(rng: random.Random, orders: list[list[str | None]]) -> list[list[object]]:
    rows: list[list[object]] = []
    icounter = 1
    for order in orders:
        oid = order[0]
        category = order[3]
        for _ in range(rng.randint(MIN_LINE_ITEMS, MAX_LINE_ITEMS)):
            line_category = category if rng.random() < 0.85 else rng.choice(CATEGORIES)
            product = rng.choice(PRODUCTS[line_category])
            price = rng.choice(PRICES[line_category])
            qty = rng.randint(1, 4)
            rows.append([f"OI-{icounter:04d}", oid, product, line_category, price, qty])
            icounter += 1
    return rows


def main() -> None:
    rng = random.Random(SEED)
    out_dir = Path(__file__).resolve().parent.parent / "samples"
    out_dir.mkdir(parents=True, exist_ok=True)

    customers = _customers(rng)
    orders = _orders(rng)
    items = _order_items(rng, orders)

    targets = [
        ("customers.csv", CUSTOMER_HEADER, customers),
        ("orders.csv", ORDERS_HEADER, orders),
        ("order_items.csv", ORDER_ITEMS_HEADER, items),
    ]
    for fname, header, data in targets:
        path = out_dir / fname
        with open(path, "w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh)
            writer.writerow(header)
            writer.writerows(data)
        print(f"{path}: {len(data)} data rows")

    print("\nGenerated with SEED=42. Rerun anytime to restore the canonical files.")


if __name__ == "__main__":
    main()