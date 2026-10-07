import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Search } from 'lucide-react'
import { api } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { candidateNote, candidateState, toggle, togglePage, type Selection } from '../releaseScope'

/**
 * VYB-0930: search the requirements by key or title and tick the ones to commit. A requirement already in this release,
 * or committed to another (a requirement belongs to one release at a time), is shown as such with the release named and
 * cannot be ticked, so the server never has to refuse it. The selection survives a change of search, so a person can
 * gather requirements across several searches before committing them with one reason.
 */
export function RequirementPicker({
  releaseId, selection, onChange,
}: { releaseId: string; selection: Selection; onChange: (next: Selection) => void }) {
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)

  // wait for a pause in typing before asking the server
  useEffect(() => {
    const t = setTimeout(() => { setQ(text.trim()); setPage(0) }, 250)
    return () => clearTimeout(t)
  }, [text])

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['release-candidates', releaseId, q, page],
    queryFn: () => api.releaseCandidates(releaseId, { q: q || undefined, page, size: 10 }),
  })
  const rows = data?.content ?? []
  const pickableOnPage = rows.filter((c) => candidateState(c, releaseId) === 'pickable')
  const allOnPageSelected = pickableOnPage.length > 0 && pickableOnPage.every((c) => selection.has(c.requirementId))

  return (
    <div>
      <div className="field" style={{ maxWidth: 420 }}>
        <label className="label" htmlFor="rp-search">Find requirements</label>
        <div style={{ position: 'relative' }}>
          <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--tx-3)' }} />
          <input id="rp-search" className="input" style={{ paddingLeft: 30, width: '100%' }} placeholder="Key or title"
            value={text} onChange={(e) => setText(e.target.value)} />
        </div>
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && (
        <div className="empty"><h4>Could not load requirements</h4><button className="btn" onClick={() => void refetch()}>Try again</button></div>
      )}
      {data && rows.length === 0 && (
        <Empty title={q ? 'No requirement matches' : 'No requirements yet'} desc={q ? 'Try a different key or title.' : 'Create a requirement first.'} />
      )}

      {rows.length > 0 && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
            <label className="hint muted" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <input type="checkbox" checked={allOnPageSelected} disabled={pickableOnPage.length === 0}
                onChange={() => onChange(togglePage(selection, rows, releaseId))} />
              Select all that can be picked on this page
            </label>
            <span className="hint muted" role="status" aria-live="polite">{selection.size} selected</span>
          </div>
          <div className="tbl-wrap">
            <table className="tbl" aria-label="Requirements to commit">
              <tbody>
                {rows.map((c) => {
                  const state = candidateState(c, releaseId)
                  const note = candidateNote(c, releaseId)
                  return (
                    <tr key={c.requirementId} style={{ cursor: 'default', opacity: state === 'pickable' ? 1 : 0.7 }}>
                      <td style={{ width: 28 }}>
                        <input type="checkbox" aria-label={`Select ${c.key}`} checked={selection.has(c.requirementId)}
                          disabled={state !== 'pickable'} onChange={() => onChange(toggle(selection, c, releaseId))} />
                      </td>
                      <td className="mono muted" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>{c.key}</td>
                      <td>{c.title}</td>
                      <td className="muted" style={{ fontSize: 11 }}>{c.status.replace('_', ' ').toLowerCase()}</td>
                      <td className="muted" style={{ fontSize: 11 }}>{note}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      {data && data.totalPages > 1 && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8 }}>
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="hint muted">Page {page + 1} of {data.totalPages}</span>
          <button className="btn" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}

      {selection.size > 0 && (
        <p className="hint muted" style={{ marginTop: 8 }}>
          Selected: {[...selection.values()].join(', ')}{' '}
          <button className="btn" style={{ fontSize: 11 }} onClick={() => onChange(new Map())}>Clear</button>
        </p>
      )}
    </div>
  )
}
