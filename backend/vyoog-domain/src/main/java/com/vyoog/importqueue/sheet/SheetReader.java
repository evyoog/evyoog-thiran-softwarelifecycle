package com.vyoog.importqueue.sheet;

import java.util.List;

/**
 * A spreadsheet as rows of plain strings, whatever the file format underneath.
 *
 * <p>The PRD template is the same grid whether somebody saved it from Excel or from
 * LibreOffice, and every rule that reads it — which column is the title, which are
 * mandatory — is identical either way. Keeping format out of the parser means those rules
 * are written and tested once.
 *
 * <p>Everything arrives as text on purpose. A priority is "Critical", not a number, and a
 * "Your Ref" of {@code 00123} must not come back as {@code 123} because a spreadsheet
 * decided it looked numeric.
 */
public interface SheetReader {

    /** Whether this reader recognises the bytes as its own format. */
    boolean supports(byte[] bytes);

    /**
     * The first sheet-like grid, as rows of cells.
     *
     * <p>Rows are ragged: trailing empty cells are trimmed, so a row's length says how far
     * the content goes, not how wide the sheet is. Callers index defensively.
     *
     * <p>A cell holding several lines keeps them, joined with {@code \n} — the template's
     * Acceptance Criteria column is "one criterion per line", so losing the newlines would
     * lose the list.
     */
    List<List<String>> read(byte[] bytes);
}
