package com.vyoog.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyoog.baseline.EvidencePackService.BaselineInfo;
import com.vyoog.baseline.EvidencePackService.DesignRow;
import com.vyoog.baseline.EvidencePackService.Pack;
import com.vyoog.baseline.EvidencePackService.RequirementRow;
import com.vyoog.baseline.EvidencePackService.ResultRow;
import com.vyoog.baseline.EvidencePackService.StatusChangeRow;
import com.vyoog.baseline.EvidencePackService.TestRow;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/** VYB-0948 (D39): the bundle's structure, its hashes, and that hostile cell text cannot become a formula or break a file. */
class EvidencePackWriterTest {

    private static final Instant FROZEN = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-12T09:30:00Z");
    private final EvidencePackWriter writer = new EvidencePackWriter(new ObjectMapper());

    private static Pack pack(String title, String statement) {
        var base = new BaselineInfo(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Release 4 / GA", "R-4", FROZEN, "Ada Lovelace", 2, 2);
        var r1 = new RequirementRow("VY-1", 2, title, statement, "FUNCTIONAL", "HIGH", "APPROVED", "APPROVED", 3, true, false, "Billing", "Invoicing", "Finance");
        var r2 = new RequirementRow("VY-2", 1, null, null, "NON_FUNCTIONAL", "LOW", "DRAFT", "REJECTED", 1, false, true, null, null, null);
        return new Pack(base, List.of(r1, r2),
            List.of(new DesignRow("VY-1", "step", "Export invoice", "Invoicing")),
            List.of(new TestRow("VY-1", "TC-1", "Export is checked")),
            List.of(new ResultRow("VY-1", 2, "TC-1", "FAIL", Instant.parse("2026-08-30T08:00:00Z"), "b-41", "ci", true),
                    new ResultRow("VY-1", 2, "TC-1", "PASS", Instant.parse("2026-08-31T08:00:00Z"), "b-42", "ci", true),
                    new ResultRow("VY-1", 2, null, "PASS", Instant.parse("2026-09-02T08:00:00Z"), null, null, false)),
            List.of(new StatusChangeRow("VY-1", "REVIEWED", "APPROVED", "Grace Hopper", Instant.parse("2026-08-31T12:00:00Z"), "", true),
                    new StatusChangeRow("VY-1", "APPROVED", "NEEDS_REVISION", "Ada Lovelace", Instant.parse("2026-09-05T12:00:00Z"), "Wording, then \"quoted\"", false)));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    private static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    @Test
    void VYB0948_AC1_theBundleHoldsTheReportTheDataFilesAndTheHashesInOrder() throws Exception {
        Map<String, byte[]> files = unzip(writer.build(pack("Invoice export", "The system shall export invoices."), NOW, "Grace Hopper"));
        assertThat(files.keySet()).containsExactly("README.txt", "baseline.csv", "requirements.csv", "design.csv", "tests.csv", "results.csv",
            "approvals.csv", "report.docx", "SHA256SUMS", "manifest.json");
        assertThat(text(files.get("README.txt"))).contains("Evidence pack for baseline \"Release 4 / GA\"", "Grace Hopper", "AS AT THE EXPORT", "sha256sum -c SHA256SUMS");
    }

    @Test
    void VYB0948_AC2_sha256sumsAndTheManifestMatchTheFilesAndAChangedFileIsDetectable() throws Exception {
        Map<String, byte[]> files = unzip(writer.build(pack("Invoice export", "Statement."), NOW, "Grace Hopper"));
        JsonNode manifest = new ObjectMapper().readTree(files.get("manifest.json"));
        assertThat(manifest.get("format").asText()).isEqualTo("vyoog-evidence-pack/1");
        assertThat(manifest.get("baseline").get("name").asText()).isEqualTo("Release 4 / GA");
        assertThat(manifest.get("counts").get("requirements").asInt()).isEqualTo(2);
        assertThat(manifest.get("counts").get("results").asInt()).isEqualTo(3);

        Map<String, String> listed = new LinkedHashMap<>();
        manifest.get("files").forEach(f -> listed.put(f.get("path").asText(), f.get("sha256").asText()));
        assertThat(listed.keySet()).doesNotContain("manifest.json").containsAll(files.keySet().stream().filter(n -> !n.equals("manifest.json")).toList());
        for (var e : listed.entrySet()) {
            assertThat(EvidencePackWriter.sha256(files.get(e.getKey()))).as(e.getKey()).isEqualTo(e.getValue());
        }
        for (String line : text(files.get("SHA256SUMS")).split("\n")) {
            String[] parts = line.split("  ", 2);
            assertThat(EvidencePackWriter.sha256(files.get(parts[1]))).as(parts[1]).isEqualTo(parts[0]);
        }
        manifest.get("files").forEach(f -> assertThat(files.get(f.get("path").asText()).length).isEqualTo(f.get("bytes").asInt()));
        // a different statement gives a different hash for the file that carries it, and the same for one that does not
        Map<String, byte[]> other = unzip(writer.build(pack("Invoice export", "A different statement."), NOW, "Grace Hopper"));
        assertThat(EvidencePackWriter.sha256(other.get("requirements.csv"))).isNotEqualTo(listed.get("requirements.csv"));
        assertThat(EvidencePackWriter.sha256(other.get("tests.csv"))).isEqualTo(listed.get("tests.csv"));
    }

    @Test
    void VYB0948_AC3_theSameContentGivesTheSameBytes() {
        assertThat(writer.build(pack("T", "S"), NOW, "G")).isEqualTo(writer.build(pack("T", "S"), NOW, "G"));
    }

    @Test
    void VYB0948_AC4_csvFilesCarryAByteOrderMarkQuoteCorrectlyAndRecordWhatWasNotRecorded() throws Exception {
        Map<String, byte[]> files = unzip(writer.build(pack("Title, with \"quotes\"\nand a break", "S"), NOW, "G"));
        byte[] csv = files.get("requirements.csv");
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        String body = text(csv).substring(1);
        assertThat(body).startsWith("requirement,frozen_revision,title,");
        assertThat(body).contains("\"Title, with \"\"quotes\"\"\nand a break\"");
        assertThat(body).contains("VY-2,1,(wording of this revision not recorded),,NON_FUNCTIONAL,LOW,DRAFT,REJECTED,1,no,yes,,,");
        assertThat(body).contains("\r\n");
        assertThat(text(files.get("results.csv"))).contains("(no test case)");
        assertThat(text(files.get("approvals.csv"))).contains("Grace Hopper").contains("\"Wording, then \"\"quoted\"\"\"").contains(",no\r\n");
    }

    @Test
    void VYB0948_AC5_aCellThatASpreadsheetWouldRunAsAFormulaIsNeutralised() {
        assertThat(EvidencePackWriter.cell("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(EvidencePackWriter.cell("+1+1")).isEqualTo("'+1+1");
        assertThat(EvidencePackWriter.cell("-2")).isEqualTo("'-2");
        assertThat(EvidencePackWriter.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(EvidencePackWriter.cell("\tTabbed")).isEqualTo("'\tTabbed");
        assertThat(EvidencePackWriter.cell("plain")).isEqualTo("plain");
        assertThat(EvidencePackWriter.cell(null)).isEmpty();
        assertThat(EvidencePackWriter.cell("a=b")).isEqualTo("a=b");
    }

    @Test
    void VYB0948_AC6_theReportIsAWellFormedWordFileThatSaysWhatWasFrozenWhatChangedAndWhatPassed() throws Exception {
        Map<String, byte[]> files = unzip(writer.build(pack("Invoice export", "The system shall export invoices."), NOW, "Grace Hopper"));
        Map<String, byte[]> docx = unzip(files.get("report.docx"));
        assertThat(docx.keySet()).contains("word/document.xml", "word/styles.xml", "[Content_Types].xml");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var parsed = f.newDocumentBuilder().parse(new ByteArrayInputStream(docx.get("word/document.xml")));
        String all = parsed.getDocumentElement().getTextContent();
        assertThat(text(docx.get("word/document.xml"))).contains("w:pStyle w:val=\"Title\"");
        assertThat(all).contains("Evidence pack: Release 4 / GA", "Ada Lovelace", "1 September 2026", "2 open gap(s)",
            "2 requirements frozen", "1 were Approved at the freeze", "1 have at least one linked test",
            "1 had a passing result at the frozen revision at or before the freeze", "1 have changed since the freeze", "1 have been deleted since the freeze",
            "VY-1 — Invoice export (revision 2)", "The system shall export invoices.",
            "Since the freeze: now revision 3, status APPROVED",
            "REVIEWED → APPROVED by Grace Hopper on 31 August 2026",
            "After the freeze: APPROVED → NEEDS_REVISION by Ada Lovelace",
            "Design (as at the export): step “Export invoice” in Invoicing",
            "Test TC-1 Export is checked: passed on 31 August 2026 (build b-42)",
            "1 result(s) recorded against the requirement without a test case",
            "VY-2 — (wording of this revision not recorded) (revision 1)", "deleted", "No test is linked.");
    }

    @Test
    void VYB0948_AC7_aBaselineWithNothingInItStillMakesAValidPack() throws Exception {
        var empty = new Pack(new BaselineInfo(UUID.randomUUID(), "Empty", null, FROZEN, null, 0, 0), List.of(), List.of(), List.of(), List.of(), List.of());
        Map<String, byte[]> files = unzip(writer.build(empty, NOW, "G"));
        assertThat(files).hasSize(10);
        assertThat(text(files.get("README.txt"))).contains("an unrecorded person");
        assertThat(text(files.get("SHA256SUMS")).split("\n")).hasSize(8);
        assertThat(text(files.get("requirements.csv"))).isEqualTo("﻿requirement,frozen_revision,title,statement,type,priority,status_at_freeze,"
            + "current_status,current_revision,changed_since_freeze,deleted_since_freeze,capability,application,product\r\n");
    }

    @Test
    void VYB0948_AC8_theFileNameIsSafeAndDated() {
        var b = pack("t", "s").baseline();
        assertThat(writer.filename(b, NOW)).isEqualTo("evidence-pack-Release-4-GA-2026-10-12.zip");
        assertThat(writer.filename(new BaselineInfo(UUID.randomUUID(), "///", null, FROZEN, null, 0, 0), NOW)).isEqualTo("evidence-pack-baseline-2026-10-12.zip");
    }
}
