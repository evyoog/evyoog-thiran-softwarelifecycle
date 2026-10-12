import { describe, expect, it } from 'vitest'
import type { DefectMatrix, ReleaseMatrix } from '@/shared/api/client'
import {
  defectSummary, defectsOf, isReleaseGap, orderedDefects, releaseSummary, releaseTotals, severityText, visibleDefectRows, visibleReleaseRows,
} from './matrices'

const row = (key: string, status: 'APPROVED' | 'DRAFT', releaseId?: string, supersededById?: string) =>
  ({ requirementId: `id-${key}`, key, title: key, status, releaseId, supersededById })
const releases: ReleaseMatrix = {
  releases: [{ id: 'rel1', name: 'R-1', state: 'OPEN', requirements: 1 }],
  rows: [row('VY-1', 'APPROVED', 'rel1'), row('VY-2', 'APPROVED'), row('VY-3', 'DRAFT'), row('VY-4', 'APPROVED', undefined, 'id-VY-9')],
}

describe('VYB-0943 — requirement by release', () => {
  it('VYB0943_AC16_aGapIsAnApprovedRequirementInNoReleaseThatHasNotBeenReplaced', () => {
    expect(releases.rows.map(isReleaseGap)).toEqual([false, true, false, false])
  })

  it('VYB0943_AC16_gapsOnlyKeepsJustThoseAndTheDefaultKeepsEveryRow', () => {
    expect(visibleReleaseRows(releases, true).map((r) => r.key)).toEqual(['VY-2'])
    expect(visibleReleaseRows(releases, false)).toHaveLength(4)
  })

  it('VYB0943_AC17_theTotalsAddUpAndTheSummaryIsInWords', () => {
    expect(releaseTotals(releases)).toEqual({ inARelease: 1, inNone: 3, gaps: 1 })
    expect(releaseSummary(releases)).toBe('1 of 4 requirements are in a release; 3 are in none, 1 of those approved and waiting for one.')
    expect(releaseSummary({ releases: [], rows: [] })).toBe('This application has no requirements.')
  })
})

const col = (key: string, requirementId: string | null, severity: 'CRITICAL' | 'LOW' = 'LOW', state: 'OPEN' | 'FIXED' = 'OPEN') =>
  ({ id: `d-${key}`, key, title: key, severity, state, requirementId })
const defects: DefectMatrix = {
  rows: [row('VY-1', 'APPROVED'), row('VY-2', 'APPROVED'), row('VY-3', 'DRAFT')],
  defects: [col('DEF-2', 'id-VY-3'), col('DEF-1', 'id-VY-1', 'CRITICAL'), col('DEF-3', 'id-VY-1')],
  untraced: [col('DEF-9', null)],
}

describe('VYB-0943 — requirement by defect', () => {
  it('VYB0943_AC18_aRequirementsDefectsAreTheColumnsMarkedForIt', () => {
    expect(defectsOf(defects, 'id-VY-1').map((d) => d.key)).toEqual(['DEF-1', 'DEF-3'])
    expect(defectsOf(defects, 'id-VY-2')).toEqual([])
  })

  it('VYB0943_AC18_byDefaultOnlyRequirementsWithADefectShowAndTheRestOnRequest', () => {
    expect(visibleDefectRows(defects, false).map((r) => r.key)).toEqual(['VY-1', 'VY-3'])
    expect(visibleDefectRows(defects, true)).toHaveLength(3)
  })

  it('VYB0943_AC19_columnsAreGroupedUnderTheirRequirementInRowOrder', () => {
    expect(orderedDefects(defects, visibleDefectRows(defects, false)).map((d) => d.key)).toEqual(['DEF-1', 'DEF-3', 'DEF-2'])
  })

  it('VYB0943_AC19_severityIsAWordAndAGlyphAndTheSummaryExplainsTheOneMarkPerColumn', () => {
    expect(severityText('CRITICAL')).toBe('◆◆◆ critical')
    expect(severityText('LOW')).toBe('◇ low')
    expect(defectSummary(defects, false)).toBe('3 open defects across 2 of 3 requirements. A defect has one requirement, so each column has one mark.')
    expect(defectSummary({ rows: [], defects: [], untraced: [] }, true)).toBe('No defects are linked to a requirement of this application.')
  })
})
