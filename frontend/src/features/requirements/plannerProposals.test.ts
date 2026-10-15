import { describe, expect, it } from 'vitest'
import type { PlannerProposal } from '@/shared/api/client'
import { acceptBlockedReason, diffProposal, splitProposals, stateNote } from './plannerProposals'

const proposal = (over: Partial<PlannerProposal> = {}): PlannerProposal => ({
  id: 'p1', state: 'PENDING', receivedAt: '2026-10-15T09:00:00Z', sourceFunctionId: 'FN-1',
  title: 'Pause', statement: 'The system shall pause.', priority: 'MEDIUM', criteria: ['One'], ...over,
})
const now = { title: 'Pause', statement: 'The system shall pause.', priority: 'MEDIUM' as const }

describe('VYB-0919 — a change the Planner proposed, as the requirement detail shows it', () => {
  it('VYB0919_AC10_saysWhichOfTheFieldsWouldChangeAndIgnoresSpacing', () => {
    const d = diffProposal(now, ['One'], proposal({ title: ' Pause ', statement: 'The system shall pause or cancel.', priority: 'HIGH' }))
    expect(d.title).toBeUndefined()
    expect(d.statement).toEqual({ now: 'The system shall pause.', proposed: 'The system shall pause or cancel.' })
    expect(d.priority).toEqual({ now: 'MEDIUM', proposed: 'HIGH' })
    expect(d.nothingChanges).toBe(false)
  })

  it('VYB0919_AC10_criteriaAreAddedNeverRemovedAndTheOnesNotSentAreSaidToStay', () => {
    const d = diffProposal(now, ['Keep me', 'Shared'], proposal({ criteria: ['Shared', 'A new one'] }))
    expect(d.criteriaAdded).toEqual(['A new one'])
    expect(d.criteriaKept).toEqual(['Keep me'])
  })

  it('VYB0919_AC10_aProposalThatSaysWhatTheRequirementAlreadySaysChangesNothing', () => {
    expect(diffProposal(now, ['One'], proposal()).nothingChanges).toBe(true)
    expect(diffProposal(now, ['One', 'Extra'], proposal()).nothingChanges).toBe(true) // an extra criterion here is simply kept
    expect(diffProposal(now, [], proposal()).nothingChanges).toBe(false)
  })

  it('VYB0919_AC11_onlyAnApprovedRequirementCannotAcceptAndTheReasonSaysWhatToDo', () => {
    expect(acceptBlockedReason('APPROVED')).toContain('change request')
    for (const s of ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'REJECTED', 'NEEDS_REVISION'] as const) expect(acceptBlockedReason(s)).toBeUndefined()
  })

  it('VYB0919_AC12_waitingProposalsAreSeparatedFromDecidedOnesAndAReplacedOneIsNotShown', () => {
    const { pending, decided } = splitProposals([
      proposal({ id: 'a' }), proposal({ id: 'b', state: 'ACCEPTED' }), proposal({ id: 'c', state: 'REJECTED' }), proposal({ id: 'd', state: 'SUPERSEDED' }),
    ])
    expect(pending.map((p) => p.id)).toEqual(['a'])
    expect(decided.map((p) => p.id)).toEqual(['b', 'c'])
    expect(splitProposals(undefined)).toEqual({ pending: [], decided: [] })
  })

  it('VYB0919_AC12_everyStateIsAGlyphAndWordsNotColourAlone', () => {
    for (const s of ['PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED'] as const) {
      const n = stateNote(s)
      expect(n.glyph).not.toBe('')
      expect(n.text).not.toBe('')
    }
  })
})
