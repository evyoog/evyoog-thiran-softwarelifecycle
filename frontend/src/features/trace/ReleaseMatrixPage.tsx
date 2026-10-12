import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/shared/api/client'
import { Empty, Page } from '@/shared/ui/Page'
import { StatusBadge } from '@/shared/ui/Badges'
import { ApplicationScope, MatrixTabs } from './MatrixScope'
import { isReleaseGap, releaseSummary, visibleReleaseRows } from './matrices'

/**
 * VYB-0943 (F19, D36): requirement by release for one application. Every requirement is a row and every release a column; a mark
 * says the requirement is committed to that release (a requirement is in one at a time). "Gaps only" shows the approved
 * requirements in no release. A mark is a glyph and the word "in", never colour alone.
 */
export function ReleaseMatrixPage() {
  const navigate = useNavigate()
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [gapsOnly, setGapsOnly] = useState(false)
  const { data: matrix, isLoading } = useQuery({
    queryKey: ['release-matrix', applicationId], queryFn: () => api.releaseMatrix(applicationId), enabled: !!applicationId,
  })
  const rows = matrix ? visibleReleaseRows(matrix, gapsOnly) : []

  return (
    <Page title="Release matrix" desc="Requirement × release for one application. A requirement is committed to one release at a time; the rows with no mark are not in any release yet.">
      <MatrixTabs />
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' }}>
        <ApplicationScope productId={productId} applicationId={applicationId} onProduct={setProductId} onApplication={setApplicationId} />
        <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5 }}>
          <input type="checkbox" checked={gapsOnly} onChange={(e) => setGapsOnly(e.target.checked)} />
          Gaps only (approved requirements in no release)
        </label>
      </div>
      {!applicationId && <p className="hint muted">Choose a product and an application.</p>}
      {isLoading && <p className="eyebrow">Loading…</p>}
      {matrix && <p className="hint" role="status">{releaseSummary(matrix)}</p>}
      {matrix && matrix.rows.length > 0 && rows.length === 0 && <Empty title="No gaps" desc="Every approved requirement of this application is in a release." />}
      {matrix && rows.length > 0 && (
        <div className="tbl-wrap">
          <table className="tbl" aria-label="Requirements by release">
            <thead>
              <tr>
                <th scope="col">Requirement</th><th scope="col">Status</th>
                {matrix.releases.map((r) => (
                  <th key={r.id} scope="col" style={{ textAlign: 'center' }}>
                    {r.name}<div className="hint muted" style={{ fontWeight: 400 }}>{r.state.toLowerCase()} · {r.requirements}</div>
                  </th>
                ))}
                <th scope="col">Release</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.requirementId} style={{ cursor: 'pointer' }} onClick={() => navigate(`/requirements/${r.requirementId}`)}>
                  <td><span className="mono key">{r.key}</span> <span className="muted">{r.title}</span>{r.supersededById && <span className="hint"> · replaced by a newer version</span>}</td>
                  <td><StatusBadge status={r.status} /></td>
                  {matrix.releases.map((c) => (
                    <td key={c.id} style={{ textAlign: 'center' }}>{r.releaseId === c.id ? <span aria-label={`in ${c.name}`}>● in</span> : <span className="muted" aria-hidden="true">·</span>}</td>
                  ))}
                  <td className={isReleaseGap(r) ? '' : 'muted'}>{r.releaseId ? matrix.releases.find((c) => c.id === r.releaseId)?.name ?? 'in a release' : isReleaseGap(r) ? '▲ approved, in no release' : 'in no release'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  )
}
