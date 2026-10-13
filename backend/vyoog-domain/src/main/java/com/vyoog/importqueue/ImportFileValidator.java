package com.vyoog.importqueue;

import com.vyoog.importqueue.prd.PrdRow;
import com.vyoog.importqueue.prd.PrdTemplateParser;
import com.vyoog.importqueue.prd.PrdTemplateResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * VYB-0957 (F25, D44): checks an import file <b>before</b> anything is stored. It reads the file exactly as extraction will (same
 * parser, same placement lookup) and reports, at once, every problem there is: the whole-file ones (unreadable, no table, a column
 * missing, too many rows) and each row's own (a required cell empty, a type or priority outside the register's, a product, app or
 * capability that does not exist). Nothing is written. A file with a whole-file problem cannot be uploaded; a row with a problem can,
 * and arrives unticked with its reason, as it always has.
 */
@Service
public class ImportFileValidator {

    /** How many rows with problems a report lists; the count of the rest is still given. */
    public static final int MAX_LISTED = 200;

    public record FileProblem(String rule, String message) {}

    public record RowProblem(int row, String ref, String title, List<String> problems) {}

    /**
     * @param format      what the file is by its content (xlsx, ods, xls, csv, docx, reqif), or null when it is not one the import reads
     * @param rows        requirements found (rows with a title or a statement)
     * @param ready       of those, how many have no problem
     * @param rowProblems the rows with problems, in file order, at most {@link #MAX_LISTED}
     * @param withProblems how many rows have problems in all (may exceed what is listed)
     */
    public record Report(String format, int rows, int ready, int withProblems, List<FileProblem> fileProblems, List<RowProblem> rowProblems) {
        public boolean canUpload() { return fileProblems.isEmpty(); }
    }

    private final PrdTemplateParser parser;
    private final PrdTemplateResolver resolver;

    public ImportFileValidator(PrdTemplateParser parser, PrdTemplateResolver resolver) {
        this.parser = parser;
        this.resolver = resolver;
    }

    /** @param applicationId the application the rows will be imported into: rows that name no product are placed there */
    public Report validate(byte[] bytes, UUID applicationId) {
        if (bytes == null || bytes.length == 0) {
            return failed(null, new FileProblem("empty-file", "The file is empty."));
        }
        String format = parser.formatOf(bytes);
        List<PrdRow> rows;
        try {
            rows = parser.parse(bytes);
        } catch (DocumentValidationException e) {
            return failed(format, new FileProblem(e.getFailedRule(), e.getMessage()));
        } catch (RuntimeException e) {
            return failed(format, new FileProblem("unreadable", "The file could not be read: " + e.getMessage()));
        }
        List<RowProblem> listed = new ArrayList<>();
        int withProblems = 0;
        for (PrdRow row : rows) {
            List<String> problems = new ArrayList<>(row.problems());
            problems.addAll(resolver.resolve(row, applicationId).problems());
            if (problems.isEmpty()) continue;
            withProblems++;
            if (listed.size() < MAX_LISTED) listed.add(new RowProblem(row.sourceRow(), row.ref(), row.title(), List.copyOf(problems)));
        }
        return new Report(format, rows.size(), rows.size() - withProblems, withProblems, List.of(), List.copyOf(listed));
    }

    private static Report failed(String format, FileProblem problem) {
        return new Report(format, 0, 0, 0, List.of(problem), List.of());
    }
}
