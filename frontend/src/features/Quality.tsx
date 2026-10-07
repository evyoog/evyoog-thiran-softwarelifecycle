import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Plus } from 'lucide-react'
import {
  api, type DefectSeverity, type FoundIn,
} from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { useRovingGrid } from '@/shared/ui/useRovingGrid'
import { TestCaseAuthoringPanel } from './quality/TestCaseAuthoringPanel'
import { DependenciesTab } from './quality/DependenciesTab'
import { BulkTestCaseReviewPanel } from './quality/BulkTestCaseReviewPanel'
import { TestCasesTab } from './quality/TestCasesTab'
import { TestRunsTab } from './quality/TestRunsTab'
import { PassRatesTab } from './quality/PassRatesTab'
import { DefectDetailPanel } from './quality/DefectDetailPanel'
import {
  FILTERS, FILTER_LABEL, STATE_CLASS, STATE_GLYPH, STATE_LABEL, assigneeText, emptyText, type StateFilter,
} from './quality/defects'
import { useMe } from '@/shared/useMe'

type Tab = 'verification' | 'dependencies' | 'defects' | 'test-cases' | 'test-runs' | 'pass-rate'

const TAB_LABEL: Record<Tab, string> = {
  verification: 'Verification', dependencies: 'Dependencies', defects: 'Defects', 'test-cases': 'Test cases',
  'test-runs': 'Test runs', 'pass-rate': 'Pass rate',
}

const SEVERITIES: DefectSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const FOUND_IN: FoundIn[] = ['DEV', 'QA', 'UAT', 'PRODUCTION']
/**
 * VYB-0826: review rounds removed from this screen at the product owner's request —
 * the backend review-round endpoints/service are untouched, only this screen's UI for
 * them is gone, same "UI-only removal" scope as Session 38's "My views" removal.
 * VYB-0810: verification here is quality evidence — whether a passing test exists at
 * the current revision — kept for reporting even though it no longer sets any
 * requirement status; nothing on this screen can set or unset it directly, only
 * {@code VerificationService} ingesting a test result can.
 */
export function Quality() {
  const [tab, setTab] = useState<Tab>('verification')

  return (
    <Page
      title="Quality"
      desc="Verification and defects. A requirement is Verified only when a test passed against its current revision."
    >
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
          <button key={t} className={`btn${t === tab ? ' pri' : ''}`} onClick={() => setTab(t)}>{TAB_LABEL[t]}</button>
        ))}
      </div>

      {tab === 'verification' && <VerificationTab />}
      {tab === 'dependencies' && <DependenciesTab />}
      {tab === 'defects' && <DefectsTab />}
      {tab === 'test-cases' && <TestCasesTab />}
      {tab === 'test-runs' && <TestRunsTab />}
      {tab === 'pass-rate' && <PassRatesTab />}
    </Page>
  )
}

/** VYB-0362: unverified and stale are shown separately, never conflated. */
function VerificationTab() {
  const qc = useQueryClient()
  const [draftFor, setDraftFor] = useState<{ id: string; key: string } | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [showBulk, setShowBulk] = useState(false)
  // VYB-0826: a requirement generating a test case doesn't make it "verified" (that
  // still needs a real test run to pass) — so it can't honestly disappear from a
  // backend query keyed on verification. This is a purely local "I just acted on this,
  // stop showing it to me here" filter; the row is still real in `unverified`, and it's
  // now also real in the "Test cases" tab, which is where it actually belongs next.
  const [justGenerated, setJustGenerated] = useState<Set<string>>(new Set())
  const { data: summary } = useQuery({ queryKey: ['evidence-summary'], queryFn: api.evidenceSummary })
  const { data: unverifiedAll } = useQuery({ queryKey: ['evidence-unverified'], queryFn: api.unverifiedRequirements })
  const { data: stale } = useQuery({ queryKey: ['evidence-stale'], queryFn: api.staleEvidenceRequirements })
  const unverified = unverifiedAll?.filter((r) => !justGenerated.has(r.id))

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['evidence-summary'] })
    void qc.invalidateQueries({ queryKey: ['evidence-unverified'] })
    void qc.invalidateQueries({ queryKey: ['test-cases-by-requirement'] })
    void qc.invalidateQueries({ queryKey: ['test-cases-for-requirement'] })
  }

  const toggleSelected = (id: string) => setSelected((s) => {
    const next = new Set(s)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })

  // VYB-0830: the single "Add test case" flow (manual or AI) and the bulk-checkbox flow
  // both funnel through here now, so a requirement generated either way disappears from
  // Unverified the same way — this used to only fire from the bulk flow.
  const onGenerated = (touchedIds: string[]) => {
    if (touchedIds.length > 0) {
      setJustGenerated((j) => new Set([...j, ...touchedIds]))
      setSelected(new Set())
    }
    invalidate()
  }

  return (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12, marginBottom: 20 }}>
        <div className="card"><div className="eyebrow">Test cases</div><div style={{ fontSize: 28, fontWeight: 700 }}>{summary?.testCaseCount ?? '—'}</div></div>
        <div className="card"><div className="eyebrow">Unverified</div><div style={{ fontSize: 28, fontWeight: 700, color: 'var(--crit)' }}>{summary?.unverifiedCount ?? '—'}</div></div>
        <div className="card"><div className="eyebrow">Stale evidence</div><div style={{ fontSize: 28, fontWeight: 700, color: 'var(--high)' }}>{summary?.staleCount ?? '—'}</div></div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
        <h4 className="section-h" style={{ marginBottom: 0 }}>Unverified — approved, no passing test at the current revision</h4>
        <button className="btn pri" style={{ fontSize: 11 }} disabled={selected.size === 0} onClick={() => setShowBulk(true)}>
          Generate for selected ({selected.size})
        </button>
      </div>
      {unverified && unverified.length === 0 && <Empty title="Nothing unverified" desc="Every approved requirement has a current passing test." />}
      {unverified?.map((r) => (
        <div key={r.id} className="list-item">
          <input type="checkbox" checked={selected.has(r.id)} onChange={() => toggleSelected(r.id)} aria-label={`Select ${r.key} for bulk generation`} />
          <span className="mono muted" style={{ fontSize: 10 }}>{r.key}</span>
          <span style={{ flex: 1 }}>{r.title}</span>
          <button className="btn" style={{ fontSize: 11 }} onClick={() => setDraftFor({ id: r.id, key: r.key })}>
            Add test case
          </button>
        </div>
      ))}

      <div style={{ marginTop: 20 }}>
        <h4 className="section-h">Stale evidence — passed once, but against an earlier revision</h4>
        {stale && stale.length === 0 && <Empty title="No stale evidence" desc="Nothing needs re-verifying." />}
        {stale?.map((r) => (
          <div key={r.id} className="list-item">
            <span className="mono muted" style={{ fontSize: 10 }}>{r.key}</span>
            <span style={{ flex: 1 }}>{r.title}</span>
          </div>
        ))}
      </div>

      <p className="hint muted" style={{ marginTop: 12 }}>
        VYB-0363/0824/0826: "Add test case" raises a proposal — manual or AI-generated, one requirement or a bulk
        selection (plus its direct dependencies) — always a real {'test_case'} row, status {'DRAFT'}, linked with a
        real VERIFIES trace link, exactly like a CI-ingested one. An AI suggestion is never saved on its own; only
        accepting one (editable first) creates the row. Nothing runs it, it isn't "borrowed" (VYB-0507) until it
        stays that way, and it only counts as verifying evidence once an actual test run passes it.
      </p>

      {draftFor && (
        <TestCaseAuthoringPanel requirement={draftFor} onClose={() => setDraftFor(null)} onGenerated={onGenerated} />
      )}
      {showBulk && (
        <BulkTestCaseReviewPanel requirementIds={[...selected]} onClose={() => setShowBulk(false)} onGenerated={onGenerated} />
      )}
    </>
  )
}

/** VYB-0364/0365: root cause is visible in the list, and the requirement-vs-coding split is a real figure. */
function DefectsTab() {
  const qc = useQueryClient()
  const { data: me } = useMe()
  const [showRaise, setShowRaise] = useState(false)
  const [state, setState] = useState<StateFilter>('OPEN')
  const [severity, setSeverity] = useState<DefectSeverity | ''>('')
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)

  // wait for a pause in typing before asking the server
  useEffect(() => {
    const t = setTimeout(() => { setQ(text.trim()); setPage(0) }, 250)
    return () => clearTimeout(t)
  }, [text])

  const { data, isLoading } = useQuery({
    queryKey: ['defects', state, severity, q, page],
    queryFn: () => api.defects({ state, severity: severity || undefined, q: q || undefined, page, size: 25 }),
  })
  const { data: split } = useQuery({ queryKey: ['defect-split'], queryFn: () => api.defectRootCauseSplit() })

  const invalidate = () => { void qc.invalidateQueries({ queryKey: ['defects'] }); void qc.invalidateQueries({ queryKey: ['defect-split'] }) }
  // VYB-0767: the list is grid-navigable (arrow keys), and Enter opens the defect in the panel beside it.
  const rows = data?.content ?? []
  const defectGrid = useRovingGrid(rows.length, 5, (row) => { if (rows[row]) setSelected(rows[row].id) })

  const requirementCaused = split?.filter((s) => s.rootCause === 'REQUIREMENT_AMBIGUITY' || s.rootCause === 'REQUIREMENT_OMISSION')
    .reduce((sum, s) => sum + s.count, 0) ?? 0
  const totalClassified = split?.reduce((sum, s) => sum + s.count, 0) ?? 0
  const empty = emptyText(state, q !== '' || severity !== '')

  return (
    <>
      <div className="card" style={{ marginBottom: 14 }}>
        <div className="eyebrow" style={{ marginBottom: 4 }}>Requirement-caused vs. coding error</div>
        <div style={{ fontSize: 20, fontWeight: 700 }}>
          {totalClassified === 0 ? '—' : `${Math.round((requirementCaused / totalClassified) * 100)}%`}
          <span className="muted" style={{ fontSize: 12, fontWeight: 400 }}> of classified defects trace to the requirement, not the code</span>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div role="group" aria-label="Defect state" style={{ display: 'flex', gap: 6 }}>
            {FILTERS.map((f) => (
              <button key={f} className={`btn${f === state ? ' pri' : ''}`} aria-pressed={f === state}
                onClick={() => { setState(f); setPage(0); setSelected(null) }}>{FILTER_LABEL[f]}</button>
            ))}
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label className="label" htmlFor="df-q">Search</label>
            <input id="df-q" className="input" placeholder="Key or title" value={text} onChange={(e) => setText(e.target.value)} />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label className="label" htmlFor="df-sev">Severity</label>
            <select id="df-sev" className="select" value={severity} onChange={(e) => { setSeverity(e.target.value as DefectSeverity | ''); setPage(0) }}>
              <option value="">Any</option>
              {SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
        </div>
        <button className="btn pri" onClick={() => setShowRaise(true)}><Plus /> Raise a defect</button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: selected ? 'minmax(0, 1.1fr) minmax(0, 1fr)' : 'minmax(0, 1fr)', gap: 16, alignItems: 'start' }}>
        <div>
          {isLoading && <p className="eyebrow">Loading…</p>}
          {data && rows.length === 0 && <Empty title={empty.title} desc={empty.desc} />}
          {rows.length > 0 && (
            <div className="tbl-wrap">
              <table className="tbl" role="grid" aria-rowcount={rows.length} aria-colcount={5} onKeyDown={defectGrid.onKeyDown}>
                <thead><tr role="row"><th>Key</th><th>Title</th><th>Severity</th><th>State</th><th>Routed to</th></tr></thead>
                <tbody>
                  {rows.map((d, rowIdx) => (
                    <tr key={d.id} role="row" aria-selected={d.id === selected} onClick={() => setSelected(d.id)}
                      style={{ cursor: 'pointer', outline: d.id === selected ? '2px solid var(--brand)' : undefined, outlineOffset: -2 }}>
                      <td className="mono" {...defectGrid.cellProps(rowIdx, 0)}>{d.key}</td>
                      <td {...defectGrid.cellProps(rowIdx, 1)}>{d.title}{d.requirementKey && <span className="hint muted"> · {d.requirementKey}</span>}</td>
                      <td {...defectGrid.cellProps(rowIdx, 2)}>{d.severity}</td>
                      <td {...defectGrid.cellProps(rowIdx, 3)}>
                        <span className={`badge ${STATE_CLASS[d.state]}`}><span aria-hidden="true">{STATE_GLYPH[d.state]}</span> {STATE_LABEL[d.state]}</span>
                      </td>
                      {/* VYB-0322 AC2: the routing itself, not only a notification that fired once. */}
                      <td className="muted" {...defectGrid.cellProps(rowIdx, 4, { fontSize: 11 })}>{assigneeText(d)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && data.totalPages > 1 && (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8 }}>
              <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span className="hint muted">Page {page + 1} of {data.totalPages}</span>
              <button className="btn" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </div>
        {selected && <DefectDetailPanel defectId={selected} me={me} onClose={() => setSelected(null)} />}
      </div>

      {showRaise && <RaiseDefectModal onClose={() => setShowRaise(false)} onRaised={invalidate} />}
    </>
  )
}

function RaiseDefectModal({ onClose, onRaised }: { onClose: () => void; onRaised: () => void }) {
  const [title, setTitle] = useState('')
  const [severity, setSeverity] = useState<DefectSeverity>('MEDIUM')
  const [foundIn, setFoundIn] = useState<FoundIn>('QA')
  const [requirementId, setRequirementId] = useState('')

  const raise = useMutation({
    mutationFn: () => api.raiseDefect({ title, severity, foundIn, requirementId: requirementId.trim() || undefined }),
    onSuccess: () => { onRaised(); onClose() },
  })

  return (
    <Modal onClose={onClose} title="Raise a defect">
        <h3>Raise a defect</h3>
        <div className="field">
          <label className="label">Title</label>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="row">
          <div className="field">
            <label className="label">Severity</label>
            <select className="select" value={severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)}>
              {SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="label">Found in</label>
            <select className="select" value={foundIn} onChange={(e) => setFoundIn(e.target.value as FoundIn)}>
              {FOUND_IN.map((f) => <option key={f} value={f}>{f}</option>)}
            </select>
          </div>
        </div>
        <div className="field">
          <label className="label">Requirement ID (optional — leave blank if untraced)</label>
          <input className="input" value={requirementId} onChange={(e) => setRequirementId(e.target.value)} />
        </div>
        {raise.isError && <p className="err-text">Could not raise the defect.</p>}
        <div className="actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn pri" disabled={!title.trim() || raise.isPending} onClick={() => raise.mutate()}>
            Raise
          </button>
        </div>
    </Modal>
  )
}
