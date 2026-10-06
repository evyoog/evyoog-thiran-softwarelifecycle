import { describe, expect, it } from 'vitest'
import type { Me, PassRate, RunSummary, TestRun } from '@/shared/api/client'
import {
  RESULT_CLASS, RESULT_GLYPH, RESULT_LABEL, STATUS_CLASS, TONE_CLASS, basisText, breakdownText, canExecute, isStale,
  passRateText, passRateTone, resultNeedsActual, runActions, runTitle, summaryText, validateResult,
} from './testRuns'

const summary = (over: Partial<RunSummary> = {}): RunSummary =>
  ({ total: 4, passed: 1, failed: 1, blocked: 1, notRun: 1, verificationsRecorded: 0, ...over })

const rate = (over: Partial<PassRate> = {}): PassRate => ({
  requirementId: 'r', key: 'VY-1', title: 'T', status: 'DRAFT', revision: 1, cases: 4, passed: 2, failed: 0, stale: 0, notRun: 2, ...over,
})

const me = (over: Partial<Me> = {}): Me => ({ id: 'u', email: 'e', displayName: 'n', platformAdministrator: false, activeGrants: [], ...over })

describe('VYB-0927 test runs: how a result is shown', () => {
  it('VYB0927_AC1_everyResultHasALabelAndAGlyphSoItIsNeverColourAlone', () => {
    for (const r of ['PASS', 'FAIL', 'BLOCKED', 'NOT_RUN'] as const) {
      expect(RESULT_LABEL[r]).toBeTruthy()
      expect(RESULT_GLYPH[r]).toBeTruthy()
    }
    expect(new Set(Object.values(RESULT_GLYPH)).size).toBe(4)
    expect(new Set(Object.values(RESULT_LABEL)).size).toBe(4)
  })

  it('VYB0927_AC1_noStatusUsesTheAmberTokenBecauseAmberMeansAi', () => {
    const classes = [...Object.values(RESULT_CLASS), ...Object.values(STATUS_CLASS), ...Object.values(TONE_CLASS)]
    for (const c of classes) expect(c).not.toMatch(/(^|-)ai(-|$)|amber/)
  })
})

describe('VYB-0927 test runs: recording a result', () => {
  it('VYB0927_AC2_aStepThatDidNotPassNeedsAnActualResultAndAPassDoesNot', () => {
    expect(resultNeedsActual('PASS')).toBe(false)
    expect(resultNeedsActual('FAIL')).toBe(true)
    expect(resultNeedsActual('BLOCKED')).toBe(true)
    expect(validateResult('PASS', '')).toBeUndefined()
    expect(validateResult('FAIL', '   ')).toMatch(/actual result/)
    expect(validateResult('BLOCKED', '')).toMatch(/blocked/)
    expect(validateResult('FAIL', 'Got a 500')).toBeUndefined()
  })

  it('VYB0927_AC3_onlyATesterOrAnAdministratorSeesTheExecuteButtons', () => {
    expect(canExecute(undefined)).toBe(false)
    expect(canExecute(me())).toBe(false)
    expect(canExecute(me({ activeGrants: [{ role: 'VIEWER', scopeType: 'PLATFORM' }] }))).toBe(false)
    expect(canExecute(me({ activeGrants: [{ role: 'BUSINESS_ANALYST', scopeType: 'CAPABILITY', scopeId: 'c' }] }))).toBe(false)
    expect(canExecute(me({ activeGrants: [{ role: 'TESTER', scopeType: 'CAPABILITY', scopeId: 'c' }] }))).toBe(true)
    expect(canExecute(me({ platformAdministrator: true }))).toBe(true)
  })
})

describe('VYB-0927 test runs: what a run offers at each point', () => {
  it('VYB0927_AC4_aPlannedRunCanOnlyBeStarted', () => {
    const a = runActions('PLANNED', summary({ passed: 0, failed: 0, blocked: 0, notRun: 4 }))
    expect(a).toMatchObject({ canStart: true, canRecord: false, canComplete: false, canRetest: false })
  })

  it('VYB0927_AC4_aRunInProgressCanRecordAndCompleteOnlyWhenEveryCaseHasAResult', () => {
    const open = runActions('IN_PROGRESS', summary())
    expect(open).toMatchObject({ canStart: false, canRecord: true, canComplete: false })
    expect(open.completeBlockedReason).toBe('1 of 4 cases still has no result.')
    expect(runActions('IN_PROGRESS', summary({ notRun: 3 })).completeBlockedReason).toBe('3 of 4 cases still have no result.')
    const done = runActions('IN_PROGRESS', summary({ notRun: 0 }))
    expect(done.canComplete).toBe(true)
    expect(done.completeBlockedReason).toBeUndefined()
  })

  it('VYB0927_AC4_aCompletedRunIsFinalAndCanBeRetestedOnlyWhenSomethingFailedOrWasBlocked', () => {
    const failed = runActions('COMPLETED', summary({ notRun: 0 }))
    expect(failed).toMatchObject({ canStart: false, canRecord: false, canComplete: false, canRetest: true })
    const clean = runActions('COMPLETED', summary({ failed: 0, blocked: 0, notRun: 0, passed: 4 }))
    expect(clean.canRetest).toBe(false)
    expect(clean.retestBlockedReason).toMatch(/nothing to retest/)
    expect(runActions('COMPLETED', summary({ failed: 0, blocked: 1, notRun: 0 })).canRetest).toBe(true)
  })

  it('VYB0927_AC4_theSummaryAndTitleReadPlainly', () => {
    expect(summaryText(summary())).toBe('1 passed, 1 failed, 1 blocked, 1 not run')
    const run = { planName: 'Release 4', suiteName: 'Login', buildLabel: 'b-9' } as TestRun
    expect(runTitle(run)).toBe('Release 4 / Login · b-9')
    expect(runTitle({} as TestRun)).toBe('Run')
  })

  it('VYB0927_AC4_aRequirementEditedAfterTheRunStartedIsMarkedStale', () => {
    expect(isStale({ requirementId: 'r', key: 'VY-1', testedRevision: 2, currentRevision: 2 })).toBe(false)
    expect(isStale({ requirementId: 'r', key: 'VY-1', testedRevision: 2, currentRevision: 3 })).toBe(true)
  })
})

describe('VYB-0927 pass rate per requirement', () => {
  it('VYB0927_AC5_aRateWithNothingToMeasureReadsNotRunNeverZeroPercent', () => {
    expect(passRateText(rate({ passRate: undefined, passed: 0, notRun: 4 }))).toBe('Not run')
    expect(passRateText(rate({ passRate: null as unknown as undefined }))).toBe('Not run')
    expect(passRateText(rate({ passRate: 0, passed: 0, failed: 2 }))).toBe('0%')
    expect(passRateText(rate({ passRate: 0.5 }))).toBe('50%')
    expect(passRateText(rate({ passRate: 2 / 3 }))).toBe('67%')
    expect(passRateText(rate({ passRate: 1 }))).toBe('100%')
  })

  it('VYB0927_AC5_theBasisSaysHowManyCasesTheRateRestsOn', () => {
    expect(basisText(rate({ cases: 5, passed: 2, failed: 1 }))).toBe('3 of 5 cases have a result at this revision')
    expect(basisText(rate({ cases: 1, passed: 1, failed: 0, notRun: 0 }))).toBe('1 of 1 case has a result at this revision')
    expect(basisText(rate({ cases: 3, passed: 0, failed: 0, notRun: 3 }))).toBe('0 of 3 cases have a result at this revision')
  })

  it('VYB0927_AC5_anyFailureIsAFailureAndAPassWithGapsIsOnlyPartial', () => {
    expect(passRateTone(rate({ passed: 3, failed: 1, notRun: 0 }))).toBe('fail')
    expect(passRateTone(rate({ passed: 4, failed: 0, notRun: 0, cases: 4 }))).toBe('pass')
    expect(passRateTone(rate({ passed: 2, failed: 0, notRun: 2 }))).toBe('partial')
    expect(passRateTone(rate({ passed: 2, failed: 0, notRun: 0, stale: 1 }))).toBe('partial')
    expect(passRateTone(rate({ passed: 0, failed: 0, notRun: 4 }))).toBe('none')
  })

  it('VYB0927_AC5_notRunAndStaleAreNamedAndNeverCountedAsFailed', () => {
    expect(breakdownText(rate({ passed: 2, failed: 0, stale: 0, notRun: 0 }))).toBe('2 passed, 0 failed')
    expect(breakdownText(rate({ passed: 1, failed: 1, stale: 2, notRun: 3 }))).toBe('1 passed, 1 failed, 2 stale, 3 not run')
  })
})
