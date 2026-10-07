import { describe, expect, it } from 'vitest'
import type { AiProposal, ElaborationStatus } from '@/shared/api/client'
import { acceptLabel, acceptProblem, detailOf, draftedText, editsFor, statusText } from './elaborationReview'

function proposal(over: Partial<AiProposal> = {}): AiProposal {
  return {
    id: 'p1', kind: 'BRIEF_ELABORATION', state: 'PENDING', requirementKey: 'VY-12', stale: false,
    payload: { detail: 'The AI wrote this.' }, model: 'm', proposedAt: '2026-10-07T10:00:00Z', ...over,
  }
}
const status = (over: Partial<ElaborationStatus> = {}): ElaborationStatus => ({ requirementsInScope: 5, accepted: 0, pending: [], ...over })

describe('VYB-0938 — brief elaboration review', () => {
  it('VYB0938_AC23_theStatusLineSaysWhatIsReviewedAndWhatIsWaitingAndNeverReadsBlank', () => {
    expect(statusText(undefined)).toMatch(/Checking/)
    expect(statusText(status({ requirementsInScope: 0 }))).toMatch(/Nothing in this scope would be briefed/)
    expect(statusText(status())).toBe('No requirement in scope has a reviewed elaboration yet.')
    expect(statusText(status({ accepted: 1 }))).toBe('1 of 5 requirements in scope has a reviewed elaboration.')
    expect(statusText(status({ accepted: 3, pending: [proposal(), proposal({ id: 'p2' })] })))
      .toBe('3 of 5 requirements in scope have a reviewed elaboration. 2 are waiting for review.')
    expect(statusText(status({ requirementsInScope: 1, accepted: 1, pending: [proposal()] }))).toMatch(/1 is waiting/)
  })

  it('VYB0938_AC23_anUntouchedDraftIsAcceptedAsProposedAndAnEditIsSentWithItsText', () => {
    const p = proposal()
    expect(editsFor(p, 'The AI wrote this.')).toBeUndefined()
    expect(editsFor(p, '  The AI wrote this.  ')).toBeUndefined()
    expect(editsFor(p, 'My version.')).toEqual({ detail: 'My version.' })
    expect(editsFor(p, '   ')).toBeUndefined()
    expect(acceptLabel(p, 'The AI wrote this.')).toBe('Accept')
    expect(acceptLabel(p, 'My version.')).toBe('Accept with my edit')
  })

  it('VYB0938_AC23_aDraftForARequirementThatChangedCannotBeAcceptedAndSaysWhy', () => {
    expect(acceptProblem(proposal({ stale: true }), 'x')).toMatch(/VY-12 has changed since this was drafted/)
    expect(acceptProblem(proposal(), '')).toMatch(/no text/)
    expect(acceptProblem(proposal(), 'fine')).toBeUndefined()
  })

  it('VYB0938_AC23_detailIsReadFromThePayloadAndAnAbsentOneIsEmptyNotUndefined', () => {
    expect(detailOf(proposal())).toBe('The AI wrote this.')
    expect(detailOf(proposal({ payload: {} }))).toBe('')
    expect(detailOf(proposal({ payload: { detail: 3 } }))).toBe('')
  })

  it('VYB0938_AC23_afterDraftingItSaysNothingIsInABriefUntilAccepted', () => {
    expect(draftedText(3, 4)).toBe('Drafted 3 elaborations for 4 requirements. None is in a brief until you accept it.')
    expect(draftedText(1, 1)).toBe('Drafted 1 elaboration for 1 requirement. None is in a brief until you accept it.')
    expect(draftedText(0, 2)).toBe('The AI returned nothing usable for the 2 requirements in scope.')
  })
})
