package com.dataanalytics.backend.dto;

import java.util.List;

public record CsvPreviewResponse(List<String> columns, List<List<String>> rows) {
}
