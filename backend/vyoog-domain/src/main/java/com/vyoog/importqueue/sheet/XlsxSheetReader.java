package com.vyoog.importqueue.sheet;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** {@code .xlsx}, through the Apache POI this codebase already uses to read .docx and write .xlsx. */
@Component
public class XlsxSheetReader implements SheetReader {

    @Override
    public boolean supports(byte[] bytes) {
        // Both .xlsx and .ods are ZIPs, so the PK header alone decides nothing. The
        // workbook part is what makes it an OOXML spreadsheet.
        return isZip(bytes) && ZipPeek.hasEntry(bytes, "xl/workbook.xml");
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        List<List<String>> rows = new ArrayList<>();
        try (var in = new ByteArrayInputStream(bytes); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            // Formatter rather than getStringCellValue: a cell may legitimately hold a
            // number or a date, and this renders what the author sees rather than throwing.
            DataFormatter formatter = new DataFormatter();
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        // A formula's cached value, not its expression — the template's
                        // "Ready?" column is a formula and its text is what a reader sees.
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                }
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                rows.add(List.copyOf(cells));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read this .xlsx: " + e.getMessage(), e);
        }
        return List.copyOf(rows);
    }

    static boolean isZip(byte[] b) {
        return b != null && b.length > 4 && b[0] == 'P' && b[1] == 'K';
    }
}
