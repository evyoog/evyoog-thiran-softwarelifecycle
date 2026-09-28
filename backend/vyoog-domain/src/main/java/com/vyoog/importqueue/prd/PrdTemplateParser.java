package com.vyoog.importqueue.prd;

import com.vyoog.importqueue.DocumentValidationException;
import com.vyoog.importqueue.sheet.CsvSheetReader;
import com.vyoog.importqueue.sheet.OdsSheetReader;
import com.vyoog.importqueue.sheet.SheetReader;
import com.vyoog.importqueue.sheet.XlsSheetReader;
import com.vyoog.importqueue.sheet.XlsxSheetReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads the standard PRD template into requirements, deterministically.
 *
 * <p>No model runs here, by design. The template exists precisely so the author has
 * already made every judgement an extraction agent would otherwise be guessing at —
 * which sentence is the requirement, what type it is, what "done" means. Re-deriving
 * those from prose when the author has written them in a labelled column would be
 * throwing away the better answer to reproduce it worse and non-repeatably. AI has a
 * job on this path, but it is the one after this: reading the imported requirements and
 * suggesting how to improve them, which the reviewer accepts or ignores (Principle 6).
 *
 * <p>So the contract is that the same file always yields the same rows, and that
 * anything the parser cannot honestly determine becomes a reported problem rather than
 * a default. Structure is found rather than assumed — the header row is located by its
 * labels, so inserting a note above it or reordering columns does not silently shift
 * every value one column left.
 */
@Component
public class PrdTemplateParser {

    /** How far down to look for the header before concluding this is not the template. */
    private static final int HEADER_SEARCH_DEPTH = 25;

    /**
     * How many mandatory labels a row must carry to be the header. The instruction block
     * above the real header quotes column names in its prose ("fill in Requirement Title
     * before…"), so matching on any single label picks the wrong row and then reports
     * every genuine column as missing. Three is enough to be unambiguous while still
     * letting a sheet that has dropped some columns be diagnosed properly rather than
     * dismissed as "not the template".
     */
    private static final int HEADER_MATCH_THRESHOLD = 3;

    /**
     * The columns without which a row is not a requirement. These are the same seven the
     * template's own "Ready?" formula counts, so the sheet and the importer agree on
     * what complete means and a row showing "OK" always imports.
     */
    private static final List<String> MANDATORY =
        List.of("product", "app", "capability", "requirement title", "requirement statement", "type", "priority");

    /** Header label to canonical key. Aliases exist because people rename columns. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("application", "app"),
        Map.entry("title", "requirement title"),
        Map.entry("statement", "requirement statement"),
        Map.entry("requirement", "requirement statement"),
        Map.entry("description", "requirement statement"),
        Map.entry("acceptance criteria", "acceptance criteria"),
        Map.entry("ac", "acceptance criteria"),
        Map.entry("reference", "your ref"),
        Map.entry("ref", "your ref"),
        Map.entry("external id", "your ref"),
        Map.entry("source", "source / requested by"),
        Map.entry("requested by", "source / requested by"),
        Map.entry("release", "target release"),
        Map.entry("parent", "parent requirement"));

    private static final Set<String> TYPES = Set.of(
        "FUNCTIONAL", "NON_FUNCTIONAL", "BUSINESS_RULE", "INTERFACE", "DATA", "REPORT", "SECURITY", "COMPLIANCE");
    private static final Set<String> PRIORITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /**
     * Spellings the template's own dropdown offers that are not the register's. Kept as
     * an explicit list rather than a fuzzy match: silently accepting "non functional
     * perf" as NON_FUNCTIONAL would be guessing, and a guess in a column the author
     * chose from a dropdown is never warranted.
     */
    private static final Map<String, String> TYPE_SYNONYMS = Map.of(
        "NONFUNCTIONAL", "NON_FUNCTIONAL",
        "QUALITY", "NON_FUNCTIONAL",
        "RULE", "BUSINESS_RULE",
        "INTEGRATION", "INTERFACE",
        "REPORTING", "REPORT",
        "REGULATORY", "COMPLIANCE");

    /**
     * Order matters: the binary formats identify themselves by content, and CSV is the
     * fallback for anything that is honestly text. Putting CSV first would have it claim
     * a .ods, since a ZIP is bytes like any other.
     */
    private final List<SheetReader> readers =
        List.of(new OdsSheetReader(), new XlsxSheetReader(), new XlsSheetReader(), new CsvSheetReader());

    /** True when this looks like a spreadsheet at all — not that it is the template. */
    public boolean supports(byte[] bytes) {
        return readers.stream().anyMatch(r -> r.supports(bytes));
    }

    public List<PrdRow> parse(byte[] bytes) {
        SheetReader reader = readers.stream().filter(r -> r.supports(bytes)).findFirst().orElseThrow(
            () -> new DocumentValidationException("not-a-spreadsheet",
                "This file is not a spreadsheet. Upload the template as .ods, .xlsx, .xls or .csv."));

        List<List<String>> rows = reader.read(bytes);
        int headerIndex = findHeader(rows);
        Map<String, Integer> columns = mapColumns(rows.get(headerIndex));

        List<String> missing = MANDATORY.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            // A missing mandatory column is a whole-file problem, not a per-row one:
            // every row would carry the same complaint, and importing with a column
            // absent would put nulls into fields the register requires.
            throw new DocumentValidationException("missing-columns",
                "The sheet is missing required column%s: %s. Download a fresh copy of the PRD template, or rename your columns to match.".formatted(
                    missing.size() == 1 ? "" : "s", String.join(", ", missing)));
        }

        List<PrdRow> out = new ArrayList<>();
        for (int i = headerIndex + 1; i < rows.size(); i++) {
            PrdRow row = readRow(rows.get(i), columns, i + 1);
            if (row != null) {
                out.add(row);
            }
        }
        if (out.isEmpty()) {
            throw new DocumentValidationException("no-rows",
                "The template's columns are all present but no row below the header has a title or a statement, so there is nothing to import.");
        }
        return out;
    }

    /**
     * Finds the header by its labels rather than trusting a fixed row number. The
     * shipped template puts a banner, an instruction block and a legend above the
     * header, and any of those can grow by a line without the file being wrong.
     */
    private static int findHeader(List<List<String>> rows) {
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < Math.min(HEADER_SEARCH_DEPTH, rows.size()); i++) {
            Map<String, Integer> columns = mapColumns(rows.get(i));
            int score = (int) MANDATORY.stream().filter(columns::containsKey).count();
            // Strictly greater, so the first and topmost of equally good candidates wins
            // — the header, never a repeat of it frozen further down the sheet.
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (bestScore < HEADER_MATCH_THRESHOLD) {
            throw new DocumentValidationException("no-header",
                "No header row was found in the first %d rows. This does not look like the standard PRD template — the header must name its columns, starting with Product, App and Capability."
                    .formatted(HEADER_SEARCH_DEPTH));
        }
        return best;
    }

    /**
     * Canonical column name to its index. First occurrence wins, so a stray duplicate
     * further right cannot displace the real column.
     */
    private static Map<String, Integer> mapColumns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = canonical(header.get(i));
            if (!key.isEmpty()) {
                columns.putIfAbsent(key, i);
            }
        }
        return columns;
    }

    /**
     * Normalises a header cell to its canonical key. The template puts help text under
     * the label in the same cell and marks mandatory columns with an asterisk, so only
     * the first line matters and the marker is not part of the name.
     */
    private static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        String first = raw.split("\n", 2)[0]
            .replace("*", "")
            .replace("(required)", "")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
        return ALIASES.getOrDefault(first, first);
    }

    /** @return the parsed row, or null when the row is blank and should be skipped. */
    private static PrdRow readRow(List<String> cells, Map<String, Integer> columns, int sourceRow) {
        String title = cell(cells, columns, "requirement title");
        String statement = cell(cells, columns, "requirement statement");
        if (title.isEmpty() && statement.isEmpty()) {
            // Spreadsheets carry trailing rows that hold formatting, or a stray value in
            // a note column. Without a title or a statement there is no requirement here,
            // and reporting each as a problem would bury the real ones.
            return null;
        }

        List<String> problems = new ArrayList<>();
        for (String column : MANDATORY) {
            if (cell(cells, columns, column).isEmpty()) {
                problems.add("%s is required".formatted(label(column)));
            }
        }

        String type = vocabulary(cell(cells, columns, "type"), TYPES, TYPE_SYNONYMS);
        if (type == null && !cell(cells, columns, "type").isEmpty()) {
            problems.add("Type \"%s\" is not one of %s".formatted(
                cell(cells, columns, "type"), String.join(", ", sorted(TYPES))));
        }
        String priority = vocabulary(cell(cells, columns, "priority"), PRIORITIES, Map.of());
        if (priority == null && !cell(cells, columns, "priority").isEmpty()) {
            problems.add("Priority \"%s\" is not one of %s".formatted(
                cell(cells, columns, "priority"), String.join(", ", sorted(PRIORITIES))));
        }

        return new PrdRow(
            sourceRow,
            cell(cells, columns, "your ref"),
            cell(cells, columns, "product"),
            cell(cells, columns, "app"),
            cell(cells, columns, "capability"),
            title,
            statement,
            type,
            priority,
            lines(cell(cells, columns, "acceptance criteria")),
            cell(cells, columns, "verification method"),
            cell(cells, columns, "owner"),
            cell(cells, columns, "source / requested by"),
            cell(cells, columns, "parent requirement"),
            list(cell(cells, columns, "depends on")),
            list(cell(cells, columns, "tags")),
            cell(cells, columns, "regulatory reference"),
            cell(cells, columns, "target release"),
            cell(cells, columns, "notes"),
            List.copyOf(problems));
    }

    private static String label(String canonical) {
        return Character.toUpperCase(canonical.charAt(0)) + canonical.substring(1);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    /** Absent column or short row both mean "not filled in", never an exception. */
    private static String cell(List<String> cells, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
    }

    /** @return the register's spelling, or null when the cell is empty or unrecognised. */
    private static String vocabulary(String raw, Set<String> allowed, Map<String, String> synonyms) {
        if (raw.isEmpty()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (allowed.contains(key)) {
            return key;
        }
        String synonym = synonyms.get(key.replace("_", ""));
        return synonym != null && allowed.contains(synonym) ? synonym : null;
    }

    /**
     * Splits a multi-criterion cell into one criterion per entry. The template asks for
     * one per line, and the numbering people add is presentation — keeping "1." on the
     * front would put it in the criterion text and then renumber wrongly on display.
     */
    private static List<String> lines(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        return raw.lines()
            .map(l -> l.replaceFirst("^\\s*(?:[0-9]+[.)]|[-*•])\\s*", "").trim())
            .filter(l -> !l.isEmpty())
            .toList();
    }

    /** Comma- or newline-separated cells: tags, dependency references. */
    private static List<String> list(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,\n;]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    }
}
