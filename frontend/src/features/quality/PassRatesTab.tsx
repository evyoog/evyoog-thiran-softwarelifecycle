import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Search } from 'lucide-react'
import { api } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { TONE_CLASS, TONE_GLYPH, basisText, breakdownText, passRateText, passRateTone } from './testRuns'

/**
 * VYB-0927 (F14): the pass rate per requirement. Each test case that verifies a requirement counts once, by its
 * latest result at the requirement's current revision (CI and manual together). A case with no result, or only
 * one from an earlier revision, is "not run" or "stale", never a failure, and a rate with nothing to measure
 * reads "Not run", never 0%. Worst first. Read-only.
 */
export function PassRatesTab() {
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['pass-rates', q, page],
    queryFn: () => api.passRates({ q: q.trim() || undefined, page, size: 25 }),
  })

  return (
    <>
      <p className="hint muted" style={{ marginBottom: 12 }}>
        Each test case counts once, by its latest result at the requirement's current revision, CI and manual runs
        together. A case with no result, or only one from before the requirement last changed, is shown as not run or
        stale and is never counted as a failure. The rate is passed out of the cases that have a result. Worst first.
      </p>

      <div className="field" style={{ maxWidth: 380 }}>
        <label className="label" htmlFor="pr-search">Search</label>
        <div style={{ position: 'relative' }}>
          <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--tx-3)' }} />
          <input id="pr-search" className="input" style={{ paddingLeft: 30, width: '100%' }} placeholder="Requirement key or title"
            value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} />
        </div>
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && (
        <div className="empty">
          <h4>Could not load pass rates</h4>
          <button className="btn" onClick={() => void refetch()}>Try again</button>
        </div>
      )}
      {data && data.content.length === 0 && (
        <Empty title={q.trim() ? 'No requirement matches' : 'No requirement has a test case yet'}
          desc={q.trim() ? 'Try a different key or title.' : 'Add a test case to a requirement, or run a suite, and its pass rate appears here.'} />
      )}

      {data && data.content.length > 0 && (
        <div className="tbl-wrap">
          <table className="tbl tr-tbl">
            <thead><tr><th>Requirement</th><th>Pass rate</th><th>Cases</th><th>Last result</th></tr></thead>
            <tbody>
              {data.content.map((p) => {
                const tone = passRateTone(p)
                return (
                  <tr key={p.requirementId} style={{ cursor: 'default' }}>
                    <td>
                      <span className="mono muted" style={{ fontSize: 10 }}>{p.key}</span>{' '}
                      <span>{p.title}</span>
                      <div className="hint muted">{p.status.replace('_', ' ').toLowerCase()} · revision {p.revision}</div>
                    </td>
                    <td>
                      <span className={`badge ${TONE_CLASS[tone]}`}>
                        <span aria-hidden="true">{TONE_GLYPH[tone]}</span> {passRateText(p)}
                      </span>
                      <div className="hint muted">{basisText(p)}</div>
                    </td>
                    <td className="muted">{breakdownText(p)}</td>
                    <td className="muted">{p.lastResultAt ? new Date(p.lastResultAt).toLocaleString() : 'No result yet'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 10 }}>
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="hint muted">Page {page + 1} of {data.totalPages}</span>
          <button className="btn" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </>
  )
}
