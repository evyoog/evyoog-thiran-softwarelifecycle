import type { DefectMatrix, DefectMatrixColumn, DefectMatrixRow, DefectSeverity, ReleaseMatrix, ReleaseMatrixRow } from '@/shared/api/client'

/**
 * VYB-0943 (F19, D36): what the requirement-by-release and requirement-by-defect matrices show. Pure functions over what the
 * server sends. A requirement in no release is a row with no mark, and a defect with no requirement is listed apart; nothing is
 * dropped and absence is said in words, never left blank.
 */

/** An approved requirement that is in no release and has not been replaced: the gap this matrix exists to show. */
export function isReleaseGap(r: ReleaseMatrixRow): boolean {
  return r.status === 'APPROVED' && !r.releaseId && !r.supersededById
}

export function visibleReleaseRows(m: ReleaseMatrix, gapsOnly: boolean): ReleaseMatrixRow[] {
  return gapsOnly ? m.rows.filter(isReleaseGap) : m.rows
}

export interface ReleaseTotals { inARelease: number; inNone: number; gaps: number }

export function releaseTotals(m: ReleaseMatrix): ReleaseTotals {
  const inARelease = m.rows.filter((r) => r.releaseId).length
  return { inARelease, inNone: m.rows.length - inARelease, gaps: m.rows.filter(isReleaseGap).length }
}

export function releaseSummary(m: ReleaseMatrix): string {
  const t = releaseTotals(m)
  if (m.rows.length === 0) return 'This application has no requirements.'
  return `${t.inARelease} of ${m.rows.length} requirements are in a release; ${t.inNone} are in none, ${t.gaps} of those approved and waiting for one.`
}

/** The defects of one requirement. */
export function defectsOf(m: DefectMatrix, requirementId: string): DefectMatrixColumn[] {
  return m.defects.filter((d) => d.requirementId === requirementId)
}

/** Requirements with at least one defect in view, or all of them when asked. */
export function visibleDefectRows(m: DefectMatrix, showClean: boolean): DefectMatrixRow[] {
  return showClean ? m.rows : m.rows.filter((r) => defectsOf(m, r.requirementId).length > 0)
}

/** The columns in reading order: grouped by their requirement's row, so each requirement's defects sit together. */
export function orderedDefects(m: DefectMatrix, rows: DefectMatrixRow[]): DefectMatrixColumn[] {
  return rows.flatMap((r) => defectsOf(m, r.requirementId))
}

export const SEVERITY_GLYPH: Record<DefectSeverity, string> = { CRITICAL: '◆◆◆', HIGH: '◆◆', MEDIUM: '◆', LOW: '◇' }

/** Severity as a word and a glyph, never colour alone and never amber. */
export function severityText(s: DefectSeverity): string {
  return `${SEVERITY_GLYPH[s]} ${s.toLowerCase()}`
}

export function defectSummary(m: DefectMatrix, includeClosed: boolean): string {
  const open = includeClosed ? 'defects' : 'open defects'
  const withDefects = new Set(m.defects.map((d) => d.requirementId)).size
  if (m.defects.length === 0) return `No ${open} are linked to a requirement of this application.`
  return `${m.defects.length} ${open} across ${withDefects} of ${m.rows.length} requirements. A defect has one requirement, so each column has one mark.`
}
