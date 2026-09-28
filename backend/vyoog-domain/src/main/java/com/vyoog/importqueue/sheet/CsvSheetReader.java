package com.vyoog.importqueue.sheet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Delimited text — the third way the PRD template arrives, after {@code .ods} and
 * {@code .xlsx}. CSV is what every spreadsheet tool can export and what survives being
 * mailed around, so refusing it would push people back to a binary format for no reason.
 *
 * <p>RFC 4180 quoting is honoured properly rather than split on commas, because the
 * template's own columns need it: an Acceptance Criteria cell holds several lines and a
 * Statement routinely contains a comma. Splitting naively turns one requirement into
 * three malformed ones, and does it silently.
 *
 * <p>The delimiter is sniffed rather than assumed. A "CSV" exported on a machine with a
 * comma decimal separator is semicolon-delimited, and reading it as commas yields one
 * enormous column with every value in it — which looks like a template with no
 * recognisable header rather than like a delimiter problem.
 */
public class CsvSheetReader implements SheetReader {

    /** Tried in order; the one that best divides the first non-empty line wins. */
    private static final char[] DELIMITERS = {',', ';', '\t', '|'};

    @Override
    public boolean supports(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || XlsxSheetReader.isZip(bytes)) {
            // A ZIP is one of the real spreadsheet formats; this reader is the fallback
            // for everything that is honestly text.
            return false;
        }
        return isProbablyText(bytes);
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        return parse(text, sniffDelimiter(text));
    }

    /**
     * Rejects binary that happens not to be a ZIP. A NUL byte never appears in the text
     * encodings a spreadsheet exports, and is the cheapest reliable tell — without it a
     * stray .pdf reads as one enormous garbage row instead of being refused.
     */
    private static boolean isProbablyText(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    /** The delimiter that appears most often outside quotes on the first non-empty line. */
    private static char sniffDelimiter(String text) {
        String line = text.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        char best = ',';
        int bestCount = 0;
        for (char candidate : DELIMITERS) {
            int count = 0;
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') quoted = !quoted;
                else if (ch == candidate && !quoted) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * RFC 4180: {@code ""} inside a quoted field is a literal quote, and a newline inside
     * quotes belongs to the value rather than ending the record.
     */
    public static List<List<String>> parse(String rawText, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < rawText.length(); i++) {
            char ch = rawText.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < rawText.length() && rawText.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
                records.add(List.copyOf(row));
                row.clear();
                if (ch == '\r' && i + 1 < rawText.length() && rawText.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString());
        records.add(List.copyOf(row));

        // A file ending in a newline leaves one empty trailing record, which would read as
        // a blank requirement row.
        while (!records.isEmpty() && isBlank(records.get(records.size() - 1))) {
            records.remove(records.size() - 1);
        }
        return List.copyOf(records);
    }

    private static boolean isBlank(List<String> record) {
        return record.stream().allMatch(c -> c == null || c.isBlank());
    }
}
