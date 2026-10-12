import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Plus } from 'lucide-react'
import { api, type RiskBand } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { RiskForm } from './RiskForm'
import { RiskPanel } from './RiskPanel'
import {
  BANDS, BAND_GLYPH, BAND_LABEL, BAND_VAR, DEFAULT_FILTER, IMPACT_LABEL, LIKELIHOOD_LABEL, STATUS_LABEL, STATUSES, filterRisks, heatGrid, ratingText,
  summaryLine, summarise, type RiskFilter, type StatusFilter,
} from './riskModel'

/**
 * VYB-0949 (F41, D40): the risk register. A 5 by 5 grid of the risks that are not closed (click a cell to filter), a summary, filters,
 * and the list, highest rating first. Likelihood and impact are qualitative; nothing here is a cost or an effort.
 */
export function RisksTab({ canManage, initialRisk }: { canManage: boolean; initialRisk?: string }) {
  const [filter, setFilter] = useState<RiskFilter>(DEFAULT_FILTER)
  const [selected, setSelected] = useState(initialRisk ?? '')
  const [adding, setAdding] = useState(false)
  const set = (patch: Partial<RiskFilter>) => setFilter((f) => ({ ...f, ...patch }))

  const { data: rows, isLoading, isError } = useQuery({ queryKey: ['risks'], queryFn: api.risks })
  const all = useMemo(() => rows ?? [], [rows])
  const shown = useMemo(() => filterRisks(all, filter), [all, filter])
  const grid = useMemo(() => heatGrid(all), [all])
  const summary = useMemo(() => summarise(all), [all])
  const filtered = JSON.stringify(filter) !== JSON.stringify(DEFAULT_FILTER)

  return (
    <>
      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && <p className="err-text" role="alert">The register could not be loaded.</p>}
      {rows && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
            <p role="status" className="hint" style={{ margin: 0, flex: 1 }}>{summaryLine(summary)}</p>
            {canManage && <button className="btn pri" onClick={() => setAdding(true)}><Plus /> Add a risk</button>}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: 16, alignItems: 'start', marginBottom: 14 }}>
            <figure style={{ margin: 0 }} aria-label="Open risks by likelihood and impact">
              <table style={{ borderCollapse: 'separate', borderSpacing: 3 }}>
                <caption className="hint muted" style={{ captionSide: 'bottom', textAlign: 'left', paddingTop: 4 }}>Open risks. Click a cell to list them.</caption>
                <tbody>
                  {grid.map((line) => (
                    <tr key={line[0]!.likelihood}>
                      <th scope="row" className="hint muted" style={{ fontWeight: 500, textAlign: 'right', paddingRight: 6, fontSize: 11 }}>{line[0]!.likelihood} {LIKELIHOOD_LABEL[line[0]!.likelihood]}</th>
                      {line.map((c) => {
                        const on = filter.cell?.likelihood === c.likelihood && filter.cell?.impact === c.impact
                        return (
                          <td key={c.impact} style={{ padding: 0 }}>
                            <button
                              onClick={() => set({ cell: on ? undefined : { likelihood: c.likelihood, impact: c.impact } })}
                              aria-pressed={on}
                              aria-label={`Likelihood ${c.likelihood}, impact ${c.impact}: ${BAND_LABEL[c.band]}, ${c.count} open risk${c.count === 1 ? '' : 's'}`}
                              title={`${BAND_LABEL[c.band]} (${c.rating}) · ${c.count} open`}
                              style={{
                                width: 46, height: 34, borderRadius: 4, cursor: 'pointer', fontSize: 12, fontWeight: 600,
                                border: `${on ? 2 : 1}px solid var(--${BAND_VAR[c.band]})`, color: `var(--${BAND_VAR[c.band]})`,
                                background: c.count > 0 ? `var(--${BAND_VAR[c.band]}-dim, var(--panel-2))` : 'transparent',
                              }}>
                              <span aria-hidden="true">{BAND_GLYPH[c.band]}</span> {c.count > 0 ? c.count : ''}
                            </button>
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                  <tr>
                    <td />
                    {[1, 2, 3, 4, 5].map((i) => <th key={i} scope="col" className="hint muted" style={{ fontWeight: 500, fontSize: 10.5 }} title={IMPACT_LABEL[i]}>{i}</th>)}
                  </tr>
                </tbody>
              </table>
              <div className="hint muted" style={{ textAlign: 'center', fontSize: 11 }}>Impact →</div>
            </figure>

            <div>
              <div className="row toolbar" style={{ marginBottom: 12 }}>
                <div className="field">
                  <label className="label" htmlFor="rf-status">Status</label>
                  <select id="rf-status" className="select" value={filter.status} onChange={(e) => set({ status: e.target.value as StatusFilter })}>
                    <option value="LIVE">Not closed</option>
                    <option value="ALL">All</option>
                    {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label className="label" htmlFor="rf-band">Band</label>
                  <select id="rf-band" className="select" value={filter.band} onChange={(e) => set({ band: e.target.value as RiskBand | '' })}>
                    <option value="">All</option>
                    {BANDS.slice().reverse().map((b) => <option key={b} value={b}>{BAND_LABEL[b]}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label className="label" htmlFor="rf-q">Search</label>
                  <input id="rf-q" className="input" value={filter.q} placeholder="Key, title or owner" onChange={(e) => set({ q: e.target.value })} />
                </div>
                {filtered && <button className="btn" style={{ alignSelf: 'end' }} onClick={() => setFilter(DEFAULT_FILTER)}>Clear filters</button>}
              </div>
              {filter.cell && (
                <p className="hint" style={{ margin: '0 0 8px' }}>
                  Showing likelihood {filter.cell.likelihood} × impact {filter.cell.impact} ({LIKELIHOOD_LABEL[filter.cell.likelihood]}, {IMPACT_LABEL[filter.cell.impact].toLowerCase()}).
                </p>
              )}
            </div>
          </div>

          {all.length === 0 ? (
            <Empty title="No risks yet" desc={canManage ? 'Add the first risk: what could go wrong, how likely and how bad.' : 'An approver, compliance lead or architect records risks here.'} />
          ) : shown.length === 0 ? (
            <Empty title="No risk matches" desc="Change or clear the filters." />
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: selected ? 'minmax(0, 1fr) 400px' : '1fr', gap: 16 }}>
              <div className="card" style={{ padding: 0, overflow: 'auto' }}>
                <table className="tbl" style={{ width: '100%' }}>
                  <thead><tr><th>Risk</th><th>Title</th><th>Rating</th><th>Status</th><th>Owner</th><th>Requirements</th><th>Releases</th></tr></thead>
                  <tbody>
                    {shown.map((r) => (
                      <tr key={r.id} aria-selected={r.id === selected} style={{ cursor: 'pointer', background: r.id === selected ? 'var(--panel-2)' : undefined }} onClick={() => setSelected(r.id)}>
                        <td className="mono">{r.key}</td>
                        <td>
                          <button style={{ textAlign: 'left', background: 'none', border: 0, padding: 0, cursor: 'pointer', color: 'inherit' }}
                            onClick={(e) => { e.stopPropagation(); setSelected(r.id) }}>{r.title}</button>
                        </td>
                        <td style={{ color: `var(--${BAND_VAR[r.band]})` }}><span aria-hidden="true">{BAND_GLYPH[r.band]} </span>{ratingText(r)}</td>
                        <td>{STATUS_LABEL[r.status]}</td>
                        <td>{r.ownerName ?? '—'}</td>
                        <td className="mono">{r.requirements}</td>
                        <td className="mono">{r.releases}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {selected && <RiskPanel key={selected} riskId={selected} canManage={canManage} onClose={() => setSelected('')} />}
            </div>
          )}
        </>
      )}
      {adding && <RiskForm onClose={() => setAdding(false)} onSaved={(id) => setSelected(id)} />}
    </>
  )
}
