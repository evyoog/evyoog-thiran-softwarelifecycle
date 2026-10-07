import { describe, expect, it } from 'vitest'
import type { ReleaseCandidate } from '@/shared/api/client'
import {
  MAX_PER_COMMIT, candidateNote, candidateState, commitBlockedReason, commitLabel, lockMessage, resultText, scopeLocked,
  toggle, togglePage,
} from './releaseScope'

const cand = (over: Partial<ReleaseCandidate> = {}): ReleaseCandidate => ({ requirementId: 'r1', key: 'VY-1', title: 'T', status: 'DRAFT', ...over })
const HERE = 'rel-here'

describe('VYB-0930 scope picker', () => {
  it('VYB0930_AC7_aFrozenOrReleasedReleaseHasALockedScopeWithAReasonInWords', () => {
    expect(scopeLocked('FROZEN')).toBe(true)
    expect(scopeLocked('RELEASED')).toBe(true)
    expect(scopeLocked('PLANNED')).toBe(false)
    expect(scopeLocked('OPEN')).toBe(false)
    expect(scopeLocked(undefined)).toBe(false)
    expect(lockMessage('FROZEN')).toMatch(/Reopen it/)
    expect(lockMessage('RELEASED')).toMatch(/can no longer change/)
    expect(lockMessage('OPEN')).toBeUndefined()
  })

  it('VYB0930_AC7_aCandidateIsFreeAlreadyHereOrTakenByAnotherReleaseAndSaysSo', () => {
    expect(candidateState(cand(), HERE)).toBe('pickable')
    expect(candidateState(cand({ committedToId: HERE, committedToName: 'Here' }), HERE)).toBe('here')
    expect(candidateState(cand({ committedToId: 'other', committedToName: 'Release 3' }), HERE)).toBe('elsewhere')
    expect(candidateNote(cand(), HERE)).toBe('')
    expect(candidateNote(cand({ committedToId: HERE }), HERE)).toBe('Already committed to this release')
    expect(candidateNote(cand({ committedToId: 'other', committedToName: 'Release 3' }), HERE)).toBe('Committed to Release 3')
    expect(candidateNote(cand({ committedToId: 'other' }), HERE)).toBe('Committed to another release')
  })

  it('VYB0930_AC8_onlyAFreeCandidateCanBeToggledIntoTheSelectionAndAToggleIsReversible', () => {
    const a = cand({ requirementId: 'a', key: 'VY-1' })
    const taken = cand({ requirementId: 't', key: 'VY-2', committedToId: 'other' })
    const mine = cand({ requirementId: 'm', key: 'VY-3', committedToId: HERE })
    let sel: ReturnType<typeof toggle> = new Map()
    sel = toggle(sel, taken, HERE)
    sel = toggle(sel, mine, HERE)
    expect(sel.size).toBe(0)
    sel = toggle(sel, a, HERE)
    expect([...sel.entries()]).toEqual([['a', 'VY-1']])
    sel = toggle(sel, a, HERE)
    expect(sel.size).toBe(0)
  })

  it('VYB0930_AC8_theSelectionIsNeverMutatedInPlace', () => {
    const before: ReturnType<typeof toggle> = new Map()
    const after = toggle(before, cand(), HERE)
    expect(before.size).toBe(0)
    expect(after).not.toBe(before)
  })

  it('VYB0930_AC8_selectAllOnThePageTakesOnlyTheFreeOnesAndClearsThemWhenAllAreSelected', () => {
    const page = [cand({ requirementId: 'a', key: 'A' }), cand({ requirementId: 'b', key: 'B' }),
      cand({ requirementId: 'x', key: 'X', committedToId: 'other' })]
    const all = togglePage(new Map(), page, HERE)
    expect([...all.keys()]).toEqual(['a', 'b'])
    expect(togglePage(all, page, HERE).size).toBe(0)
    // a selection from another search is kept when this page is added or cleared
    const kept: ReturnType<typeof toggle> = new Map([['z', 'Z']])
    expect([...togglePage(kept, page, HERE).keys()]).toEqual(['z', 'a', 'b'])
    expect([...togglePage(togglePage(kept, page, HERE), page, HERE).keys()]).toEqual(['z'])
    // nothing to pick on the page: unchanged
    expect(togglePage(kept, [page[2]], HERE)).toBe(kept)
  })

  it('VYB0930_AC9_commitIsBlockedWithAReasonInWordsUntilThereIsASelectionAndAReason', () => {
    expect(commitBlockedReason(0, 'because', false)).toBe('Pick at least one requirement.')
    expect(commitBlockedReason(2, '   ', false)).toMatch(/reason/)
    expect(commitBlockedReason(2, 'planning', true)).toBe('The scope is locked.')
    expect(commitBlockedReason(MAX_PER_COMMIT + 1, 'planning', false)).toMatch(/at most 200/)
    expect(commitBlockedReason(2, 'planning', false)).toBeUndefined()
    expect(commitBlockedReason(MAX_PER_COMMIT, 'planning', false)).toBeUndefined()
  })

  it('VYB0930_AC9_theButtonAndTheResultReadInPlainWords', () => {
    expect(commitLabel(0)).toBe('Commit')
    expect(commitLabel(1)).toBe('Commit 1 requirement')
    expect(commitLabel(3)).toBe('Commit 3 requirements')
    expect(resultText(1, 0)).toBe('Committed 1 requirement.')
    expect(resultText(3, 2)).toBe('Committed 3 requirements; 2 already in this release.')
  })
})
