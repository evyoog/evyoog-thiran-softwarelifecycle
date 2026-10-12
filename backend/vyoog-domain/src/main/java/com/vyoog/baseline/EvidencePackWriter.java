package com.vyoog.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.baseline.EvidencePackService.BaselineInfo;
import com.vyoog.baseline.EvidencePackService.Pack;
import com.vyoog.baseline.EvidencePackService.RequirementRow;
import com.vyoog.baseline.EvidencePackService.ResultRow;
import com.vyoog.baseline.EvidencePackService.StatusChangeRow;
import com.vyoog.platform.export.DocxFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * VYB-0948 (D39): turns an assembled {@link Pack} into the bundle (a zip): a README, one CSV per section, a Word report to read,
 * {@code SHA256SUMS} (so {@code sha256sum -c SHA256SUMS} checks every file) and {@code manifest.json} (the index: every file, its size
 * and hash, and the counts). A change to any file after the export is detectable from the manifest. The manifest is the one file
 * nothing hashes; it is the index.
 *
 * <p>Pure: no database, no clock beyond what it is given. Cells that a spreadsheet would read as a formula are neutralised (a leading
 * {@code = + - @} gets an apostrophe in front), and CSVs start with a byte-order mark so Excel reads them as UTF-8.
 */
@Component
public class EvidencePackWriter {

    public static final String FORMAT = "vyoog-evidence-pack/1";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneOffset.UTC);
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final ObjectMapper json;

    public EvidencePackWriter(ObjectMapper json) {
        this.json = json;
    }

    public String filename(BaselineInfo b, Instant generatedAt) {
        String slug = b.name().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return "evidence-pack-" + (slug.isEmpty() ? "baseline" : slug) + "-" + DAY.format(generatedAt) + ".zip";
    }

    // ------------------------------------------------------------------ the bundle

    public byte[] build(Pack pack, Instant generatedAt, String generatedBy) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.txt", utf8(readme(pack, generatedAt, generatedBy)));
        files.put("baseline.csv", csv(List.of("name", "release", "frozen_at", "frozen_by", "gaps_at_freeze", "requirements"),
            List.of(List.of(pack.baseline().name(), nz(pack.baseline().releaseName()), iso(pack.baseline().frozenAt()), nz(pack.baseline().frozenBy()),
                String.valueOf(pack.baseline().gapsAtFreeze()), String.valueOf(pack.requirements().size())))));
        files.put("requirements.csv", csv(List.of("requirement", "frozen_revision", "title", "statement", "type", "priority", "status_at_freeze",
                "current_status", "current_revision", "changed_since_freeze", "deleted_since_freeze", "capability", "application", "product"),
            pack.requirements().stream().map(r -> List.of(r.key(), String.valueOf(r.frozenRevision()), nz(r.title(), "(wording of this revision not recorded)"),
                nz(r.statement()), nz(r.type()), nz(r.priority()), r.statusAtFreeze(), r.currentStatus(), String.valueOf(r.currentRevision()),
                yn(r.changedSinceFreeze()), yn(r.deleted()), nz(r.capability()), nz(r.application()), nz(r.product()))).toList()));
        files.put("design.csv", csv(List.of("requirement", "node_kind", "node_label", "application", "as_of"),
            pack.design().stream().map(d -> List.of(d.requirementKey(), d.nodeKind(), d.nodeLabel(), nz(d.application()), "export")).toList()));
        files.put("tests.csv", csv(List.of("requirement", "test", "test_title"),
            pack.tests().stream().map(t -> List.of(t.requirementKey(), t.testKey(), t.testTitle())).toList()));
        files.put("results.csv", csv(List.of("requirement", "requirement_revision", "test", "result", "verified_at", "build", "source", "at_or_before_freeze"),
            pack.results().stream().map(r -> List.of(r.requirementKey(), String.valueOf(r.requirementRevision()), nz(r.testKey(), "(no test case)"), r.result(),
                iso(r.verifiedAt()), nz(r.buildLabel()), nz(r.source()), yn(r.atOrBeforeFreeze()))).toList()));
        files.put("approvals.csv", csv(List.of("requirement", "from_status", "to_status", "by", "at", "reason", "at_or_before_freeze"),
            pack.statusChanges().stream().map(c -> List.of(c.requirementKey(), nz(c.fromStatus()), nz(c.toStatus()), nz(c.actor(), "(system or unknown)"),
                iso(c.at()), nz(c.reason()), yn(c.atOrBeforeFreeze()))).toList()));
        files.put("report.docx", report(pack, generatedAt, generatedBy));

        Map<String, String> hashes = new LinkedHashMap<>();
        files.forEach((name, bytes) -> hashes.put(name, sha256(bytes)));
        byte[] sums = utf8(hashes.entrySet().stream().map(e -> e.getValue() + "  " + e.getKey() + "\n").collect(Collectors.joining()));
        files.put("SHA256SUMS", sums);
        hashes.put("SHA256SUMS", sha256(sums));
        files.put("manifest.json", manifest(pack, generatedAt, generatedBy, files, hashes));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0); // the same content gives the same bytes
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the evidence pack", e);
        }
    }

    private byte[] manifest(Pack pack, Instant generatedAt, String generatedBy, Map<String, byte[]> files, Map<String, String> hashes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("generatedAt", iso(generatedAt));
        m.put("generatedBy", generatedBy);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", pack.baseline().id().toString());
        b.put("name", pack.baseline().name());
        b.put("release", pack.baseline().releaseName());
        b.put("frozenAt", iso(pack.baseline().frozenAt()));
        b.put("frozenBy", pack.baseline().frozenBy());
        b.put("gapsAtFreeze", pack.baseline().gapsAtFreeze());
        m.put("baseline", b);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("requirements", pack.requirements().size());
        counts.put("designNodeLinks", pack.design().size());
        counts.put("tests", pack.tests().size());
        counts.put("results", pack.results().size());
        counts.put("statusChanges", pack.statusChanges().size());
        m.put("counts", counts);
        List<Map<String, Object>> list = new ArrayList<>();
        files.forEach((name, bytes) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", name);
            f.put("bytes", bytes.length);
            f.put("sha256", hashes.get(name));
            list.add(f);
        });
        m.put("files", list);
        m.put("note", "manifest.json is the index and is not itself hashed. Every other file is listed with its SHA-256.");
        try {
            return utf8(json.writerWithDefaultPrettyPrinter().writeValueAsString(m) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the manifest", e);
        }
    }

    // ------------------------------------------------------------------ the readable parts

    private String readme(Pack pack, Instant generatedAt, String generatedBy) {
        BaselineInfo b = pack.baseline();
        return """
            Evidence pack for baseline "%s"
            ================================

            Baseline:      %s%s
            Frozen:        %s by %s, with %d open gap(s) recorded at the freeze
            Requirements:  %d
            Exported:      %s by %s
            Format:        %s

            What is in this bundle
            ----------------------
            report.docx      A report to read: the figures, then each requirement as it was frozen.
            baseline.csv     The baseline's own record.
            requirements.csv Each frozen requirement at the revision that was frozen (its wording, type, priority), its status at the
                             freeze, and where it stands now (current revision and status, changed or deleted since).
            design.csv       The design nodes drawn for each requirement. Design has no history: these are AS AT THE EXPORT, not as frozen.
            tests.csv        The tests linked to each requirement.
            results.csv      Every test result recorded against the frozen revision, with when and in which build, and whether it was
                             recorded at or before the freeze.
            approvals.csv    Every move of a requirement between statuses (the reviews and approvals), by whom and when, with whether it
                             was at or before the freeze.
            manifest.json    The index: every file with its size and SHA-256, and the counts.
            SHA256SUMS       The same hashes, so that `sha256sum -c SHA256SUMS` checks every file.

            How to read it
            --------------
            * "Status at freeze" is the last recorded move at or before the freeze; a requirement with none was still a draft. It is read
              from the audit log, so moves older than the log's retention may be missing.
            * A pass or a fail is recorded against a requirement's revision. Only results for the frozen revision are in this pack.
            * Dates are UTC (ISO 8601). CSV files are UTF-8 with a byte-order mark and open directly in Excel. A cell that begins with
              = + - or @ has an apostrophe in front so a spreadsheet does not run it as a formula.
            * Nothing here is a cost, an estimate or a figure about a person's productivity.
            * manifest.json is the one file nothing hashes: it is the index.
            """.formatted(b.name(), b.name(), b.releaseName() == null ? "" : " (release " + b.releaseName() + ")", iso(b.frozenAt()),
            nz(b.frozenBy(), "an unrecorded person"), b.gapsAtFreeze(), pack.requirements().size(), iso(generatedAt), generatedBy, FORMAT);
    }

    private byte[] report(Pack pack, Instant generatedAt, String generatedBy) {
        BaselineInfo b = pack.baseline();
        Map<String, List<ResultRow>> results = pack.results().stream().collect(Collectors.groupingBy(ResultRow::requirementKey));
        Map<String, List<StatusChangeRow>> changes = pack.statusChanges().stream().collect(Collectors.groupingBy(StatusChangeRow::requirementKey));
        Map<String, List<EvidencePackService.TestRow>> tests = pack.tests().stream().collect(Collectors.groupingBy(EvidencePackService.TestRow::requirementKey));
        Map<String, List<EvidencePackService.DesignRow>> design = pack.design().stream().collect(Collectors.groupingBy(EvidencePackService.DesignRow::requirementKey));

        long approvedAtFreeze = pack.requirements().stream().filter(r -> "APPROVED".equals(r.statusAtFreeze())).count();
        long withTests = pack.requirements().stream().filter(r -> tests.containsKey(r.key())).count();
        long passedAtFreeze = pack.requirements().stream()
            .filter(r -> results.getOrDefault(r.key(), List.of()).stream().anyMatch(x -> "PASS".equals(x.result()) && x.atOrBeforeFreeze())).count();
        long changed = pack.requirements().stream().filter(RequirementRow::changedSinceFreeze).count();
        long deleted = pack.requirements().stream().filter(RequirementRow::deleted).count();

        DocxFile doc = new DocxFile();
        doc.title("Evidence pack: " + b.name());
        doc.text("Baseline " + b.name() + (b.releaseName() == null ? "" : " (release " + b.releaseName() + ")") + ", frozen "
            + LONG_DAY.format(b.frozenAt()) + " by " + nz(b.frozenBy(), "an unrecorded person") + ", with " + b.gapsAtFreeze() + " open gap(s) recorded at the freeze.");
        doc.text("Exported " + LONG_DAY.format(generatedAt) + " by " + generatedBy + ". Format " + FORMAT + ". The files beside this report carry the same data; SHA256SUMS and manifest.json let anyone check that nothing changed after the export.");

        doc.heading1("In figures");
        doc.bullet(pack.requirements().size() + " requirements frozen");
        doc.bullet(approvedAtFreeze + " were Approved at the freeze");
        doc.bullet(withTests + " have at least one linked test");
        doc.bullet(passedAtFreeze + " had a passing result at the frozen revision at or before the freeze");
        doc.bullet(changed + " have changed since the freeze (a newer revision exists)");
        if (deleted > 0) doc.bullet(deleted + " have been deleted since the freeze");
        doc.text("Design links have no history, so they are shown as at the export.");

        doc.heading1("Requirements as frozen");
        if (pack.requirements().isEmpty()) doc.text("This baseline holds no requirements.");
        for (RequirementRow r : pack.requirements()) {
            doc.heading2(r.key() + " — " + nz(r.title(), "(wording of this revision not recorded)") + " (revision " + r.frozenRevision() + ")");
            doc.text(nz(r.statement(), "(statement of this revision not recorded)"));
            doc.bullet("Status at the freeze: " + r.statusAtFreeze() + (r.type() == null ? "" : " · " + r.type() + " · " + nz(r.priority())));
            String place = java.util.stream.Stream.of(r.product(), r.application(), r.capability()).filter(x -> x != null).collect(Collectors.joining(" › "));
            if (!place.isEmpty()) doc.bullet("Placed at: " + place);
            if (r.changedSinceFreeze() || r.deleted()) {
                doc.bullet("Since the freeze: now revision " + r.currentRevision() + ", status " + r.currentStatus() + (r.deleted() ? ", deleted" : ""));
            }
            for (StatusChangeRow c : changes.getOrDefault(r.key(), List.of())) {
                doc.bullet((c.atOrBeforeFreeze() ? "" : "After the freeze: ") + nz(c.fromStatus(), "—") + " → " + nz(c.toStatus(), "—") + " by "
                    + nz(c.actor(), "(system or unknown)") + " on " + LONG_DAY.format(c.at()) + (c.reason() == null || c.reason().isBlank() ? "" : " (" + c.reason() + ")"));
            }
            for (var d : design.getOrDefault(r.key(), List.of())) {
                doc.bullet("Design (as at the export): " + d.nodeKind() + " “" + d.nodeLabel() + "”" + (d.application() == null ? "" : " in " + d.application()));
            }
            var ts = tests.getOrDefault(r.key(), List.of());
            if (ts.isEmpty()) doc.bullet("No test is linked.");
            for (var t : ts) {
                var mine = results.getOrDefault(r.key(), List.of()).stream().filter(x -> t.testKey().equals(x.testKey())).toList();
                String last = mine.isEmpty() ? "no result at this revision" : lastResult(mine);
                doc.bullet("Test " + t.testKey() + " " + t.testTitle() + ": " + last);
            }
            long untested = results.getOrDefault(r.key(), List.of()).stream().filter(x -> x.testKey() == null).count();
            if (untested > 0) doc.bullet(untested + " result(s) recorded against the requirement without a test case");
        }
        return doc.toBytes();
    }

    private static String lastResult(List<ResultRow> mine) {
        ResultRow last = mine.get(mine.size() - 1);
        return (last.result().equals("PASS") ? "passed" : "failed") + " on " + LONG_DAY.format(last.verifiedAt())
            + (last.buildLabel() == null ? "" : " (build " + last.buildLabel() + ")") + (last.atOrBeforeFreeze() ? "" : ", after the freeze");
    }

    // ------------------------------------------------------------------ small helpers

    /** RFC 4180: CRLF, a field is quoted when it holds a comma, a quote or a line break. A formula-looking cell is neutralised. */
    byte[] csv(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(row(header));
        for (List<String> r : rows) sb.append(row(r));
        byte[] body = utf8(sb.toString());
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    private static String row(List<String> cells) {
        return cells.stream().map(EvidencePackWriter::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String iso(Instant t) { return t == null ? "" : t.toString(); }
    private static String yn(boolean b) { return b ? "yes" : "no"; }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nz(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
