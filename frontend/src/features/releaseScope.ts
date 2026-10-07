import type { ReleaseCandidate, ReleaseState } from '@/shared/api/client'

/**
 * VYB-0930: the logic of the Releases screen's scope picker, kept out of the component so it can be tested without a
 * browser. A requirement can be committed to one release at a time, and a frozen or released release's scope is
 * locked (VYB-0928); the picker shows both as facts in words instead of letting the server refuse them.
 */

/** Frozen and released releases cannot change what is committed; reopening a frozen one unlocks it. */
export function scopeLocked(state: ReleaseState | undefined): boolean {
  return state === 'FROZEN' || state === 'RELEASED'
}

export function lockMessage(state: ReleaseState | undefined): string | undefined {
  if (state === 'FROZEN') return 'This release is frozen, so what is committed to it cannot change. Reopen it to change the scope.'
  if (state === 'RELEASED') return 'This release has been released, so what is committed to it can no longer change.'
  return undefined
}

export type CandidateState = 'pickable' | 'here' | 'elsewhere'

/** Where a candidate stands: free to pick, already in this release, or committed to another one. */
export function candidateState(c: ReleaseCandidate, releaseId: string): CandidateState {
  if (!c.committedToId) return 'pickable'
  return c.committedToId === releaseId ? 'here' : 'elsewhere'
}

/** The words shown beside a candidate that cannot be picked; empty for one that can. */
export function candidateNote(c: ReleaseCandidate, releaseId: string): string {
  switch (candidateState(c, releaseId)) {
    case 'here': return 'Already committed to this release'
    case 'elsewhere': return `Committed to ${c.committedToName ?? 'another release'}`
    default: return ''
  }
}

/** A selection is a map from requirement id to its key, so the keys survive a change of search. */
export type Selection = ReadonlyMap<string, string>

export function toggle(selection: Selection, c: ReleaseCandidate, releaseId: string): Selection {
  if (candidateState(c, releaseId) !== 'pickable') return selection
  const next = new Map(selection)
  if (next.has(c.requirementId)) next.delete(c.requirementId)
  else next.set(c.requirementId, c.key)
  return next
}

/** Selects every pickable candidate of the page shown, or clears them when all of them already are selected. */
export function togglePage(selection: Selection, page: ReleaseCandidate[], releaseId: string): Selection {
  const pickable = page.filter((c) => candidateState(c, releaseId) === 'pickable')
  if (pickable.length === 0) return selection
  const next = new Map(selection)
  if (pickable.every((c) => next.has(c.requirementId))) pickable.forEach((c) => next.delete(c.requirementId))
  else pickable.forEach((c) => next.set(c.requirementId, c.key))
  return next
}

export const MAX_PER_COMMIT = 200

/** Why the Commit button is disabled, or undefined when it can be pressed. */
export function commitBlockedReason(selected: number, reason: string, locked: boolean): string | undefined {
  if (locked) return 'The scope is locked.'
  if (selected === 0) return 'Pick at least one requirement.'
  if (selected > MAX_PER_COMMIT) return `Commit at most ${MAX_PER_COMMIT} at a time.`
  if (reason.trim() === '') return 'Give a reason; it is recorded with your name.'
  return undefined
}

export function commitLabel(selected: number): string {
  return selected === 0 ? 'Commit' : `Commit ${selected} requirement${selected === 1 ? '' : 's'}`
}

/** What happened, in words: how many were committed and how many were already there. */
export function resultText(committed: number, already: number): string {
  const parts = [`Committed ${committed} requirement${committed === 1 ? '' : 's'}`]
  if (already > 0) parts.push(`${already} already in this release`)
  return parts.join('; ') + '.'
}
