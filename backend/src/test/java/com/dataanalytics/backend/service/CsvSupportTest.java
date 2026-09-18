package com.dataanalytics.backend.service;

import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CsvSupport transparently strips a leading UTF-8 BOM so header lookups for
 * the first column never collide with a "\uFEFF" prefix.
 */
class CsvSupportTest {

    @TempDir
    Path dir;

    @Test
    void open_stripsUtf8BomFromHeader() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        Path file = dir.resolve("bom.csv");
        Files.write(file, concat(bom, "name,value\nx,1\n".getBytes(StandardCharsets.UTF_8)));

        try (CSVParser parser = CsvSupport.open(file)) {
            assertThat(parser.getHeaderMap()).containsKey("name");
            assertThat(parser.getHeaderMap()).containsKey("value");
            assertThat(parser.getHeaderNames()).startsWith("name");
        }
    }

    @Test
    void open_handlesPlainCsvWithoutBom() throws Exception {
        Path file = dir.resolve("plain.csv");
        Files.writeString(file, "name,value\nx,1\n", StandardCharsets.UTF_8);

        try (CSVParser parser = CsvSupport.open(file)) {
            assertThat(parser.getHeaderMap()).containsKeys("name", "value");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}