package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.ChartDataResponse;
import com.dataanalytics.backend.exception.InvalidFileException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads the stored CSV file and computes on-the-fly aggregations for chart
 * rendering: role-aware counts, histograms, temporal rollups (day/week/
 * month/quarter), cross-column aggregates and scatter pairs.
 */
@Service
@Slf4j
public class ChartDataService {

    /** Cap on scatter points returned; sampled evenly when exceeded. */
    private static final int MAX_SCATTER_POINTS = 5000;

    /** Cap on category labels for count-based charts, for readability. */
    private static final int MAX_CATEGORY_LABELS = 20;

    /** Target bin count for histograms; reduced automatically for small ranges. */
    private static final int HISTOGRAM_TARGET_BINS = 10;

    // ===== Public entry points =====

    /**
     * Min/max parsed dates of a column, used to choose the temporal grouping
     * period when building LINE chart suggestions. Empty when no value in the
     * column parses as a date.
     */
    public Optional<LocalDate[]> dateSpan(Path filePath, String column) {
        return readCsv(filePath, parser -> {
            requireColumns(parser, column);
            LocalDate min = null;
            LocalDate max = null;
            for (CSVRecord record : parser) {
                LocalDate date = parseDate(record.get(column));
                if (date == null) {
                    continue;
                }
                if (min == null || date.isBefore(min)) {
                    min = date;
                }
                if (max == null || date.isAfter(max)) {
                    max = date;
                }
            }
            return min == null
                    ? Optional.<LocalDate[]>empty()
                    : Optional.of(new LocalDate[]{min, max});
        });
    }

    /** Lowercase human label for the period chosen between two dates. */
    public static String periodLabel(LocalDate min, LocalDate max) {
        return switch (periodForSpan(ChronoUnit.DAYS.between(min, max) + 1)) {
            case "DAY" -> "day";
            case "WEEK" -> "week";
            case "MONTH" -> "month";
            default -> "quarter";
        };
    }

    public ChartDataResponse computeAggregation(
            Path filePath, String xColumn, String yColumn,
            String type, String aggregation, String xRole) {
        String agg = aggregation == null ? "COUNT" : aggregation.trim().toUpperCase(Locale.ROOT);
        boolean discreteX = "NUMERIC_DISCRETE".equals(xRole);
        return switch (type) {
            case "SCATTER" -> scatter(filePath, xColumn, yColumn);
            case "HISTOGRAM" -> histogram(filePath, xColumn);
            case "LINE" -> lineChart(filePath, xColumn, yColumn, agg);
            case "BAR" -> barChart(filePath, xColumn, yColumn, agg, discreteX);
            default -> counts(filePath, xColumn, discreteX); // PIE and legacy requests
        };
    }

    // ===== Chart implementations =====

    /** Single-column counts: pies, categorical bars, discrete-value bars. */
    private ChartDataResponse counts(Path filePath, String column, boolean numericDiscrete) {
        return readCsv(filePath, parser -> {
            requireColumns(parser, column);
            Map<String, Long> tally = new LinkedHashMap<>();
            for (CSVRecord record : parser) {
                String key = record.get(column);
                if (key == null || key.isBlank()) {
                    key = "(empty)";
                }
                tally.merge(key, 1L, Long::sum);
            }
            List<Map.Entry<String, Long>> entries = new ArrayList<>(tally.entrySet());
            if (numericDiscrete) {
                // NUMERIC_DISCRETE bars only read as a distribution when the
                // x-axis follows numeric order; unparseable stragglers (e.g.
                // '(empty)' from blank cells) sort to the end so invalid
                // values surface as one distinct cluster instead of being
                // scattered by frequency. A simple all-labels-numeric check
                // would break on exactly those stragglers, hence role-driven.
                entries.sort((a, b) -> {
                    Double da = tryDouble(a.getKey());
                    Double db = tryDouble(b.getKey());
                    if (da != null && db != null) {
                        return Double.compare(da, db);
                    }
                    if (da != null) {
                        return -1;
                    }
                    if (db != null) {
                        return 1;
                    }
                    return a.getKey().compareTo(b.getKey());
                });
            } else {
                boolean allNumeric =
                        entries.stream().allMatch(e -> tryDouble(e.getKey()) != null);
                if (allNumeric) {
                    // Discrete numeric values read best ordered by value.
                    entries.sort(Map.Entry.comparingByKey(
                            (a, b) -> Double.compare(tryDouble(a), tryDouble(b))));
                } else {
                    entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
                }
            }
            List<String> labels = new ArrayList<>();
            List<Number> values = new ArrayList<>();
            entries.stream().limit(MAX_CATEGORY_LABELS).forEach(e -> {
                labels.add(e.getKey());
                values.add(e.getValue());
            });
            return new ChartDataResponse(labels, values);
        });
    }

    /**
     * Cross-column bars: group by the categorical x value and aggregate the
     * numeric y value with SUM or AVG, sorted by aggregate descending.
     * Falls back to plain counts when no numeric y/aggregation is requested.
     */
    private ChartDataResponse barChart(
            Path filePath, String xColumn, String yColumn,
            String aggregation, boolean numericDiscrete) {
        if (yColumn == null || "COUNT".equals(aggregation)) {
            return counts(filePath, xColumn, numericDiscrete);
        }
        if (!"SUM".equals(aggregation) && !"AVG".equals(aggregation)) {
            throw new InvalidFileException("Unsupported aggregation: " + aggregation);
        }
        return readCsv(filePath, parser -> {
            requireColumns(parser, xColumn, yColumn);
            Map<String, List<Double>> groups = new LinkedHashMap<>();
            for (CSVRecord record : parser) {
                Double value = tryDouble(record.get(yColumn));
                if (value == null) {
                    continue;
                }
                String key = record.get(xColumn);
                if (key == null || key.isBlank()) {
                    key = "(empty)";
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
            List<Map.Entry<String, List<Double>>> entries = new ArrayList<>(groups.entrySet());
            final String agg = aggregation;
            entries.sort((a, b) -> Double.compare(
                    aggregate(b.getValue(), agg), aggregate(a.getValue(), agg)));
            List<String> labels = new ArrayList<>();
            List<Number> values = new ArrayList<>();
            entries.stream().limit(MAX_CATEGORY_LABELS).forEach(e -> {
                labels.add(e.getKey());
                values.add(round(aggregate(e.getValue(), agg)));
            });
            return new ChartDataResponse(labels, values);
        });
    }

    /**
     * LINE charts: TEMPORAL columns are rolled up by day/week/month/quarter
     * according to their date span; y is row count per period by default or an
     * aggregate of the given numeric column. Data without parseable dates
     * falls back to plain string-keyed grouping.
     */
    private ChartDataResponse lineChart(
            Path filePath, String xColumn, String yColumn, String aggregation) {
        final boolean counting = yColumn == null || "COUNT".equals(aggregation);
        if (!counting && !"SUM".equals(aggregation) && !"AVG".equals(aggregation)) {
            throw new InvalidFileException("Unsupported aggregation: " + aggregation);
        }
        return readCsv(filePath, parser -> {
            requireColumns(parser, xColumn, yColumn);

            List<LocalDate> dates = new ArrayList<>();
            List<Double> metrics = new ArrayList<>();
            Map<String, List<Double>> legacyGroups = new LinkedHashMap<>();

            for (CSVRecord record : parser) {
                // NB: no ternary here - mixing primitive 1.0 with tryDouble's
                // Double would auto-unbox a null and NPE before the guard below.
                Double metric = 1.0;
                if (!counting) {
                    metric = tryDouble(record.get(yColumn));
                    if (metric == null) {
                        continue; // rows without a usable metric value
                    }
                }
                LocalDate date = parseDate(record.get(xColumn));
                if (date != null) {
                    dates.add(date);
                    metrics.add(metric);
                } else {
                    String key = record.get(xColumn);
                    if (key == null || key.isBlank()) {
                        key = "(empty)";
                    }
                    legacyGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(metric);
                }
            }

            List<String> labels = new ArrayList<>();
            List<Number> values = new ArrayList<>();
            final String agg = counting ? "COUNT" : aggregation;

            if (!dates.isEmpty()) {
                LocalDate min = dates.get(0);
                LocalDate max = dates.get(0);
                for (LocalDate d : dates) {
                    if (d.isBefore(min)) min = d;
                    if (d.isAfter(max)) max = d;
                }
                long spanDays = ChronoUnit.DAYS.between(min, max) + 1;
                String period = periodForSpan(spanDays);

                Map<String, List<Double>> buckets = new TreeMap<>();
                for (int i = 0; i < dates.size(); i++) {
                    buckets.computeIfAbsent(bucketKey(dates.get(i), period), k -> new ArrayList<>())
                            .add(metrics.get(i));
                }
                for (Map.Entry<String, List<Double>> entry : buckets.entrySet()) {
                    labels.add(entry.getKey());
                    values.add(round(aggregate(entry.getValue(), agg)));
                }
                return new ChartDataResponse(labels, values);
            }

            for (Map.Entry<String, List<Double>> entry : legacyGroups.entrySet()) {
                labels.add(entry.getKey());
                values.add(round(aggregate(entry.getValue(), agg)));
            }
            return new ChartDataResponse(labels, values);
        });
    }

    /**
     * HISTOGRAM: numeric values binned across [min, max] using a "nice" step
     * size so bins stay readable; degenerate ranges collapse to one bin.
     */
    private ChartDataResponse histogram(Path filePath, String column) {
        return readCsv(filePath, parser -> {
            requireColumns(parser, column);
            List<Double> parsed = new ArrayList<>();
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (CSVRecord record : parser) {
                Double value = tryDouble(record.get(column));
                if (value == null) {
                    continue;
                }
                parsed.add(value);
                if (value < min) min = value;
                if (value > max) max = value;
            }
            if (parsed.isEmpty()) {
                return new ChartDataResponse(List.of(), List.of());
            }
            if (!(max > min)) {
                // All values identical (or a single value): one honest bin.
                return new ChartDataResponse(List.of(formatNumber(min, 1.0)), List.of(parsed.size()));
            }

            double range = max - min;
            double step = niceStep(range / HISTOGRAM_TARGET_BINS);
            int binCount = (int) Math.ceil(range / step - 1e-9);
            if (binCount > HISTOGRAM_TARGET_BINS) {
                step = range / HISTOGRAM_TARGET_BINS;
                binCount = HISTOGRAM_TARGET_BINS;
            }

            long[] bins = new long[binCount];
            for (double value : parsed) {
                int index = (int) Math.floor((value - min) / step);
                if (index >= binCount) index = binCount - 1; // last bin includes max
                if (index < 0) index = 0;
                bins[index]++;
            }

            List<String> labels = new ArrayList<>();
            List<Number> values = new ArrayList<>();
            for (int i = 0; i < binCount; i++) {
                double low = min + i * step;
                double high = Math.min(min + (i + 1) * step, max);
                labels.add(formatBinLabel(low, high, i == binCount - 1));
                values.add(bins[i]);
            }
            return new ChartDataResponse(labels, values);
        });
    }

    /** SCATTER: paired (x, y) points; rows where either side is unusable are skipped. */
    private ChartDataResponse scatter(Path filePath, String xColumn, String yColumn) {
        if (yColumn == null) {
            throw new InvalidFileException("Scatter charts need two numeric columns.");
        }
        return readCsv(filePath, parser -> {
            requireColumns(parser, xColumn, yColumn);
            List<ChartDataResponse.Point> points = new ArrayList<>();
            for (CSVRecord record : parser) {
                Double x = tryDouble(record.get(xColumn));
                Double y = tryDouble(record.get(yColumn));
                if (x == null || y == null) {
                    continue;
                }
                points.add(new ChartDataResponse.Point(x, y));
            }
            if (points.size() > MAX_SCATTER_POINTS) {
                List<ChartDataResponse.Point> sampled = new ArrayList<>(MAX_SCATTER_POINTS);
                double stride = (double) points.size() / MAX_SCATTER_POINTS;
                for (double i = 0; (int) i < points.size() && sampled.size() < MAX_SCATTER_POINTS;
                        i += stride) {
                    sampled.add(points.get((int) i));
                }
                points = sampled;
            }
            return new ChartDataResponse(List.of(), List.of(), points);
        });
    }

    // ===== Helpers =====

    @FunctionalInterface
    private interface CsvFunction<T> {
        T apply(CSVParser parser) throws Exception;
    }

    private <T> T readCsv(Path filePath, CsvFunction<T> function) {
        try (CSVParser parser = CsvSupport.open(filePath)) {
            return function.apply(parser);
        } catch (InvalidFileException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to compute chart data", e);
            throw new InvalidFileException("Could not read the dataset file for chart data.");
        }
    }

    private static void requireColumns(CSVParser parser, String... names) {
        Map<String, Integer> headerMap = parser.getHeaderMap();
        if (headerMap == null) {
            throw new InvalidFileException("Dataset has no header row.");
        }
        for (String name : names) {
            if (name != null && !headerMap.containsKey(name)) {
                throw new InvalidFileException("Column not found: " + name);
            }
        }
    }

    /**
     * Temporal grouping rule: <=31 days by day; up to ~90 days by week;
     * up to two years by month; longer spans by quarter.
     */
    private static String periodForSpan(long spanDays) {
        if (spanDays <= 31) {
            return "DAY";
        }
        if (spanDays <= 90) {
            return "WEEK";
        }
        if (spanDays <= 730) {
            return "MONTH";
        }
        return "QUARTER";
    }

    /** Lexicographically sortable bucket key for the given period. */
    private static String bucketKey(LocalDate date, String period) {
        return switch (period) {
            case "WEEK" -> String.format(Locale.US, "%04d-W%02d",
                    date.get(IsoFields.WEEK_BASED_YEAR),
                    date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "MONTH" -> String.format(Locale.US, "%04d-%02d",
                    date.getYear(), date.getMonthValue());
            case "QUARTER" -> date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
            default -> date.toString();
        };
    }

    /** Accepts ISO dates, ISO timestamps and common US-style dates. */
    private static final DateTimeFormatter US_DATE =
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);
    private static final DateTimeFormatter US_DATE_SHORT_YEAR =
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US);

    static LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to other formats
        }
        if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(10) == 'T') {
            try {
                return LocalDate.parse(value.substring(0, 10));
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        try {
            return LocalDate.parse(value, US_DATE);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value, US_DATE_SHORT_YEAR);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        return null;
    }

    static Double tryDouble(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double aggregate(List<Double> nums, String aggregation) {
        if (nums.isEmpty()) {
            return 0;
        }
        return switch (aggregation) {
            case "SUM" -> nums.stream().mapToDouble(Double::doubleValue).sum();
            case "AVG" -> nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            default -> nums.size(); // COUNT: each stored entry represents one row
        };
    }

    /** Integral results serialize as clean JSON integers; others keep 4 decimals. */
    private static Number round(double value) {
        double rounded = Math.round(value * 10000.0) / 10000.0;
        if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
            return (long) rounded;
        }
        return rounded;
    }

    /** Rounds a raw width up to a readable step from {1, 2, 2.5, 5} x 10^k. */
    static double niceStep(double rawWidth) {
        if (!(rawWidth > 0) || !Double.isFinite(rawWidth)) {
            return 1.0;
        }
        double exponent = Math.floor(Math.log10(rawWidth));
        double base = Math.pow(10, exponent);
        double fraction = rawWidth / base;
        double[] steps = {1, 2, 2.5, 5};
        for (double candidate : steps) {
            if (fraction <= candidate + 1e-9) {
                return candidate * base;
            }
        }
        return 10 * base;
    }

    private static String formatBinLabel(double low, double high, boolean last) {
        String closing = last ? "]" : ")";
        return "[" + formatNumber(low, high - low) + "–"
                + formatNumber(high, high - low) + closing;
    }

    /** Formats a number with just enough decimals to distinguish the given step. */
    static String formatNumber(double value, double step) {
        int decimals = 0;
        double scaled = Math.abs(step);
        while (decimals < 6 && scaled > 0
                && Math.abs(scaled - Math.rint(scaled)) > 1e-9) {
            scaled *= 10;
            decimals++;
        }
        String text = String.format(Locale.US, "%." + decimals + "f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "-0".equals(text) ? "0" : text;
    }
}
