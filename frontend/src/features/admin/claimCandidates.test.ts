import { describe, expect, it } from 'vitest'
import { claimCandidates, isAwaitingFirstSignIn, sharedWords, type DirectoryEntry } from './claimCandidates'

const entry = (id: string, email: string, displayName: string, over: Partial<DirectoryEntry> = {}): DirectoryEntry =>
  ({ id, email, displayName, source: 'SSO', lastSeenAt: '2026-10-01T10:00:00Z', activeGrantCount: 0, ...over })

const prepared = entry('p', 'pam.jones@typed.test', 'Pam Jones', { source: 'LOCAL', lastSeenAt: null })

describe('VYB-0961 — joining a prepared entry to the account its person signed in with', () => {
  it('VYB0961_AC11_onlyASignedInAccountWithNoAccessCanBeJoined', () => {
    const directory = [
      prepared,
      entry('a', 'pjones@company.test', 'P Jones'),
      entry('b', 'has.access@company.test', 'Has Access', { activeGrantCount: 2 }),
      entry('c', 'other.prepared@typed.test', 'Other', { source: 'LOCAL', lastSeenAt: null }),
      entry('d', 'never@company.test', 'Never', { lastSeenAt: null }),
    ]
    expect(claimCandidates(prepared, directory).map((c) => c.entry.id)).toEqual(['a'])
  })

  it('VYB0961_AC11_accountsThatShareAWordWithThePreparedEntryComeFirstThenTheMostRecentSignIns', () => {
    const directory = [
      entry('old', 'zed@company.test', 'Zed', { lastSeenAt: '2026-09-01T00:00:00Z' }),
      entry('new', 'quinn@company.test', 'Quinn', { lastSeenAt: '2026-10-10T00:00:00Z' }),
      entry('likely', 'jones.p@company.test', 'Someone Else', { lastSeenAt: '2026-08-01T00:00:00Z' }),
    ]
    const ranked = claimCandidates(prepared, directory)
    expect(ranked.map((c) => c.entry.id)).toEqual(['likely', 'new', 'old'])
    expect(ranked.map((c) => c.likely)).toEqual([true, false, false])
  })

  it('VYB0961_AC11_wordsAreCompareWithoutCaseAndShortOnesDoNotCount', () => {
    expect(sharedWords(entry('1', 'A.B@x.test', 'Al Bo'), entry('2', 'a.b@y.test', 'Al Bo'))).toBe(0) // every word is under three letters
    expect(sharedWords(entry('1', 'PAM@x.test', 'Pam'), entry('2', 'pam@y.test', 'PAM'))).toBe(1)
  })

  it('VYB0961_AC11_onlyAnEntryNobodyHasSignedIntoIsOfferedAClaim', () => {
    expect(isAwaitingFirstSignIn({ source: 'LOCAL', lastSeenAt: null })).toBe(true)
    expect(isAwaitingFirstSignIn({ source: 'LOCAL', lastSeenAt: '2026-10-01T00:00:00Z' })).toBe(false)
    expect(isAwaitingFirstSignIn({ source: 'SSO', lastSeenAt: null })).toBe(false)
  })
})
