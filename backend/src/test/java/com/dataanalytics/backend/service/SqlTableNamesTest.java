package com.dataanalytics.backend.service;

import com.dataanalytics.backend.model.Dataset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SQL-safe table naming. The mapping here MUST stay in lockstep
 * with the analytics service's duckdb_query.py (sanitize_name/build_table_map);
 * any drift silently breaks AI query execution.
 */
class SqlTableNamesTest {

    private static Dataset dataset(Long id, String name, String originalFilename) {
        return Dataset.builder().id(id).name(name).originalFilename(originalFilename).build();
    }

    @Test
    void sanitize_stripsExtensionAndLowercases() {
        assertThat(SqlTableNames.sanitize("Sales Report.CSV")).isEqualTo("sales_report");
    }

    @Test
    void sanitize_keepsUnderscoresAndDigits() {
        assertThat(SqlTableNames.sanitize("order_items_2024.csv")).isEqualTo("order_items_2024");
    }

    @Test
    void sanitize_handlesSpacesSlashesAndDashes() {
        assertThat(SqlTableNames.sanitize("2024/Sales - Q1.csv")).isEqualTo("t_2024_sales___q1");
        // Leading digit gets a t_ prefix so the identifier stays SQL-safe.
        assertThat(SqlTableNames.sanitize("2024_sales_q1")).isEqualTo("t_2024_sales_q1");
        assertThat(SqlTableNames.sanitize("Q1 Sales.csv")).isEqualTo("q1_sales");
    }

    @Test
    void sanitize_trimsUnderscoresAndFallsBack() {
        assertThat(SqlTableNames.sanitize("__Q1 Sales__.csv")).isEqualTo("q1_sales");
        assertThat(SqlTableNames.sanitize(".csv")).isEqualTo("table");
        assertThat(SqlTableNames.sanitize("_")).isEqualTo("table");
        assertThat(SqlTableNames.sanitize(null)).isEqualTo("table");
    }

    @Test
    void buildMapping_suffixesCollisionsDeterministically() {
        Map<String, String> mapping = SqlTableNames.buildMapping(
                List.of("sales.csv", "Sales.csv", "sales (2).csv", "payments.csv"));
        // "(2)" is part of the stem; adjacent separators collapse into one underscore.
        assertThat(mapping.keySet()).containsExactly(
                "sales", "sales_2", "sales__2", "payments");
        assertThat(mapping.get("sales")).isEqualTo("sales.csv");
        assertThat(mapping.get("sales_2")).isEqualTo("Sales.csv");
        assertThat(mapping.get("sales__2")).isEqualTo("sales (2).csv");
    }

    @Test
    void uniqueTableNames_stableByDatasetOrder() {
        List<Dataset> datasets = List.of(
                dataset(10L, "sales.csv", "sales.csv"),
                dataset(11L, "Sales.csv", "Sales.csv"),
                dataset(12L, "payments.csv", null));
        Map<Long, String> names = SqlTableNames.uniqueTableNames(datasets);
        assertThat(names).containsExactly(
                Map.entry(10L, "sales"),
                Map.entry(11L, "sales_2"),
                Map.entry(12L, "payments"));
    }

    @Test
    void uniqueTableNames_usesNameWhenFilenameMissing() {
        Map<Long, String> names = SqlTableNames.uniqueTableNames(
                List.of(dataset(1L, "Monthly report.csv", null)));
        assertThat(names.get(1L)).isEqualTo("monthly_report");
    }
}