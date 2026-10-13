package com.vyoog.importqueue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.springframework.stereotype.Service;

/**
 * VYB-0957 (F25, D44): the blank files people fill in to import requirements: an Excel workbook and a Word file. Both use the
 * column headings the import reads (the same ones {@code PrdTemplateParser} finds by name), so a file made from them always
 * passes the "has the right columns" check. A required column is marked {@code *}. The example row is in the instructions, not in
 * the table to fill in, so it can never be imported by accident. Generated on request: there is no binary template to keep in step
 * with the importer.
 */
@Service
public class ImportTemplateService {

    /** @param heading what the header cell says; @param required marked with * and counted by the import; @param help what to enter; @param example one value */
    public record Column(String heading, boolean required, String help, String example) {}

    private static final String TYPES = "FUNCTIONAL, NON_FUNCTIONAL, BUSINESS_RULE, INTERFACE, DATA, REPORT, SECURITY, COMPLIANCE";
    private static final String PRIORITIES = "CRITICAL, HIGH, MEDIUM, LOW";

    /** In the order the Excel template shows them. */
    public static final List<Column> COLUMNS = List.of(
        new Column("Product", true, "The product the requirement belongs to, as named in Portfolio.", "Vyoog"),
        new Column("App", true, "The application within that product.", "Requirements"),
        new Column("Capability", true, "The capability within that application.", "Import"),
        new Column("Requirement Title", true, "A short name for the requirement.", "Reject an unreadable file"),
        new Column("Requirement Statement", true, "The requirement itself, in full: \"The system shall ...\".", "The system shall refuse an import file it cannot read and say why."),
        new Column("Type", true, "One of: " + TYPES + ".", "FUNCTIONAL"),
        new Column("Priority", true, "One of: " + PRIORITIES + ".", "HIGH"),
        new Column("Acceptance Criteria", false, "How you will know it is done. One criterion per line.", "An unreadable file is refused\nThe reason names the problem"),
        new Column("Your Ref", false, "Your own identifier. Other rows refer to it in Depends On.", "REQ-101"),
        new Column("Verification Method", false, "Test, inspection, demonstration or analysis.", "Test"),
        new Column("Owner", false, "Who owns it.", "Ada Lovelace"),
        new Column("Source / Requested By", false, "Where it came from.", "Pilot team"),
        new Column("Parent Requirement", false, "The Your Ref of the requirement this refines.", "REQ-100"),
        new Column("Depends On", false, "The Your Ref of each requirement this needs, separated by commas.", "REQ-099, REQ-098"),
        new Column("Tags", false, "Words to find it by, separated by commas.", "import, validation"),
        new Column("Regulatory Reference", false, "A clause it answers to.", ""),
        new Column("Target Release", false, "The release it is planned for.", ""),
        new Column("Notes", false, "Anything else.", ""));

    /** The Word template carries the columns that fit on a page; any other column of the Excel template may be added by its heading. */
    static final List<String> WORD_COLUMNS = List.of("Your Ref", "Product", "App", "Capability", "Requirement Title", "Requirement Statement",
        "Type", "Priority", "Acceptance Criteria", "Depends On");

    private static String label(Column c) { return c.heading() + (c.required() ? " *" : ""); }

    // ------------------------------------------------------------------ Excel

    public byte[] xlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // The import reads the first sheet, so the sheet to fill in comes first.
            Sheet sheet = wb.createSheet("Requirements");
            CellStyle bold = wb.createCellStyle();
            var boldFont = wb.createFont();
            boldFont.setBold(true);
            bold.setFont(boldFont);
            CellStyle head = wb.createCellStyle();
            head.cloneStyleFrom(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setWrapText(true);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);

            Row banner = sheet.createRow(0);
            banner.createCell(0).setCellValue("Vyoog requirements template. One requirement per row from row 3 down. Columns marked * are required. See the Instructions sheet.");
            banner.getCell(0).setCellStyle(bold);
            Row header = sheet.createRow(1);
            for (int i = 0; i < COLUMNS.size(); i++) {
                header.createCell(i).setCellValue(label(COLUMNS.get(i)));
                header.getCell(i).setCellStyle(head);
                boolean long_ = COLUMNS.get(i).heading().equals("Requirement Statement") || COLUMNS.get(i).heading().equals("Acceptance Criteria") || COLUMNS.get(i).heading().equals("Notes");
                sheet.setColumnWidth(i, (long_ ? 50 : 22) * 256);
                if (long_) for (int r = 2; r < 502; r++) sheet.createRow(r).createCell(i).setCellStyle(wrap);
            }
            sheet.createFreezePane(0, 2);
            dropdown(sheet, indexOf("Type"), TYPES.split(", "));
            dropdown(sheet, indexOf("Priority"), PRIORITIES.split(", "));

            Sheet help = wb.createSheet("Instructions");
            String[] intro = {
                "How to use this template",
                "1. Fill in the Requirements sheet, one requirement per row. Do not rename or reorder the headings; extra columns are ignored.",
                "2. Upload it on the Import queue screen. The file is checked at once and every problem is listed by row before anything is imported.",
                "3. Nothing is guessed: a required cell left empty, or a Type or Priority not in the lists below, is reported as a problem on that row.",
                "4. The Product, App and Capability must already exist in Portfolio. A name that does not match is reported, never created.",
                "5. Delete nothing above the heading row. Rows with no Requirement Title and no Requirement Statement are ignored.",
                ""};
            for (int i = 0; i < intro.length; i++) {
                help.createRow(i).createCell(0).setCellValue(intro[i]);
                if (i == 0) help.getRow(0).getCell(0).setCellStyle(bold);
            }
            Row hh = help.createRow(intro.length);
            String[] titles = {"Column", "Required", "What to enter", "Example"};
            for (int i = 0; i < titles.length; i++) {
                hh.createCell(i).setCellValue(titles[i]);
                hh.getCell(i).setCellStyle(head);
            }
            int r = intro.length + 1;
            for (Column c : COLUMNS) {
                Row row = help.createRow(r++);
                row.createCell(0).setCellValue(c.heading());
                row.createCell(1).setCellValue(c.required() ? "Yes" : "No");
                row.createCell(2).setCellValue(c.help());
                row.createCell(3).setCellValue(c.example());
                for (int i = 2; i <= 3; i++) row.getCell(i).setCellStyle(wrap);
            }
            help.setColumnWidth(0, 26 * 256);
            help.setColumnWidth(1, 10 * 256);
            help.setColumnWidth(2, 70 * 256);
            help.setColumnWidth(3, 50 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the template", e);
        }
    }

    private static int indexOf(String heading) {
        for (int i = 0; i < COLUMNS.size(); i++) if (COLUMNS.get(i).heading().equals(heading)) return i;
        throw new IllegalArgumentException(heading);
    }

    private static void dropdown(Sheet sheet, int column, String[] values) {
        var helper = sheet.getDataValidationHelper();
        DataValidationConstraint list = helper.createExplicitListConstraint(values);
        DataValidation validation = helper.createValidation(list, new CellRangeAddressList(2, 501, column, column));
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("Not in the list", "Choose one of: " + String.join(", ", values));
        sheet.addValidationData(validation);
    }

    // ------------------------------------------------------------------ Word

    public byte[] docx() {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr section = doc.getDocument().getBody().addNewSectPr();
            CTPageSz page = section.addNewPgSz();
            page.setW(BigInteger.valueOf(16838)); // A4, landscape: ten columns need the width
            page.setH(BigInteger.valueOf(11906));
            page.setOrient(STPageOrientation.LANDSCAPE);

            paragraph(doc, "Vyoog requirements template", 16, true);
            paragraph(doc, "Fill in the table below, one requirement per row, then upload this file on the Import queue screen. The file is checked at once and every problem is listed by row before anything is imported.", 10, false);
            paragraph(doc, "Columns marked * are required. Type is one of: " + TYPES + ". Priority is one of: " + PRIORITIES + ".", 10, false);
            paragraph(doc, "Product, App and Capability must already exist in Portfolio. Acceptance Criteria: one per line in the cell. Depends On: the Your Ref of each requirement this needs, separated by commas.", 10, false);
            paragraph(doc, "Do not rename the headings. To use another column of the Excel template (Owner, Tags, Target Release ...), add a column with the same heading. Only the first table that has these headings is read; the text outside it is not.", 10, false);
            paragraph(doc, "Example row: REQ-101 | Vyoog | Requirements | Import | Reject an unreadable file | The system shall refuse an import file it cannot read and say why. | FUNCTIONAL | HIGH | An unreadable file is refused | REQ-099", 9, false);

            XWPFTable table = doc.createTable(4, WORD_COLUMNS.size());
            XWPFTableRow header = table.getRow(0);
            for (int i = 0; i < WORD_COLUMNS.size(); i++) {
                Column c = COLUMNS.get(indexOf(WORD_COLUMNS.get(i)));
                var cell = header.getCell(i);
                cell.setColor("D9D9D9");
                XWPFParagraph p = cell.getParagraphs().get(0);
                XWPFRun run = p.createRun();
                run.setBold(true);
                run.setFontSize(8);
                run.setText(label(c));
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the template", e);
        }
    }

    private static void paragraph(XWPFDocument doc, String text, int size, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = p.createRun();
        run.setFontSize(size);
        run.setBold(bold);
        run.setText(text);
    }
}
