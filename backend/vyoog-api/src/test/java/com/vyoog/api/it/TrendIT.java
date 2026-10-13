package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.vyoog.release.ReadinessSnapshotService;
import com.vyoog.release.ReleaseService;
import com.vyoog.report.TrendReportService;
import com.vyoog.report.TrendService;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0956 (F24, D43): the trends for defects, test pass rate and release readiness, the nightly readiness record, and the xlsx and
 * PDF files. Against a real PostgreSQL, with history written straight into the tables so the dates are exact, and HTTP with real
 * tokens. Every figure is asserted through the portfolio the test made, so other data in the database does not move it.
 */
@AutoConfigureMockMvc
class TrendIT extends IntegrationTestBase {

    @Autowired TrendService trends;
    @Autowired TrendReportService reports;
    @Autowired ReadinessSnapshotService snapshots;
    @Autowired ReleaseService releases;
    @Autowired MockMvc mvc;

    private Portfolio p;
    private UUID author, admin;
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private final LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    @BeforeEach
    void fixtures() {
        p = newPortfolio();
        author = newUser("author");
        admin = newAdministrator();
    }

    private static Timestamp at(LocalDate day, int hour) { return Timestamp.from(day.atTime(hour, 0).toInstant(ZoneOffset.UTC)); }

    private UUID defect(UUID requirementId, LocalDate raised, String finalState) {
        return jdbc.queryForObject("""
            INSERT INTO defect (key, title, severity, requirement_id, found_in, state, raised_at)
            VALUES (?, 'A defect', 'MEDIUM', ?, 'QA', ?, ?) RETURNING id
            """, UUID.class, unique("DEF"), requirementId, finalState, at(raised, 9));
    }

    private void move(UUID defect, String from, String to, LocalDate day) {
        jdbc.update("INSERT INTO defect_transition (defect_id, from_state, to_state, reason, changed_at) VALUES (?, ?, ?, 'because', ?)", defect, from, to, at(day, 11));
    }

    private void result(UUID requirementId, String result, LocalDate day) {
        jdbc.update("INSERT INTO verification (requirement_id, requirement_revision, result, verified_at) VALUES (?, 1, ?, ?)", requirementId, result, at(day, 12));
    }

    private List<TrendService.DefectWeek> defectsOf(UUID application, int days) {
        return trends.trends(null, application, days, null).defects();
    }

    // ------------------------------------------------------------------ defects

    @Test
    void VYB0956_AC2_theDefectTrendCountsRaisedClosedAndTheBacklogAtTheEndOfEachWeekAndAReopenedDefectIsBackInIt() {
        UUID req = newRequirement(p, author).getId();
        LocalDate twoAgo = thisMonday.minusWeeks(2).plusDays(2), lastWeek = thisMonday.minusWeeks(1).plusDays(1);

        UUID a = defect(req, twoAgo, "CLOSED");                    // raised two weeks ago, closed last week
        move(a, "OPEN", "FIXED", lastWeek);
        move(a, "FIXED", "CLOSED", lastWeek.plusDays(1));
        UUID b = defect(req, lastWeek, "OPEN");                     // raised last week, still open
        UUID c = defect(req, thisMonday, "OPEN");                   // raised this week, closed, then reopened
        move(c, "OPEN", "CLOSED", thisMonday);
        move(c, "CLOSED", "OPEN", thisMonday.plusDays(1));

        var weeks = defectsOf(p.applicationId(), 30);

        assertThat(weeks).hasSize(5);
        assertThat(weeks.get(4).weekStart()).isEqualTo(thisMonday);
        assertThat(weeks.stream().map(TrendService.DefectWeek::weekStart)).isSorted();
        var w2 = weeks.get(2); var w3 = weeks.get(3); var w4 = weeks.get(4);
        assertThat(weeks.subList(0, 2)).allSatisfy(w -> assertThat(List.of(w.opened(), w.closed(), w.open())).containsExactly(0, 0, 0));
        assertThat(List.of(w2.opened(), w2.closed(), w2.open())).as("two weeks ago: A raised and open").containsExactly(1, 0, 1);
        assertThat(List.of(w3.opened(), w3.closed(), w3.open())).as("last week: B raised, A closed, B open").containsExactly(1, 1, 1);
        assertThat(List.of(w4.opened(), w4.closed(), w4.open())).as("this week: C raised and closed once, reopened, so B and C are open").containsExactly(1, 1, 2);
        assertThat(b).isNotNull();
    }

    @Test
    void VYB0956_AC3_theFiltersNarrowToTheApplicationOrProductAndADefectWithNoRequirementCountsOnlyUnfiltered() {
        UUID req = newRequirement(p, author).getId();
        // a second application in the same product, and a different product altogether
        UUID app2 = jdbc.queryForObject("INSERT INTO application (product_id, name) VALUES (?, ?) RETURNING id", UUID.class, p.productId(), unique("app2"));
        UUID cap2 = jdbc.queryForObject("INSERT INTO capability (application_id, name) VALUES (?, ?) RETURNING id", UUID.class, app2, unique("cap2"));
        UUID req2 = requirementService.create(unique("T"), "The system shall " + unique("x") + ".", "FUNCTIONAL", "MEDIUM",
            com.vyoog.requirements.Placement.capability(cap2), author).getId();
        Portfolio other = newPortfolio();
        UUID reqOther = newRequirement(other, author).getId();

        defect(req, thisMonday, "OPEN");
        defect(req2, thisMonday, "OPEN");
        defect(reqOther, thisMonday, "OPEN");
        int unscopedBefore = trends.trends(null, null, 30, null).defects().get(4).open();
        UUID orphan = defect(null, thisMonday, "OPEN");

        assertThat(defectsOf(p.applicationId(), 30).get(4).open()).as("one application").isEqualTo(1);
        assertThat(defectsOf(app2, 30).get(4).open()).isEqualTo(1);
        assertThat(trends.trends(p.productId(), null, 30, null).defects().get(4).open()).as("the whole product: both applications").isEqualTo(2);
        assertThat(trends.trends(p.productId(), p.applicationId(), 30, null).defects().get(4).open()).as("both filters together").isEqualTo(1);
        assertThat(trends.trends(null, null, 30, null).defects().get(4).open()).as("unfiltered gains the defect with no requirement").isEqualTo(unscopedBefore + 1);
        assertThat(defectsOf(p.applicationId(), 30).get(4).open()).as("a filter leaves it out").isEqualTo(1);
        assertThat(orphan).isNotNull();
    }

    // ------------------------------------------------------------------ pass rate

    @Test
    void VYB0956_AC4_thePassRateIsTheShareOfResultsThatPassedAndAWeekWithNoResultsHasNoRateNotZero() {
        UUID req = newRequirement(p, author).getId();
        LocalDate lastWeek = thisMonday.minusWeeks(1).plusDays(1), twoAgo = thisMonday.minusWeeks(2).plusDays(1);
        for (int i = 0; i < 3; i++) result(req, "PASS", lastWeek);
        result(req, "FAIL", lastWeek);
        result(req, "FAIL", twoAgo);
        result(req, "FAIL", twoAgo);

        var weeks = trends.trends(null, p.applicationId(), 30, null).passRate();

        assertThat(weeks).hasSize(5);
        assertThat(weeks.get(3).passed()).isEqualTo(3);
        assertThat(weeks.get(3).failed()).isEqualTo(1);
        assertThat(weeks.get(3).rate()).isEqualTo(0.75);
        assertThat(weeks.get(2).rate()).as("two failures and nothing passed is a real zero").isEqualTo(0.0);
        assertThat(weeks.get(4).rate()).as("this week has no results at all").isNull();
        assertThat(weeks.get(0).rate()).isNull();
        assertThat(trends.trends(null, p.applicationId(), 30, null).passRate().get(3).rate()).isEqualTo(0.75);
        Portfolio other = newPortfolio();
        assertThat(trends.trends(null, other.applicationId(), 30, null).passRate()).allSatisfy(w -> assertThat(w.rate()).isNull());
    }

    // ------------------------------------------------------------------ readiness

    private UUID releaseWith(int approvedCount, int plainCount) {
        UUID id = releases.create(unique("R")).getId();
        java.util.List<UUID> made = new java.util.ArrayList<>();
        for (int i = 0; i < approvedCount; i++) {
            UUID r = approved(newRequirement(p, author), author, admin).getId();
            made.add(r);
            releases.commit(id, r, admin, "in");
        }
        for (int i = 0; i < plainCount; i++) {
            UUID r = newRequirement(p, author).getId();
            made.add(r);
            releases.commit(id, r, admin, "in");
        }
        awaitEnrichment(made); // their gaps are found on another thread; count them only once it has finished
        return id;
    }

    private int snapshotRows(UUID release) {
        return jdbc.queryForObject("SELECT count(*) FROM release_readiness_snapshot WHERE release_id = ?", Integer.class, release);
    }

    @Test
    void VYB0956_AC5_theNightlyRecordHoldsTheFiguresTheReleaseScreenUsesOneRowPerReleasePerDayAndATwiceRunReplacesIt() {
        UUID rel = releaseWith(1, 1);
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'report.readiness_snapshot_taken'", Integer.class);

        snapshots.takeAll(today);
        snapshots.takeAll(today);

        assertThat(snapshotRows(rel)).as("the second run replaced the first").isEqualTo(1);
        var s = snapshots.history(rel, today).get(0);
        var live = releases.readiness(rel);
        assertThat(s.takenOn()).isEqualTo(today);
        assertThat(s.committed()).isEqualTo(2);
        assertThat(s.approved()).isEqualTo(1);
        assertThat(s.verified()).isEqualTo(live.verified());
        assertThat(s.blocked()).isEqualTo(releases.blocked(rel).size());
        assertThat(s.criticalGaps()).isEqualTo(live.criticalOpenGaps());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'report.readiness_snapshot_taken'", Integer.class))
            .as("one audit event for each run").isEqualTo(audits + 2);
        assertThat(snapshots.measure(rel, today)).isEqualTo(s);
    }

    @Test
    void VYB0956_AC6_aReleaseIsRecordedWhileItIsBeingWorkedAndTheDayAfterItIsReleasedButNotLongAfter() {
        UUID working = releaseWith(0, 1);
        UUID justReleased = releaseWith(0, 1);
        UUID longAgo = releaseWith(0, 1);
        for (UUID id : List.of(justReleased, longAgo)) jdbc.update("UPDATE release SET state = 'RELEASED' WHERE id = ?", id);
        jdbc.update("INSERT INTO release_transition (release_id, from_state, to_state, changed_at, signature_acr) VALUES (?, 'FROZEN', 'RELEASED', ?, 'it')", justReleased, at(today, 1));
        jdbc.update("INSERT INTO release_transition (release_id, from_state, to_state, changed_at, signature_acr) VALUES (?, 'FROZEN', 'RELEASED', ?, 'it')", longAgo, at(today.minusDays(5), 1));

        snapshots.takeAll(today);

        assertThat(snapshotRows(working)).isEqualTo(1);
        assertThat(snapshotRows(justReleased)).as("its last figures are kept").isEqualTo(1);
        assertThat(snapshotRows(longAgo)).as("a release long since out is left alone").isZero();
    }

    @Test
    void VYB0956_AC7_theReadinessTrendIsTheRecordedDaysInOrderWithADayNotRecordedMissingAndTheLiveFiguresBesideThem() {
        UUID rel = releaseWith(1, 0);
        jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 4, 1, 0, 3, 1)", rel, today.minusDays(10));
        jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 4, 3, 2, 1, 0)", rel, today.minusDays(3));
        jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 1, 1, 1, 0, 0)", rel, today.minusDays(200));

        var t = trends.trends(null, null, 30, rel).readiness();

        assertThat(t.releaseId()).isEqualTo(rel);
        assertThat(t.history()).as("only the days in the period, oldest first, and the days between are simply absent")
            .extracting(s -> s.takenOn()).containsExactly(today.minusDays(10), today.minusDays(3));
        assertThat(t.history().get(1).verified()).isEqualTo(2);
        assertThat(t.current().committed()).as("live, not stored").isEqualTo(1);
        assertThat(snapshotRows(rel)).as("asking for the trend wrote nothing").isEqualTo(3);
        assertThat(trends.trends(null, null, 30, null).readiness()).isNull();
        assertThatThrownBy(() -> trends.trends(null, null, 30, UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void VYB0956_AC8_theDatabaseRefusesAnImpossibleDayAndASecondRowForTheSameDay() {
        UUID rel = releaseWith(0, 1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 1, 2, 0, 0, 0)", rel, today))
            .hasMessageContaining("release_readiness_snapshot");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 1, 0, 0, 0, -1)", rel, today))
            .hasMessageContaining("release_readiness_snapshot");
        jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 1, 0, 0, 0, 0)", rel, today);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_readiness_snapshot (release_id, taken_on, committed, approved, verified, blocked, critical_gaps) VALUES (?, ?, 1, 0, 0, 0, 0)", rel, today))
            .hasMessageContaining("release_readiness_snapshot_pkey");
    }

    // ------------------------------------------------------------------ validation

    @Test
    void VYB0956_AC9_thePeriodIsThirtyNinetyOrAHundredAndEightyDaysAndAnUnknownScopeIsRefused() {
        assertThat(trends.trends(null, null, 30, null).defects()).hasSize(5);
        assertThat(trends.trends(null, null, 90, null).defects()).hasSize(13);
        assertThat(trends.trends(null, null, 180, null).passRate()).hasSize(26);
        assertThatThrownBy(() -> trends.trends(null, null, 7, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("30, 90 or 180");
        assertThatThrownBy(() -> trends.trends(null, UUID.randomUUID(), 30, null)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> trends.trends(UUID.randomUUID(), null, 30, null)).isInstanceOf(NoSuchElementException.class);
    }

    // ------------------------------------------------------------------ the files

    @Test
    void VYB0956_AC10_theWorkbookHasASheetPerTrendWithNumbersAsNumbersAndAnAbsenceSaidInWordsNeverZero() throws Exception {
        UUID req = newRequirement(p, author).getId();
        result(req, "PASS", thisMonday.minusWeeks(1).plusDays(1));
        result(req, "FAIL", thisMonday.minusWeeks(1).plusDays(1));
        defect(req, thisMonday, "OPEN");
        UUID rel = releaseWith(1, 0);

        var file = reports.export(TrendReportService.Format.XLSX, null, p.applicationId(), 30, rel, admin, "Ada");

        assertThat(file.filename()).matches("vyoog-trends-\\d{4}-\\d{2}-\\d{2}\\.xlsx");
        assertThat(file.contentType()).contains("spreadsheetml");
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
            assertThat(wb.sheetIterator()).toIterable().extracting(s -> s.getSheetName()).containsExactly("Summary", "Defects", "Pass rate", "Readiness");
            assertThat(wb.getSheet("Summary").getRow(2).getCell(1).getStringCellValue()).isEqualTo("Ada");
            var defects = wb.getSheet("Defects");
            assertThat(defects.getLastRowNum()).isEqualTo(5);
            assertThat(defects.getRow(5).getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(defects.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(1.0);
            var pass = wb.getSheet("Pass rate");
            assertThat(pass.getRow(4).getCell(3).getNumericCellValue()).as("last week").isEqualTo(0.5);
            assertThat(pass.getRow(5).getCell(3).getCellType()).as("this week").isEqualTo(CellType.STRING);
            assertThat(pass.getRow(5).getCell(3).getStringCellValue()).isEqualTo("no results");
            var ready = wb.getSheet("Readiness");
            assertThat(ready.getRow(1).getCell(0).getStringCellValue()).as("no day recorded yet, so the live row comes first").startsWith("Now");
            assertThat(ready.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1.0);
            assertThat(ready.getRow(2).getCell(0).getStringCellValue()).isEqualTo("No days recorded in this period yet.");
        }
    }

    @Test
    void VYB0956_AC11_theWorkbookAndPdfCarryNoReadinessSheetFiguresWhenNoReleaseIsChosenAndSayNoReleaseWasChosen() throws Exception {
        var xlsx = reports.export(TrendReportService.Format.XLSX, null, p.applicationId(), 30, null, admin, "Ada");
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx.bytes()))) {
            assertThat(wb.getSheet("Readiness").getRow(1).getCell(0).getStringCellValue()).isEqualTo("No release was chosen.");
        }
        var pdf = reports.export(TrendReportService.Format.PDF, null, p.applicationId(), 30, null, admin, "Ada");
        assertThat(new String(pdf.bytes(), 0, 5)).isEqualTo("%PDF-");
        String text = pdfText(pdf.bytes());
        assertThat(text).contains("Vyoog trend report").contains("Defects").contains("Test pass rate").contains("No release was chosen.").contains("no results");
    }

    private static String pdfText(byte[] bytes) throws Exception {
        PdfReader reader = new PdfReader(bytes);
        try {
            StringBuilder b = new StringBuilder();
            PdfTextExtractor x = new PdfTextExtractor(reader);
            for (int i = 1; i <= reader.getNumberOfPages(); i++) b.append(x.getTextFromPage(i)).append('\n');
            return b.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void VYB0956_AC12_thePdfListsTheReadinessDaysAndTheLiveFiguresAndEachExportIsAudited() throws Exception {
        UUID rel = releaseWith(1, 0);
        snapshots.takeAll(today);
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'report.trends_exported' AND actor_id = ?", Integer.class, admin);

        var pdf = reports.export(TrendReportService.Format.PDF, null, null, 90, rel, admin, "Ada");
        reports.export(TrendReportService.Format.XLSX, null, null, 90, rel, admin, "Ada");

        String text = pdfText(pdf.bytes());
        assertThat(text).contains(today.toString()).contains("Now (live)").contains("Verified share");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'report.trends_exported' AND actor_id = ?", Integer.class, admin)).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT after->>'format' FROM audit_event WHERE action = 'report.trends_exported' AND actor_id = ? ORDER BY occurred_at DESC LIMIT 1", String.class, admin))
            .isEqualTo("xlsx");
        assertThatThrownBy(() -> TrendReportService.Format.of("docx")).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ HTTP

    private JwtRequestPostProcessor anyone() {
        String id = unique("p");
        users.upsert("sub-" + id, id + "@it.test", id);
        return jwt().jwt(j -> j.subject("sub-" + id).claim("email", id + "@it.test").claim("preferred_username", id).claim("azp", "vyoog-web"));
    }

    @Test
    void VYB0956_AC13_anyoneSignedInReadsTheTrendsAndDownloadsTheFilesAndNoOneElseDoes() throws Exception {
        UUID rel = releaseWith(1, 0);
        JwtRequestPostProcessor nobody = anyone();

        mvc.perform(get("/api/v1/trends").with(nobody).param("applicationId", p.applicationId().toString()).param("days", "30").param("releaseId", rel.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.days").value(30))
            .andExpect(jsonPath("$.defects.length()").value(5))
            .andExpect(jsonPath("$.passRate.length()").value(5))
            .andExpect(jsonPath("$.passRate[4].rate").doesNotExist())
            .andExpect(jsonPath("$.readiness.releaseId").value(rel.toString()))
            .andExpect(jsonPath("$.readiness.current.committed").value(1));
        mvc.perform(get("/api/v1/trends").with(nobody)).andExpect(status().isOk()).andExpect(jsonPath("$.days").value(90)).andExpect(jsonPath("$.readiness").doesNotExist());
        mvc.perform(get("/api/v1/trends/export").with(nobody).param("format", "xlsx").param("days", "30"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("spreadsheetml")))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern("attachment; filename=\"vyoog-trends-.*\\.xlsx\"")));
        mvc.perform(get("/api/v1/trends/export").with(nobody).param("format", "pdf"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"));

        mvc.perform(get("/api/v1/trends/export").with(nobody).param("format", "docx")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trends").with(nobody).param("days", "7")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trends").with(nobody).param("applicationId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trends")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trends/export").param("format", "pdf")).andExpect(status().isUnauthorized());
    }
}
