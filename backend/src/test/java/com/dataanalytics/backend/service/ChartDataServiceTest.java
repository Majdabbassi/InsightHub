package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.ChartDataResponse;
import com.dataanalytics.backend.exception.InvalidFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for chart/aggregation logic. Each test writes a small CSV
 * into a temp dir and reads it back through CsvSupport - no Spring context.
 */
class ChartDataServiceTest {

    private final ChartDataService service = new ChartDataService();

    @TempDir
    Path dir;

    private Path csv(String content) throws Exception {
        Path file = dir.resolve("data" + System.nanoTime() + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void counts_sortsByFrequencyAndKeepsEmptyBucket() throws Exception {
        Path file = csv("""
                category
                a
                b
                a
                x
                y
                """);
        // Non-numeric labels: sorted by count descending (a=2 first, then ties).
        ChartDataResponse data = service.computeAggregation(file, "category", null, "PIE", "COUNT", null);
        assertThat(data.labels().get(0)).isEqualTo("a");
        assertThat(data.values()).containsExactly(2L, 1L, 1L, 1L);
    }

    @Test
    void counts_placesNumericLabelsInValueOrder() throws Exception {
        Path file = csv("v\n10\n2\n1\n10\n2\n2\n");
        ChartDataResponse data = service.computeAggregation(file, "v", null, "PIE", "COUNT", null);
        assertThat(data.labels()).containsSubsequence("1", "2", "10");
        assertThat(data.values()).containsExactly(1L, 3L, 2L);
    }

    @Test
    void barChart_sumsByCategoryDescending() throws Exception {
        Path file = csv("region,sales\nNorth,10\nNorth,20\nSouth,5\n");
        ChartDataResponse data = service.computeAggregation(
                file, "region", "sales", "BAR", "SUM", null);
        assertThat(data.labels()).containsExactly("North", "South");
        assertThat(data.values()).containsExactly(30L, 5L);
    }

    @Test
    void barChart_rejectsUnknownAggregation() throws Exception {
        Path file = csv("region,sales\nNorth,10\n");
        assertThatThrownBy(() -> service.computeAggregation(
                file, "region", "sales", "BAR", "MEDIAN", null))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void lineChart_rollsDatesUpByDay() throws Exception {
        Path file = csv("date,amount\n2024-01-01,5\n2024-01-01,7\n2024-01-02,3\n");
        ChartDataResponse data = service.computeAggregation(
                file, "date", "amount", "LINE", "SUM", null);
        assertThat(data.labels()).containsExactly("2024-01-01", "2024-01-02");
        assertThat(data.values()).containsExactly(12L, 3L);
    }

    @Test
    void lineChart_usesWeeklyBucketsForLongerSpans() throws Exception {
        Path file = csv("date\n2024-01-01\n2024-02-01\n");
        ChartDataResponse data = service.computeAggregation(
                file, "date", null, "LINE", "COUNT", null);
        assertThat(data.labels()).allMatch(label -> label.matches("2024-W\\d{2}"));
        assertThat(data.values().stream().mapToLong(Number::longValue).sum()).isEqualTo(2L);
    }

    @Test
    void lineChart_fallsBackToStringKeysWhenUndated() throws Exception {
        Path file = csv("bucket\nx\nx\ny\n");
        ChartDataResponse data = service.computeAggregation(
                file, "bucket", null, "LINE", "COUNT", null);
        assertThat(data.labels()).containsExactlyInAnyOrder("x", "y");
        assertThat(data.values()).containsExactlyInAnyOrder(2L, 1L);
    }

    @Test
    void lineChart_skipsRowsWithoutUsableMetric() throws Exception {
        Path file = csv("date,amount\n2024-01-01,5\n2024-01-02,oops\n2024-01-02,3\n");
        ChartDataResponse data = service.computeAggregation(
                file, "date", "amount", "LINE", "SUM", null);
        assertThat(data.labels()).containsExactly("2024-01-01", "2024-01-02");
        assertThat(data.values()).containsExactly(5L, 3L);
    }

    @Test
    void histogram_binsValuesPreservingTotal() throws Exception {
        Path file = csv("v\n1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n");
        ChartDataResponse data = service.computeAggregation(file, "v", null, "HISTOGRAM", null, null);
        assertThat(data.values().stream().mapToLong(Number::longValue).sum()).isEqualTo(10L);
        assertThat(data.labels()).isNotEmpty();
    }

    @Test
    void histogram_degenerateRange_collapsesToOneBin() throws Exception {
        Path file = csv("v\n5\n5\n5\n");
        ChartDataResponse data = service.computeAggregation(file, "v", null, "HISTOGRAM", null, null);
        assertThat(data.labels()).containsExactly("5");
        assertThat(data.values()).containsExactly(3);
    }

    @Test
    void scatter_returnsPairedPointsSkippingBadRows() throws Exception {
        Path file = csv("x,y\n1,2\n3,4\n5,\n,8\n7,9\n");
        ChartDataResponse data = service.computeAggregation(file, "x", "y", "SCATTER", null, null);
        assertThat(data.points()).hasSize(3);
        assertThat(data.points()).containsExactly(
                new ChartDataResponse.Point(1, 2),
                new ChartDataResponse.Point(3, 4),
                new ChartDataResponse.Point(7, 9));
    }

    @Test
    void scatter_requiresTwoColumns() throws Exception {
        Path file = csv("x\n1\n");
        assertThatThrownBy(() -> service.computeAggregation(file, "x", null, "SCATTER", null, null))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void chartWithMissingColumn_reportsColumnName() throws Exception {
        Path file = csv("a\n1\n");
        assertThatThrownBy(() -> service.computeAggregation(file, "nope", null, "PIE", "COUNT", null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Column not found: nope");
    }

    @Test
    void dateSpan_returnsMinMaxForDateColumn() throws Exception {
        Path file = csv("d\n2024-03-01\n2024-01-15\n2024-02-01\n");
        LocalDate[] span = service.dateSpan(file, "d").orElseThrow();
        assertThat(span[0]).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(span[1]).isEqualTo(LocalDate.of(2024, 3, 1));
    }

    @Test
    void dateSpan_isEmptyWhenNothingParses() throws Exception {
        Path file = csv("d\nnope\nalso no\n");
        assertThat(service.dateSpan(file, "d")).isEmpty();
    }

    @Test
    void periodLabel_matchesSpanLength() {
        assertThat(ChartDataService.periodLabel(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 15))).isEqualTo("day");
        assertThat(ChartDataService.periodLabel(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 15))).isEqualTo("week");
        assertThat(ChartDataService.periodLabel(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 5, 1))).isEqualTo("month");
        assertThat(ChartDataService.periodLabel(
                LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1))).isEqualTo("quarter");
    }

    @Test
    void parseDate_acceptsIsoUsShortAndTimestamps() {
        assertThat(ChartDataService.parseDate("2024-01-15")).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(ChartDataService.parseDate("1/15/2024")).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(ChartDataService.parseDate("1/15/24")).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(ChartDataService.parseDate("2024-01-15T10:30:00"))
                .isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(ChartDataService.parseDate(null)).isNull();
        assertThat(ChartDataService.parseDate("  ")).isNull();
        assertThat(ChartDataService.parseDate("garbage")).isNull();
    }

    @Test
    void tryDouble_acceptsNumbersWithWhitespaceOnly() {
        assertThat(ChartDataService.tryDouble("12")).isEqualTo(12.0);
        assertThat(ChartDataService.tryDouble(" 3.5 ")).isEqualTo(3.5);
        assertThat(ChartDataService.tryDouble("")).isNull();
        // Double.NaN echoes back as NaN (a real parsed double, not null).
        assertThat(ChartDataService.tryDouble("NaN").doubleValue()).isNaN();
        assertThat(ChartDataService.tryDouble(null)).isNull();
    }

    @Test
    void aggregate_supportsSumAvgCount() {
        assertThat(ChartDataService.aggregate(List.of(1.0, 2.0, 3.0), "SUM")).isEqualTo(6.0);
        assertThat(ChartDataService.aggregate(List.of(1.0, 2.0, 3.0), "AVG")).isEqualTo(2.0);
        assertThat(ChartDataService.aggregate(List.of(1.0, 2.0), "COUNT")).isEqualTo(2.0);
        assertThat(ChartDataService.aggregate(List.of(), "SUM")).isEqualTo(0.0);
    }

    @Test
    void niceStep_roundsUpToReadableValues() {
        assertThat(ChartDataService.niceStep(7)).isEqualTo(10.0);
        assertThat(ChartDataService.niceStep(1.4)).isEqualTo(2.0);
        assertThat(ChartDataService.niceStep(0)).isEqualTo(1.0);
    }
}