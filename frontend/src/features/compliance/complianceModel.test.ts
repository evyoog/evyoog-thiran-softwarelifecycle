import { describe, it, expect } from 'vitest'
import type { ClauseRegisterRow } from '@/shared/api/client'
import {
  COVERAGE_GLYPH, COVERAGE_LABEL, NO_FILTER, clauseName, evidenceLabel, excerpt, filterRows, standardsOf, summaryLine, summarise, testResultLabel,
} from './complianceModel'

const row = (over: Partial<ClauseRegisterRow>): ClauseRegisterRow => ({
  id: 'c', standard: 'ISO 27001', section: 'A.5.1', text: 'Access is controlled.', mappedRequirements: 0, evidencedRequirements: 0, coverage: 'UNMAPPED', ...over,
})
const rows = [
  row({ id: '1', standard: 'ISO 27001', section: 'A.5.1', coverage: 'UNMAPPED' }),
  row({ id: '2', standard: 'ISO 27001', section: 'A.8.2', text: 'Keys are rotated.', mappedRequirements: 2, evidencedRequirements: 1, coverage: 'MAPPED' }),
  row({ id: '3', standard: 'SOC 2', section: null, text: 'Logs are kept.', mappedRequirements: 1, evidencedRequirements: 1, coverage: 'EVIDENCED' }),
]

describe('VYB-0947 — the clause register screen', () => {
  it('VYB0947_AC15_everyCoverageHasAGlyphAndAWordSoColourIsNeverTheOnlySignal', () => {
    for (const c of ['UNMAPPED', 'MAPPED', 'EVIDENCED'] as const) {
      expect(COVERAGE_LABEL[c]).toBeTruthy()
      expect(COVERAGE_GLYPH[c]).toBeTruthy()
    }
    expect(new Set(Object.values(COVERAGE_GLYPH)).size).toBe(3)
  })

  it('VYB0947_AC16_theRegisterFiltersByStandardCoverageAndWords', () => {
    expect(filterRows(rows, NO_FILTER)).toHaveLength(3)
    expect(filterRows(rows, { ...NO_FILTER, standard: 'ISO 27001' }).map((r) => r.id)).toEqual(['1', '2'])
    expect(filterRows(rows, { ...NO_FILTER, coverage: 'EVIDENCED' }).map((r) => r.id)).toEqual(['3'])
    expect(filterRows(rows, { ...NO_FILTER, q: ' ROTATED ' }).map((r) => r.id)).toEqual(['2'])
    expect(filterRows(rows, { ...NO_FILTER, q: 'a.5.1' }).map((r) => r.id)).toEqual(['1'])
    expect(filterRows(rows, { standard: 'SOC 2', coverage: 'UNMAPPED', q: '' })).toEqual([])
  })

  it('VYB0947_AC16_standardsAreListedOnceAndSorted', () => {
    expect(standardsOf(rows)).toEqual(['ISO 27001', 'SOC 2'])
    expect(standardsOf([])).toEqual([])
  })

  it('VYB0947_AC17_theSummaryCountsEachCoverageOverTheWholeRegisterNotTheFilter', () => {
    const s = summarise(rows)
    expect(s).toEqual({ total: 3, unmapped: 1, mapped: 1, evidenced: 1 })
    expect(summaryLine(s)).toBe('3 clauses: 1 evidenced, 1 mapped but not evidenced, 1 unmapped')
    expect(summaryLine(summarise([rows[0]]))).toBe('1 clause: 0 evidenced, 0 mapped but not evidenced, 1 unmapped')
    expect(summaryLine(summarise([]))).toBe('No clauses in the register yet.')
  })

  it('VYB0947_AC18_aMappedRequirementSaysInWordsWhatItContributes', () => {
    const base = { counts: true, status: 'APPROVED' as const, evidenced: false, staleEvidence: false, replacedByKey: null }
    expect(evidenceLabel({ ...base, evidenced: true })).toBe('Approved, with a passing test at its current revision')
    expect(evidenceLabel(base)).toBe('No passing test yet')
    expect(evidenceLabel({ ...base, staleEvidence: true })).toBe('Passed only at an earlier revision')
    expect(evidenceLabel({ ...base, status: 'DRAFT' })).toBe('Not approved yet')
    expect(evidenceLabel({ ...base, counts: false, status: 'REJECTED' })).toBe('Rejected, so it does not count')
    expect(evidenceLabel({ ...base, counts: false, replacedByKey: 'VY-9' })).toBe('Replaced by VY-9, so it does not count')
  })

  it('VYB0947_AC18_aTestResultIsAGlyphAndAWordAndNotRunIsNotPassOrFail', () => {
    expect(testResultLabel('PASS')).toEqual({ glyph: '✓', word: 'Passed' })
    expect(testResultLabel('FAIL')).toEqual({ glyph: '✗', word: 'Failed' })
    expect(testResultLabel(null).word).toBe('Not run at this revision')
    expect(testResultLabel(undefined).glyph).toBe('–')
  })

  it('VYB0947_AC15_aClauseIsNamedByStandardAndSectionAndLongTextIsShortened', () => {
    expect(clauseName({ standard: 'ISO 27001', section: 'A.5.1' })).toBe('ISO 27001 A.5.1')
    expect(clauseName({ standard: 'SOC 2', section: null })).toBe('SOC 2')
    expect(excerpt('short')).toBe('short')
    expect(excerpt('a  b\n c')).toBe('a b c')
    expect(excerpt('x'.repeat(200), 20)).toHaveLength(20)
    expect(excerpt('x'.repeat(200), 20).endsWith('…')).toBe(true)
  })
})
