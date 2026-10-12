import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { LayoutTemplate, Plus } from 'lucide-react'
import { api, type ClauseCoverage } from '@/shared/api/client'
import { Empty, Page } from '@/shared/ui/Page'
import { useMe } from '@/shared/useMe'
import { ClauseForm } from './compliance/ClauseForm'
import { ClausePanel } from './compliance/ClausePanel'
import { TemplatePicker } from './compliance/TemplatePicker'
import { RisksTab } from './compliance/RisksTab'
import { canManageRisks } from './compliance/riskModel'
import {
  COVERAGE_GLYPH, COVERAGE_HELP, COVERAGE_LABEL, NO_FILTER, excerpt, filterRows, standardsOf, summaryLine, summarise, type RegisterFilter,
} from './compliance/complianceModel'

const COVERAGES: ClauseCoverage[] = ['UNMAPPED', 'MAPPED', 'EVIDENCED']

/**
 * VYB-0947 (F41, D38): the clause register. Each control clause of a standard, how many requirements are mapped to it, and whether
 * every one of them is approved with a passing test at its current revision. Evidence comes from the requirements and their tests;
 * nothing is entered on the clause itself. Administrators keep the register; whoever may create trace links maps a requirement.
 */
export function Compliance() {
  const { data: me } = useMe()
  const isAdmin = !!me?.platformAdministrator
  const [params, setParams] = useSearchParams()
  const tab: 'clauses' | 'risks' = params.get('tab') === 'risks' ? 'risks' : 'clauses'
  const [addingClause, setAddingClause] = useState(false)
  const [templates, setTemplates] = useState(false)
  const choose = (t: 'clauses' | 'risks') => setParams(t === 'risks' ? { tab: 'risks' } : {}, { replace: true })

  return (
    <Page
      title="Compliance"
      desc="Control clauses and whether each is evidenced by a passing test, and the risks you are carrying."
      actions={tab === 'clauses' && isAdmin ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={() => setTemplates(true)}><LayoutTemplate /> From a template</button>
          <button className="btn pri" onClick={() => setAddingClause(true)}><Plus /> Add a clause</button>
        </div>
      ) : undefined}
    >
      <div role="tablist" aria-label="Compliance" style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <button role="tab" aria-selected={tab === 'clauses'} className={`btn${tab === 'clauses' ? ' pri' : ''}`} onClick={() => choose('clauses')}>Clauses</button>
        <button role="tab" aria-selected={tab === 'risks'} className={`btn${tab === 'risks' ? ' pri' : ''}`} onClick={() => choose('risks')}>Risks</button>
      </div>
      {templates && <TemplatePicker onClose={() => setTemplates(false)} />}
      {tab === 'clauses' ? <ClausesTab isAdmin={isAdmin} adding={addingClause} setAdding={setAddingClause} /> : <RisksTab canManage={canManageRisks(me)} initialRisk={params.get('risk') ?? undefined} />}
    </Page>
  )
}

function ClausesTab({ isAdmin, adding, setAdding }: { isAdmin: boolean; adding: boolean; setAdding: (v: boolean) => void }) {
  const [filter, setFilter] = useState<RegisterFilter>(NO_FILTER)
  const [selected, setSelected] = useState('')
  const set = (patch: Partial<RegisterFilter>) => setFilter((f) => ({ ...f, ...patch }))

  const { data: rows, isLoading, isError } = useQuery({ queryKey: ['clause-register'], queryFn: api.clauseRegister })
  const all = useMemo(() => rows ?? [], [rows])
  const shown = useMemo(() => filterRows(all, filter), [all, filter])
  const standards = useMemo(() => standardsOf(all), [all])
  const summary = useMemo(() => summarise(all), [all])
  const filtered = filter.standard !== '' || filter.coverage !== '' || filter.q.trim() !== ''

  return (
    <>
      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && <p className="err-text" role="alert">The register could not be loaded.</p>}
      {rows && (
        <>
          <p role="status" className="hint" style={{ marginBottom: 10 }}>{summaryLine(summary)}</p>
          <div className="row toolbar" style={{ marginBottom: 12 }}>
            <div className="field">
              <label className="label" htmlFor="cf-standard">Standard</label>
              <select id="cf-standard" className="select" value={filter.standard} onChange={(e) => set({ standard: e.target.value })}>
                <option value="">All</option>
                {standards.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div className="field">
              <label className="label" htmlFor="cf-coverage">Coverage</label>
              <select id="cf-coverage" className="select" value={filter.coverage} onChange={(e) => set({ coverage: e.target.value as ClauseCoverage | '' })}>
                <option value="">All</option>
                {COVERAGES.map((c) => <option key={c} value={c}>{COVERAGE_LABEL[c]}</option>)}
              </select>
            </div>
            <div className="field">
              <label className="label" htmlFor="cf-q">Search</label>
              <input id="cf-q" className="input" value={filter.q} placeholder="Section or wording" onChange={(e) => set({ q: e.target.value })} />
            </div>
            {filtered && <button className="btn" style={{ alignSelf: 'end' }} onClick={() => setFilter(NO_FILTER)}>Clear filters</button>}
          </div>

          {all.length === 0 ? (
            <Empty title="No clauses yet" desc={isAdmin ? 'Add the first control clause of the standard you are held to.' : 'An administrator adds the clauses of the standard you are held to.'} />
          ) : shown.length === 0 ? (
            <Empty title="No clause matches" desc="Change or clear the filters." />
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: selected ? 'minmax(0, 1fr) 380px' : '1fr', gap: 16 }}>
              <div className="card" style={{ padding: 0, overflow: 'auto' }}>
                <table className="tbl" style={{ width: '100%' }}>
                  <thead>
                    <tr><th>Standard</th><th>Section</th><th>Control</th><th>Requirements</th><th>Evidenced</th><th>Coverage</th></tr>
                  </thead>
                  <tbody>
                    {shown.map((r) => (
                      <tr key={r.id} aria-selected={r.id === selected} style={{ cursor: 'pointer', background: r.id === selected ? 'var(--panel-2)' : undefined }}
                        onClick={() => setSelected(r.id)}>
                        <td>{r.standard}</td>
                        <td className="mono">{r.section ?? '—'}</td>
                        <td>
                          <button className="link" style={{ textAlign: 'left', background: 'none', border: 0, padding: 0, cursor: 'pointer', color: 'inherit' }}
                            onClick={(e) => { e.stopPropagation(); setSelected(r.id) }}>{excerpt(r.text)}</button>
                        </td>
                        <td className="mono">{r.mappedRequirements}</td>
                        <td className="mono">{r.evidencedRequirements}/{r.mappedRequirements}</td>
                        <td title={COVERAGE_HELP[r.coverage]}><span aria-hidden="true">{COVERAGE_GLYPH[r.coverage]} </span>{COVERAGE_LABEL[r.coverage]}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {selected && <ClausePanel key={selected} clauseId={selected} isAdmin={isAdmin} onClose={() => setSelected('')} onDeleted={() => setSelected('')} />}
            </div>
          )}
        </>
      )}
      {adding && <ClauseForm onClose={() => setAdding(false)} onSaved={(id) => setSelected(id)} />}
    </>
  )
}
