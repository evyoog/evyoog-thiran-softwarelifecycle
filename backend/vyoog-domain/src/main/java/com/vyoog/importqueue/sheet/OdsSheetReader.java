package com.vyoog.importqueue.sheet;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * {@code .ods}, read directly from the OpenDocument package.
 *
 * <p>Hand-rolled on the JDK's own StAX rather than adding ODFDOM. This needs one thing —
 * the text in each cell of the first sheet — and ODFDOM is a large dependency, with its
 * own transitive tree, to answer that. POI cannot help here: it reads OOXML only, which
 * is why an .ods upload previously failed as "not a valid .xlsx".
 *
 * <p>Three OpenDocument details this has to respect, all of which silently corrupt a grid
 * if missed:
 *
 * <ul>
 *   <li>{@code number-columns-repeated} — consecutive identical cells are stored once with
 *       a count. Ignoring it collapses the row and every column after the first gap lands
 *       in the wrong place.
 *   <li>{@code number-rows-repeated} — the same for rows, and the trailing filler at the
 *       end of a sheet is often repeated a million times. Expanding that literally would
 *       exhaust memory, so filler beyond the real content is dropped.
 *   <li>A cell holds one {@code <text:p>} per line. The template's Acceptance Criteria
 *       column is "one criterion per line", so these are joined with newlines rather than
 *       concatenated into a single run-on string.
 *   <li>A cell comment is stored <em>inside</em> the cell as an {@code office:annotation}
 *       built from the same {@code <text:p>} elements as the value. Reading them alike
 *       prepends the comment to the value — which on the PRD template meant every
 *       annotated header cell read as its own help text instead of its column name.
 * </ul>
 */
@Component
public class OdsSheetReader implements SheetReader {

    /**
     * Beyond this, a repeat count is spreadsheet padding rather than data. LibreOffice
     * writes runs of 1,000,000+ empty rows and 16,000+ empty columns to fill the grid;
     * expanding those is both pointless and a way to run out of heap on a 20 KB file.
     */
    private static final int PADDING_THRESHOLD = 512;

    @Override
    public boolean supports(byte[] bytes) {
        return XlsxSheetReader.isZip(bytes) && ZipPeek.hasEntry(bytes, "content.xml");
    }

    @Override
    public List<List<String>> read(byte[] bytes) {
        byte[] content = ZipPeek.entry(bytes, "content.xml");
        if (content == null) {
            throw new IllegalArgumentException("Not an OpenDocument file — it has no content.xml.");
        }

        List<List<String>> rows = new ArrayList<>();
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            // Untrusted input: no external entity resolution, no DTD.
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            XMLStreamReader xml = factory.createXMLStreamReader(new ByteArrayInputStream(content));

            boolean inTable = false;
            List<String> row = null;
            StringBuilder cell = null;
            int cellRepeat = 1;
            int rowRepeat = 1;
            List<String> paragraphs = null;
            List<List<String>> tableRows = null;
            List<List<String>> firstNonEmptyTable = null;
            // Depth of nesting inside an <office:annotation>. A counter rather than a flag
            // because an annotation contains its own nested elements, and any of them
            // ending would clear a flag while still inside the comment.
            int inAnnotation = 0;

            // if/else rather than an arrow switch: an unlabelled `break` inside a
            // `case ->` block nested in this loop breaks the *loop*, not the case, which
            // is a silent parse truncation rather than a compile error.
            while (xml.hasNext()) {
                int event = xml.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = xml.getLocalName();
                    if (name.equals("annotation")) {
                        inAnnotation++;
                    } else if (inAnnotation > 0) {
                        // Everything under an annotation is comment prose, not cell value.
                        // Falling through here would open a <text:p> and start collecting it.
                    } else if (name.equals("table")) {
                        inTable = true;
                        tableRows = new ArrayList<>();
                    } else if (inTable && name.equals("table-row")) {
                        row = new ArrayList<>();
                        rowRepeat = repeat(xml, "number-rows-repeated");
                    } else if (inTable && row != null
                            && (name.equals("table-cell") || name.equals("covered-table-cell"))) {
                        cellRepeat = repeat(xml, "number-columns-repeated");
                        paragraphs = new ArrayList<>();
                    } else if (paragraphs != null && name.equals("p")) {
                        cell = new StringBuilder();
                    } else if (cell != null && name.equals("s")) {
                        // <text:s/> is a run of spaces the format stores as an element
                        // rather than as characters; without this, words run together.
                        cell.append(' ');
                    }

                } else if (event == XMLStreamConstants.CHARACTERS && cell != null) {
                    cell.append(xml.getText());

                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = xml.getLocalName();
                    if (name.equals("annotation")) {
                        inAnnotation--;
                    } else if (inAnnotation > 0) {
                        // Same as above: a </text:p> in here closes nothing we opened.
                    } else if (name.equals("p")) {
                        if (paragraphs != null && cell != null) paragraphs.add(cell.toString());
                        cell = null;
                    } else if (name.equals("table-cell") || name.equals("covered-table-cell")) {
                        if (row != null && paragraphs != null) {
                            String text = String.join("\n", paragraphs).trim();
                            for (int i = 0; i < cellRepeat; i++) row.add(text);
                        }
                        paragraphs = null;
                        cellRepeat = 1;
                    } else if (name.equals("table-row")) {
                        if (row != null) {
                            while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) {
                                row.remove(row.size() - 1);
                            }
                            List<String> finished = List.copyOf(row);
                            if (tableRows != null) {
                                for (int i = 0; i < rowRepeat; i++) tableRows.add(finished);
                            }
                        }
                        row = null;
                        rowRepeat = 1;
                    } else if (name.equals("table") && inTable) {
                        trimTrailingEmptyRows(tableRows);
                        if (tableRows != null && !tableRows.isEmpty()) {
                            if (firstNonEmptyTable == null) {
                                firstNonEmptyTable = List.copyOf(tableRows);
                            }
                            if (tableRows.stream().anyMatch(r -> r.size() > 1)) {
                                // Some ODS writers serialize validation ranges as tiny
                                // one-column tables before the visible sheet. The first
                                // multi-column table is the spreadsheet grid users see.
                                rows = tableRows;
                                break;
                            }
                        }
                        inTable = false;
                        tableRows = null;
                    }
                }
            }
            xml.close();
            if (rows.isEmpty() && firstNonEmptyTable != null) {
                rows = new ArrayList<>(firstNonEmptyTable);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read this .ods: " + e.getMessage(), e);
        }

        trimTrailingEmptyRows(rows);
        return List.copyOf(rows);
    }

    /** A repeat count, with spreadsheet padding treated as one rather than expanded. */
    private static int repeat(XMLStreamReader xml, String attribute) {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            if (!xml.getAttributeLocalName(i).equals(attribute)) continue;
            try {
                int n = Integer.parseInt(xml.getAttributeValue(i));
                return n > PADDING_THRESHOLD ? 1 : Math.max(1, n);
            } catch (NumberFormatException notANumber) {
                return 1;
            }
        }
        return 1;
    }

    private static void trimTrailingEmptyRows(List<List<String>> rows) {
        if (rows == null) {
            return;
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isEmpty()) {
            rows.remove(rows.size() - 1);
        }
    }
}
