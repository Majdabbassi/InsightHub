# Screenshots & video — how these were made

All visuals are dark-themed (the app's default theme — no toggle needed) and are
captured against the seeded demo data. The steps below reproduce them exactly.

## Prereqs

1. Stack running + seeded: `./scripts/run-demo.sh` then `./scripts/seed-demo.sh`
   (Windows: `run-demo.ps1 -PullModel`, `seed-demo.ps1`). See
   [`docs/DEMO.md`](../DEMO.md).
2. Sign in as `demo@insighthub.dev` / `demo-password-123` and open the
   **E-commerce Demo** project.

## Dimensions

The README images render from the raw PNGs:

| File | Size | What it shows |
|---|---|---|
| `dashboard.png` | 1907×917 | Project **Dashboard** tab (auto-curated charts for `orders.csv`) |
| `analysis.png` | 1911×917 | **Analysis** tab for `orders.csv` (semantic roles, DQ score, anomalies) |
| `insights.png` | 1906×915 | **Insights** tab — Trends / Period comparison / Top performers |
| `cleaning.png` | 1907×910 | **Cleaning** tab with suggested fixes for `orders.csv` |
| `relationships.png` | 621×882 | **Relationships** diagram (cropped to the graph) |

The four widescreen shots were captured with the browser window at fullscreen on
a 1920×1080 display (app chrome + nginx-scoped URL `http://localhost:4200`,
login via the header). The relationship diagram was cropped tightly around the
graph area.

## Reproducing a screenshot

1. Navigate to the tab and confirm the expected content (see `docs/DEMO.md` for
   what each step should show).
2. Capture the browser window (PrtScr / Snipping Tool) **or** use
   `Capture Region` targeting the viewport.
3. Save as `docs/screenshots/<name>.png`, overwriting the tracked file.
4. Keep proportions close to the originals so the README table stays balanced.

## Recording the walkthrough video (Phase 8)

No screen recorder is bundled. Recommended (free, offline):

- Windows: **OBS Studio** — record the browser window at 1920×1080, then export
  as GIF/MP4.
- macOS/Linux: built-in `screencapture`/`ffmpeg` (`ffmpeg -f gdigrab` on Windows).

Suggested script (follow `docs/DEMO.md` 1–6 — roughly five minutes):

1. One-command start + seed
2. Sign in, open the project
3. Run **Analyze** on `orders.csv` — orbit through the result
4. Confirm relationships across the three samples
5. Walk through cleaning suggestions
6. Dashboard + insights
7. Ask the assistant a question

Keep the resolution ≤1280 wide for a reasonable GIF size.
