import { describe, it, expect } from 'vitest'
import type { RiskRow } from '@/shared/api/client'
import {
  BANDS, BAND_GLYPH, BAND_LABEL, BAND_VAR, DEFAULT_FILTER, bandOf, canManageRisks, filterRisks, heatGrid, needsReason, ratingText, statusChangeProblem, summarise,
  summaryLine,
} from './riskModel'

const risk = (over: Partial<RiskRow>): RiskRow => ({
  id: 'r', key: 'RSK-1', title: 'Provider outage', likelihood: 3, impact: 3, rating: 9, band: 'MEDIUM', status: 'OPEN', ownerName: 'Ada', requirements: 0, releases: 0, ...over,
})
const rows = [
  risk({ id: '1', key: 'RSK-1', likelihood: 5, impact: 4, rating: 20, band: 'CRITICAL', status: 'OPEN' }),
  risk({ id: '2', key: 'RSK-2', title: 'Key person leaves', likelihood: 2, impact: 3, rating: 6, band: 'MEDIUM', status: 'MITIGATING', ownerName: 'Grace' }),
  risk({ id: '3', key: 'RSK-3', likelihood: 1, impact: 2, rating: 2, band: 'LOW', status: 'ACCEPTED' }),
  risk({ id: '4', key: 'RSK-4', likelihood: 5, impact: 4, rating: 20, band: 'CRITICAL', status: 'CLOSED' }),
]

describe('VYB-0949 — the risk register screen', () => {
  it('VYB0949_AC17_theBandsAreTheSameOnTheScreenAsOnTheServerAndEachHasAWordAndAGlyph', () => {
    expect([1, 4, 5, 9, 10, 14, 15, 25].map(bandOf)).toEqual(['LOW', 'LOW', 'MEDIUM', 'MEDIUM', 'HIGH', 'HIGH', 'CRITICAL', 'CRITICAL'])
    for (const b of BANDS) { expect(BAND_LABEL[b]).toBeTruthy(); expect(BAND_GLYPH[b]).toBeTruthy(); expect(BAND_VAR[b]).not.toBe('ai') }
    expect(new Set(Object.values(BAND_GLYPH)).size).toBe(4)
    expect(ratingText({ rating: 20, band: 'CRITICAL' })).toBe('Critical (20)')
  })

  it('VYB0949_AC18_theDefaultViewHidesClosedRisksAndEveryFilterNarrows', () => {
    expect(filterRisks(rows, DEFAULT_FILTER).map((r) => r.key)).toEqual(['RSK-1', 'RSK-2', 'RSK-3'])
    expect(filterRisks(rows, { ...DEFAULT_FILTER, status: 'ALL' })).toHaveLength(4)
    expect(filterRisks(rows, { ...DEFAULT_FILTER, status: 'CLOSED' }).map((r) => r.key)).toEqual(['RSK-4'])
    expect(filterRisks(rows, { ...DEFAULT_FILTER, band: 'CRITICAL' }).map((r) => r.key)).toEqual(['RSK-1'])
    expect(filterRisks(rows, { ...DEFAULT_FILTER, q: ' KEY PERSON ' }).map((r) => r.key)).toEqual(['RSK-2'])
    expect(filterRisks(rows, { ...DEFAULT_FILTER, q: 'grace' }).map((r) => r.key)).toEqual(['RSK-2'])
    expect(filterRisks(rows, { ...DEFAULT_FILTER, cell: { likelihood: 1, impact: 2 } }).map((r) => r.key)).toEqual(['RSK-3'])
  })

  it('VYB0949_AC19_theSummaryCountsOpenRisksByBandAndClosedOnesApart', () => {
    const s = summarise(rows)
    expect(s).toEqual({ live: 3, closed: 1, byBand: { LOW: 1, MEDIUM: 1, HIGH: 0, CRITICAL: 1 } })
    expect(summaryLine(s)).toBe('3 open risks (1 critical, 1 medium, 1 low), 1 closed')
    expect(summaryLine(summarise([]))).toBe('No risks in the register yet.')
    expect(summaryLine(summarise([risk({ status: 'CLOSED' })]))).toBe('0 open risks, 1 closed')
  })

  it('VYB0949_AC20_theHeatGridIsFiveByFiveWithOpenRisksCountedWhereTheyBelongAndClosedLeftOut', () => {
    const g = heatGrid(rows)
    expect(g).toHaveLength(5)
    expect(g.every((r) => r.length === 5)).toBe(true)
    expect(g[0]![0]).toMatchObject({ likelihood: 5, impact: 1 })
    expect(g[4]![4]).toMatchObject({ likelihood: 1, impact: 5 })
    const cell = (l: number, i: number) => g.flat().find((c) => c.likelihood === l && c.impact === i)!
    expect(cell(5, 4)).toMatchObject({ count: 1, rating: 20, band: 'CRITICAL' })   // the closed one at 5,4 is not counted
    expect(cell(2, 3).count).toBe(1)
    expect(cell(1, 2).count).toBe(1)
    expect(g.flat().reduce((n, c) => n + c.count, 0)).toBe(3)
  })

  it('VYB0949_AC21_acceptingOrClosingNeedsAReasonAndAChangeToTheSameStatusIsRefused', () => {
    expect(needsReason('ACCEPTED')).toBe(true)
    expect(needsReason('CLOSED')).toBe(true)
    expect(needsReason('MITIGATING')).toBe(false)
    expect(statusChangeProblem('OPEN', 'CLOSED', '  ')).toBe('Say why this risk is closed.')
    expect(statusChangeProblem('OPEN', 'ACCEPTED', '')).toBe('Say why this risk is accepted.')
    expect(statusChangeProblem('OPEN', 'CLOSED', 'Mitigated by the failover.')).toBeNull()
    expect(statusChangeProblem('OPEN', 'MITIGATING', '')).toBeNull()
    expect(statusChangeProblem('CLOSED', 'CLOSED', 'x')).toBe('This risk is already closed.')
  })

  it('VYB0949_AC22_theRiskControlsAreShownToAdministratorsAndTheThreeRolesOnly', () => {
    const grants = (...roles: string[]) => ({ platformAdministrator: false, activeGrants: roles.map((role) => ({ role, scopeType: 'PRODUCT' })) })
    expect(canManageRisks(undefined)).toBe(false)
    expect(canManageRisks({ platformAdministrator: true, activeGrants: [] })).toBe(true)
    for (const role of ['APPROVER', 'COMPLIANCE_LEAD', 'ARCHITECT']) expect(canManageRisks(grants(role))).toBe(true)
    for (const role of ['REVIEWER', 'BUSINESS_ANALYST', 'VIEWER', 'TESTER']) expect(canManageRisks(grants(role))).toBe(false)
    expect(canManageRisks(grants())).toBe(false)
  })
})
