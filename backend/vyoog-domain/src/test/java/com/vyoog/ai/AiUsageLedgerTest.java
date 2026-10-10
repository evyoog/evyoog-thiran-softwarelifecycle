package com.vyoog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** VYB0939: the ledger's own rules — what a row's total is, when a refusal row is written, and that a write failure is never thrown. */
class AiUsageLedgerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AtomicInteger inserts = new AtomicInteger();
    private final AiUsageLedger ledger = new AiUsageLedger(jdbc, tx, clock::get);

    {
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(i -> inserts.incrementAndGet());
    }

    private static AiUsageLedger.Entry entry(String purpose, AiUsageLedger.Outcome outcome, Integer prompt, Integer completion) {
        return new AiUsageLedger.Entry(purpose, "abcd1234", "CHAT", "m", CallKind.INTERACTIVE, outcome, prompt, completion, 12);
    }

    @Test
    void VYB0939_AC9_theTotalIsThePromptPlusTheCompletionTokensReportedAndNullWhenNeitherWas() {
        assertThat(entry("p", AiUsageLedger.Outcome.OK, 9, 6).totalTokens()).isEqualTo(15);
        assertThat(entry("p", AiUsageLedger.Outcome.OK, 5, null).totalTokens()).isEqualTo(5);
        assertThat(entry("p", AiUsageLedger.Outcome.OK, null, null).totalTokens()).isNull();
    }

    @Test
    void VYB0939_AC9_aFailedOrRefusedCallHasNoTotalEvenIfSomeNumberCameBack() {
        assertThat(entry("p", AiUsageLedger.Outcome.FAILED, 9, 6).totalTokens()).isNull();
        assertThat(entry("p", AiUsageLedger.Outcome.BUDGET_REFUSED, null, null).totalTokens()).isNull();
    }

    @Test
    void VYB0939_AC10_everySuccessfulCallIsWrittenButARefusalRowIsWrittenOncePerMinutePerPurpose() {
        assertThat(ledger.record(entry("a", AiUsageLedger.Outcome.OK, 1, 1))).isTrue();
        assertThat(ledger.record(entry("a", AiUsageLedger.Outcome.OK, 1, 1))).isTrue();

        assertThat(ledger.record(entry("a", AiUsageLedger.Outcome.BUDGET_REFUSED, null, null))).isTrue();
        assertThat(ledger.record(entry("a", AiUsageLedger.Outcome.BUDGET_REFUSED, null, null))).as("same purpose, same minute").isFalse();
        assertThat(ledger.record(entry("b", AiUsageLedger.Outcome.BUDGET_REFUSED, null, null))).as("another purpose").isTrue();

        clock.addAndGet(AiUsageLedger.REFUSAL_ROW_EVERY_MILLIS);
        assertThat(ledger.record(entry("a", AiUsageLedger.Outcome.BUDGET_REFUSED, null, null))).as("a minute later").isTrue();
        assertThat(inserts.get()).isEqualTo(5);
    }

    @Test
    void VYB0939_AC11_aFailureToWriteTheRowIsNotThrownSoTheAiCallThePersonWaitsOnStillSucceeds() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThat(ledger.record(entry("a", AiUsageLedger.Outcome.OK, 1, 1))).isFalse();
    }
}
