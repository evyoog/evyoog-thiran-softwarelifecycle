package com.vyoog.release;

import com.vyoog.platform.export.DocxFile;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * VYB-0930: release notes as a Markdown file or a Word (.docx) file, from the same notes the screen shows
 * ({@link ReleaseService#releaseNotes}): the requirements that reached Approved, grouped by capability, and,
 * separately and always, the committed requirements that are still held short of it (VYB-0484: never silently dropped).
 *
 * <p>The Word file is written here, with no document library: a .docx is a zip of a few XML parts, and release notes
 * need a title, three heading levels and list lines. Every piece of text from a requirement is escaped for the format it
 * lands in, and characters XML cannot carry are removed rather than allowed to corrupt the file. Nothing in the notes
 * is a cost, an estimate or a per-person figure (CLAUDE.md rule 7).
 */
@Component
public class ReleaseNotesExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneOffset.UTC);

    public static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String MARKDOWN_CONTENT_TYPE = "text/markdown; charset=utf-8";

    /** What the export says about the release itself. */
    public record Header(String name, ReleaseState state, Instant targetDate, Instant generatedAt) {}

    // ------------------------------------------------------------------ shared

    private static String targetLine(Header h) {
        return h.targetDate() == null ? "no target date" : "target " + DATE.format(h.targetDate());
    }

    private static int count(Map<String, List<ReleaseService.NoteItem>> byCapability) {
        return byCapability.values().stream().mapToInt(List::size).sum();
    }

    private static String summary(ReleaseService.ReleaseNotes n) {
        int approved = count(n.approvedByCapability());
        return approved + " approved, " + n.held().size() + " held";
    }

    // ---------------------------------------------------------------- markdown

    public String markdown(Header h, ReleaseService.ReleaseNotes notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(md(h.name())).append(" release notes\n\n");
        sb.append("- State: ").append(h.state().name().charAt(0)).append(h.state().name().substring(1).toLowerCase()).append('\n');
        sb.append("- ").append(capitalise(targetLine(h))).append('\n');
        sb.append("- Generated ").append(DATE.format(h.generatedAt())).append(" (").append(summary(notes)).append(")\n\n");

        if (notes.approvedByCapability().isEmpty() && notes.held().isEmpty()) {
            sb.append("Nothing is committed to this release.\n");
            return sb.toString();
        }
        sb.append("## Approved\n\n");
        if (notes.approvedByCapability().isEmpty()) {
            sb.append("No committed requirement has been approved yet.\n\n");
        }
        notes.approvedByCapability().forEach((capability, items) -> {
            sb.append("### ").append(md(capability)).append("\n\n");
            items.forEach(i -> sb.append("- ").append(md(i.key())).append(" — ").append(md(i.title())).append('\n'));
            sb.append('\n');
        });
        if (!notes.held().isEmpty()) {
            sb.append("## Held — not approved\n\n");
            sb.append("Committed to this release but not yet approved; listed here, never left out.\n\n");
            notes.held().forEach(i -> sb.append("- ").append(md(i.key())).append(" — ").append(md(i.title()))
                .append(" (").append(md(i.capabilityName())).append(")\n"));
            sb.append('\n');
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Escapes what Markdown would read as formatting or HTML, and flattens line breaks so one item stays one line. */
    static String md(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("[\\r\\n\\u2028\\u2029]+", " ").strip();
        StringBuilder sb = new StringBuilder(flat.length() + 8);
        for (char c : flat.toCharArray()) {
            if ("\\`*_{}[]<>()#+!|~".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------- docx

    public byte[] docx(Header h, ReleaseService.ReleaseNotes notes) {
        DocxFile doc = new DocxFile();
        doc.title(h.name() + " release notes");
        doc.text("State: " + h.state().name().charAt(0) + h.state().name().substring(1).toLowerCase());
        doc.text(capitalise(targetLine(h)));
        doc.text("Generated " + DATE.format(h.generatedAt()) + " (" + summary(notes) + ")");

        if (notes.approvedByCapability().isEmpty() && notes.held().isEmpty()) {
            doc.text("Nothing is committed to this release.");
        } else {
            doc.heading1("Approved");
            if (notes.approvedByCapability().isEmpty()) doc.text("No committed requirement has been approved yet.");
            notes.approvedByCapability().forEach((capability, items) -> {
                doc.heading2(capability);
                items.forEach(i -> doc.bullet(i.key() + " — " + i.title()));
            });
            if (!notes.held().isEmpty()) {
                doc.heading1("Held — not approved");
                doc.text("Committed to this release but not yet approved; listed here, never left out.");
                notes.held().forEach(i -> doc.bullet(i.key() + " — " + i.title() + " (" + i.capabilityName() + ")"));
            }
        }
        return doc.toBytes();
    }

    /** Kept for the tests that pin how text is escaped for the Word file. */
    static String xml(String text) {
        return DocxFile.xml(text);
    }
}
