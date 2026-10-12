import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, type RiskStatus } from '@/shared/api/client'
import { StatusBadge } from '@/shared/ui/Badges'
import { RiskForm } from './RiskForm'
import { BAND_GLYPH, IMPACT_LABEL, LIKELIHOOD_LABEL, STATUSES, STATUS_LABEL, needsReason, ratingText, statusChangeProblem } from './riskModel'

/**
 * VYB-0949 (D40): one risk: what it is, its rating, status and the reason for it, the requirements and releases it is linked to,
 * and, for those who may manage risks, the controls to edit it, move it between statuses and link or unlink. The server decides who
 * may; a refusal is shown in its words.
 */
export function RiskPanel({ riskId, canManage, onClose }: { riskId: string; canManage: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [target, setTarget] = useState<RiskStatus | ''>('')
  const [reason, setReason] = useState('')
  const [search, setSearch] = useState('')
  const [releaseId, setReleaseId] = useState('')
  const q = search.trim()

  const { data, isLoading, isError } = useQuery({ queryKey: ['risk', riskId], queryFn: () => api.risk(riskId) })
  const found = useQuery({ queryKey: ['risk-pick-requirements', q], queryFn: () => api.requirements({ title: q, size: 8 }), enabled: q.length >= 2 && canManage })
  const releases = useQuery({ queryKey: ['releases'], queryFn: api.releases, enabled: canManage })

  const refresh = () => { for (const key of ['risk', 'risks', 'requirement-linked']) void qc.invalidateQueries({ queryKey: [key] }) }
  const why = (e: unknown, fallback: string) => (e instanceof ApiError ? (e.detail ?? e.title) : fallback)

  const change = useMutation({
    mutationFn: () => api.changeRiskStatus(riskId, target as RiskStatus, reason.trim() || undefined),
    onSuccess: () => { setTarget(''); setReason(''); refresh() },
  })
  const linkReq = useMutation({ mutationFn: (id: string) => api.linkRiskRequirement(riskId, id), onSuccess: () => { setSearch(''); refresh() } })
  const unlinkReq = useMutation({ mutationFn: (id: string) => api.unlinkRiskRequirement(riskId, id), onSuccess: refresh })
  const linkRel = useMutation({ mutationFn: (id: string) => api.linkRiskRelease(riskId, id), onSuccess: () => { setReleaseId(''); refresh() } })
  const unlinkRel = useMutation({ mutationFn: (id: string) => api.unlinkRiskRelease(riskId, id), onSuccess: refresh })

  if (isLoading) return <div className="card" style={{ alignSelf: 'start' }}><p className="eyebrow">Loading…</p></div>
  if (isError || !data) return <div className="card" style={{ alignSelf: 'start' }}><p className="err-text" role="alert">That risk could not be loaded.</p></div>

  const r = data.risk
  const closed = r.status === 'CLOSED'
  const linkedIds = new Set(data.requirementLinks.map((x) => x.id))
  const linkedReleaseIds = new Set(data.releaseLinks.map((x) => x.id))
  const problem = target === '' ? null : statusChangeProblem(r.status, target, reason)

  return (
    <aside className="card" style={{ alignSelf: 'start' }} aria-label="Risk">
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
        <strong><span className="mono">{r.key}</span> {r.title}</strong>
        <button className="btn" style={{ padding: 4 }} onClick={onClose} aria-label="Close">✕</button>
      </div>
      <p className="text-sm" style={{ margin: '8px 0 2px' }}>
        <span aria-hidden="true">{BAND_GLYPH[r.band]} </span><b>{ratingText(r)}</b> · {STATUS_LABEL[r.status]}
      </p>
      <p className="hint muted" style={{ margin: 0 }}>
        Likelihood {r.likelihood} ({LIKELIHOOD_LABEL[r.likelihood]}) × impact {r.impact} ({IMPACT_LABEL[r.impact]}) · Owner: {r.ownerName ?? 'nobody yet'}
      </p>
      {data.statusReason && <p className="text-sm" style={{ marginTop: 6 }}><b>{STATUS_LABEL[r.status]}:</b> {data.statusReason}</p>}
      {data.description && <p className="text-sm text-tx-2" style={{ marginTop: 6 }}>{data.description}</p>}
      {data.mitigation && <p className="text-sm" style={{ marginTop: 6 }}><b>Mitigation:</b> {data.mitigation}</p>}

      {canManage && (
        <div style={{ margin: '10px 0' }}>
          {!closed && <button className="btn" onClick={() => setEditing(true)}>Edit</button>}
          <div className="field" style={{ marginTop: 8 }}>
            <label className="label" htmlFor="rk-status">Move to</label>
            <select id="rk-status" className="select" value={target} onChange={(e) => setTarget(e.target.value as RiskStatus | '')}>
              <option value="">Choose a status…</option>
              {STATUSES.filter((s) => s !== r.status).map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
            </select>
          </div>
          {target !== '' && needsReason(target) && (
            <div className="field">
              <label className="label" htmlFor="rk-reason">Why is it {STATUS_LABEL[target].toLowerCase()}?</label>
              <textarea id="rk-reason" className="input" rows={2} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
          )}
          {target !== '' && (
            <button className="btn pri" disabled={!!problem || change.isPending} title={problem ?? undefined} onClick={() => change.mutate()}>Move to {STATUS_LABEL[target].toLowerCase()}</button>
          )}
          {change.isError && <p className="err-text" role="alert">{why(change.error, 'Could not change the status.')}</p>}
        </div>
      )}

      <div className="eyebrow" style={{ margin: '12px 0 4px' }}>Requirements it touches</div>
      {data.requirementLinks.length === 0 && <p className="hint muted">None linked.</p>}
      {data.requirementLinks.map((x) => (
        <div key={x.id} style={{ display: 'flex', alignItems: 'baseline', gap: 6, padding: '3px 0' }}>
          <Link to={`/requirements/${x.id}`} className="mono" style={{ fontSize: 11 }}>{x.key}</Link>
          <span style={{ flex: 1, fontSize: 12.5 }}>{x.title}</span>
          <StatusBadge status={x.status} />
          {canManage && !closed && <button className="btn" style={{ padding: '2px 6px' }} aria-label={`Unlink ${x.key}`} disabled={unlinkReq.isPending} onClick={() => unlinkReq.mutate(x.id)}>✕</button>}
        </div>
      ))}
      {unlinkReq.isError && <p className="err-text" role="alert">{why(unlinkReq.error, 'Could not unlink that requirement.')}</p>}
      {canManage && !closed && (
        <>
          <div className="field" style={{ marginTop: 6 }}>
            <label className="label" htmlFor="rk-req">Link a requirement (search by title)</label>
            <input id="rk-req" className="input" value={search} placeholder="At least two letters" onChange={(e) => setSearch(e.target.value)} />
          </div>
          {q.length >= 2 && (
            <div role="listbox" aria-label="Requirements to link" className="mt-1 border border-line rounded max-h-40 overflow-auto">
              {(found.data?.content ?? []).length === 0 && <p className="text-sm text-tx-3 p-2">{found.isLoading ? 'Searching…' : 'No requirement matches.'}</p>}
              {(found.data?.content ?? []).map((x) => (
                <button key={x.id} role="option" aria-selected="false" disabled={linkedIds.has(x.id) || linkReq.isPending} onClick={() => linkReq.mutate(x.id)}
                  className="block w-full text-left text-sm px-2 py-1.5 border-b border-line last:border-0 hover:bg-panel-2 disabled:opacity-50">
                  <span className="mono">{x.key}</span> {x.title}{linkedIds.has(x.id) ? ' — already linked' : ''}
                </button>
              ))}
            </div>
          )}
          {linkReq.isError && <p className="err-text" role="alert">{why(linkReq.error, 'Could not link that requirement.')}</p>}
        </>
      )}

      <div className="eyebrow" style={{ margin: '12px 0 4px' }}>Releases it threatens</div>
      {data.releaseLinks.length === 0 && <p className="hint muted">None linked.</p>}
      {data.releaseLinks.map((x) => (
        <div key={x.id} style={{ display: 'flex', alignItems: 'baseline', gap: 6, padding: '3px 0' }}>
          <span style={{ flex: 1, fontSize: 12.5 }}>{x.name}</span>
          <span className="muted" style={{ fontSize: 11 }}>{x.state.toLowerCase()}</span>
          {canManage && !closed && <button className="btn" style={{ padding: '2px 6px' }} aria-label={`Unlink ${x.name}`} disabled={unlinkRel.isPending} onClick={() => unlinkRel.mutate(x.id)}>✕</button>}
        </div>
      ))}
      {unlinkRel.isError && <p className="err-text" role="alert">{why(unlinkRel.error, 'Could not unlink that release.')}</p>}
      {canManage && !closed && (
        <div className="field" style={{ marginTop: 6 }}>
          <label className="label" htmlFor="rk-rel">Link a release</label>
          <select id="rk-rel" className="select" value={releaseId} onChange={(e) => { setReleaseId(e.target.value); if (e.target.value) linkRel.mutate(e.target.value) }}>
            <option value="">Choose a release…</option>
            {(releases.data ?? []).filter((x) => !linkedReleaseIds.has(x.id)).map((x) => <option key={x.id} value={x.id}>{x.name} ({x.state.toLowerCase()})</option>)}
          </select>
          {linkRel.isError && <p className="err-text" role="alert">{why(linkRel.error, 'Could not link that release.')}</p>}
        </div>
      )}
      {closed && canManage && <p className="hint muted" style={{ marginTop: 8 }}>This risk is closed. Move it back to open to change or relink it.</p>}

      {editing && <RiskForm risk={data} onClose={() => setEditing(false)} onSaved={() => undefined} />}
    </aside>
  )
}
