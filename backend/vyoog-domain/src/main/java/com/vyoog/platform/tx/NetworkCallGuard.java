package com.vyoog.platform.tx;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * VYB-0940 (F31): a call over the network (a model provider, object storage) must never be made while a database
 * transaction is open. The transaction holds a pooled connection and its locks for as long as the call takes (seconds to
 * minutes for a model), and a rollback cannot take back what the call did.
 *
 * <p>Code that is about to make such a call asks this first. If a transaction is open on the calling thread:
 * <ul>
 *   <li>{@code warn} (the default): the call goes ahead, an ERROR is logged naming it and the counter
 *       {@code network.calls.in-transaction} goes up. Production runs this way, so a path that was missed degrades to
 *       today's behaviour and is visible, not broken.</li>
 *   <li>{@code fail}: the call is refused with an {@link IllegalStateException}. The tests run this way, so a path that
 *       puts a network call back inside a transaction fails the build.</li>
 * </ul>
 * Set with {@code vyoog.network-calls.in-transaction}. Anything else is refused at startup.
 *
 * <p>Work run after a commit (see {@link AfterCommitRunner}) is outside the transaction even while Spring still reports
 * it as active, so {@link #outsideTransaction(Runnable)} marks it.
 */
@Component
public class NetworkCallGuard {

    private static final Logger log = LoggerFactory.getLogger(NetworkCallGuard.class);
    private static final ThreadLocal<Boolean> AFTER_COMMIT = new ThreadLocal<>();

    private final boolean fail;
    private final MeterRegistry meters;

    public NetworkCallGuard(@Value("${vyoog.network-calls.in-transaction:warn}") String mode, MeterRegistry meters) {
        String m = mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
        if (!m.equals("warn") && !m.equals("fail")) {
            throw new IllegalStateException("vyoog.network-calls.in-transaction must be 'warn' or 'fail', not '" + mode + "'.");
        }
        this.fail = m.equals("fail");
        this.meters = meters;
    }

    /** A guard that only warns, for code that builds its collaborators by hand (unit tests, no Spring). */
    public static NetworkCallGuard warnOnly() {
        return new NetworkCallGuard("warn", new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** A guard that refuses, for tests that prove a path makes its call outside a transaction. */
    public static NetworkCallGuard refusing() {
        return new NetworkCallGuard("fail", new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** True when a database transaction is open on this thread and its work is not already committed. */
    public static boolean inTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive() && AFTER_COMMIT.get() == null;
    }

    /**
     * @param what a short name for the call, for the log and the message ("AI call: rewrite-suggestion")
     * @throws IllegalStateException in {@code fail} mode, if a transaction is open
     */
    public void beforeNetworkCall(String what) {
        if (!inTransaction()) return;
        meters.counter("network.calls.in-transaction").increment();
        String message = "A network call (" + what + ") was about to be made inside a database transaction.";
        if (fail) throw new IllegalStateException(message);
        log.error("[tx] {} It holds a connection while it waits. Move it out of the transaction (VYB-0940).", message,
            new IllegalStateException("where it was called from"));
    }

    /** Runs work that follows a commit; a call made in it is not inside the transaction. */
    public static void outsideTransaction(Runnable work) {
        Boolean before = AFTER_COMMIT.get();
        AFTER_COMMIT.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (before == null) AFTER_COMMIT.remove(); else AFTER_COMMIT.set(before);
        }
    }
}
