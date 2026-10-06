import type { CurrentRelease, ReleaseMove, ReleaseState } from '@/shared/api/client'

/**
 * VYB-0929: the logic of the Home screen's "blocking the release" panel, kept out of the component so it can be
 * tested without a browser. The panel reads the release being prepared (OPEN or FROZEN, earliest target date first)
 * from the server, and says in words what stands in the way of its next move.
 *
 * A state is a label and a glyph as well as a colour, never amber (amber means AI). Absence is said in words ("no
 * target date", "nothing blocking it"), never blank or zero.
 */

export const STATE_LABEL: Record<ReleaseState, string> = {
  PLANNED: 'Planned', OPEN: 'Open', FROZEN: 'Frozen', RELEASED: 'Released',
}

export const STATE_GLYPH: Record<ReleaseState, string> = {
  PLANNED: '○', OPEN: '◐', FROZEN: '◆', RELEASED: '●',
}

/** Badge classes (tokens.css). */
export const STATE_CLASS: Record<ReleaseState, string> = {
  PLANNED: 'tr-none', OPEN: 'tr-blocked', FROZEN: 'tr-blocked', RELEASED: 'tr-pass',
}

/** What the person would call the move: Open, Freeze, Release, Reopen. */
export function moveLabel(move: ReleaseMove): string {
  switch (move.to) {
    case 'OPEN': return move.needsReason ? 'Reopen' : 'Open'
    case 'FROZEN': return 'Freeze'
    case 'RELEASED': return 'Release'
    default: return 'Plan'
  }
}

/** The moves that are guarded by readiness gates: the forward ones. A reopen has no gates and is not "what blocks the release". */
export function forwardMoves(moves: ReleaseMove[]): ReleaseMove[] {
  return moves.filter((m) => m.to === 'FROZEN' || m.to === 'RELEASED')
}

export function failingGates(move: ReleaseMove) {
  return move.gates.filter((g) => !g.passed)
}

/** "Ready to freeze" or "Not ready to freeze: 2 checks failing". */
export function readinessText(move: ReleaseMove): string {
  const verb = moveLabel(move).toLowerCase()
  if (move.ready) return `Ready to ${verb}`
  const n = failingGates(move).length
  return `Not ready to ${verb}: ${n} check${n === 1 ? '' : 's'} failing`
}

const REASON_LABEL: Record<string, string> = {
  unverified: 'Not verified', conflicting: 'In conflict', unowned: 'No owner',
}

/** The blocked-item reason in words; an unknown reason is shown as the server sent it, never dropped. */
export function blockedReason(reason: string): string {
  return REASON_LABEL[reason] ?? reason
}

/** The release's own planned date: set by a person here, never inferred, and never borrowed. */
export function targetDateText(iso: string | undefined): string {
  if (!iso) return 'no target date'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return 'no target date'
  return `target ${d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}`
}

/** Whether there is anything to show beyond "all clear": a failing gate on a forward move, or a blocked requirement. */
export function hasBlockers(r: CurrentRelease): boolean {
  return r.blocked.length > 0 || forwardMoves(r.moves).some((m) => !m.ready)
}
