package com.vyoog.importqueue.prd;

import static org.assertj.core.api.Assertions.*;

import com.vyoog.importqueue.DocumentValidationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * VYB-0666: the template as CSV. This is the format most likely to arrive by mail or out
 * of a tool export, and the one whose quoting rules quietly destroy data when they are
 * approximated — so the cases below are the ones where a naive comma-split differs.
 */
class PrdTemplateCsvTest {

    private final PrdTemplateParser parser = new PrdTemplateParser();

    private static final String HEADER =
        "Product *,App *,Capability *,Your Ref,Requirement Title *,Requirement Statement *,"
            + "Type *,Priority *,Acceptance Criteria,Verification Method,Owner,"
            + "Source / Requested By,Tags,Regulatory Reference,Notes";

    private static byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void VYB0666_AC1_readsTheTemplateFromPlainCsv() {
        PrdRow r = parser.parse(csv(HEADER,
            "Valam,HRI,Attendance,HR-ATT-01,Capture punch events,The system shall capture punches.,"
                + "Functional,Critical,Appears within 60s.,Test,R. Chen,HR Ops,attendance,,A note")).get(0);

        assertThat(r.title()).isEqualTo("Capture punch events");
        assertThat(r.type()).isEqualTo("FUNCTIONAL");
        assertThat(r.priority()).isEqualTo("CRITICAL");
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void VYB0666_AC1_keepsACommaInsideAQuotedStatementInsteadOfSplittingTheRow() {
        // A naive split turns this one requirement into three malformed ones, shifting
        // every later column — and it does it without complaining.
        PrdRow r = parser.parse(csv(HEADER,
            "P,A,C,R1,T,\"The system shall record channel, device ID, and timestamp.\","
                + "Functional,High,,,,,,,")).get(0);

        assertThat(r.statement()).isEqualTo("The system shall record channel, device ID, and timestamp.");
        assertThat(r.type()).isEqualTo("FUNCTIONAL");
    }

    @Test
    void VYB0666_AC1_keepsAMultiLineAcceptanceCriteriaCellAsOneRow() {
        // The template asks for one criterion per line, and in CSV those lines live
        // inside one quoted field. Treating the newline as a record break would produce a
        // second row with no title.
        List<PrdRow> rows = parser.parse(csv(HEADER,
            "P,A,C,R1,T,S,Functional,High,\"1. Appears within 60s.\n2. Records the channel.\",,,,,,"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).acceptanceCriteria())
            .containsExactly("Appears within 60s.", "Records the channel.");
    }

    @Test
    void VYB0666_AC1_readsASemicolonDelimitedExportFromALocalisedSpreadsheet() {
        // Excel on a comma-decimal locale writes semicolons. Read as commas this is one
        // giant column, which surfaces as "not the template" rather than as a delimiter.
        PrdRow r = parser.parse(csv(HEADER.replace(',', ';'),
            "Valam;HRI;Attendance;HR-ATT-01;Capture punches;The system shall capture punches.;"
                + "Functional;Critical;;;;;;;")).get(0);

        assertThat(r.product()).isEqualTo("Valam");
        assertThat(r.title()).isEqualTo("Capture punches");
    }

    @Test
    void VYB0666_AC1_toleratesTheByteOrderMarkExcelWrites() {
        PrdRow r = parser.parse(("﻿" + HEADER + "\nP,A,C,R1,T,S,Functional,Low,,,,,,,")
            .getBytes(StandardCharsets.UTF_8)).get(0);

        assertThat(r.product()).isEqualTo("P");
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void VYB0666_AC1_doublesQuotesAreALiteralQuoteNotAFieldBreak() {
        PrdRow r = parser.parse(csv(HEADER,
            "P,A,C,R1,T,\"The label reads \"\"Punch in\"\" on the device.\",Functional,Low,,,,,,,")).get(0);

        assertThat(r.statement()).isEqualTo("The label reads \"Punch in\" on the device.");
    }

    @Test
    void VYB0666_AC3_ignoresTheTrailingNewlineRatherThanReportingABlankRow() {
        List<PrdRow> rows = parser.parse(csv(HEADER, "P,A,C,R1,T,S,Functional,Low,,,,,,,", ""));

        assertThat(rows).hasSize(1);
    }

    @Test
    void VYB0666_AC3_refusesBinaryThatIsNotASpreadsheetInsteadOfReadingItAsText() {
        byte[] pdfish = new byte[]{'%', 'P', 'D', 'F', 0, 1, 2, 3};

        assertThatThrownBy(() -> parser.parse(pdfish))
            .isInstanceOf(DocumentValidationException.class)
            .hasMessageContaining(".csv");
    }
}
