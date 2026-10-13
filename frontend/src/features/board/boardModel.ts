import type { BoardTemplate, Me, RequirementStatus, TeamMember } from '@/shared/api/client'
import { NEXT_STATES, isVerifyMove } from '../requirements/lifecycle'

/**
 * VYB-0953 (D42): the rules of the board that need no server. The board is the requirement lifecycle laid out as columns; a card's
 * column is its status. Which drops are even worth asking the server about, which columns a template shows, and who sees the
 * template controls are decided here; whether a move is allowed to this person is the server's, always.
 */
export const ALL_STATUSES: RequirementStatus[] = ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED', 'REJECTED']

export const COLUMN_LABEL: Record<RequirementStatus, string> = {
  DRAFT: 'Draft', IN_REVIEW: 'In review', REVIEWED: 'Reviewed', NEEDS_REVISION: 'Needs revision', APPROVED: 'Approved', REJECTED: 'Rejected',
}

export type Outcome = 'SAME' | 'VERIFY' | 'MOVE' | 'ILLEGAL'

/** What dropping a card from one column onto another means. */
export function outcome(from: RequirementStatus, to: RequirementStatus): Outcome {
  if (from === to) return 'SAME'
  if (isVerifyMove(from, to)) return 'VERIFY'
  return NEXT_STATES[from].includes(to) ? 'MOVE' : 'ILLEGAL'
}

/** Why a drop is refused before the server is asked, in words (null when it is not). */
export function refusal(from: RequirementStatus, to: RequirementStatus): string | null {
  if (outcome(from, to) !== 'ILLEGAL') return null
  if (from === 'APPROVED') return 'An approved requirement does not move: it changes only through a change request.'
  const next = NEXT_STATES[from].map((s) => COLUMN_LABEL[s].toLowerCase())
  return `A requirement in ${COLUMN_LABEL[from].toLowerCase()} can only move to ${next.length ? next.join(' or ') : 'nowhere'}.`
}

/** Every column a card could be dropped on, from where it is (not counting staying put). */
export function legalTargets(from: RequirementStatus, columns: RequirementStatus[]): RequirementStatus[] {
  return columns.filter((c) => outcome(from, c) === 'MOVE' || outcome(from, c) === 'VERIFY')
}

/** The query for one column's cards. A template's scope is at most one of product, application and capability. */
export function columnParams(t: Pick<BoardTemplate, 'productId' | 'applicationId' | 'capabilityId' | 'type' | 'priority'>, status: RequirementStatus, size = 50, page = 0) {
  return {
    status, page, size, sort: ['key,asc'],
    ...(t.capabilityId ? { capabilityId: t.capabilityId } : {}),
    ...(t.applicationId ? { applicationId: t.applicationId } : {}),
    ...(t.productId ? { productId: t.productId } : {}),
    ...(t.type ? { type: t.type } : {}),
    ...(t.priority ? { priority: t.priority } : {}),
  }
}

/** A template in one line: its scope and filters (the columns are on the board itself). */
export function templateSummary(t: Pick<BoardTemplate, 'productName' | 'applicationName' | 'capabilityName' | 'type' | 'priority'>): string {
  const parts = [
    t.capabilityName ? `Capability ${t.capabilityName}` : t.applicationName ? `Application ${t.applicationName}` : t.productName ? `Product ${t.productName}` : 'All requirements',
    t.type ? t.type.toLowerCase().replace('_', ' ') : null,
    t.priority ? `${t.priority.toLowerCase()} priority` : null,
  ].filter(Boolean)
  return parts.join(' · ')
}

/** The picker's label: the team first, so two teams' templates of the same name are told apart. */
export function templateLabel(t: Pick<BoardTemplate, 'name' | 'teamName' | 'isDefault'>): string {
  return t.isDefault ? `${t.name} (everyone)` : `${t.teamName ?? 'Team'}: ${t.name}`
}

/** Who sees the template controls: an administrator, or a lead of the template's team. The default is an administrator's only. */
export function canEditTemplate(me: Pick<Me, 'id' | 'platformAdministrator'> | undefined, t: Pick<BoardTemplate, 'isDefault'> | undefined, members: Pick<TeamMember, 'userId' | 'role'>[] | undefined): boolean {
  if (!me || !t) return false
  if (me.platformAdministrator) return true
  return !t.isDefault && !!members?.some((m) => m.userId === me.id && m.role === 'LEAD')
}

/** Moves a column one place left (-1) or right (+1) in the order; out of range leaves it as it is. */
export function moveColumn(columns: RequirementStatus[], index: number, dir: -1 | 1): RequirementStatus[] {
  const to = index + dir
  if (index < 0 || index >= columns.length || to < 0 || to >= columns.length) return columns
  const out = columns.slice()
  ;[out[index], out[to]] = [out[to]!, out[index]!]
  return out
}

/** Adds a column at the end, or removes it; the last column cannot be removed (a board needs one). */
export function toggleColumn(columns: RequirementStatus[], status: RequirementStatus): RequirementStatus[] {
  if (columns.includes(status)) return columns.length === 1 ? columns : columns.filter((c) => c !== status)
  return [...columns, status]
}
