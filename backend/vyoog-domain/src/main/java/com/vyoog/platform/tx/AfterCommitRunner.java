package com.vyoog.platform.tx;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * VYB-0940: runs work once the current transaction has committed, on the enrichment executor, so the work (a model call) is
 * never inside the transaction. Nothing runs if the transaction rolls back: the thing the work was for does not exist.
 * With no transaction in progress the work is handed to the executor at once.
 *
 * <p>The executor degrades to running in the caller's own thread when its queue is full; that is still after the commit,
 * and {@link NetworkCallGuard#outsideTransaction} says so.
 */
@Component
public class AfterCommitRunner {

    private final Executor executor;

    public AfterCommitRunner(@Qualifier("requirementEnrichmentExecutor") Executor executor) {
        this.executor = executor;
    }

    public void run(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    hand(work);
                }
            });
        } else {
            hand(work);
        }
    }

    private void hand(Runnable work) {
        executor.execute(() -> NetworkCallGuard.outsideTransaction(work));
        // A caller-runs rejection executes the lambda above in this thread, where the transaction is still bound but
        // already committed; the lambda marks itself, so nothing more is needed here.
    }
}
