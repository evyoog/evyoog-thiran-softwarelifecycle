import { describe, expect, it } from 'vitest'
import type { ChangeImpact } from '@/shared/api/client'
import { countLabel, impactSections, listMatchesCount } from './impactItems'

const impact: ChangeImpact = {
  requirements: 2, tests: 1, applications: 1, briefs: 1,
  items: {
    requirements: [
      { id: 'r1', key: 'VY-1', title: 'Named', status: 'APPROVED', inScope: true },
      { id: 'r2', key: 'VY-2', title: 'Downstream', status: 'IN_REVIEW', inScope: false },
    ],
    tests: [{ id: 't1', key: 'TC-1', title: 'Checks it' }],
    applications: [{ id: 'a1', name: 'Billing', productName: 'Finance' }],
    briefs: [{ id: 'b1', applicationName: 'Billing', target: 'CLAUDE_CODE', generatedAt: '2026-10-09T10:00:00Z', stale: true }],
  },
}

describe('VYB-0943 — the items behind the impact counts', () => {
  it('VYB0943_AC20_eachCountHasItsItemsAndAListAsLongAsItsCount', () => {
    const sections = impactSections(impact)
    expect(sections.map((s) => s.kind)).toEqual(['requirements', 'tests', 'applications', 'briefs'])
    expect(sections.every(listMatchesCount)).toBe(true)
  })

  it('VYB0943_AC20_aRequirementSaysWhetherTheChangeRequestNamesItOrItWasReachedDownstream', () => {
    const [named, downstream] = impactSections(impact)[0]!.items
    expect(named).toMatchObject({ label: 'VY-1 — Named', href: '/requirements/r1' })
    expect(named!.detail).toContain('named by this change request')
    expect(downstream!.detail).toBe('reached downstream, in review')
  })

  it('VYB0943_AC21_applicationsAndBriefsAreNamedByWhatARepresentsNotByAnId', () => {
    const s = impactSections(impact)
    expect(s[2]!.items[0]).toEqual({ id: 'a1', label: 'Billing', detail: 'Finance' })
    expect(s[3]!.items[0]).toMatchObject({ label: 'claude code brief for Billing', detail: '2026-10-09, out of date' })
  })

  it('VYB0943_AC21_theToggleReadsTheCountInWordsWithTheRightPlural', () => {
    const s = impactSections(impact)
    expect(countLabel(s[0]!)).toBe('2 requirements')
    expect(countLabel(s[1]!)).toBe('1 test')
    expect(countLabel({ label: 'Brief', count: 0 })).toBe('0 briefs')
  })

  it('VYB0943_AC21_aListThatDisagreesWithItsCountIsSaidToAndAMissingItemsFieldGivesEmptyLists', () => {
    const noItems = impactSections({ requirements: 3, tests: 0, applications: 0, briefs: 0 })
    expect(noItems[0]!.items).toEqual([])
    expect(listMatchesCount(noItems[0]!)).toBe(false)
    expect(listMatchesCount(noItems[1]!)).toBe(true)
  })
})
