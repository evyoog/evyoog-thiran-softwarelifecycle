package com.vyoog.importqueue.sheet;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/**
 * Legacy binary Excel — {@code .xls}, the format Excel used before 2007.
 *
 * <p>Still worth supporting because it is what older internal tools and exports produce,
 * and because the failure without it is confusing rather than obvious: an {@code .xls} is
 * an OLE2 compound file, not a ZIP, so it does not look like an {@code .xlsx} and does
 * not look like text either — it would simply be refused as "not a spreadsheet" while
 * plainly being one.
 *
 * <p>Values are read through {@link DataFormatter}, so a cell renders as the sheet shows
 * it. Reading numerically instead would turn a reference of {@code 00123} into
 * {@code 123.0} and a release of {@code 3.20} into {@code 3.2}.
 */
public class XlsSheetReader implements SheetReader {

    /** The OLE2 compound-document signature every .xls begins with. */
    private static final byte[] OLE2_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(byte[] bytes) {
        if (bytes == null || bytes.length < OLE2_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < OLE2_MAGIC.length; i++) {
            if (bytes[i] != OLE2_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        List<List<String>> rows = new ArrayList<>();
        try (var in = new ByteArrayInputStream(bytes); HSSFWorkbook workbook = new HSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    // A gap in the sheet is still a row as far as the grid is concerned —
                    // skipping it would shift every row number below it, and those numbers
                    // are what a problem report points the author at.
                    rows.add(List.of());
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                rows.add(List.copyOf(cells));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read this .xls: " + e.getMessage(), e);
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isEmpty()) {
            rows.remove(rows.size() - 1);
        }
        return List.copyOf(rows);
    }
}
