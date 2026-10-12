package com.vyoog.clause;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.platform.audit.AuditService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0950 (F41, D27, D41): standard templates for the clause register. A template is a starter set of clauses for one framework,
 * shipped with the application (a JSON file under {@code clause-templates/}); applying it adds the clauses the register does not
 * already have. They are ordinary clauses afterwards: edited, mapped and deleted like any other.
 *
 * <p>A template carries our own short paraphrase of what each clause is for, never the standard's text (the standards are
 * copyrighted); the template's note tells the reader to check the numbering against a licensed copy. The files are read and checked when
 * the application starts, so a malformed template stops the application rather than failing on first use.
 */
@Service
public class ClauseTemplateService {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Entry(String section, String title, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Template(String id, String name, String standard, String note, List<Entry> clauses) {}

    /** A template as the register screen lists it: how many clauses it holds and how many the register already has. */
    public record TemplateSummary(String id, String name, String standard, String note, int clauses, int alreadyInRegister) {}

    public record Applied(String templateId, int added, int skipped, List<String> skippedSections) {}

    private final List<Template> templates;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ClauseTemplateService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.templates = load(json);
    }

    private static List<Template> load(ObjectMapper json) {
        List<Template> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:clause-templates/*.json")) {
                try (InputStream in = r.getInputStream()) {
                    Template t = json.readValue(in, Template.class);
                    validate(t, String.valueOf(r.getFilename()));
                    if (!ids.add(t.id())) throw new IllegalStateException("Clause template id used twice: " + t.id());
                    out.add(t);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the clause templates", e);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(out);
    }

    /** What every template must satisfy, so applying one can never be refused by the register's own rules. */
    static void validate(Template t, String file) {
        if (t == null || blank(t.id()) || blank(t.name()) || blank(t.standard()) || t.clauses() == null || t.clauses().isEmpty()) {
            throw new IllegalStateException("Clause template " + file + " needs an id, a name, a standard and at least one clause");
        }
        if (t.standard().length() > ClauseService.MAX_STANDARD) throw new IllegalStateException(file + ": standard is too long");
        Set<String> sections = new HashSet<>();
        for (Entry e : t.clauses()) {
            if (blank(e.section()) || blank(e.title()) || blank(e.text())) throw new IllegalStateException(file + ": every clause needs a section, a title and a text");
            if (e.section().length() > ClauseService.MAX_SECTION) throw new IllegalStateException(file + ": section too long: " + e.section());
            if (text(e).length() > ClauseService.MAX_TEXT) throw new IllegalStateException(file + ": clause text too long: " + e.section());
            if (!sections.add(e.section().strip().toLowerCase())) throw new IllegalStateException(file + ": section used twice: " + e.section());
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** What is stored as the clause's text: the title, then what it is for. */
    static String text(Entry e) { return e.title().strip() + ". " + e.text().strip(); }

    public List<Template> all() { return templates; }

    private Template find(String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("No such template"));
    }

    private Set<String> sectionsInRegister(String standard) {
        return new HashSet<>(jdbc.query("SELECT lower(section) FROM clause WHERE lower(standard) = lower(?) AND section IS NOT NULL",
            (rs, n) -> rs.getString(1), standard));
    }

    public List<TemplateSummary> summaries() {
        return templates.stream().map(t -> {
            Set<String> have = sectionsInRegister(t.standard());
            int already = (int) t.clauses().stream().filter(e -> have.contains(e.section().strip().toLowerCase())).count();
            return new TemplateSummary(t.id(), t.name(), t.standard(), t.note(), t.clauses().size(), already);
        }).toList();
    }

    /**
     * Adds the template's clauses that the register does not already have (the same standard and section, case-insensitive); the
     * ones it has are left exactly as they are. One audit event records what was added. Clauses are not rescanned one by one: the
     * unmapped-clause rule picks them up at its next sweep.
     */
    @Transactional
    public Applied apply(String templateId, UUID actor) {
        Template t = find(templateId);
        Set<String> have = sectionsInRegister(t.standard());
        int added = 0;
        List<String> skipped = new ArrayList<>();
        for (Entry e : t.clauses()) {
            String key = e.section().strip().toLowerCase();
            if (have.contains(key)) { skipped.add(e.section().strip()); continue; }
            jdbc.update("INSERT INTO clause (standard, section, text) VALUES (?, ?, ?)", t.standard(), e.section().strip(), text(e));
            have.add(key);
            added++;
        }
        audit.record(actor, "clause.template_applied", "CLAUSE_TEMPLATE", null, null,
            Map.of("template", t.id(), "standard", t.standard(), "added", added, "skipped", skipped.size()));
        return new Applied(t.id(), added, skipped.size(), List.copyOf(skipped));
    }
}
