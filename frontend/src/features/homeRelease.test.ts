import { describe, expect, it } from 'vitest'
import type { CurrentRelease, ReleaseMove } from '@/shared/api/client'
import {
  STATE_CLASS, STATE_GLYPH, STATE_LABEL, blockedReason, failingGates, forwardMoves, hasBlockers, moveLabel,
  readinessText, targetDateText,
} from './homeRelease'

const move = (over: Partial<ReleaseMove> = {}): ReleaseMove => ({
  to: 'FROZEN', needsReason: false, signatureRequired: true, ready: false,
  gates: [
    { gate: 'ALL_APPROVED', passed: false, detail: '1 of 2 committed requirements are not Approved (VY-3)' },
    { gate: 'SCOPE_NOT_EMPTY', passed: true, detail: '2 committed' },
  ], ...over,
})

const release = (over: Partial<CurrentRelease> = {}): CurrentRelease => ({
  id: 'r', name: 'Release 4', state: 'OPEN', targetDate: '2027-01-01T00:00:00Z', moves: [move()], blocked: [], ...over,
})

describe('VYB-0929 Home: the release being prepared', () => {
  it('VYB0929_AC8_everyStateHasALabelAndAGlyphSoItIsNeverColourAlone', () => {
    for (const s of ['PLANNED', 'OPEN', 'FROZEN', 'RELEASED'] as const) {
      expect(STATE_LABEL[s]).toBeTruthy()
      expect(STATE_GLYPH[s]).toBeTruthy()
      expect(STATE_CLASS[s]).not.toMatch(/(^|-)ai(-|$)|amber/)
    }
    expect(new Set(Object.values(STATE_GLYPH)).size).toBe(4)
  })

  it('VYB0929_AC8_aMoveIsNamedTheWayAPersonWouldAndAReopenIsNotAnOpen', () => {
    expect(moveLabel(move({ to: 'FROZEN' }))).toBe('Freeze')
    expect(moveLabel(move({ to: 'RELEASED' }))).toBe('Release')
    expect(moveLabel(move({ to: 'OPEN', needsReason: true }))).toBe('Reopen')
    expect(moveLabel(move({ to: 'OPEN', needsReason: false }))).toBe('Open')
  })

  it('VYB0929_AC8_onlyTheForwardMovesAreWhatBlocksTheRelease', () => {
    const moves = [move({ to: 'RELEASED' }), move({ to: 'OPEN', needsReason: true, gates: [], ready: true })]
    expect(forwardMoves(moves).map((m) => m.to)).toEqual(['RELEASED'])
  })

  it('VYB0929_AC8_readinessIsSaidInWordsWithTheNumberOfChecksFailing', () => {
    expect(readinessText(move({ ready: true, gates: [] }))).toBe('Ready to freeze')
    expect(readinessText(move())).toBe('Not ready to freeze: 1 check failing')
    expect(readinessText(move({ to: 'RELEASED', gates: [
      { gate: 'A', passed: false, detail: 'a' }, { gate: 'B', passed: false, detail: 'b' }] }))).toBe('Not ready to release: 2 checks failing')
    expect(failingGates(move()).map((g) => g.gate)).toEqual(['ALL_APPROVED'])
  })

  it('VYB0929_AC8_aBlockedReasonReadsInWordsAndAnUnknownOneIsStillShown', () => {
    expect(blockedReason('unverified')).toBe('Not verified')
    expect(blockedReason('conflicting')).toBe('In conflict')
    expect(blockedReason('unowned')).toBe('No owner')
    expect(blockedReason('something-new')).toBe('something-new')
  })

  it('VYB0929_AC8_aMissingTargetDateIsSaidNotLeftBlank', () => {
    expect(targetDateText(undefined)).toBe('no target date')
    expect(targetDateText('not a date')).toBe('no target date')
    expect(targetDateText('2027-01-01T00:00:00Z')).toMatch(/^target .*2027/)
  })

  it('VYB0929_AC8_thereIsSomethingToShowOnlyWhenAForwardMoveIsNotReadyOrARequirementIsBlocked', () => {
    expect(hasBlockers(release())).toBe(true)
    expect(hasBlockers(release({ moves: [move({ ready: true, gates: [] })] }))).toBe(false)
    expect(hasBlockers(release({ moves: [move({ ready: true, gates: [] })], blocked: [{ requirementId: 'x', key: 'VY-1', reason: 'unowned' }] }))).toBe(true)
    // a reopen that is "not ready" is not what blocks the release
    expect(hasBlockers(release({ moves: [move({ to: 'OPEN', needsReason: true, ready: false })] }))).toBe(false)
  })
})
