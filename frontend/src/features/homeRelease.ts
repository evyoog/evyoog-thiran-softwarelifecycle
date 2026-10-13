import type { CurrentRelease, ReleaseMove, ReleaseState } from '@/shared/api/client'
import { createFormatters, tEn, type Formatters, type Translator } from '@/shared/i18n/i18n'

/**
 * VYB-0929: the logic of the Home screen's "blocking the release" panel, kept out of the component so it can be
 * tested without a browser. The panel reads the release being prepared (OPEN or FROZEN, earliest target date first)
 * from the server, and says in words what stands in the way of its next move.
 *
 * A state is a label and a glyph as well as a colour, never amber (amber means AI). Absence is said in words ("no
 * target date", "nothing blocking it"), never blank or zero.
 *
 * VYB-0958: every sentence here comes from the message catalogue. The functions take the translator (English when none is given) and
 * never build a sentence from pieces, because other languages order the pieces differently.
 */

/** A release state as a person reads it. */
export function stateLabel(state: ReleaseState, t: Translator = tEn): string {
  return t(`release.state.${state}`)
}

export const STATE_GLYPH: Record<ReleaseState, string> = {
  PLANNED: '○', OPEN: '◐', FROZEN: '◆', RELEASED: '●',
}

/** Badge classes (tokens.css). */
export const STATE_CLASS: Record<ReleaseState, string> = {
  PLANNED: 'tr-none', OPEN: 'tr-blocked', FROZEN: 'tr-blocked', RELEASED: 'tr-pass',
}

/** What the person would call the move: Open, Freeze, Release, Reopen. */
export function moveLabel(move: ReleaseMove, t: Translator = tEn): string {
  return t(`release.move.${moveKey(move)}`)
}

/** PLANNED, OPEN, REOPEN (an open that needs a reason), FROZEN or RELEASED: the key the move's words hang from. */
function moveKey(move: ReleaseMove): 'PLANNED' | 'OPEN' | 'REOPEN' | 'FROZEN' | 'RELEASED' {
  if (move.to === 'OPEN') return move.needsReason ? 'REOPEN' : 'OPEN'
  if (move.to === 'FROZEN' || move.to === 'RELEASED') return move.to
  return 'PLANNED'
}

/** The moves that are guarded by readiness gates: the forward ones. A reopen has no gates and is not "what blocks the release". */
export function forwardMoves(moves: ReleaseMove[]): ReleaseMove[] {
  return moves.filter((m) => m.to === 'FROZEN' || m.to === 'RELEASED')
}

export function failingGates(move: ReleaseMove) {
  return move.gates.filter((g) => !g.passed)
}

/** "Ready to freeze" or "Not ready to freeze: 2 checks failing". */
export function readinessText(move: ReleaseMove, t: Translator = tEn): string {
  if (move.ready) return t(`release.ready.${moveKey(move)}`)
  return t(`release.notReady.${moveKey(move)}`, { count: failingGates(move).length })
}

const KNOWN_REASONS = ['unverified', 'conflicting', 'unowned'] as const

/** The blocked-item reason in words; an unknown reason is shown as the server sent it, never dropped. */
export function blockedReason(reason: string, t: Translator = tEn): string {
  return (KNOWN_REASONS as readonly string[]).includes(reason) ? t(`release.reason.${reason as (typeof KNOWN_REASONS)[number]}`) : reason
}

/** The release's own planned date: set by a person here, never inferred, and never borrowed. */
export function targetDateText(iso: string | undefined, t: Translator = tEn, fmt: Formatters = createFormatters('en')): string {
  const date = fmt.date(iso)
  return date ? t('release.target', { date }) : t('release.noTarget')
}

/** Whether there is anything to show beyond "all clear": a failing gate on a forward move, or a blocked requirement. */
export function hasBlockers(r: CurrentRelease): boolean {
  return r.blocked.length > 0 || forwardMoves(r.moves).some((m) => !m.ready)
}
