import type { Defect, DefectState, DefectTransition, Me } from '@/shared/api/client'
import { canExecute } from './testRuns'

/**
 * VYB-0931: the logic of the Defects tab, kept out of the components so it can be tested without a browser.
 *
 * Who may do what mirrors the server, which still decides: marking a defect fixed is the assigned developer's, a
 * Tester's or an administrator's; closing, reopening, editing, assigning and linking are a Tester's (or an
 * administrator's); anyone signed in can comment. A state is a label and a glyph as well as a colour, never amber
 * (amber means AI). Absence is said in words ("nobody assigned", "not linked"), never blank.
 */

export const STATE_LABEL: Record<DefectState, string> = { OPEN: 'Open', FIXED: 'Fixed', CLOSED: 'Closed' }
export const STATE_GLYPH: Record<DefectState, string> = { OPEN: '●', FIXED: '◐', CLOSED: '✓' }
/** Badge classes (tokens.css). */
export const STATE_CLASS: Record<DefectState, string> = { OPEN: 'df-open', FIXED: 'df-fixed', CLOSED: 'df-closed' }

export type StateFilter = DefectState | 'ALL'
export const FILTERS: StateFilter[] = ['OPEN', 'FIXED', 'CLOSED', 'ALL']
export const FILTER_LABEL: Record<StateFilter, string> = { OPEN: 'Open', FIXED: 'Fixed', CLOSED: 'Closed', ALL: 'All' }

export interface DefectActions {
  fix: boolean
  close: boolean
  /** Why closing is not possible yet (a root cause is required), when it is a Tester's to do. */
  closeBlockedReason?: string
  reopen: boolean
  edit: boolean
  assign: boolean
  link: boolean
  comment: boolean
}

/** What the person may be offered on this defect. The server enforces the same rules and says so if it disagrees. */
export function defectActions(d: Defect, me: Me | undefined): DefectActions {
  const tester = canExecute(me) // a Tester, or an administrator, who passes every rule
  const assignedToMe = !!me && !!d.developerId && d.developerId === me.id
  const closed = d.state === 'CLOSED'
  return {
    fix: d.state === 'OPEN' && (assignedToMe || tester),
    close: tester && !closed && !!d.rootCause,
    closeBlockedReason: tester && !closed && !d.rootCause ? 'Classify the root cause first.' : undefined,
    reopen: tester && d.state !== 'OPEN',
    edit: tester && !closed,
    assign: tester,
    link: tester,
    comment: !!me,
  }
}

/** Who a defect is routed to, in words. */
export function assigneeText(d: Defect): string {
  const parts: string[] = []
  if (d.developerName ?? d.developerId) parts.push(`developer ${d.developerName ?? 'assigned'}`)
  if (d.testerName ?? d.testerId) parts.push(`tester ${d.testerName ?? 'assigned'}`)
  return parts.length === 0 ? 'nobody assigned' : parts.join(', ')
}

/** "Opened" is the start of the story; every other move is "Fixed", "Closed" or "Reopened". */
export function transitionVerb(t: Pick<DefectTransition, 'from' | 'to'>): string {
  if (t.to === 'OPEN') return 'Reopened'
  return t.to === 'FIXED' ? 'Marked fixed' : 'Closed'
}

export function transitionText(t: DefectTransition): string {
  const who = t.changedByName ?? 'someone'
  return `${transitionVerb(t)} by ${who}${t.reason ? `: ${t.reason}` : ''}`
}

export const MAX_COMMENT = 4000

/** Why a comment cannot be sent, or undefined. */
export function commentProblem(body: string): string | undefined {
  if (body.trim() === '') return 'Write something first.'
  if (body.length > MAX_COMMENT) return `A comment is at most ${MAX_COMMENT} characters (this is ${body.length}).`
  return undefined
}

/** Why a reopen cannot be sent, or undefined: a reason is needed. */
export function reopenProblem(reason: string): string | undefined {
  return reason.trim() === '' ? 'Say why it is being reopened.' : undefined
}

/** A link in words: the label, or that it is not linked. */
export function linkText(label: string | undefined, kind: string): string {
  return label ? label : `no ${kind} linked`
}

/** Empty-list wording for the chosen filter. */
export function emptyText(filter: StateFilter, searching: boolean): { title: string; desc: string } {
  if (searching) return { title: 'No defect matches', desc: 'Try a different key or title, or another state.' }
  switch (filter) {
    case 'OPEN': return { title: 'No open defects', desc: 'Raise one above, or look at Fixed and Closed.' }
    case 'FIXED': return { title: 'No fixed defects', desc: 'Nothing is waiting for its fix to be verified.' }
    case 'CLOSED': return { title: 'No closed defects', desc: 'Closed defects appear here.' }
    default: return { title: 'No defects', desc: 'Raise one above.' }
  }
}
