import type { RequirementStatus } from '@/shared/api/client'

/**
 * The requirement lifecycle as the screens use it (D15 to D17): which statuses a requirement may move to, what each move is called,
 * and when a move needs a reason. Shared by the requirement page and the board so the two cannot disagree. The server decides every
 * move (who, separation of duties, blocking questions); this only says what is offered and what to ask for.
 */

/**
 * What each move is called, keyed by where it comes from as well as where it goes.
 *
 * <p>Keying on the target alone would get "reopen" wrong: NEEDS_REVISION is reached
 * both by a decision maker sending REVIEWED back for changes, and by reopening a
 * REJECTED requirement — two different actions that both land on the same status.
 */
export const MOVE_LABEL: Record<string, { button: string; sentence: string }> = {
  'DRAFT>IN_REVIEW':          { button: 'Submit for review',   sentence: 'submit this for review' },
  // VYB-0813 (D17): the manual review gate. The same decision maker who used to click
  // "Approve" straight from IN_REVIEW now clicks this first, then a separate decision
  // below — two actions, still one role, per the human-decides rule (no AI review yet).
  'IN_REVIEW>REVIEWED':       { button: 'Move to Reviewed',     sentence: 'mark this reviewed' },
  'IN_REVIEW>DRAFT':          { button: 'Withdraw to draft',    sentence: 'withdraw this back to draft' },
  'REVIEWED>APPROVED':        { button: 'Approve',              sentence: 'approve this' },
  'REVIEWED>REJECTED':        { button: 'Reject',               sentence: 'reject this' },
  'REVIEWED>NEEDS_REVISION':  { button: 'Send back for revision', sentence: 'send this back for revision' },
  'NEEDS_REVISION>IN_REVIEW': { button: 'Resubmit',             sentence: 'resubmit this for review' },
  // VYB-0813: a rejection reopens into NEEDS_REVISION, not DRAFT — the rejection
  // already carries the reason the author needs to act on.
  'REJECTED>NEEDS_REVISION':  { button: 'Reopen',               sentence: 'reopen this into needs revision' },
}

export function moveLabel(from: string, target: string) {
  return MOVE_LABEL[`${from}>${target}`] ?? { button: target, sentence: `move this to ${target}` }
}

/**
 * VYB-0813 (D17): a full replacement of the prior five-state machine, not an
 * extension of it. Rejecting directly from IN_REVIEW is gone; NEEDS_REVISION is new;
 * REJECTED reopens into NEEDS_REVISION rather than DRAFT; APPROVED is fully terminal —
 * no move leaves it (editing an approved requirement is refused, and forking a new
 * version on such an edit is a deferred follow-up, not built yet).
 */
export const NEXT_STATES: Record<RequirementStatus, RequirementStatus[]> = {
  DRAFT: ['IN_REVIEW'],
  IN_REVIEW: ['DRAFT', 'REVIEWED'],
  REVIEWED: ['APPROVED', 'REJECTED', 'NEEDS_REVISION'],
  NEEDS_REVISION: ['IN_REVIEW'],
  APPROVED: [],
  REJECTED: ['NEEDS_REVISION'],
}


/**
 * VYB-0813 / VYB-0802: a reason is required to reject, to send a reviewed requirement back for revision, and to submit one that has
 * no acceptance criteria (an explicit override). Reopening a rejected one needs none: the rejection already carries its reason.
 */
export function reasonRequired(from: RequirementStatus, target: RequirementStatus, criteriaCount: number): boolean {
  return target === 'REJECTED'
    || (target === 'NEEDS_REVISION' && from === 'REVIEWED')
    || (target === 'IN_REVIEW' && criteriaCount === 0)
}

/**
 * IN_REVIEW to REVIEWED is not a plain move: a person reads the requirement on the Design screen and records it there (VYB-0811,
 * VYB-0946). The requirement page replaces that button with Verify, and the board sends a drop there to the same place.
 */
export function isVerifyMove(from: RequirementStatus, target: RequirementStatus): boolean {
  return from === 'IN_REVIEW' && target === 'REVIEWED'
}

/** The moves offered as plain buttons or drops: everything legal except the verify move. */
export function plainTargets(from: RequirementStatus): RequirementStatus[] {
  return NEXT_STATES[from].filter((t) => !isVerifyMove(from, t))
}
