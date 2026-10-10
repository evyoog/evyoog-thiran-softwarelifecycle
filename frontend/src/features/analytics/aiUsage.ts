/**
 * VYB-0939 (F29, F30): how the AI usage screen and the budget card say what they know. Tokens and calls only: nothing here is
 * an amount of money, and nothing is per person (CLAUDE.md rule 7, D32). The server decides every figure; this only words them.
 */

export type BudgetState = 'NONE' | 'OK' | 'NEAR' | 'OVER'

/** At or above this share of a limit the screen says "near". */
export const NEAR_SHARE = 0.8

export const BUDGET_LABEL: Record<BudgetState, string> = { NONE: 'No limit', OK: 'Within limit', NEAR: 'Near limit', OVER: 'Used up' }
export const BUDGET_GLYPH: Record<BudgetState, string> = { NONE: '○', OK: '●', NEAR: '▲', OVER: '■' }
/** Badge classes (tokens.css). Never the amber token: amber means AI (CLAUDE.md rule 5). */
export const BUDGET_CLASS: Record<BudgetState, string> = { NONE: 'ai-bud-none', OK: 'ai-bud-ok', NEAR: 'ai-bud-near', OVER: 'ai-bud-over' }

export function budgetState(used: number, limit: number | null | undefined): BudgetState {
  if (limit == null) return 'NONE'
  if (used >= limit) return 'OVER'
  if (used >= limit * NEAR_SHARE) return 'NEAR'
  return 'OK'
}

/** 0 to 100, for the bar. No limit has no bar. */
export function percentOf(used: number, limit: number | null | undefined): number | null {
  if (limit == null || limit <= 0) return null
  return Math.min(100, Math.round((used / limit) * 100))
}

export function tokens(n: number): string {
  return `${n.toLocaleString('en-US')} tokens`
}

export function usedOf(used: number, limit: number | null | undefined): string {
  return limit == null ? `${used.toLocaleString('en-US')} tokens, no limit set` : `${used.toLocaleString('en-US')} of ${limit.toLocaleString('en-US')} tokens`
}

export interface BudgetInput { ok: true; value: number | null }
export interface BudgetInputError { ok: false; message: string }

/** Empty means no limit; otherwise a whole number of tokens above zero. */
export function parseBudget(raw: string): BudgetInput | BudgetInputError {
  const text = raw.replace(/[,\s_]/g, '')
  if (text === '') return { ok: true, value: null }
  if (!/^\d+$/.test(text)) return { ok: false, message: 'Enter a whole number of tokens, or leave it empty for no limit.' }
  const n = Number(text)
  if (!Number.isSafeInteger(n) || n <= 0) return { ok: false, message: 'A limit must be more than zero tokens, or empty for no limit.' }
  return { ok: true, value: n }
}

/** One line under the budget card: what applies now, in words. */
export function budgetSummary(daily: number | null | undefined, monthly: number | null | undefined): string {
  if (daily == null && monthly == null) return 'No limit is set. The AI is never refused for its token use.'
  const parts = [daily != null ? `${daily.toLocaleString('en-US')} tokens a day` : null, monthly != null ? `${monthly.toLocaleString('en-US')} tokens a month` : null]
    .filter((p): p is string => p !== null)
  return `Limit: ${parts.join(' and ')} (UTC)${daily != null && monthly != null ? '; whichever is reached first applies' : ''}. Once reached, AI calls are refused with the reason until it resets or is raised.`
}

export interface PurposeRow {
  purpose: string
  promptVersion: string
  model: string
  calls: number
  failed: number
  refused: number
  totalTokens: number
}

/** The share of the month's tokens each row used, largest first as the server sends them. Whole percent; a row too small to show says "<1%". */
export function shareText(row: Pick<PurposeRow, 'totalTokens'>, all: Pick<PurposeRow, 'totalTokens'>[]): string {
  const total = all.reduce((sum, r) => sum + r.totalTokens, 0)
  if (total <= 0 || row.totalTokens <= 0) return '—'
  const pct = (row.totalTokens / total) * 100
  return pct < 1 ? '<1%' : `${Math.round(pct)}%`
}

/** The tallest day's tokens, to scale the day bars. At least 1 so an all-zero chart does not divide by zero. */
export function tallest(days: { tokens: number }[]): number {
  return Math.max(1, ...days.map((d) => d.tokens))
}

export function dayLabel(iso: string): string {
  const [, m, d] = iso.split('-')
  return `${d}/${m}`
}
