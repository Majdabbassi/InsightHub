package com.dataanalytics.backend.service;

import com.dataanalytics.backend.model.Dataset;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns dataset filenames into SQL-safe table names for sandboxed DuckDB
 * queries. MUST mirror duckdb_query.py:sanitize_name/build_table_map in the
 * analytics service exactly — both sides have to agree on the mapping.
 */
public final class SqlTableNames {

    private SqlTableNames() {
    }

    public static String sanitize(String originalFilename) {
        String stem = originalFilename == null ? ""
                : (originalFilename.contains(".")
                        ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                        : originalFilename);
        String cleaned = stem.replaceAll("[^A-Za-z0-9_]", "_");
        cleaned = trimUnderscores(cleaned).toLowerCase();
        if (cleaned.isEmpty()) {
            return "table";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            return "t_" + cleaned;
        }
        return cleaned;
    }

    /** Python str.strip('_') equivalent. */
    private static String trimUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Maps sanitized names to original filenames for a list of datasets
     * (order matters: collisions get _2, _3 suffixes deterministically).
     */
    public static Map<String, String> buildMapping(Iterable<String> originalFilenames) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String filename : originalFilenames) {
            String base = sanitize(filename);
            String candidate = base;
            int suffix = 2;
            while (mapping.containsKey(candidate)) {
                candidate = base + "_" + suffix++;
            }
            mapping.put(candidate, filename);
        }
        return mapping;
    }

    /**
     * Assigns a unique sanitized table name to each dataset, in the given
     * order, applying _2/_3 suffixes on collision. This is the single source
     * of truth for the SQL catalog shown to the model AND the executor's
     * binding map: if the two ever diverge, the model can be told to query
     * one table name while the executor binds another. Callers must pass the
     * datasets in the same deterministic order (by id ascending).
     */
    public static Map<Long, String> uniqueTableNames(List<Dataset> datasetsByIdAscending) {
        Map<Long, String> byId = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (Dataset dataset : datasetsByIdAscending) {
            String base = sanitize(dataset.getOriginalFilename() == null
                    ? dataset.getName() : dataset.getOriginalFilename());
            String candidate = base;
            int suffix = 2;
            while (used.contains(candidate)) {
                candidate = base + "_" + suffix++;
            }
            used.add(candidate);
            byId.put(dataset.getId(), candidate);
        }
        return byId;
    }
}
