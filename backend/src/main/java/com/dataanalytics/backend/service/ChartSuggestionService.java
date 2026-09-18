package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsColumnCorrelation;
import com.dataanalytics.backend.dto.AnalyticsColumnStats;
import com.dataanalytics.backend.dto.ChartSuggestion;
import com.dataanalytics.backend.model.AnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-logic service that generates curated, role-aware chart suggestions
 * from the analysis engine's semantic signals (roles, outliers, correlations)
 * plus the stored CSV for temporal span lookups. No analytics calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartSuggestionService {

    private final ObjectMapper objectMapper;
    private final ChartDataService chartDataService;

    /** How many non-scatter suggestions are featured by default (top 6-8). */
    static final int FEATURED_CHART_LIMIT = 7;
    private static final int CATEGORICAL_PIE_MAX_UNIQUE = 6;
    /**
     * CATEGORICAL columns above this cardinality get NO single-column chart.
     * A column can carry the CATEGORICAL role with 100+ distinct values when
     * classification took the low-cardinality-ratio fallback; such a chart is
     * unreadable, so the gate below is an explicit curation rule, not an
     * accident of the if/else chain. NUMERIC_DISCRETE/TEMPORAL/other roles
     * are unaffected.
     */
    private static final int CHART_CATEGORICAL_MAX_UNIQUE = 20;
    private static final int CROSS_COLUMN_MAX_UNIQUE = 15;
    private static final int CROSS_TOP_CATEGORICAL = 2;
    private static final int CROSS_TOP_NUMERIC = 2;
    private static final int MAX_CORRELATION_SCATTERS = 2;

    // Part 2 relevance weights.
    private static final double NEAR_CONSTANT_PENALTY = 0.3;
    private static final double NEAR_CONSTANT_TOP_SHARE = 95.0; // percent of rows
    private static final double CORRELATION_BOOST = 0.2;
    private static final double TEMPORAL_OUTLIER_BOOST = 0.15;
    private static final double CROSS_COLUMN_BOOST = 0.25;

    public List<ChartSuggestion> generateSuggestions(AnalysisResult result, Path filePath) {
        List<AnalyticsColumnStats> columns = parseColumns(result.getColumnStatsJson());
        if (columns.isEmpty()) {
            return List.of();
        }
        List<AnalyticsColumnCorrelation> correlations =
                parseCorrelations(result.getCorrelationsJson());

        Set<String> correlatedColumns = new HashSet<>();
        for (AnalyticsColumnCorrelation correlation : correlations) {
            correlatedColumns.add(correlation.columnA());
            correlatedColumns.add(correlation.columnB());
        }

        long rowCount = result.getRowCount();
        Map<String, Double> scoreByColumn = new HashMap<>();
        for (AnalyticsColumnStats col : columns) {
            scoreByColumn.put(col.name(), relevanceScore(col, rowCount, correlatedColumns));
        }

        // ===== Part 1: role-aware single-column charts =====

        List<ChartSuggestion> singles = new ArrayList<>();
        AnalyticsColumnStats primaryTemporal = null;
        AnalyticsColumnStats temporalMetricCandidate = null;

        for (AnalyticsColumnStats col : columns) {
            String role = col.semanticRole() == null ? "" : col.semanticRole();
            double score = scoreByColumn.getOrDefault(col.name(), 1.0);
            switch (role) {
                case "BOOLEAN" -> singles.add(new ChartSuggestion(
                        "pie_" + col.name(), "PIE",
                        col.name() + " distribution",
                        col.name(), null, "COUNT", false, score, false, null));
                case "CATEGORICAL" -> {
                    if (col.uniqueCount() <= CATEGORICAL_PIE_MAX_UNIQUE) {
                        singles.add(new ChartSuggestion(
                                "pie_" + col.name(), "PIE",
                                col.name() + " distribution",
                                col.name(), null, "COUNT", false, score, false, null));
                    } else if (col.uniqueCount() <= CHART_CATEGORICAL_MAX_UNIQUE) {
                        singles.add(new ChartSuggestion(
                                "bar_" + col.name(), "BAR",
                                col.name() + " counts",
                                col.name(), null, "COUNT", false, score, false, null));
                    }
                    // Explicit curation gate: CATEGORICAL columns with more
                    // than CHART_CATEGORICAL_MAX_UNIQUE distinct values (e.g.
                    // via the low-cardinality-ratio fallback) generate no
                    // single-column chart at all.
                }
                case "NUMERIC_DISCRETE" -> singles.add(new ChartSuggestion(
                        "bar_" + col.name(), "BAR",
                        col.name() + " counts",
                        col.name(), null, "COUNT", false, score, false, null));
                case "NUMERIC_CONTINUOUS" -> {
                    singles.add(new ChartSuggestion(
                            "hist_" + col.name(), "HISTOGRAM",
                            col.name() + " histogram",
                            col.name(), null, "COUNT", false, score, false, null));
                    if (temporalMetricCandidate == null
                            || col.missingCount() < temporalMetricCandidate.missingCount()) {
                        temporalMetricCandidate = col;
                    }
                }
                case "TEMPORAL" -> {
                    String period = temporalPeriodLabel(filePath, col.name());
                    String title = period == null
                            ? col.name() + " over time"
                            : col.name() + " over time (" + period + ")";
                    singles.add(new ChartSuggestion(
                            "line_" + col.name(), "LINE",
                            title,
                            col.name(), null, "COUNT", false, score, false, null));
                    if (primaryTemporal == null) {
                        primaryTemporal = col;
                    }
                }
                default -> {
                    // IDENTIFIER / CONSTANT / EMPTY / FREE_TEXT / INCONSISTENT:
                    // excluded from single-column charts entirely.
                }
            }
        }

        // Second metric-based line chart for the primary temporal column:
        // sum of the least-missing numeric column per period (max 1 per dataset).
        if (primaryTemporal != null && temporalMetricCandidate != null) {
            String period = temporalPeriodLabel(filePath, primaryTemporal.name());
            if (period == null) {
                period = "";
            }
            singles.add(new ChartSuggestion(
                    "line_" + primaryTemporal.name() + "_" + temporalMetricCandidate.name(),
                    "LINE",
                    "Sum of " + temporalMetricCandidate.name() + " over time"
                            + (period.isBlank() ? "" : " (" + period + ")"),
                    primaryTemporal.name(), temporalMetricCandidate.name(), "SUM",
                    false, scoreByColumn.getOrDefault(temporalMetricCandidate.name(), 1.0),
                    true, null));
        }

        // ===== Part 3: cross-column categorical x numeric bars =====

        Comparator<AnalyticsColumnStats> byScoreDesc = Comparator.comparingDouble(
                (AnalyticsColumnStats c) -> scoreByColumn.getOrDefault(c.name(), 1.0)).reversed();

        List<AnalyticsColumnStats> crossCategories = columns.stream()
                .filter(c -> "CATEGORICAL".equals(c.semanticRole()))
                .filter(c -> c.uniqueCount() >= 1 && c.uniqueCount() <= CROSS_COLUMN_MAX_UNIQUE)
                .sorted(byScoreDesc)
                .limit(CROSS_TOP_CATEGORICAL)
                .toList();
        List<AnalyticsColumnStats> crossNumerics = columns.stream()
                .filter(c -> "NUMERIC_CONTINUOUS".equals(c.semanticRole())
                        || "NUMERIC_DISCRETE".equals(c.semanticRole()))
                .sorted(byScoreDesc)
                .limit(CROSS_TOP_NUMERIC)
                .toList();

        List<ChartSuggestion> crosses = new ArrayList<>();
        for (AnalyticsColumnStats cat : crossCategories) {
            for (AnalyticsColumnStats num : crossNumerics) {
                if (cat.name().equals(num.name())) {
                    continue;
                }
                double pairScore = Math.min(
                        round2(scoreByColumn.getOrDefault(cat.name(), 1.0)),
                        round2(scoreByColumn.getOrDefault(num.name(), 1.0)))
                        + CROSS_COLUMN_BOOST;
                crosses.add(new ChartSuggestion(
                        "cross_bar_" + num.name() + "_by_" + cat.name(),
                        "BAR",
                        "Sum of " + num.name() + " by " + cat.name(),
                        cat.name(), num.name(), "SUM",
                        false, round2(pairScore), true, null));
            }
        }

        // ===== Part 4: scatter charts from the strongest correlations =====

        List<ChartSuggestion> scatters = new ArrayList<>();
        correlations.stream()
                .filter(c -> !c.lowConfidence())
                .sorted(Comparator.comparingDouble(
                        (AnalyticsColumnCorrelation c) -> Math.abs(c.correlation())).reversed())
                .limit(MAX_CORRELATION_SCATTERS)
                .forEach(c -> {
                    double displayScore = round2(Math.max(
                            scoreByColumn.getOrDefault(c.columnA(), 1.0),
                            scoreByColumn.getOrDefault(c.columnB(), 1.0))
                            + CORRELATION_BOOST);
                    scatters.add(new ChartSuggestion(
                            "scatter_" + c.columnA() + "_" + c.columnB(),
                            "SCATTER",
                            c.columnA() + " vs " + c.columnB()
                                    + " (r = " + formatR(c.correlation()) + ")",
                            c.columnA(), c.columnB(), "NONE",
                            true, displayScore, false, c.correlation()));
                });

        // ===== Part 2: featured selection =====
        // Scatter charts are always featured; everything else is ranked by
        // relevance and the top N are flagged. All eligible charts are returned.

        List<ChartSuggestion> ranked = new ArrayList<>(singles);
        ranked.addAll(crosses);

        List<String> featuredIds = new ArrayList<>();
        ranked.stream()
                .sorted(Comparator.comparingDouble(ChartSuggestion::relevanceScore).reversed())
                .limit(FEATURED_CHART_LIMIT)
                .forEach(s -> featuredIds.add(s.id()));

        ranked.addAll(scatters);

        List<ChartSuggestion> output = new ArrayList<>();
        for (ChartSuggestion suggestion : ranked) {
            boolean featured = "SCATTER".equals(suggestion.type())
                    || featuredIds.contains(suggestion.id());
            output.add(new ChartSuggestion(
                    suggestion.id(), suggestion.type(), suggestion.title(),
                    suggestion.xColumn(), suggestion.yColumn(), suggestion.aggregation(),
                    featured, suggestion.relevanceScore(),
                    suggestion.crossColumn(), suggestion.correlation()));
        }
        return output;
    }

    /**
     * Part 2 relevance score: starts at 1.0, penalizes missingness and
     * near-constant low-variance columns, boosts correlated, temporal and
     * outlier-bearing numeric columns.
     */
    private double relevanceScore(
            AnalyticsColumnStats col, long rowCount, Set<String> correlatedColumns) {
        double score = 1.0;
        score -= col.missingPercentage() / 100.0;

        String role = col.semanticRole() == null ? "" : col.semanticRole();
        boolean penalizableRole = "CATEGORICAL".equals(role)
                || "NUMERIC_DISCRETE".equals(role);
        if (penalizableRole && col.uniqueCount() == 2 && rowCount > 0
                && topValueSharePercent(col, rowCount) > NEAR_CONSTANT_TOP_SHARE) {
            score -= NEAR_CONSTANT_PENALTY;
        }

        if (correlatedColumns.contains(col.name())) {
            score += CORRELATION_BOOST;
        }
        if ("TEMPORAL".equals(role)) {
            score += TEMPORAL_OUTLIER_BOOST;
        }
        if ("NUMERIC_CONTINUOUS".equals(role)
                && col.outlierAnalysis() != null
                && col.outlierAnalysis().outlierCount() > 0) {
            score += TEMPORAL_OUTLIER_BOOST;
        }
        return round2(score);
    }

    private double topValueSharePercent(AnalyticsColumnStats col, long rowCount) {
        if (col.topValues() == null || col.topValues().isEmpty()) {
            return 0.0;
        }
        long count = col.topValues().get(0).count();
        return 100.0 * count / rowCount;
    }

    /** Day/week/month/quarter label for a temporal column, or null without file access. */
    private String temporalPeriodLabel(Path filePath, String columnName) {
        if (filePath == null) {
            return null;
        }
        try {
            Optional<LocalDate[]> span = chartDataService.dateSpan(filePath, columnName);
            return span.map(range -> ChartDataService.periodLabel(range[0], range[1]))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not derive date span for {}: {}", columnName, e.getMessage());
            return null;
        }
    }

    private static String formatR(double r) {
        String text = String.format(Locale.US, "%.2f", r);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public List<AnalyticsColumnStats> parseColumns(String columnStatsJson) {
        try {
            return objectMapper.readValue(
                    columnStatsJson,
                    new TypeReference<List<AnalyticsColumnStats>>() {});
        } catch (Exception e) {
            log.error("Failed to parse columnStatsJson", e);
            return List.of();
        }
    }

    public List<AnalyticsColumnCorrelation> parseCorrelations(String correlationsJson) {
        if (correlationsJson == null || correlationsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    correlationsJson,
                    new TypeReference<List<AnalyticsColumnCorrelation>>() {});
        } catch (Exception e) {
            log.error("Failed to parse correlationsJson", e);
            return List.of();
        }
    }
}
