package com.vyoog.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

/** VYB-0960: a write the database stopped over a lock conflict is a 409 that says to try again, not a 500 with a database message. */
class LockConflictResponseTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void VYB0960_AC12_aDeadlockOrALockTheDatabaseWouldNotWaitForIsAConflictThatSaysTryAgainAndNothingWasSaved() {
        for (PessimisticLockingFailureException e : new PessimisticLockingFailureException[] {
                new CannotAcquireLockException("could not execute batch: deadlock detected; Process 1 waits for ShareLock on transaction 7"),
                new DeadlockLoserDataAccessException("deadlock", null)}) {
            var pd = handler.onLockConflict(e);

            assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(pd.getTitle()).isEqualTo("Try again");
            assertThat(pd.getDetail()).contains("Nothing was saved").contains("Try again");
            assertThat(pd.getDetail()).as("no database internals in the answer").doesNotContain("ShareLock").doesNotContain("transaction 7");
        }
    }
}
