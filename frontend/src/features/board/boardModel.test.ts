import { describe, it, expect } from 'vitest'
import type { BoardTemplate } from '@/shared/api/client'
import { ALL_STATUSES, canEditTemplate, columnParams, legalTargets, moveColumn, outcome, refusal, templateLabel, templateSummary, toggleColumn } from './boardModel'
import { NEXT_STATES, plainTargets, reasonRequired } from '../requirements/lifecycle'

const tpl = (o: Partial<BoardTemplate> = {}): BoardTemplate => ({ id: 't', name: 'Mine', columns: ['DRAFT'], isDefault: false, updatedAt: '', ...o })

describe('VYB-0953 — the board follows the lifecycle', () => {
  it('VYB0953_AC1_aDropIsMoveVerifyIllegalOrSameFromTheLifecycleTable', () => {
    expect(outcome('DRAFT', 'IN_REVIEW')).toBe('MOVE')
    expect(outcome('IN_REVIEW', 'REVIEWED')).toBe('VERIFY')
    expect(outcome('DRAFT', 'APPROVED')).toBe('ILLEGAL')
    expect(outcome('APPROVED', 'DRAFT')).toBe('ILLEGAL')
    expect(outcome('REVIEWED', 'REVIEWED')).toBe('SAME')
    // every status pair agrees with NEXT_STATES, so the board can never offer what the page would not
    for (const a of ALL_STATUSES) for (const b of ALL_STATUSES) {
      if (a !== b) expect(outcome(a, b) !== 'ILLEGAL').toBe(NEXT_STATES[a].includes(b))
    }
  })

  it('VYB0953_AC2_anIllegalDropIsRefusedInWordsAndALegalOneIsNot', () => {
    expect(refusal('DRAFT', 'APPROVED')).toContain('can only move to in review')
    expect(refusal('APPROVED', 'DRAFT')).toContain('change request')
    expect(refusal('DRAFT', 'IN_REVIEW')).toBeNull()
    expect(refusal('IN_REVIEW', 'REVIEWED')).toBeNull()
  })

  it('VYB0953_AC3_aCardOffersOnlyTheLegalColumnsThatTheTemplateShows', () => {
    expect(legalTargets('REVIEWED', ['DRAFT', 'APPROVED', 'REJECTED'])).toEqual(['APPROVED', 'REJECTED'])
    expect(legalTargets('APPROVED', ALL_STATUSES)).toEqual([])
    expect(legalTargets('IN_REVIEW', ALL_STATUSES)).toEqual(['DRAFT', 'REVIEWED'])
    expect(plainTargets('IN_REVIEW')).toEqual(['DRAFT'])
  })

  it('VYB0953_AC4_aReasonIsAskedForExactlyWhereThePageAsksForOne', () => {
    expect(reasonRequired('REVIEWED', 'REJECTED', 1)).toBe(true)
    expect(reasonRequired('REVIEWED', 'NEEDS_REVISION', 1)).toBe(true)
    expect(reasonRequired('REJECTED', 'NEEDS_REVISION', 1)).toBe(false)
    expect(reasonRequired('DRAFT', 'IN_REVIEW', 0)).toBe(true)
    expect(reasonRequired('DRAFT', 'IN_REVIEW', 2)).toBe(false)
  })
})

describe('VYB-0953 — templates', () => {
  it('VYB0953_AC5_aColumnQueryCarriesTheStatusAndOnlyTheScopeAndFiltersSet', () => {
    expect(columnParams(tpl(), 'DRAFT')).toEqual({ status: 'DRAFT', page: 0, size: 50, sort: ['key,asc'] })
    expect(columnParams(tpl({ applicationId: 'a', type: 'SECURITY', priority: 'HIGH' }), 'APPROVED', 10))
      .toEqual({ status: 'APPROVED', page: 0, size: 10, sort: ['key,asc'], applicationId: 'a', type: 'SECURITY', priority: 'HIGH' })
  })

  it('VYB0953_AC6_theSummaryNamesTheDeepestScopeAndTheFilters', () => {
    expect(templateSummary(tpl())).toBe('All requirements')
    expect(templateSummary(tpl({ productName: 'P', applicationName: 'A', capabilityName: 'C', type: 'NON_FUNCTIONAL', priority: 'LOW' })))
      .toBe('Capability C · non functional · low priority')
    expect(templateLabel(tpl({ isDefault: true, name: 'Default' }))).toBe('Default (everyone)')
    expect(templateLabel(tpl({ teamName: 'Platform' }))).toBe('Platform: Mine')
  })

  it('VYB0953_AC7_onlyAnAdministratorOrTheTeamsLeadSeesTheControls', () => {
    const me = { id: 'u1', platformAdministrator: false }
    expect(canEditTemplate(me, tpl(), [{ userId: 'u1', role: 'LEAD' }])).toBe(true)
    expect(canEditTemplate(me, tpl(), [{ userId: 'u1', role: 'MEMBER' }])).toBe(false)
    expect(canEditTemplate(me, tpl(), [{ userId: 'u2', role: 'LEAD' }])).toBe(false)
    expect(canEditTemplate(me, tpl({ isDefault: true }), [{ userId: 'u1', role: 'LEAD' }])).toBe(false)
    expect(canEditTemplate({ ...me, platformAdministrator: true }, tpl({ isDefault: true }), undefined)).toBe(true)
    expect(canEditTemplate(undefined, tpl(), [])).toBe(false)
  })

  it('VYB0953_AC8_columnsReorderAndTheLastOneStays', () => {
    expect(moveColumn(['DRAFT', 'APPROVED'], 0, 1)).toEqual(['APPROVED', 'DRAFT'])
    expect(moveColumn(['DRAFT', 'APPROVED'], 0, -1)).toEqual(['DRAFT', 'APPROVED'])
    expect(toggleColumn(['DRAFT', 'APPROVED'], 'DRAFT')).toEqual(['APPROVED'])
    expect(toggleColumn(['APPROVED'], 'APPROVED')).toEqual(['APPROVED'])
    expect(toggleColumn(['APPROVED'], 'DRAFT')).toEqual(['APPROVED', 'DRAFT'])
  })
})
