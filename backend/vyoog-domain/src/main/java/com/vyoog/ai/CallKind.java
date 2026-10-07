package com.vyoog.ai;

/**
 * VYB-0936: who is waiting for the answer, which decides how hard the gateway retries.
 *
 * <p>{@link #INTERACTIVE}: a person is waiting (a rewrite suggestion, a trace proposal, an embedding on a
 * write). Two attempts inside a short total limit, so nobody waits a minute on a struggling provider.
 * {@link #BATCH}: a sweep or an analysis pass nobody is watching. Up to four attempts and a longer total limit.
 */
public enum CallKind {
    INTERACTIVE,
    BATCH
}
