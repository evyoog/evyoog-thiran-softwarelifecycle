import { describe, it, expect } from 'vitest'
import { gapBefore, dayLabel, dayOffsets, defaultRelease, countAxis, noDefects, noResults, percentText, readinessShares, runs, share } from './trendModel'

describe('VYB-0956 — the trend charts never turn a missing value into a zero', () => {
  it('VYB0956_AC14_aMissingRateIsSaidInWordsAndARealZeroIsZero', () => {
    expect(percentText(null)).toBe('no results')
    expect(percentText(undefined)).toBe('no results')
    expect(percentText(0)).toBe('0%')
    expect(percentText(0.755)).toBe('76%')
    expect(percentText(null, 'no data')).toBe('no data')
  })

  it('VYB0956_AC14_aShareOfAnEmptyScopeIsNotZeroPercent', () => {
    expect(share(0, 0)).toBeNull()
    expect(share(0, 4)).toBe(0)
    expect(share(3, 4)).toBe(0.75)
    const s = readinessShares([
      { day: '2026-10-01', committed: 0, approved: 0, verified: 0, blocked: 0, criticalGaps: 0 },
      { day: '2026-10-02', committed: 4, approved: 2, verified: 1, blocked: 0, criticalGaps: 0 },
    ])
    expect(s.approved).toEqual([null, 0.5])
    expect(s.verified).toEqual([null, 0.25])
  })

  it('VYB0956_AC15_aLineBreaksAtAGapAndKeepsEachPointsPosition', () => {
    expect(runs([1, 2, null, 3, null, null, 4, 5])).toEqual([
      [{ index: 0, value: 1 }, { index: 1, value: 2 }],
      [{ index: 3, value: 3 }],
      [{ index: 6, value: 4 }, { index: 7, value: 5 }],
    ])
    expect(runs([null, undefined])).toEqual([])
    // a recorded day that does not follow the one before also ends the line
    expect(runs([1, 2, 3, 4], [false, false, true, false])).toEqual([
      [{ index: 0, value: 1 }, { index: 1, value: 2 }],
      [{ index: 2, value: 3 }, { index: 3, value: 4 }],
    ])
    expect(runs([0, 0]).flat().map((p) => p.value)).toEqual([0, 0])
  })

  it('VYB0956_AC15_recordedDaysSitWhereTheyWereTakenSoAMissingDayLeavesSpace', () => {
    expect(dayOffsets(['2026-10-01', '2026-10-02', '2026-10-09'], '2026-10-01')).toEqual([0, 1, 8])
    expect(gapBefore([0, 1, 8, 9, 10, 40])).toEqual([false, false, true, false, false, true])
    expect(gapBefore([])).toEqual([])
    expect(dayOffsets(['2026-03-29', '2026-03-30'], '2026-03-28')).toEqual([1, 2])   // across a clock change
  })

  it('VYB0956_AC16_labelsReadAsPlainDatesAndScalesRoundUp', () => {
    expect(dayLabel('2026-10-12')).toBe('12 Oct')
    expect(dayLabel('2026-01-05')).toBe('5 Jan')
    expect(countAxis(0)).toEqual({ top: 1, ticks: [0, 1] })
    expect(countAxis(3)).toEqual({ top: 3, ticks: [0, 1, 2, 3] })
    expect(countAxis(4)).toEqual({ top: 4, ticks: [0, 1, 2, 3, 4] })
    expect(countAxis(7)).toEqual({ top: 8, ticks: [0, 2, 4, 6, 8] })
    expect(countAxis(13)).toEqual({ top: 15, ticks: [0, 5, 10, 15] })
    expect(countAxis(130).top).toBe(150)
    expect(countAxis(130).ticks.every(Number.isInteger)).toBe(true)
  })

  it('VYB0956_AC17_theReleaseShownFirstIsTheFirstOneStillBeingWorkedElseNone', () => {
    expect(defaultRelease([{ id: 'a', state: 'RELEASED' }, { id: 'b', state: 'OPEN' }, { id: 'c', state: 'PLANNED' }])).toBe('b')
    expect(defaultRelease([{ id: 'a', state: 'RELEASED' }])).toBe('')
    expect(defaultRelease(undefined)).toBe('')
  })

  it('VYB0956_AC17_aChartHasNothingToShowOnlyWhenEveryWeekIsEmpty', () => {
    expect(noDefects([{ opened: 0, open: 0 }, { opened: 0, open: 0 }])).toBe(true)
    expect(noDefects([{ opened: 0, open: 0 }, { opened: 0, open: 2 }])).toBe(false)
    expect(noResults([{ rate: null }, {}])).toBe(true)
    expect(noResults([{ rate: null }, { rate: 0 }])).toBe(false)
  })
})
