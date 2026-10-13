import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/shared/api/client'
import { Empty, Page } from '@/shared/ui/Page'
import { StatusBadge } from '@/shared/ui/Badges'
import { ApplicationScope, MatrixTabs } from './MatrixScope'
import { defectSummary, defectsOf, orderedDefects, severityText, visibleDefectRows } from './matrices'

/**
 * VYB-0943 (F19, D36): requirement by defect for one application. Every defect in view is a column, marked in its requirement's
 * row; a defect has one requirement, so each column has one mark. Open defects unless asked for all. The open defects with no
 * requirement are listed under the matrix, and each can be linked to a requirement from that requirement's page.
 */
export function DefectMatrixPage() {
  const navigate = useNavigate()
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [includeClosed, setIncludeClosed] = useState(false)
  const [showClean, setShowClean] = useState(false)
  const { data: matrix, isLoading } = useQuery({
    queryKey: ['defect-matrix', applicationId, includeClosed], queryFn: () => api.defectMatrix(applicationId, includeClosed), enabled: !!applicationId,
  })
  const rows = matrix ? visibleDefectRows(matrix, showClean) : []
  const columns = matrix ? orderedDefects(matrix, rows) : []

  return (
    <Page title="Defect matrix" desc="Requirement × defect for one application. A defect belongs to one requirement, so each defect column has exactly one mark.">
      <MatrixTabs />
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' }}>
        <ApplicationScope productId={productId} applicationId={applicationId} onProduct={setProductId} onApplication={setApplicationId} />
        <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5 }}>
          <input type="checkbox" checked={includeClosed} onChange={(e) => setIncludeClosed(e.target.checked)} /> Include fixed and closed defects
        </label>
        <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5 }}>
          <input type="checkbox" checked={showClean} onChange={(e) => setShowClean(e.target.checked)} /> Show requirements with no defects
        </label>
      </div>
      {!applicationId && <p className="hint muted">Choose a product and an application.</p>}
      {isLoading && <p className="eyebrow">Loading…</p>}
      {matrix && <p className="hint" role="status">{defectSummary(matrix, includeClosed)}</p>}
      {matrix && rows.length === 0 && <Empty title="No defects to show" desc="No requirement of this application has a defect in view." />}
      {matrix && rows.length > 0 && (
        <div className="tbl-wrap" style={{ marginBottom: 16 }}>
          <table className="tbl" aria-label="Requirements by defect">
            <thead>
              <tr>
                <th scope="col">Requirement</th><th scope="col">Status</th>
                {columns.map((d) => (
                  <th key={d.id} scope="col" style={{ textAlign: 'center' }}>
                    <span className="mono">{d.key}</span><div className="hint muted" style={{ fontWeight: 400 }}>{severityText(d.severity)}{d.state !== 'OPEN' ? ` · ${d.state.toLowerCase()}` : ''}</div>
                  </th>
                ))}
                <th scope="col" style={{ textAlign: 'right' }}>Defects</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.requirementId} style={{ cursor: 'pointer' }} onClick={() => navigate(`/requirements/${r.requirementId}`)}>
                  <td><span className="mono key">{r.key}</span> <span className="muted">{r.title}</span></td>
                  <td><StatusBadge status={r.status} /></td>
                  {columns.map((d) => (
                    <td key={d.id} style={{ textAlign: 'center' }}>{d.requirementId === r.requirementId ? <span aria-label={`${d.key} is on ${r.key}`}>◆ yes</span> : <span className="muted" aria-hidden="true">·</span>}</td>
                  ))}
                  <td className="mono" style={{ textAlign: 'right' }}>{defectsOf(matrix, r.requirementId).length}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {matrix && (
        <>
          <h2 className="section-h">Defects with no requirement{includeClosed ? '' : ' (open)'}</h2>
          {matrix.untraced.length === 0
            ? <p className="hint muted">None: every defect is linked to a requirement.</p>
            : (
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {matrix.untraced.map((d) => (
                  <li key={d.id} style={{ fontSize: 13 }}>
                    <button className="btn" style={{ padding: '0 6px' }} onClick={() => navigate(`/defects/${d.id}`)}><span className="mono">{d.key}</span></button>{' '}
                    {d.title} <span className="hint">· {severityText(d.severity)}, {d.state.toLowerCase()}</span>
                  </li>
                ))}
              </ul>
            )}
        </>
      )}
    </Page>
  )
}
