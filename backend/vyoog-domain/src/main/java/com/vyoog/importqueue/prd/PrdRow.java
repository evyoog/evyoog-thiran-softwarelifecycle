package com.vyoog.importqueue.prd;

import java.util.List;

/**
 * One requirement as the standard PRD template states it, with the column values
 * normalised but nothing invented.
 *
 * <p>Every field here comes from a cell the author filled in. Where the template offers
 * a controlled vocabulary the value is normalised to the register's spelling; where a
 * cell names something that lives in the database — a product, an owner, a release —
 * the <em>name</em> is carried through unresolved, because this class is parsed from
 * bytes and has no business knowing what exists. {@link PrdTemplateResolver} turns
 * names into ids and says which ones it could not.
 *
 * @param sourceRow 1-based row number in the sheet, so a problem can be pointed at
 * @param problems  what this row gets wrong; a row with problems is still returned, so
 *                  the user sees the whole report at once rather than one error per
 *                  upload (Principle 8)
 */
public record PrdRow(
    int sourceRow,
    String ref,
    String product,
    String application,
    String capability,
    String title,
    String statement,
    String type,
    String priority,
    List<String> acceptanceCriteria,
    String verificationMethod,
    String owner,
    String requestedBy,
    String parentRef,
    List<String> dependsOnRefs,
    List<String> tags,
    String regulatoryReference,
    String targetRelease,
    String notes,
    List<String> problems) {

    public boolean isValid() {
        return problems.isEmpty();
    }
}
