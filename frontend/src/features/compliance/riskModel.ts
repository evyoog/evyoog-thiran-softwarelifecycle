import type { Me, RiskBand, RiskRow, RiskStatus } from '@/shared/api/client'

/**
 * VYB-0949 (D40): how the risk register reads. A band is a word and a glyph as well as a colour, and none of the colours is amber
 * (that is for AI). Likelihood and impact are qualitative 1 to 5; nothing here is money or hours.
 */
export const BANDS: RiskBand[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
export const BAND_LABEL: Record<RiskBand, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' }
export const BAND_GLYPH: Record<RiskBand, string> = { LOW: '▫', MEDIUM: '◇', HIGH: '◆', CRITICAL: '▲' }
export const BAND_VAR: Record<RiskBand, string> = { LOW: 'ok', MEDIUM: 'info', HIGH: 'high', CRITICAL: 'crit' }

export const STATUSES: RiskStatus[] = ['OPEN', 'MITIGATING', 'ACCEPTED', 'CLOSED']
export const STATUS_LABEL: Record<RiskStatus, string> = { OPEN: 'Open', MITIGATING: 'Mitigating', ACCEPTED: 'Accepted', CLOSED: 'Closed' }

export const LEVELS = [1, 2, 3, 4, 5]
export const LIKELIHOOD_LABEL: Record<number, string> = { 1: 'Rare', 2: 'Unlikely', 3: 'Possible', 4: 'Likely', 5: 'Almost certain' }
export const IMPACT_LABEL: Record<number, string> = { 1: 'Negligible', 2: 'Minor', 3: 'Moderate', 4: 'Major', 5: 'Severe' }

/** The same bands the server reads (RiskScale): Low 1-4, Medium 5-9, High 10-14, Critical 15-25. */
export function bandOf(rating: number): RiskBand {
  return rating >= 15 ? 'CRITICAL' : rating >= 10 ? 'HIGH' : rating >= 5 ? 'MEDIUM' : 'LOW'
}

export function ratingText(r: Pick<RiskRow, 'rating' | 'band'>): string {
  return `${BAND_LABEL[r.band]} (${r.rating})`
}

/** Statuses that still need attention; Closed is history. Accepted stays in view: it is a decision to live with it, not an end. */
export const isLive = (status: RiskStatus): boolean => status !== 'CLOSED'

export type StatusFilter = 'LIVE' | 'ALL' | RiskStatus
export interface RiskFilter { status: StatusFilter; band: RiskBand | ''; q: string; cell?: { likelihood: number; impact: number } }
export const DEFAULT_FILTER: RiskFilter = { status: 'LIVE', band: '', q: '' }

export function filterRisks(rows: RiskRow[], f: RiskFilter): RiskRow[] {
  const q = f.q.trim().toLowerCase()
  return rows.filter((r) =>
    (f.status === 'ALL' || (f.status === 'LIVE' ? isLive(r.status) : r.status === f.status))
    && (!f.band || r.band === f.band)
    && (!f.cell || (r.likelihood === f.cell.likelihood && r.impact === f.cell.impact))
    && (!q || `${r.key} ${r.title} ${r.ownerName ?? ''}`.toLowerCase().includes(q)))
}

export interface RiskSummary { live: number; closed: number; byBand: Record<RiskBand, number> }

/** Counts over the whole register; the band counts are of risks that are not closed. */
export function summarise(rows: RiskRow[]): RiskSummary {
  const byBand: Record<RiskBand, number> = { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 }
  for (const r of rows) if (isLive(r.status)) byBand[r.band]++
  return { live: rows.filter((r) => isLive(r.status)).length, closed: rows.filter((r) => !isLive(r.status)).length, byBand }
}

export function summaryLine(s: RiskSummary): string {
  if (s.live === 0 && s.closed === 0) return 'No risks in the register yet.'
  const parts = BANDS.slice().reverse().filter((b) => s.byBand[b] > 0).map((b) => `${s.byBand[b]} ${BAND_LABEL[b].toLowerCase()}`)
  return `${s.live} open risk${s.live === 1 ? '' : 's'}${parts.length ? ` (${parts.join(', ')})` : ''}, ${s.closed} closed`
}

export interface HeatCell { likelihood: number; impact: number; rating: number; band: RiskBand; count: number }

/** The 5 by 5 grid, likelihood from 5 (top) to 1, impact from 1 to 5, with how many risks that are not closed sit in each cell. */
export function heatGrid(rows: RiskRow[]): HeatCell[][] {
  const live = rows.filter((r) => isLive(r.status))
  return [5, 4, 3, 2, 1].map((likelihood) => [1, 2, 3, 4, 5].map((impact) => ({
    likelihood, impact, rating: likelihood * impact, band: bandOf(likelihood * impact),
    count: live.filter((r) => r.likelihood === likelihood && r.impact === impact).length,
  })))
}

/** Accepting or closing a risk needs a reason: it is a decision, and the reason is kept. */
export function needsReason(target: RiskStatus): boolean {
  return target === 'ACCEPTED' || target === 'CLOSED'
}

/** What is still needed before a status change can be sent; null when it can. */
export function statusChangeProblem(current: RiskStatus, target: RiskStatus, reason: string): string | null {
  if (target === current) return `This risk is already ${STATUS_LABEL[target].toLowerCase()}.`
  if (needsReason(target) && reason.trim() === '') return `Say why this risk is ${STATUS_LABEL[target].toLowerCase()}.`
  return null
}

/** The roles that may manage risks (the server's RISK rule); an administrator always may. The server decides; this only decides what to show. */
export const RISK_ROLES = ['APPROVER', 'COMPLIANCE_LEAD', 'ARCHITECT']
export function canManageRisks(me: Pick<Me, 'platformAdministrator' | 'activeGrants'> | undefined): boolean {
  return !!me && (me.platformAdministrator || me.activeGrants.some((g) => RISK_ROLES.includes(g.role)))
}
