import { describe, it, expect } from 'vitest'
import type { Capability, ImportCandidateInfo } from '@/shared/api/client'
import { capabilityControl, defaultCapabilityChoice, unselectedDependencies, wouldCommit } from './ImportQueue'

function candidate(over: Partial<ImportCandidateInfo> = {}): ImportCandidateInfo {
  return {
    id: 'c1', batchId: 'b1', statement: 'The system shall record the lead date.',
    capabilityConfirmed: false, criteriaCount: 1, selected: true, typeConfirmed: false, ...over,
  }
}

function capability(id: string, name: string): Capability {
  return { id, applicationId: 'app1', name, archived: false }
}

const ONE_CAPABILITY = [capability('cap-lead', 'Lead Management')]

describe('VYB-0634 — capability inference is a proposal, confirmation required', () => {
  it('VYB0634_AC1_singleCapabilityIsStillConfirmable', () => {
    // The regression this covers: with exactly one capability a `<select>` has no second
    // option, so an onChange-only confirm could never fire and every commit reported
    // "capability not confirmed". The picker must still be offered here.
    const c = candidate({ capabilityId: 'cap-lead' })
    expect(capabilityControl(c, ONE_CAPABILITY)).toBe('pick')
    expect(defaultCapabilityChoice(c, ONE_CAPABILITY)).toBe('cap-lead')
  })

  it('VYB0634_AC1_unproposedCandidateIsStillConfirmable', () => {
    // The word-overlap proposer leaves capabilityId null when nothing matched; that
    // candidate must still be importable rather than dead-ended on "Propose".
    const c = candidate({ capabilityId: undefined })
    expect(capabilityControl(c, ONE_CAPABILITY)).toBe('pick')
    expect(defaultCapabilityChoice(c, ONE_CAPABILITY)).toBe('cap-lead')
  })

  it('VYB0634_AC1_proposalAloneDoesNotCount', () => {
    // A proposal is not a confirmation — commit must still refuse it.
    expect(wouldCommit(candidate({ capabilityId: 'cap-lead', capabilityConfirmed: false }))).toBe(false)
    expect(wouldCommit(candidate({ capabilityId: 'cap-lead', capabilityConfirmed: true }))).toBe(true)
  })

  it('VYB0634_AC1_confirmedShowsNoPicker', () => {
    expect(capabilityControl(candidate({ capabilityConfirmed: true }), ONE_CAPABILITY)).toBe('confirmed')
  })

  it('VYB0634_AC1_noCapabilitiesIsNamedNotBlank', () => {
    // Rule 8: absence renders as a stated reason, never a blank control.
    expect(capabilityControl(candidate(), [])).toBe('none-available')
    expect(defaultCapabilityChoice(candidate(), [])).toBe('')
  })

  it('VYB0634_AC1_proposalOutsideTheOptionListFallsBackToFirst', () => {
    // A stale proposal (capability since archived out of the list) must not leave the
    // picker on a value the user cannot see or submit.
    const c = candidate({ capabilityId: 'cap-archived' })
    expect(defaultCapabilityChoice(c, ONE_CAPABILITY)).toBe('cap-lead')
  })
})

describe('VYB-0666 — the sheet\'s own Depends On, checked against what is ticked', () => {
  function withDependsOn(refs: string[], over: Partial<ImportCandidateInfo> = {}): ImportCandidateInfo {
    return candidate({ flags: JSON.stringify({ prdDependsOn: refs }), ...over })
  }

  it('VYB0666_AC1_flagsADependencyThatIsNotTicked', () => {
    const so010 = candidate({ id: 'so010', tag: 'SO-010', selected: false })
    const so011 = withDependsOn(['SO-010'], { id: 'so011', tag: 'SO-011', selected: true })

    expect(unselectedDependencies(so011, [so010, so011])).toEqual([so010])
  })

  it('VYB0666_AC1_bothTickedIsNoWarning', () => {
    // The case the user confirmed works: tick the dependency too, and the gap disappears.
    const so010 = candidate({ id: 'so010', tag: 'SO-010', selected: true })
    const so011 = withDependsOn(['SO-010'], { id: 'so011', tag: 'SO-011', selected: true })

    expect(unselectedDependencies(so011, [so010, so011])).toEqual([])
  })

  it('VYB0666_AC1_aDependencyAlreadyCommittedEarlierIsNotAGap', () => {
    // Already in the register from a prior import — nothing left to tick.
    const so010 = candidate({ id: 'so010', tag: 'SO-010', selected: false, committedRequirementId: 'req-9' })
    const so011 = withDependsOn(['SO-010'], { id: 'so011', tag: 'SO-011', selected: true })

    expect(unselectedDependencies(so011, [so010, so011])).toEqual([])
  })

  it('VYB0666_AC1_aDependencyRefNotInThisBatchIsSilentlyIgnored', () => {
    // The referenced row might be in a different upload entirely, or simply a typo —
    // either way there is nothing in this batch to offer ticking.
    const so011 = withDependsOn(['SO-999'], { id: 'so011', tag: 'SO-011', selected: true })

    expect(unselectedDependencies(so011, [so011])).toEqual([])
  })

  it('VYB0666_AC1_noDependsOnColumnMeansNoGaps', () => {
    const plain = candidate({ id: 'c1', tag: 'SO-001', selected: true })
    expect(unselectedDependencies(plain, [plain])).toEqual([])
  })
})