import { describe, expect, it } from 'vitest'
import type { RequirementHistoryEntry } from '@/shared/api/client'
import {
  COLLAPSED_ROWS, MAX_DIFF_CELLS, actorText, changeSummaries, defaultPair, diffStats, diffWords, fieldChanges, revisionsOf,
  statusMoveText, visibleEntries, type Revision,
} from './revisionDiff'

const rev = (n: number, over: Partial<RequirementHistoryEntry> = {}): RequirementHistoryEntry => ({
  kind: 'REVISION', at: `2026-10-0${n}T10:00:00Z`, revision: n, title: 'T', statement: 'The system shall work.', type: 'FUNCTIONAL',
  priority: 'MEDIUM', capabilityId: 'c1', capabilityName: 'Billing', statusAtRevision: 'DRAFT', actorName: 'Ada', ...over,
})
const asRevisions = (...e: RequirementHistoryEntry[]) => revisionsOf(e)

describe('VYB-0941 — word diff', () => {
  it('VYB0941_AC8_identicalTextHasNoChange', () => {
    expect(diffWords('The system shall work.', 'The system shall work.')).toEqual([{ op: 'same', text: 'The system shall work.' }])
    expect(diffWords('', '')).toEqual([])
  })

  it('VYB0941_AC8_aChangedWordIsOneRemovalAndOneAdditionInReadingOrder', () => {
    const d = diffWords('The system shall answer in 5 seconds.', 'The system shall answer in 2 seconds.')
    expect(d.map((s) => `${s.op}:${s.text}`)).toEqual(['same:The system shall answer in ', 'del:5', 'add:2', 'same: seconds.'])
  })

  it('VYB0941_AC9_addedAndRemovedWordsAreCounted', () => {
    const d = diffWords('The system shall log errors.', 'The system shall log every error and warning.')
    expect(diffStats(d)).toEqual({ added: 4, removed: 1 })
  })

  it('VYB0941_AC9_joiningTheSegmentsOfTheSameAndAddedGivesTheNewTextAndOfSameAndRemovedTheOld', () => {
    const from = 'Users may reset a password by email within 24 hours.'
    const to = 'Users shall reset a password by email or SMS within 1 hour.'
    const d = diffWords(from, to)
    expect(d.filter((s) => s.op !== 'del').map((s) => s.text).join('')).toBe(to)
    expect(d.filter((s) => s.op !== 'add').map((s) => s.text).join('')).toBe(from)
  })

  it('VYB0941_AC10_anEmptyOldTextIsAllAddedAndAnEmptyNewTextIsAllRemoved', () => {
    expect(diffWords('', 'New text')).toEqual([{ op: 'add', text: 'New text' }])
    expect(diffWords('Old text', '')).toEqual([{ op: 'del', text: 'Old text' }])
  })

  it('VYB0941_AC10_aVeryLargeComparisonIsShownAsReplacedInsteadOfFreezingThePage', () => {
    const big = 'word '.repeat(Math.ceil(Math.sqrt(MAX_DIFF_CELLS)) + 10)
    const d = diffWords(big, big + 'x')
    expect(d.map((s) => s.op)).toEqual(['del', 'add'])
  })

  it('VYB0941_AC10_changedWhitespaceAloneIsStillAChangeSoTheTextReadsAsWritten', () => {
    const d = diffWords('a b', 'a  b')
    expect(d.some((s) => s.op !== 'same')).toBe(true)
    expect(diffStats(d)).toEqual({ added: 0, removed: 0 })
  })
})

describe('VYB-0941 — revisions and what changed', () => {
  it('VYB0941_AC11_revisionsAreTheRevisionRowsOldestFirstAndStatusMovesAreLeftOut', () => {
    const entries: RequirementHistoryEntry[] = [
      { kind: 'STATUS_CHANGE', at: '2026-10-09T10:00:00Z', fromStatus: 'DRAFT', toStatus: 'IN_REVIEW' }, rev(3), rev(1), rev(2),
    ]
    expect(revisionsOf(entries).map((r) => r.revision)).toEqual([1, 2, 3])
  })

  it('VYB0941_AC12_eachRevisionNamesTheFieldsItChangedAgainstThePreviousOne', () => {
    const later = { statement: 'Changed.', priority: 'HIGH', title: 'New title' }
    const rs = asRevisions(rev(1), rev(2, { statement: 'Changed.' }), rev(3, later), rev(4, later))
    const s = changeSummaries(rs)
    expect(s.get(1)).toBe('Created')
    expect(s.get(2)).toBe('Statement')
    expect(s.get(3)).toBe('Title, Priority')
    expect(s.get(4)).toBe('No field changed')
  })

  it('VYB0941_AC12_aMovedCapabilityIsShownByItsNameAndAMissingOneIsSaidInWords', () => {
    const a = asRevisions(rev(1))[0] as Revision
    const moved = asRevisions(rev(2, { capabilityId: 'c2', capabilityName: 'Payments' }))[0] as Revision
    const gone = asRevisions(rev(3, { capabilityId: 'c9', capabilityName: null }))[0] as Revision
    const none = asRevisions(rev(4, { capabilityId: null, capabilityName: null }))[0] as Revision
    expect(fieldChanges(a, moved)).toEqual([{ field: 'capability', label: 'Capability', from: 'Billing', to: 'Payments' }])
    expect(fieldChanges(a, gone)[0]!.to).toBe('a capability that is no longer listed')
    expect(fieldChanges(a, none)[0]!.to).toBe('not placed')
  })

  it('VYB0941_AC13_theDefaultComparisonIsTheRevisionBeforeTheLatestAndTheLatest', () => {
    expect(defaultPair(asRevisions(rev(1), rev(2), rev(3)))).toEqual({ from: 2, to: 3 })
    expect(defaultPair(asRevisions(rev(1)))).toEqual({ from: 1, to: 1 })
    expect(defaultPair([])).toBeNull()
  })
})

describe('VYB-0941 — the list', () => {
  const many = Array.from({ length: COLLAPSED_ROWS + 3 }, (_, i) => rev((i % 9) + 1))

  it('VYB0941_AC14_theListIsCollapsedToTheNewestRowsUntilShowAll', () => {
    expect(visibleEntries(many, false)).toHaveLength(COLLAPSED_ROWS)
    expect(visibleEntries(many, true)).toHaveLength(many.length)
    expect(visibleEntries(many.slice(0, 3), false)).toHaveLength(3)
  })

  it('VYB0941_AC15_aPersonIsNamedOrTheirAbsenceIsSaidInWords', () => {
    expect(actorText({ actorId: 'u1', actorName: 'Ada Author' })).toBe('Ada Author')
    expect(actorText({ actorId: 'u1', actorName: null })).toBe('a person no longer on record')
    expect(actorText({ actorId: null, actorName: null })).toBe('the system')
  })

  it('VYB0941_AC15_aStatusMoveReadsFromTo', () => {
    expect(statusMoveText({ fromStatus: 'REVIEWED', toStatus: 'REJECTED' })).toBe('REVIEWED → REJECTED')
  })
})
