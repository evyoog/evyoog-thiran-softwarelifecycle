import { describe, expect, it } from 'vitest'
import type { ChangeRequest, VersionRef, RequirementVersions } from '@/shared/api/client'
import { canFork, eligibleChangeRequests, openFork, versionNotes } from './versions'

const ref = (key: string, status: VersionRef['status'] = 'DRAFT'): VersionRef => ({ id: `id-${key}`, key, title: key, status })
const none: RequirementVersions = { forks: [] }
const cr = (key: string, state: ChangeRequest['state'], scope: string[]): ChangeRequest =>
  ({ id: `cr-${key}`, key, title: key, rationale: '', state, impactRequirements: 0, impactApps: 0, scope })

describe('VYB-0942 — versions on the requirement detail', () => {
  it('VYB0942_AC12_forkIsOfferedOnlyForAnApprovedRequirementThatIsNotReplacedAndHasNoNewVersionInProgress', () => {
    expect(canFork('APPROVED', none)).toBe(true)
    expect(canFork('DRAFT', none)).toBe(false)
    expect(canFork('REVIEWED', none)).toBe(false)
    expect(canFork('APPROVED', undefined)).toBe(false)
    expect(canFork('APPROVED', { forks: [ref('VY-2')] })).toBe(false)
    expect(canFork('APPROVED', { forks: [], supersededBy: ref('VY-3', 'APPROVED') })).toBe(false)
  })

  it('VYB0942_AC12_aRejectedNewVersionDoesNotBlockAnotherFork', () => {
    expect(openFork({ forks: [ref('VY-2', 'REJECTED')] })).toBeUndefined()
    expect(canFork('APPROVED', { forks: [ref('VY-2', 'REJECTED')] })).toBe(true)
    expect(openFork({ forks: [ref('VY-2', 'REJECTED'), ref('VY-3', 'IN_REVIEW')] })?.key).toBe('VY-3')
  })

  it('VYB0942_AC13_onlyAnApprovedChangeRequestThatNamesTheRequirementCanBeChosen', () => {
    const all = [cr('CR-1', 'APPROVED', ['r1', 'r2']), cr('CR-2', 'OPEN', ['r1']), cr('CR-3', 'APPROVED', ['r9']), cr('CR-4', 'APPLIED', ['r1'])]
    expect(eligibleChangeRequests(all, 'r1').map((c) => c.key)).toEqual(['CR-1'])
    expect(eligibleChangeRequests(all, 'r5')).toEqual([])
    expect(eligibleChangeRequests(undefined, 'r1')).toEqual([])
  })

  it('VYB0942_AC14_aNewVersionSaysWhatItWasForkedFromAndForWhichChangeRequest', () => {
    const n = versionNotes({ forks: [], forkedFrom: ref('VY-1', 'APPROVED'), changeRequestKey: 'CR-7' })
    expect(n).toHaveLength(1)
    expect(n[0]!.text).toBe('A new version of VY-1, made for CR-7.')
    expect(n[0]!.target?.key).toBe('VY-1')
  })

  it('VYB0942_AC14_aReplacedRequirementSaysSoInWordsAndDoesNotAlsoSayAVersionIsInProgress', () => {
    const n = versionNotes({ forks: [ref('VY-2', 'APPROVED')], supersededBy: ref('VY-2', 'APPROVED') })
    expect(n).toHaveLength(1)
    expect(n[0]!.text).toContain('Replaced by VY-2')
    expect(n[0]!.text).toContain('still reads Approved')
  })

  it('VYB0942_AC14_anOriginalWithANewVersionInProgressNamesIt', () => {
    const n = versionNotes({ forks: [ref('VY-5', 'IN_REVIEW')] })
    expect(n.map((x) => x.text)).toEqual(['A new version is in progress: VY-5.'])
  })

  it('VYB0942_AC14_everyNoteHasAGlyphSoColourIsNeverTheOnlySignal', () => {
    const all = versionNotes({ forks: [ref('VY-5')], forkedFrom: ref('VY-1'), supersededBy: ref('VY-9') })
    expect(all.length).toBeGreaterThan(0)
    for (const note of all) expect(note.glyph.length).toBeGreaterThan(0)
    expect(versionNotes(undefined)).toEqual([])
  })
})
