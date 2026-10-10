package com.vyoog.platform.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** VYB-0940 (F31): a network call made inside a database transaction is refused in tests and logged in production. */
class NetworkCallGuardTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void closeTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void VYB0940_AC15_outsideATransactionACallIsAlwaysAllowed() {
        assertThatCode(() -> new NetworkCallGuard("fail", meters).beforeNetworkCall("AI call: x")).doesNotThrowAnyException();
        assertThat(meters.find("network.calls.in-transaction").counter()).isNull();
    }

    @Test
    void VYB0940_AC16_insideATransactionFailModeRefusesAndNamesTheCall() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> new NetworkCallGuard("fail", meters).beforeNetworkCall("AI call: rewrite-suggestion"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("AI call: rewrite-suggestion").hasMessageContaining("transaction");
        assertThat(meters.get("network.calls.in-transaction").counter().count()).isEqualTo(1.0);
    }

    @Test
    void VYB0940_AC17_insideATransactionWarnModeLetsTheCallGoAheadButCountsIt() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(() -> new NetworkCallGuard("warn", meters).beforeNetworkCall("object storage upload")).doesNotThrowAnyException();
        assertThat(meters.get("network.calls.in-transaction").counter().count()).isEqualTo(1.0);
    }

    @Test
    void VYB0940_AC18_aModeThatIsNeitherWarnNorFailIsRefusedAtStartup() {
        assertThatThrownBy(() -> new NetworkCallGuard("off", meters)).isInstanceOf(IllegalStateException.class).hasMessageContaining("'warn' or 'fail'");
        assertThatThrownBy(() -> new NetworkCallGuard(null, meters)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new NetworkCallGuard(" FAIL ", meters)).doesNotThrowAnyException();
    }

    @Test
    void VYB0940_AC19_workMarkedAsAfterTheCommitIsNotInsideTheTransactionEvenWhileSpringStillReportsOne() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicBoolean ran = new AtomicBoolean();

        NetworkCallGuard.outsideTransaction(() -> {
            new NetworkCallGuard("fail", meters).beforeNetworkCall("AI call: embedding");
            ran.set(true);
        });

        assertThat(ran).isTrue();
        assertThat(NetworkCallGuard.inTransaction()).as("the marker is removed afterwards").isTrue();
    }

    @Test
    void VYB0940_AC20_afterCommitWorkRunsOnlyOnCommitAndOutsideTheTransaction() {
        List<String> events = new ArrayList<>();
        AfterCommitRunner runner = new AfterCommitRunner(Runnable::run);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        runner.run(() -> events.add("ran, in transaction: " + NetworkCallGuard.inTransaction()));
        assertThat(events).as("nothing runs before the commit").isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        assertThat(events).containsExactly("ran, in transaction: false");
    }

    @Test
    void VYB0940_AC21_afterCommitWorkIsDroppedIfTheTransactionRollsBack() {
        List<String> events = new ArrayList<>();
        AfterCommitRunner runner = new AfterCommitRunner(Runnable::run);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        runner.run(() -> events.add("ran"));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(events).isEmpty();
    }

    @Test
    void VYB0940_AC22_withNoTransactionTheWorkIsHandedToTheExecutorAtOnce() {
        List<String> events = new ArrayList<>();

        new AfterCommitRunner(Runnable::run).run(() -> events.add("ran"));

        assertThat(events).containsExactly("ran");
    }
}
