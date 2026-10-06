import type {
  CaseResult, Me, PassRate, RunStatus, RunSummary, StepResult, TestRun, TestedRequirement,
} from '@/shared/api/client'

/**
 * VYB-0927: the logic of the Quality screen's "Test runs" and "Pass rate" tabs, kept out of the components so it
 * can be tested without a browser.
 *
 * Rules behind it: a result is a label and a glyph as well as a colour (never colour alone), and never amber,
 * because amber means AI (CLAUDE.md rule 5). "Not run" and "stale" are shown as what they are and never counted
 * as failures; a pass rate that has nothing to measure reads "Not run", never 0% (Principle 8: absence is said
 * in words).
 */

export const RESULT_LABEL: Record<CaseResult, string> = {
  PASS: 'Passed', FAIL: 'Failed', BLOCKED: 'Blocked', NOT_RUN: 'Not run',
}

export const RESULT_GLYPH: Record<CaseResult, string> = {
  PASS: '✓', FAIL: '✕', BLOCKED: '⊘', NOT_RUN: '○',
}

/** Badge classes (tokens.css). */
export const RESULT_CLASS: Record<CaseResult, string> = {
  PASS: 'tr-pass', FAIL: 'tr-fail', BLOCKED: 'tr-blocked', NOT_RUN: 'tr-none',
}

export const STATUS_LABEL: Record<RunStatus, string> = {
  PLANNED: 'Planned', IN_PROGRESS: 'In progress', COMPLETED: 'Completed',
}

export const STATUS_GLYPH: Record<RunStatus, string> = {
  PLANNED: '○', IN_PROGRESS: '◐', COMPLETED: '●',
}

export const STATUS_CLASS: Record<RunStatus, string> = {
  PLANNED: 'tr-none', IN_PROGRESS: 'tr-blocked', COMPLETED: 'tr-pass',
}

export const RESULTS: StepResult[] = ['PASS', 'FAIL', 'BLOCKED']

/**
 * The Tester role (or a platform administrator, who passes every rule) is what the server needs for every write
 * here. This only decides whether to show the buttons; the server still refuses anyone else with 403.
 */
export function canExecute(me: Me | undefined): boolean {
  if (!me) return false
  return me.platformAdministrator || me.activeGrants.some((g) => g.role === 'TESTER')
}

/** A step that did not pass needs to say what actually happened. */
export function resultNeedsActual(result: StepResult): boolean {
  return result !== 'PASS'
}

/** The message to show before sending, or undefined when it can be sent. Mirrors the server's rule. */
export function validateResult(result: StepResult, actualResult: string): string | undefined {
  if (resultNeedsActual(result) && actualResult.trim() === '') {
    return `Say what actually happened: an actual result is needed when a step is ${RESULT_LABEL[result].toLowerCase()}.`
  }
  return undefined
}

export interface RunActions {
  canStart: boolean
  canRecord: boolean
  canComplete: boolean
  /** Why completing is not possible yet, when the run is in progress. */
  completeBlockedReason?: string
  canRetest: boolean
  /** Why there is nothing to retest, when the run is completed. */
  retestBlockedReason?: string
}

export function runActions(status: RunStatus, summary: RunSummary): RunActions {
  const inProgress = status === 'IN_PROGRESS'
  const completed = status === 'COMPLETED'
  const toRetest = summary.failed + summary.blocked
  return {
    canStart: status === 'PLANNED',
    canRecord: inProgress,
    canComplete: inProgress && summary.notRun === 0,
    completeBlockedReason: inProgress && summary.notRun > 0
      ? `${summary.notRun} of ${summary.total} case${summary.total === 1 ? '' : 's'} still ${summary.notRun === 1 ? 'has' : 'have'} no result.`
      : undefined,
    canRetest: completed && toRetest > 0,
    retestBlockedReason: completed && toRetest === 0 ? 'Nothing failed or was blocked, so there is nothing to retest.' : undefined,
  }
}

export function summaryText(s: RunSummary): string {
  return `${s.passed} passed, ${s.failed} failed, ${s.blocked} blocked, ${s.notRun} not run`
}

/** "Plan / Suite", with the build and a retest marker. */
export function runTitle(run: TestRun): string {
  const base = [run.planName, run.suiteName].filter(Boolean).join(' / ') || 'Run'
  return run.buildLabel ? `${base} · ${run.buildLabel}` : base
}

/** True when the requirement was edited after the run froze its revision: the run's result is stale for the new text. */
export function isStale(r: TestedRequirement): boolean {
  return r.currentRevision > r.testedRevision
}

// ── Pass rate ──────────────────────────────────────────────────────────────────

/** "Not run" when no case has a result at the current revision, never "0%". */
export function passRateText(p: PassRate): string {
  return p.passRate === undefined || p.passRate === null ? 'Not run' : `${Math.round(p.passRate * 100)}%`
}

/** "2 of 5 cases have a result": how much the rate rests on. */
export function basisText(p: PassRate): string {
  const ran = p.passed + p.failed
  return `${ran} of ${p.cases} case${p.cases === 1 ? '' : 's'} ${ran === 1 ? 'has' : 'have'} a result at this revision`
}

export type PassRateTone = 'fail' | 'pass' | 'partial' | 'none'

/** Any failure is a failure; all cases passing is a pass; a pass with something not run or stale is only partial. */
export function passRateTone(p: PassRate): PassRateTone {
  if (p.failed > 0) return 'fail'
  if (p.passed === 0) return 'none'
  return p.notRun === 0 && p.stale === 0 ? 'pass' : 'partial'
}

export const TONE_CLASS: Record<PassRateTone, string> = {
  fail: 'tr-fail', pass: 'tr-pass', partial: 'tr-blocked', none: 'tr-none',
}

export const TONE_GLYPH: Record<PassRateTone, string> = {
  fail: '✕', pass: '✓', partial: '◐', none: '○',
}

/** The words that go with the rate for those who cannot rely on colour: what is behind it. */
export function breakdownText(p: PassRate): string {
  const parts = [`${p.passed} passed`, `${p.failed} failed`]
  if (p.stale > 0) parts.push(`${p.stale} stale`)
  if (p.notRun > 0) parts.push(`${p.notRun} not run`)
  return parts.join(', ')
}
