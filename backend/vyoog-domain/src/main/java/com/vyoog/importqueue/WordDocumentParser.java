package com.vyoog.importqueue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * VYB-0666: real Apache POI reading of a real {@code .docx} — the same library this
 * codebase already uses to *write* one ({@code DocumentWordExporter}), now also used
 * to read one. A {@code .docx} is a ZIP container, so {@code ImportController}
 * base64-encodes it the same way it already does for a real {@code .xlsx} under the
 * {@code EXCEL} kind; {@code import_batch.raw_text} stays TEXT either way.
 *
 * <p><b>Tables are read.</b> This walked {@code doc.getParagraphs()}, which in POI
 * returns only top-level body paragraphs and silently excludes everything inside a
 * table. On a real functional specification that is not an edge case: measured on
 * Lead_FRD_v2_Enhanced.docx, 823 of 979 paragraphs and 51% of the document's text sat
 * in its eighteen tables — and those tables held the field rules, the state machine and
 * the RBAC matrix, which is to say all of the requirements. What reached extraction was
 * the half of the document that isn't requirements: the title block, the version table's
 * surrounding prose, "How to Read This Document". The agents were not the weak link;
 * they were reading the wrong half of the file.
 *
 * <p>A row is one candidate, not one cell, and its cells are labelled from the header
 * row: {@code "Field Name: Customer Name | Control Type: Dropdown | M/O: M | Validation
 * & Business Rules: must already exist in the Customer Master"}. A bare tab-joined row
 * loses which column said what, and the whole value of a specification table is that
 * the column tells you whether a cell is a rule, a remark or a data type.
 *
 * <p>Prose paragraphs still become candidates one for one, tagged {@code p1}, {@code p2},
 * …, and table rows are tagged {@code t1r2} so a reviewer can see at a glance which part
 * of the document a candidate came from. Headings are not skipped — a heading vs.
 * body-text paragraph looks identical without interpreting styles this parser doesn't
 * read — so they arrive as candidates too, exactly as visible and rejectable as anything
 * else. Triage discards them when the analysis agents are configured.
 */
@Component
public class WordDocumentParser implements DocumentParser {

    /**
     * A table this wide is a layout device, not data — labelling two cells against a
     * one-cell header produces noise rather than structure. Its rows fall back to plain
     * joined text.
     */
    private static final int MIN_HEADER_COLUMNS = 2;

    @Override
    public UploadKind kind() {
        return UploadKind.WORD;
    }

    @Override
    public List<ExtractedCandidate> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new DocumentValidationException("empty-file", "The uploaded file has no content.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(rawText.strip());
        } catch (IllegalArgumentException e) {
            throw new DocumentValidationException("not-base64", "Expected a base64-encoded .docx file.");
        }

        List<ExtractedCandidate> out = new ArrayList<>();
        try (var in = new ByteArrayInputStream(bytes); XWPFDocument doc = new XWPFDocument(in)) {
            int paragraphIndex = 0;
            int tableIndex = 0;
            // getBodyElements, not getParagraphs: this is the only walk that returns
            // paragraphs and tables together, in the order the document actually has them.
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = normalise(p.getText());
                    if (text.isEmpty()) continue;
                    paragraphIndex++;
                    out.add(new ExtractedCandidate("p" + paragraphIndex, text, "paragraph " + paragraphIndex));
                } else if (element instanceof XWPFTable table) {
                    tableIndex++;
                    readTable(table, tableIndex, out);
                }
            }
        } catch (Exception e) {
            // POI's OOXML reader throws a mix of checked (IOException,
            // InvalidFormatException) and unchecked (POIXMLException, etc.) types for a
            // corrupt/non-.docx ZIP — all mean the same thing to this caller.
            throw new DocumentValidationException("unreadable-docx", "Could not read this .docx: " + e.getMessage());
        }

        if (out.isEmpty()) {
            throw new DocumentValidationException("empty-document", "No text paragraphs were found in this document.");
        }
        return out;
    }

    /** One candidate per body row, cells labelled from the header row where there is a usable one. */
    private void readTable(XWPFTable table, int tableIndex, List<ExtractedCandidate> out) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;

        List<String> headers = cellsOf(rows.get(0));
        boolean labelled = rows.size() > 1 && headers.size() >= MIN_HEADER_COLUMNS
            && headers.stream().anyMatch(h -> !h.isEmpty());
        // Without a header the first row is content, not labels, and skipping it would
        // drop a real row from a table that simply has no heading.
        int firstBodyRow = labelled ? 1 : 0;

        for (int r = firstBodyRow; r < rows.size(); r++) {
            List<String> cells = cellsOf(rows.get(r));
            if (cells.stream().allMatch(String::isEmpty)) continue; // spacer row

            StringBuilder text = new StringBuilder();
            for (int c = 0; c < cells.size(); c++) {
                if (cells.get(c).isEmpty()) continue; // an empty cell says nothing worth labelling
                if (text.length() > 0) text.append(" | ");
                String label = labelled && c < headers.size() ? headers.get(c) : "";
                if (!label.isEmpty()) text.append(label).append(": ");
                text.append(cells.get(c));
            }
            if (text.length() == 0) continue;

            int rowNumber = r + 1;
            out.add(new ExtractedCandidate(
                "t" + tableIndex + "r" + rowNumber, text.toString(),
                "table " + tableIndex + ", row " + rowNumber));
        }
    }

    private List<String> cellsOf(XWPFTableRow row) {
        List<String> cells = new ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            cells.add(normalise(cell.getText()));
        }
        return cells;
    }

    private String normalise(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }
}
