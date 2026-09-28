package com.vyoog.importqueue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * VYB-0638: the "spreadsheet formats" migration path. Handles two shapes under the
 * one {@code EXCEL} upload kind: a genuine {@code .xlsx} (base64-encoded by {@code
 * ImportController} before it ever reaches {@code raw_text}, since that column is
 * TEXT — real Apache POI parsing, not a stand-in), and plain CSV text, which every
 * spreadsheet tool can also export and which needed no binary handling at all. Which
 * one this is gets sniffed from the content itself (a ZIP-signature after base64
 * decoding means {@code .xlsx}) rather than trusting a second parameter.
 */
@Component
public class CsvSpreadsheetDocumentParser implements DocumentParser {

    private static final List<String> STATEMENT_COLUMN_NAMES =
        List.of("statement", "requirement statement", "requirement", "text", "description");
    private static final List<String> ID_COLUMN_NAMES = List.of("id", "key", "identifier", "your ref", "reference", "ref");
    private static final int HEADER_SEARCH_DEPTH = 25;

    @Override
    public UploadKind kind() {
        return UploadKind.EXCEL;
    }

    @Override
    public List<ExtractedCandidate> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new DocumentValidationException("empty-file", "The uploaded file has no content.");
        }
        DecodedUpload decoded = tryDecodeUpload(rawText);
        return decoded.xlsxBytes() != null ? parseXlsx(decoded.xlsxBytes()) : parseCsv(decoded.csvText());
    }

    /**
     * EXCEL uploads arrive base64-encoded from the controller, whether the file was a
     * binary .xlsx or a text .csv. A ZIP signature means POI should read it as .xlsx;
     * anything else is the CSV text the user uploaded.
     */
    private DecodedUpload tryDecodeUpload(String rawText) {
        try {
            byte[] bytes = Base64.getDecoder().decode(rawText.strip());
            if (bytes.length > 2 && bytes[0] == 0x50 && bytes[1] == 0x4B) {
                return new DecodedUpload(bytes, null);
            }
            return new DecodedUpload(null, new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException notBase64) {
            return new DecodedUpload(null, rawText);
        }
    }

    private record DecodedUpload(byte[] xlsxBytes, String csvText) {}

    private List<ExtractedCandidate> parseXlsx(byte[] bytes) {
        // An .ods is a ZIP too, so it reaches here looking like a workbook and then dies
        // inside POI with an OOXML complaint that tells the uploader nothing about what
        // to do. Named before POI sees it, so the message is about their file and their
        // next step rather than about a container format.
        if (new com.vyoog.importqueue.sheet.OdsSheetReader().supports(bytes)) {
            throw new DocumentValidationException("ods-as-excel",
                "This is an OpenDocument spreadsheet (.ods), not an Excel workbook. "
                    + "If it is the standard PRD template, upload it with kind \"PRD template\"; "
                    + "otherwise save it as .xlsx first.");
        }
        List<ExtractedCandidate> out = new ArrayList<>();
        try (var in = new java.io.ByteArrayInputStream(bytes); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            int headerRow = findXlsxHeader(sheet);
            if (headerRow < 0) {
                throw missingStatementColumn();
            }
            Row header = sheet.getRow(headerRow);
            int statementCol = -1;
            int idCol = -1;
            for (Cell cell : header) {
                String col = canonicalHeader(cellText(cell));
                if (statementCol < 0 && STATEMENT_COLUMN_NAMES.contains(col)) statementCol = cell.getColumnIndex();
                if (idCol < 0 && ID_COLUMN_NAMES.contains(col)) idCol = cell.getColumnIndex();
            }
            if (statementCol < 0) {
                throw missingStatementColumn();
            }
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String statement = cellText(row.getCell(statementCol)).strip();
                if (statement.isBlank()) continue;
                String idValue = idCol >= 0 ? cellText(row.getCell(idCol)).strip() : "";
                String tag = idValue.isBlank() ? "row" + (r + 1) : idValue;
                out.add(new ExtractedCandidate(tag, statement, "row " + (r + 1)));
            }
        } catch (org.apache.poi.EncryptedDocumentException | java.io.IOException e) {
            throw new DocumentValidationException("unreadable-xlsx", "Could not read this .xlsx: " + e.getMessage());
        }
        return out;
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf(cell.getNumericCellValue());
        return cell.toString();
    }

    private List<ExtractedCandidate> parseCsv(String rawText) {
        List<ExtractedCandidate> out = new ArrayList<>();
        List<List<String>> records = parseCsvRecords(rawText);
        int headerRow = findCsvHeader(records);
        if (headerRow < 0) {
            throw missingStatementColumn();
        }
        List<String> header = records.get(headerRow);
        int statementCol = -1;
        int idCol = -1;
        for (int i = 0; i < header.size(); i++) {
            String col = canonicalHeader(header.get(i));
            if (statementCol < 0 && STATEMENT_COLUMN_NAMES.contains(col)) statementCol = i;
            if (idCol < 0 && ID_COLUMN_NAMES.contains(col)) idCol = i;
        }
        if (statementCol < 0) {
            throw missingStatementColumn();
        }

        for (int row = headerRow + 1; row < records.size(); row++) {
            List<String> cells = records.get(row);
            String statement = cell(cells, statementCol).strip();
            if (statement.isBlank()) continue;
            String idValue = cell(cells, idCol).strip();
            String tag = idValue.isBlank() ? "row" + (row + 1) : idValue;
            out.add(new ExtractedCandidate(tag, statement, "row " + (row + 1)));
        }
        return out;
    }

    private static int findXlsxHeader(XSSFSheet sheet) {
        int end = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_DEPTH - 1);
        for (int r = sheet.getFirstRowNum(); r <= end; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                if (STATEMENT_COLUMN_NAMES.contains(canonicalHeader(cellText(cell)))) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static int findCsvHeader(List<List<String>> records) {
        int end = Math.min(records.size(), HEADER_SEARCH_DEPTH);
        for (int r = 0; r < end; r++) {
            for (String cell : records.get(r)) {
                if (STATEMENT_COLUMN_NAMES.contains(canonicalHeader(cell))) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static String canonicalHeader(String raw) {
        if (raw == null) return "";
        return raw.split("\n", 2)[0]
            .replace("\uFEFF", "")
            .replace("*", "")
            .replace("(required)", "")
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    private static DocumentValidationException missingStatementColumn() {
        return new DocumentValidationException("missing-statement-column",
            "No column named one of " + STATEMENT_COLUMN_NAMES + " was found in the header row.");
    }

    /**
     * Delegates to the shared reader rather than keeping a second RFC 4180 implementation
     * — the two drifted apart once already, and a quoting rule fixed in one but not the
     * other is a bug nobody notices until a statement containing a comma splits in half.
     */
    private static List<List<String>> parseCsvRecords(String rawText) {
        return com.vyoog.importqueue.sheet.CsvSheetReader.parse(rawText, ',');
    }
}
