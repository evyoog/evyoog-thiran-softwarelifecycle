import type { PlannerProposal, PlannerProposalState, RequirementPriority, RequirementStatus } from '@/shared/api/client'

/**
 * VYB-0919 (D24, D51): the words around a change the Agile Planner proposed to a requirement. The server decides every rule (who may
 * decide, that an approved requirement cannot take it); this only says what would change and hides what cannot work.
 */

export interface FieldChange<T = string> { now: T; proposed: T }

export interface ProposalDiff {
  title?: FieldChange
  statement?: FieldChange
  priority?: FieldChange<RequirementPriority>
  /** Criteria the proposal has that the requirement does not (the same words, exactly): accepting adds them at the end. */
  criteriaAdded: string[]
  /** Criteria the requirement has that the proposal does not: accepting never removes one, so they stay. */
  criteriaKept: string[]
  /** True when accepting would change nothing (the requirement already says all of it). */
  nothingChanges: boolean
}

export function diffProposal(
  current: { title: string; statement: string; priority: RequirementPriority },
  currentCriteria: string[],
  proposal: Pick<PlannerProposal, 'title' | 'statement' | 'priority' | 'criteria'>,
): ProposalDiff {
  const same = (a: string, b: string) => a.trim() === b.trim()
  const have = new Set(currentCriteria.map((c) => c.trim()))
  const offered = new Set(proposal.criteria.map((c) => c.trim()))
  const diff: ProposalDiff = {
    criteriaAdded: proposal.criteria.filter((c) => !have.has(c.trim())),
    criteriaKept: currentCriteria.filter((c) => !offered.has(c.trim())),
    nothingChanges: false,
  }
  if (!same(current.title, proposal.title)) diff.title = { now: current.title, proposed: proposal.title }
  if (!same(current.statement, proposal.statement)) diff.statement = { now: current.statement, proposed: proposal.statement }
  if (current.priority !== proposal.priority) diff.priority = { now: current.priority, proposed: proposal.priority }
  diff.nothingChanges = !diff.title && !diff.statement && !diff.priority && diff.criteriaAdded.length === 0
  return diff
}

/** Why accepting cannot work yet, or undefined when it can. Rejecting is always open to someone who may decide. */
export function acceptBlockedReason(status: RequirementStatus): string | undefined {
  return status === 'APPROVED'
    ? 'This requirement is approved, so it changes only through a change request. Raise one for this change, or reject the proposal; it stays waiting until you do.'
    : undefined
}

export function stateNote(state: PlannerProposalState): { glyph: string; text: string } {
  switch (state) {
    case 'PENDING': return { glyph: '◆', text: 'Waiting for a decision' }
    case 'ACCEPTED': return { glyph: '✓', text: 'Accepted' }
    case 'REJECTED': return { glyph: '✕', text: 'Rejected' }
    case 'SUPERSEDED': return { glyph: '–', text: 'Replaced by a newer proposal' }
  }
}

export function splitProposals(all: PlannerProposal[] | undefined): { pending: PlannerProposal[]; decided: PlannerProposal[] } {
  const list = all ?? []
  return { pending: list.filter((p) => p.state === 'PENDING'), decided: list.filter((p) => p.state === 'ACCEPTED' || p.state === 'REJECTED') }
}
