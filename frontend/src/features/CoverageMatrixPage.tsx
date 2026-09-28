import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type CoverageCellState } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { useRovingGrid } from '@/shared/ui/useRovingGrid'

const STATE_LABEL: Record<CoverageCellState, string> = {
  VERIFIED: 'Verified', LINKED_NOT_RUN: 'Linked, not run', SUSPECT: 'Suspect',
}
const STATE_COLOR: Record<CoverageCellState, string> = {
  VERIFIED: 'var(--ok)', LINKED_NOT_RUN: 'var(--high)', SUSPECT: 'var(--crit)',
}

/** VYB-0147/0213: requirement×test coverage, four states, a gaps-only filter, per-test totals. */
export function CoverageMatrixPage() {
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [gapsOnly, setGapsOnly] = useState(false)

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({
    queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId,
  })
  const { data: matrix, isLoading } = useQuery({
    queryKey: ['coverage-matrix', applicationId], queryFn: () => api.coverageMatrix(applicationId), enabled: !!applicationId,
  })

  const rows = new Map<string, { key: string; cells: typeof matrix extends undefined ? never : NonNullable<typeof matrix>['cells'] }>()
  matrix?.cells.forEach((c) => {
    const entry = rows.get(c.requirementId) ?? { key: c.requirementKey, cells: [] }
    entry.cells.push(c)
    rows.set(c.requirementId, entry)
  })
  const visibleRows = [...rows.entries()].filter(([, r]) => !gapsOnly || r.cells.some((c) => c.state !== 'VERIFIED'))
  const flatCells = visibleRows.flatMap(([reqId, r]) => r.cells.map((c) => ({ reqId, key: r.key, c })))
  // VYB-0767: matrix rows and per-test-total rows are two separate grids on this
  // page — each gets its own roving tabindex, 3-4 columns wide, matching what's
  // actually rendered rather than pretending they're one continuous grid.
  const matrixGrid = useRovingGrid(flatCells.length, 3)
  const totalsGrid = useRovingGrid(matrix?.testTotals.length ?? 0, 4)

  return (
    <Page title="Coverage matrix" desc="Requirement × test, four states — a requirement with no test linked at all doesn't appear here; that gap is what Analytics' 'noverify' rule already reports.">
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' }}>
        <select className="select" value={productId} onChange={(e) => { setProductId(e.target.value); setApplicationId('') }}>
          <option value="">Product…</option>
          {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <select className="select" value={applicationId} onChange={(e) => setApplicationId(e.target.value)} disabled={!productId}>
          <option value="">Application…</option>
          {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
        <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5 }}>
          <input type="checkbox" checked={gapsOnly} onChange={(e) => setGapsOnly(e.target.checked)} />
          Gaps only (hide fully verified requirements)
        </label>
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {applicationId && matrix && matrix.cells.length === 0 && (
        <Empty title="No requirement/test links yet" desc="Nothing in this application has a VERIFIES trace link to a test case." />
      )}

      {matrix && matrix.cells.length > 0 && (
        <>
          <div className="tbl-wrap" style={{ marginBottom: 16 }}>
            <table className="tbl" role="grid" aria-rowcount={flatCells.length} aria-colcount={3} onKeyDown={matrixGrid.onKeyDown}>
              <thead><tr role="row"><th>Requirement</th><th>Test</th><th>State</th></tr></thead>
              <tbody>
                {flatCells.map(({ reqId, key, c }, rowIdx) => (
                  <tr key={reqId + c.testCaseId} role="row">
                    <td className="mono key" {...matrixGrid.cellProps(rowIdx, 0)}>{key}</td>
                    <td className="mono muted" {...matrixGrid.cellProps(rowIdx, 1)}>{c.testCaseKey}</td>
                    <td {...matrixGrid.cellProps(rowIdx, 2, { color: STATE_COLOR[c.state], fontWeight: 600 })}>{STATE_LABEL[c.state]}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <h4 className="section-h">Per-test totals</h4>
          <div className="tbl-wrap">
            <table className="tbl" role="grid" aria-rowcount={matrix.testTotals.length} aria-colcount={4} onKeyDown={totalsGrid.onKeyDown}>
              <thead><tr role="row"><th>Test</th><th>Verified</th><th>Linked, not run</th><th>Suspect</th></tr></thead>
              <tbody>
                {matrix.testTotals.map((t, rowIdx) => (
                  <tr key={t.testCaseId} role="row">
                    <td className="mono" {...totalsGrid.cellProps(rowIdx, 0)}>{t.testCaseKey}</td>
                    <td className="mono" {...totalsGrid.cellProps(rowIdx, 1, { color: 'var(--ok)' })}>{t.verified}</td>
                    <td className="mono" {...totalsGrid.cellProps(rowIdx, 2, { color: 'var(--high)' })}>{t.linkedNotRun}</td>
                    <td className="mono" {...totalsGrid.cellProps(rowIdx, 3, { color: 'var(--crit)' })}>{t.suspect}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Page>
  )
}
