package com.vyoog.importqueue.sheet;

import com.vyoog.importqueue.DocumentValidationException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * VYB-0957: a Word file read as a grid. The requirements are a <b>table</b> whose header row names the template's columns; prose
 * is never read, because deciding which sentence is a requirement and what type it is was exactly the guessing the import left
 * behind (VYB-0666). The first table whose header row looks like the template's is the grid; a cover table or a version table
 * before it is passed over, and tables after it are not read. A cell with several paragraphs keeps them, joined with a newline
 * (the Acceptance Criteria column is one criterion per line).
 */
public final class DocxSheetReader implements SheetReader {

    private final Predicate<List<String>> looksLikeHeader;

    /** @param looksLikeHeader decides whether a row of cells names the template's columns (the parser owns that rule) */
    public DocxSheetReader(Predicate<List<String>> looksLikeHeader) {
        this.looksLikeHeader = looksLikeHeader;
    }

    @Override
    public String format() { return "docx"; }

    @Override
    public boolean supports(byte[] bytes) {
        return XlsxSheetReader.isZip(bytes) && ZipPeek.hasEntry(bytes, "word/document.xml");
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        try (var in = new ByteArrayInputStream(bytes); XWPFDocument doc = new XWPFDocument(in)) {
            int tables = 0;
            for (XWPFTable table : doc.getTables()) {
                tables++;
                List<List<String>> grid = grid(table);
                if (grid.stream().limit(5).anyMatch(looksLikeHeader)) return grid;
            }
            throw new DocumentValidationException("no-table", tables == 0
                ? "This Word file has no table. Requirements are read from a table with one row per requirement; download the Word template."
                : "None of this Word file's %d table%s has the template's column headings (Product, App, Capability, Requirement Title, Requirement Statement, Type, Priority). Download the Word template, or rename your headings to match.".formatted(tables, tables == 1 ? "" : "s"));
        } catch (DocumentValidationException e) {
            throw e;
        } catch (Exception e) {
            // POI throws a mix of checked and unchecked exceptions for a corrupt or non-.docx ZIP; all mean the same thing here.
            throw new DocumentValidationException("unreadable-docx", "Could not read this .docx: " + e.getMessage());
        }
    }

    private static List<List<String>> grid(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) cells.add(textOf(cell));
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
            rows.add(cells);
        }
        return rows;
    }

    private static String textOf(XWPFTableCell cell) {
        List<String> lines = new ArrayList<>();
        for (XWPFParagraph p : cell.getParagraphs()) {
            String t = p.getText() == null ? "" : p.getText().strip();
            if (!t.isEmpty()) lines.add(t);
        }
        return String.join("\n", lines);
    }
}
