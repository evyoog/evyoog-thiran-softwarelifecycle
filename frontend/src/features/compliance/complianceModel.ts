import type { ClauseCoverage, ClauseMappedRequirement, ClauseMappedTest, ClauseRegisterRow } from '@/shared/api/client'

/** VYB-0947 (D38): how the register reads. A glyph and a word, never colour alone, and never amber (that is AI's). */
export const COVERAGE_LABEL: Record<ClauseCoverage, string> = {
  UNMAPPED: 'Unmapped',
  MAPPED: 'Mapped, not evidenced',
  EVIDENCED: 'Evidenced',
}
export const COVERAGE_GLYPH: Record<ClauseCoverage, string> = { UNMAPPED: '○', MAPPED: '◐', EVIDENCED: '●' }
export const COVERAGE_HELP: Record<ClauseCoverage, string> = {
  UNMAPPED: 'No requirement that still counts is mapped to this clause.',
  MAPPED: 'Requirements are mapped, but not every one is Approved with a passing test at its current revision.',
  EVIDENCED: 'Every requirement mapped to this clause is Approved with a passing test at its current revision.',
}

export interface RegisterFilter { standard: string; coverage: ClauseCoverage | ''; q: string }
export const NO_FILTER: RegisterFilter = { standard: '', coverage: '', q: '' }

export function filterRows(rows: ClauseRegisterRow[], f: RegisterFilter): ClauseRegisterRow[] {
  const q = f.q.trim().toLowerCase()
  return rows.filter((r) =>
    (!f.standard || r.standard === f.standard)
    && (!f.coverage || r.coverage === f.coverage)
    && (!q || `${r.standard} ${r.section ?? ''} ${r.text}`.toLowerCase().includes(q)))
}

export function standardsOf(rows: ClauseRegisterRow[]): string[] {
  return Array.from(new Set(rows.map((r) => r.standard))).sort((a, b) => a.localeCompare(b))
}

export interface RegisterSummary { total: number; unmapped: number; mapped: number; evidenced: number }

export function summarise(rows: ClauseRegisterRow[]): RegisterSummary {
  return {
    total: rows.length,
    unmapped: rows.filter((r) => r.coverage === 'UNMAPPED').length,
    mapped: rows.filter((r) => r.coverage === 'MAPPED').length,
    evidenced: rows.filter((r) => r.coverage === 'EVIDENCED').length,
  }
}

export function summaryLine(s: RegisterSummary): string {
  if (s.total === 0) return 'No clauses in the register yet.'
  return `${s.total} clause${s.total === 1 ? '' : 's'}: ${s.evidenced} evidenced, ${s.mapped} mapped but not evidenced, ${s.unmapped} unmapped`
}

/** What a mapped requirement contributes, in words. */
export function evidenceLabel(r: Pick<ClauseMappedRequirement, 'counts' | 'status' | 'evidenced' | 'staleEvidence' | 'replacedByKey'>): string {
  if (!r.counts) return r.replacedByKey ? `Replaced by ${r.replacedByKey}, so it does not count` : 'Rejected, so it does not count'
  if (r.evidenced) return 'Approved, with a passing test at its current revision'
  if (r.status !== 'APPROVED') return 'Not approved yet'
  return r.staleEvidence ? 'Passed only at an earlier revision' : 'No passing test yet'
}

export function testResultLabel(result: ClauseMappedTest['result']): { glyph: string; word: string } {
  if (result === 'PASS') return { glyph: '✓', word: 'Passed' }
  if (result === 'FAIL') return { glyph: '✗', word: 'Failed' }
  return { glyph: '–', word: 'Not run at this revision' }
}

/** The clause as one line: "ISO 27001 A.5.1". */
export function clauseName(c: { standard: string; section?: string | null }): string {
  return c.section ? `${c.standard} ${c.section}` : c.standard
}

export function excerpt(text: string, max = 140): string {
  const t = text.replace(/\s+/g, ' ').trim()
  return t.length <= max ? t : `${t.slice(0, max - 1).trimEnd()}…`
}
