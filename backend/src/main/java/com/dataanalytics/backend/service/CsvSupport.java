package com.dataanalytics.backend.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens stored CSV files for backend-side parsing, transparently skipping a
 * leading UTF-8 BOM. Uploads produced by Windows tools frequently carry one;
 * without stripping it the first header key becomes "\uFEFFcolumn" and exact
 * header lookups for the first column silently fail.
 */
final class CsvSupport {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvSupport() {
    }

    static CSVParser open(Path filePath) throws IOException {
        PushbackInputStream in = new PushbackInputStream(Files.newInputStream(filePath), 3);
        byte[] prefix = new byte[3];
        int read = in.read(prefix, 0, 3);
        boolean hasBom = read == 3 && (prefix[0] & 0xFF) == (UTF8_BOM[0] & 0xFF)
                && (prefix[1] & 0xFF) == (UTF8_BOM[1] & 0xFF)
                && (prefix[2] & 0xFF) == (UTF8_BOM[2] & 0xFF);
        if (read > 0 && !hasBom) {
            in.unread(prefix, 0, read);
        }
        Reader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        return CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader);
    }
}
