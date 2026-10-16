import { describe, expect, it } from 'vitest'
import type { LinkedWork } from '@/shared/api/client'
import { KINDS, countText, emptyDraft, groupsOf, missing, releaseChoice, scopeEditable } from './linkWork'

const work: LinkedWork = {
  requirementId: 'r1', applicationId: 'a1',
  tests: [{ id: 't1', key: 'TC-1', title: 'Export works' }],
  designNodes: [{ id: 'n1', flowId: 'f1', kind: 'step', label: 'Export step' }],
  releases: [{ id: 'rel1', name: 'R-1', state: 'PLANNED' }],
  defects: [{ id: 'd1', key: 'DEF-3', title: 'Blank export', severity: 'CRITICAL', state: 'OPEN' }],
  risks: [{ id: 'k1', key: 'RSK-2', title: 'Provider outage', rating: 20, band: 'CRITICAL', status: 'OPEN' }],
}

describe('VYB-0943 — the Link dialog', () => {
  it('VYB0943_AC12_everyKindSaysWhatItDoesInWords', () => {
    expect(KINDS.map((k) => k.kind)).toEqual(['requirement', 'test', 'design', 'release', 'defect'])
    for (const k of KINDS) expect(k.how.length).toBeGreaterThan(20)
    expect(KINDS.find((k) => k.kind === 'defect')!.how).toContain('never moved')
    expect(KINDS.find((k) => k.kind === 'release')!.how).toContain('one release at a time')
  })

  it('VYB0943_AC13_nothingCanBeSavedUntilSomethingIsChosenAndTheMessageNamesWhat', () => {
    expect(missing(emptyDraft('test'))).toBe('Choose the test case.')
    expect(missing(emptyDraft('design'))).toBe('Choose the design node.')
    expect(missing(emptyDraft('defect'))).toBe('Choose the defect.')
    expect(missing(emptyDraft('requirement'))).toBe('Choose the requirement to link.')
    expect(missing({ ...emptyDraft('test'), targetId: 't1' })).toBeNull()
  })

  it('VYB0943_AC13_aReleaseAlsoNeedsAReasonBecauseTheScopeMovementIsRecorded', () => {
    const d = { ...emptyDraft('release'), targetId: 'rel1' }
    expect(missing(d)).toContain('reason')
    expect(missing({ ...d, reason: '   ' })).toContain('reason')
    expect(missing({ ...d, reason: 'Agreed at planning' })).toBeNull()
  })

  it('VYB0943_AC14_onlyAReleaseWhoseScopeCanStillChangeIsOffered', () => {
    expect(scopeEditable('PLANNED')).toBe(true)
    expect(scopeEditable('OPEN')).toBe(true)
    expect(scopeEditable('FROZEN')).toBe(false)
    expect(scopeEditable('RELEASED')).toBe(false)
    expect(releaseChoice({ name: 'R-1', state: 'OPEN' })).toEqual({ label: 'R-1 (open)', disabled: false })
    expect(releaseChoice({ name: 'R-2', state: 'FROZEN' }).label).toContain('reopen it')
    expect(releaseChoice({ name: 'R-3', state: 'RELEASED' })).toMatchObject({ disabled: true })
  })
})

describe('VYB-0943 — the linked work list', () => {
  it('VYB0943_AC15_theGroupsNameWhatIsLinked', () => {
    const g = groupsOf(work)
    expect(g.map((x) => x.title)).toEqual(['Tests', 'Design', 'Release', 'Defects', 'Risks', 'Commits'])
    expect(g[0]!.items[0]!.label).toBe('TC-1 — Export works')
    expect(g[1]!.items[0]).toMatchObject({ label: 'Export step', detail: 'step' })
    expect(g[2]!.items[0]).toMatchObject({ label: 'R-1', detail: 'planned', href: '/releases/rel1' })
    expect(g[3]!.items[0]).toMatchObject({ label: 'DEF-3 — Blank export', detail: 'critical, open', href: '/defects/d1' })
    expect(g[4]!.items[0]).toMatchObject({ label: 'RSK-2 — Provider outage', detail: 'critical (20), open', href: '/compliance?tab=risks&risk=k1' })
  })

  it('VYB0943_AC15_anEmptyGroupSaysSoInWordsAndNothingIsShownBeforeItLoads', () => {
    const empty = groupsOf({ ...work, tests: [], designNodes: [], releases: [], defects: [], risks: [] })
    for (const g of empty) {
      expect(g.items).toHaveLength(0)
      expect(g.empty.length).toBeGreaterThan(10)
      expect(countText(g)).toBe('none')
    }
    expect(empty[2]!.empty).toBe('Not in any release.')
    expect(groupsOf(undefined)).toEqual([])
    expect(countText(groupsOf(work)[0]!)).toBe('1')
  })
})

describe('VYB-0922 — the commits on the linked work list', () => {
  const commit = (over: Partial<NonNullable<typeof work.commits>[number]> = {}) =>
    ({ id: 'c1', sha: 'abcdef1234567', shortSha: 'abcdef1', subject: 'Pause stops billing', ingestedAt: '2026-10-16T09:00:00Z', href: 'https://github.com/evyoog/demo/commit/abcdef1234567', ...over })

  it('VYB0922_AC11_aCommitShowsItsShortShaAndSubjectAndLinksOutWhenARepositoryIsSet', () => {
    const g = groupsOf({ ...work, commits: [commit()] }).find((x) => x.kind === 'commit')!
    expect(g.source).toBe('git')
    expect(g.items[0]).toEqual({ id: 'c1', label: 'abcdef1', detail: 'Pause stops billing', url: 'https://github.com/evyoog/demo/commit/abcdef1234567' })
    expect(g.note).toBeUndefined()
  })

  it('VYB0922_AC11_withNoRepositoryAddressTheCommitsHaveNoLinkAndTheGroupSaysWhy', () => {
    const g = groupsOf({ ...work, commits: [commit({ href: null }), commit({ id: 'c2', href: undefined })] }).find((x) => x.kind === 'commit')!
    expect(g.items.every((i) => i.url === undefined)).toBe(true)
    expect(g.note).toContain('no repository address')
  })

  it('VYB0922_AC11_aRequirementWithNoCommitsSaysHowACommitNamesIt', () => {
    const g = groupsOf({ ...work, commits: [] }).find((x) => x.kind === 'commit')!
    expect(g.items).toHaveLength(0)
    expect(g.empty).toContain('Requirement: KEY')
    expect(groupsOf({ ...work, commits: undefined }).find((x) => x.kind === 'commit')!.items).toHaveLength(0)
  })
})
