import type { AiProposal, ElaborationStatus } from '@/shared/api/client'

/**
 * VYB-0938: what the Delivery screen says about AI elaboration for the chosen scope. The AI drafts; a person accepts, edits or
 * rejects each draft; a brief carries only what was accepted for the requirement as it is now. The server decides all of that;
 * this only puts it in words.
 */

/** One line: how many requirements in scope have a reviewed elaboration, and what is waiting. Absence is said, never blank. */
export function statusText(status: ElaborationStatus | undefined): string {
  if (!status) return 'Checking what has been reviewed…'
  const { requirementsInScope: total, accepted } = status
  const waiting = status.pending.length
  if (total === 0) return 'Nothing in this scope would be briefed yet (a brief carries approved requirements that have a test case).'
  const have = accepted === 0
    ? 'No requirement in scope has a reviewed elaboration yet.'
    : `${accepted} of ${total} requirement${total === 1 ? '' : 's'} in scope ${accepted === 1 ? 'has' : 'have'} a reviewed elaboration.`
  return waiting === 0 ? have : `${have} ${waiting} ${waiting === 1 ? 'is' : 'are'} waiting for review.`
}

export function detailOf(p: AiProposal): string {
  const d = p.payload?.detail
  return typeof d === 'string' ? d : ''
}

/** The edit to send with an accept: the text, only if the person changed it and left something. */
export function editsFor(p: AiProposal, text: string): Record<string, string> | undefined {
  const trimmed = text.trim()
  if (!trimmed || trimmed === detailOf(p).trim()) return undefined
  return { detail: trimmed }
}

/** Why a draft cannot be accepted as it stands, in words; undefined when it can. */
export function acceptProblem(p: AiProposal, text: string): string | undefined {
  if (p.stale) return `${p.requirementKey ?? 'This requirement'} has changed since this was drafted. Reject it and draft again.`
  if (!text.trim()) return 'There is no text to accept.'
  return undefined
}

/** The button label for what the person is about to do: accepting as drafted, or with their edit. */
export function acceptLabel(p: AiProposal, text: string): string {
  return editsFor(p, text) ? 'Accept with my edit' : 'Accept'
}

export function draftedText(count: number, requirementsInScope: number): string {
  if (count === 0) return `The AI returned nothing usable for the ${requirementsInScope} requirement${requirementsInScope === 1 ? '' : 's'} in scope.`
  return `Drafted ${count} elaboration${count === 1 ? '' : 's'} for ${requirementsInScope} requirement${requirementsInScope === 1 ? '' : 's'}. None is in a brief until you accept it.`
}
