import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '@/shared/api/client'
import { StatusBadge } from '@/shared/ui/Badges'
import { ClauseForm } from './ClauseForm'
import { COVERAGE_GLYPH, COVERAGE_HELP, COVERAGE_LABEL, clauseName, evidenceLabel, testResultLabel } from './complianceModel'

/**
 * VYB-0947 (D38): one clause: its text, the requirements mapped to it (each with its status, its evidence and its tests), and the
 * controls to map and unmap a requirement. Evidence is read from the requirements, never entered on the clause.
 */
export function ClausePanel({ clauseId, isAdmin, onClose, onDeleted }: {
  clauseId: string
  isAdmin: boolean
  onClose: () => void
  onDeleted: () => void
}) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [search, setSearch] = useState('')
  const q = search.trim()

  const { data: mapping, isLoading, isError } = useQuery({ queryKey: ['clause-mapping', clauseId], queryFn: () => api.clauseMapping(clauseId) })
  const found = useQuery({
    queryKey: ['clause-pick-requirements', q], queryFn: () => api.requirements({ title: q, size: 8 }), enabled: q.length >= 2,
  })

  const refresh = () => { for (const key of ['clause-mapping', 'clause-register', 'trace-links', 'requirement-linked']) void qc.invalidateQueries({ queryKey: [key] }) }
  const reason = (e: unknown, fallback: string) => (e instanceof ApiError ? (e.detail ?? e.title) : fallback)

  const map = useMutation({ mutationFn: (requirementId: string) => api.mapClauseRequirement(clauseId, requirementId), onSuccess: () => { setSearch(''); refresh() } })
  const unmap = useMutation({ mutationFn: (requirementId: string) => api.unmapClauseRequirement(clauseId, requirementId), onSuccess: refresh })
  const remove = useMutation({
    mutationFn: () => api.deleteClause(clauseId),
    onSuccess: () => { for (const key of ['clause-register']) void qc.invalidateQueries({ queryKey: [key] }); onDeleted() },
  })

  if (isLoading) return <div className="card" style={{ alignSelf: 'start' }}><p className="eyebrow">Loading…</p></div>
  if (isError || !mapping) return <div className="card" style={{ alignSelf: 'start' }}><p className="err-text" role="alert">That clause could not be loaded.</p></div>

  const mappedIds = new Set(mapping.requirements.map((r) => r.id))

  return (
    <aside className="card" style={{ alignSelf: 'start' }} aria-label="Clause">
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
        <strong>{clauseName(mapping)}</strong>
        <button className="btn" style={{ padding: 4 }} onClick={onClose} aria-label="Close">✕</button>
      </div>
      <p className="text-sm text-tx-2" style={{ margin: '6px 0 8px' }}>{mapping.text}</p>
      <p className="text-sm" title={COVERAGE_HELP[mapping.coverage]}>
        <span aria-hidden="true">{COVERAGE_GLYPH[mapping.coverage]} </span><b>{COVERAGE_LABEL[mapping.coverage]}</b>
      </p>
      <p className="hint muted" style={{ marginTop: 2 }}>{COVERAGE_HELP[mapping.coverage]}</p>

      {isAdmin && !confirmDelete && (
        <div style={{ display: 'flex', gap: 6, margin: '10px 0' }}>
          <button className="btn" onClick={() => setEditing(true)}>Edit</button>
          <button className="btn" onClick={() => setConfirmDelete(true)}>Delete</button>
        </div>
      )}
      {isAdmin && confirmDelete && (
        <div className="hint" style={{ display: 'flex', gap: 6, alignItems: 'center', margin: '10px 0', flexWrap: 'wrap' }}>
          Delete this clause?
          <button className="btn" disabled={remove.isPending} onClick={() => remove.mutate()}>Delete</button>
          <button className="btn" onClick={() => { setConfirmDelete(false); remove.reset() }}>Keep</button>
        </div>
      )}
      {remove.isError && <p className="err-text" role="alert">{reason(remove.error, 'Could not delete this clause.')}</p>}

      <div className="eyebrow" style={{ margin: '14px 0 4px' }}>Requirements that satisfy it</div>
      {mapping.requirements.length === 0 && <p className="hint muted">None mapped yet.</p>}
      {mapping.requirements.map((r) => (
        <div key={r.id} style={{ padding: '6px 0', borderBottom: '1px solid var(--line-2)' }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <Link to={`/requirements/${r.id}`} className="mono" style={{ fontSize: 11 }}>{r.key}</Link>
            <span style={{ flex: 1, fontSize: 12.5 }}>{r.title}</span>
            <StatusBadge status={r.status} />
            <button className="btn" style={{ padding: '2px 6px' }} disabled={unmap.isPending} aria-label={`Unmap ${r.key}`} onClick={() => unmap.mutate(r.id)}>✕</button>
          </div>
          <p className="hint muted" style={{ margin: '2px 0 0', fontSize: 11 }}>
            <span aria-hidden="true">{r.evidenced ? '✓' : r.counts ? '–' : '⊘'} </span>{evidenceLabel(r)}
          </p>
          {r.tests.length === 0 && r.counts && <p className="hint muted" style={{ margin: '2px 0 0', fontSize: 11 }}>No test is linked to this requirement.</p>}
          {r.tests.map((t) => {
            const res = testResultLabel(t.result)
            return (
              <p key={t.id} className="mono muted" style={{ margin: '2px 0 0 8px', fontSize: 10.5 }}>
                <span aria-hidden="true">{res.glyph} </span>{t.key} {t.title} — {res.word}
              </p>
            )
          })}
        </div>
      ))}
      {unmap.isError && <p className="err-text" role="alert">{reason(unmap.error, 'Could not unmap that requirement.')}</p>}

      <div className="eyebrow" style={{ margin: '14px 0 4px' }}>Map a requirement</div>
      <div className="field">
        <label className="label" htmlFor="map-q">Search by title</label>
        <input id="map-q" className="input" value={search} placeholder="At least two letters" onChange={(e) => setSearch(e.target.value)} />
      </div>
      {q.length >= 2 && (
        <div role="listbox" aria-label="Requirements to map" className="mt-2 border border-line rounded max-h-48 overflow-auto">
          {(found.data?.content ?? []).length === 0 && <p className="text-sm text-tx-3 p-2">{found.isLoading ? 'Searching…' : 'No requirement matches.'}</p>}
          {(found.data?.content ?? []).map((r) => (
            <button key={r.id} role="option" aria-selected="false" disabled={mappedIds.has(r.id) || map.isPending}
              onClick={() => map.mutate(r.id)}
              className="block w-full text-left text-sm px-2 py-1.5 border-b border-line last:border-0 hover:bg-panel-2 disabled:opacity-50">
              <span className="mono">{r.key}</span> {r.title}{mappedIds.has(r.id) ? ' — already mapped' : ''}
            </button>
          ))}
        </div>
      )}
      {map.isError && <p className="err-text" role="alert">{reason(map.error, 'Could not map that requirement.')}</p>}

      {editing && <ClauseForm clause={{ id: mapping.id, standard: mapping.standard, section: mapping.section, text: mapping.text }} onClose={() => setEditing(false)} onSaved={() => undefined} />}
    </aside>
  )
}
