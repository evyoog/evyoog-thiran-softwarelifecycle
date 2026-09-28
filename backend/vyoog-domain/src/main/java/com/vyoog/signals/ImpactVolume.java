package com.vyoog.signals;

/**
 * VYB-0464: every figure exact, none in days (AC2). {@code teams} is a real count
 * against {@code team}/{@code team_member} (V008) — {@code owners} is kept alongside
 * it as a finer-grained figure (distinct people, not just distinct teams), not a
 * proxy standing in for it anymore.
 *
 * <p>VYB-0507 (session 14): {@code testsBorrowed} is the subset of {@code tests} that
 * are CI-ingested rather than drafted inside Vyoog — a real, structural distinction,
 * not a fabricated one. Every {@code test_case} row was CI-ingested (VYB-0310) until
 * VYB-0363 (session 14) added drafting one directly; only ingested rows are
 * genuinely "borrowed" from an external system (the CI integration) rather than
 * authored here — see {@code TestCase.Status}.
 */
public record ImpactVolume(
    long requirements, long tests, long testsBorrowed, long applications, long capabilities,
    long owners, long teams, long briefs) {
}
