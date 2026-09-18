# Sample Data

Three small, synthetic e-commerce datasets that exercise every major feature
of the platform. They are generated deterministically by
[`scripts/generate_samples.py`](../scripts/generate_samples.py) — rerun it
anytime to restore the canonical files.

| File | Rows | Contents |
|---|---|---|
| `customers.csv` | 30 | Customer dimension: `customer_id`, `full_name`, `email`, `signup_date`, `country` |
| `orders.csv` | 181 | Fact table: `order_id`, `order_date`, `customer_id`, `product_category`, `region`, `quantity`, `unit_price`, `total` |
| `order_items.csv` | 353 | Line items: `order_item_id`, `order_id`, `product_name`, `product_category`, `unit_price`, `quantity` |

## What each dataset demonstrates

- **Semantic classification** — upload `orders.csv` and every column is
  classified into a semantic role (IDENTIFIER / TEMPORAL / CATEGORICAL /
  NUMERIC_DISCRETE / NUMERIC_CONTINUOUS) with confidence scores, not just a
  raw dtype.
- **Relationship detection** — upload all three and confirm the suggested
  foreign keys: `orders.customer_id → customers.customer_id` and
  `order_items.order_id → orders.order_id`. The diagram picks these up
  automatically after running the analysis.
- **Trend detection & period comparison** — `orders.csv` spans six months
  (Jan–Jun 2024) so the insights tab has a real time series to work with.
- **Anomaly detection** — one order has a deliberately inflated `total`
  (\$99999) so the anomalies panel has something to surface.
- **Cleaning suggestions** — a missing `total` value, the outlier, and two
  malformed customer emails (`bad-address@`, `@@example.com`) give the
  cleaning flow real material.
- **Duplicate detection** — one `orders.csv` row is an exact duplicate of
  another, which the data-quality analysis counts and flags.

## Try it

1. Run the app (see the README).
2. Create a project and upload all three files.
3. Run **Analyze** on each dataset.
4. Open the **Relationships** tab to confirm the suggested keys, then explore
   **Insights**, **Cleaning**, and **Dashboard**.