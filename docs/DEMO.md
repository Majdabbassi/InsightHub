# Demo Walkthrough

This is the story the project is meant to tell — from an empty shell to AI-assisted
insights — in about five minutes. Everything below works against the demo data in
[`samples/`](../samples/).

## 0. One-command start

**Linux/macOS:**

```bash
./scripts/run-demo.sh --pull-model
```

**Windows (PowerShell):**

```powershell
.\scripts\run-demo.ps1 -PullModel
```

The script creates `.env` (with a generated JWT secret) if missing, builds and
starts the stack, and waits for the backend health check. The `--pull-model` /
`-PullModel` flag downloads the local LLM (first run only). If you already have the
stack running, skip straight to seed:

```bash
./scripts/seed-demo.sh          # or: .\scripts\seed-demo.ps1
```

The seed script registers `demo@insighthub.dev / demo-password-123`, creates the
project **E-commerce Demo**, and uploads `customers.csv`, `orders.csv`, and
`order_items.csv`. Open http://localhost:4200 and sign in.

Prefer clicking? Then sign up manually and upload the three files from
[`samples/`](../samples/) into one project instead — same result.

## 1. Semantic analysis of a CSV

From the project, open **orders.csv** and click **Analyze**.

What you should see:

- **Every column labelled with a semantic role**, not just a raw type —
  `order_id` → IDENTIFIER, `order_date` → TEMPORAL, `product_category` →
  CATEGORICAL, `quantity` → NUMERIC_DISCRETE, `unit_price`/`total` →
  NUMERIC_CONTINUOUS — each with a confidence score and reasoning.
- **A Data Quality score** (0–100 with a letter grade) built from completeness,
  uniqueness, consistency, and validity.
- **Anomalies**: the dataset contains one deliberately inflated `total`
  (\$99999) — it should be flagged as an outlier, row-traceable.
- **Correlations**: `total` vs `unit_price` (and `quantity`) should register
  with |r| ≥ 0.5.

## 2. Relationships between datasets

Upload all three sample files, run **Analyze** on each, then open the
**Relationships** tab. The scanner proposes:

- `orders.customer_id → customers.customer_id` (foreign key)
- `order_items.order_id → orders.order_id` (foreign key)

Confirm them. The diagram is draggable, and confirmed keys unlock the project-level
insights (referential completeness, join aggregates).

## 3. Cleaning

Open **orders.csv** → **Cleaning**. The engine should find:

- A missing `total` value (filled with median vs. mean depending on skew).
- The \$99999 outlier (cap / remove / flag-only choices).
- Malformed emails in `customers.csv` (in `samples/` the rows `bad-address@` and
  `@@example.com`).

Apply a few suggestions. The result is saved as a **new dataset version** with
lineage back to the original — nothing is ever modified in place.

## 4. Dashboards & insights

- **Dashboard**: auto-curated charts for orders.csv (histograms for continuous
  measures, a time-series line for `order_date`, category breakdowns for
  `product_category`/`region`).
- **Insights → Trends**: linear-regression direction + strength + % change across
  the six months of order data.
- **Insights → Period comparison**: last vs. previous period with category-driven
  breakdowns.
- **Insights → Top/bottom performers**: rank `product_category` and `region` by
  `total`.

## 5. AI assistant

Open the assistant chat. With the demo project selected, try:

- *"Which product category has the highest total revenue?"*
- *"Show me orders by region as a chart."*

Because a local model (Ollama) is used, answers are answered on your machine. The
first request after an idle period can be slow while the model reloads — the
request timeout is configured generously for exactly this.

## 6. What not to expect

- No deployment — this is a local-first portfolio project.
- The IQR validity scoring weakens when contamination exceeds ~25% of a column;
  the demo data stays inside that envelope on purpose.
- The assistant is strongest with strict JSON-formatted query actions; a 3b model
  is the default, and `qwen2.5:7b` improves instruction-following if you have the
  RAM. See the README.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `backend` exits with "INSECURE secret" | `.env` was generated with the placeholder — set a real `JWT_SECRET` (≥32 bytes). The run scripts generate one automatically. |
| Assistant times out on first query | Model still loading; wait and retry — `OLLAMA_TIMEOUT_SECONDS` (default 180) covers a cold load. |
| Relationships tab is empty | Ensure **Analyze** completed on every dataset first; suggestions appear only after analysis. |
| phpMyAdmin is missing | It runs under the optional `tools` profile: `docker compose --profile tools up -d`. |
