import { describe, expect, it } from 'vitest'
import { differs, groupByGroup, toggled } from './notificationPrefs'

describe('VYB-0955 — the notification screens’ small pure parts', () => {
  it('VYB0955_AC28_kindsAreGroupedInTheOrderTheGroupsFirstAppearAndKeepTheirOwnOrder', () => {
    const kinds = [
      { kind: 'a', group: 'Reviews' }, { kind: 'b', group: 'Releases' }, { kind: 'c', group: 'Reviews' }, { kind: 'd', group: 'Quality' },
    ]
    expect(groupByGroup(kinds).map(([g, ks]) => [g, ks.map((k) => k.kind)])).toEqual([
      ['Reviews', ['a', 'c']], ['Releases', ['b']], ['Quality', ['d']],
    ])
    expect(groupByGroup([])).toEqual([])
  })

  it('VYB0955_AC28_togglingAKindMakesANewSetAndLeavesTheSavedOneAlone', () => {
    const saved = new Set(['a'])
    const added = toggled(saved, 'b', true)
    expect([...added].sort()).toEqual(['a', 'b'])
    expect([...saved]).toEqual(['a'])
    expect([...toggled(added, 'a', false)]).toEqual(['b'])
    expect([...toggled(saved, 'a', true)]).toEqual(['a'])
  })

  it('VYB0955_AC28_theSaveButtonOnlyWakesWhenTheChosenKindsReallyDifferFromTheSavedOnes', () => {
    expect(differs(new Set(['a', 'b']), ['b', 'a'])).toBe(false)
    expect(differs(new Set(['a']), ['a', 'b'])).toBe(true)
    expect(differs(new Set(['a', 'c']), ['a', 'b'])).toBe(true)
    expect(differs(new Set(), [])).toBe(false)
  })
})
