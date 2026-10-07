import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Plus } from 'lucide-react'
import { ApiError, api, type ReleaseState } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { useRovingGrid } from '@/shared/ui/useRovingGrid'
import { RequirementPicker } from './releases/RequirementPicker'
import {
  commitBlockedReason, commitLabel, lockMessage, resultText, scopeLocked, type Selection,
} from './releaseScope'

type Tab = 'scope' | 'movement' | 'baselines' | 'variants' | 'deployment' | 'notes'

/** VYB-0474–0476/0480–0484/0470–0473/0515–0521. */
export function Releases() {
  const [tab, setTab] = useState<Tab>('scope')
  const qc = useQueryClient()
  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases })
  const [releaseId, setReleaseId] = useState('')
  const [newReleaseName, setNewReleaseName] = useState('')
  const createRelease = useMutation({
    mutationFn: () => api.createRelease(newReleaseName),
    onSuccess: (r) => { setNewReleaseName(''); setReleaseId(r.id); void qc.invalidateQueries({ queryKey: ['releases'] }) },
  })
  const selectedRelease = releases?.find((r) => r.id === releaseId)
  const [targetDate, setTargetDate] = useState('')
  const setTarget = useMutation({
    mutationFn: () => api.setReleaseTargetDate(releaseId, new Date(targetDate).toISOString()),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['releases'] }),
  })

  return (
    <Page title="Releases" desc="What is committed, frozen, deployed, and what customers will be told.">
      <div className="row toolbar" style={{ marginBottom: 14 }}>
        <div className="field">
          <label className="label">Release</label>
          <select className="select" value={releaseId} onChange={(e) => setReleaseId(e.target.value)}>
            <option value="">— pick one —</option>
            {releases?.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.state})</option>)}
          </select>
        </div>
        <div className="field" style={{ flex: 2 }}>
          <label className="label">New release</label>
          <div style={{ display: 'flex', gap: 6 }}>
            <input className="input" style={{ flex: 1 }} value={newReleaseName} onChange={(e) => setNewReleaseName(e.target.value)} />
            <button className="btn" disabled={!newReleaseName.trim() || createRelease.isPending} onClick={() => createRelease.mutate()}>
              <Plus /> Create
            </button>
          </div>
        </div>
        {releaseId && (
          <div className="field">
            {/* VYB-0372: the one field the My Work calendar plots — nothing invents one if this stays blank. */}
            <label className="label">Target date {selectedRelease?.targetDate && `(currently ${new Date(selectedRelease.targetDate).toLocaleDateString()})`}</label>
            <div style={{ display: 'flex', gap: 6 }}>
              <input className="input" type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
              <button className="btn" disabled={!targetDate || setTarget.isPending} onClick={() => setTarget.mutate()}>Set</button>
            </div>
          </div>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap' }}>
        {(['scope', 'movement', 'baselines', 'variants', 'deployment', 'notes'] as Tab[]).map((t) => (
          <button key={t} className={`btn${t === tab ? ' pri' : ''}`} onClick={() => setTab(t)}>
            {t[0].toUpperCase() + t.slice(1)}
          </button>
        ))}
      </div>

      {!releaseId && tab !== 'baselines' && tab !== 'variants' && tab !== 'deployment' && (
        <Empty title="Pick a release above" desc="Scope, movement and notes all need one selected." />
      )}
      {tab === 'scope' && releaseId && <ScopeTab releaseId={releaseId} state={selectedRelease?.state} />}
      {tab === 'movement' && releaseId && <MovementTab releaseId={releaseId} />}
      {tab === 'baselines' && <BaselinesTab releaseId={releaseId} />}
      {tab === 'variants' && <VariantsTab />}
      {tab === 'deployment' && <DeploymentTab />}
      {tab === 'notes' && releaseId && <NotesTab releaseId={releaseId} />}
    </Page>
  )
}

/**
 * VYB-0515: committed requirements with verified percentage and open gaps. AC2: empty scope shows an empty state.
 * VYB-0930: the committed list shows key, title and status instead of ids, and requirements are added with a picker (search
 * by key or title, tick several, one reason, committed together or not at all). A frozen or released release's scope is
 * locked (VYB-0928), which the tab says in words instead of letting the server refuse it.
 */
function ScopeTab({ releaseId, state }: { releaseId: string; state: ReleaseState | undefined }) {
  const qc = useQueryClient()
  const locked = scopeLocked(state)
  const [selection, setSelection] = useState<Selection>(new Map())
  const [reason, setReason] = useState('')
  const [outcome, setOutcome] = useState<string | undefined>()
  const [removing, setRemoving] = useState<string | null>(null)
  const [removeReason, setRemoveReason] = useState('')
  const { data: scope } = useQuery({ queryKey: ['release-scope-items', releaseId], queryFn: () => api.releaseScopeItems(releaseId) })
  const { data: readiness } = useQuery({ queryKey: ['release-readiness', releaseId], queryFn: () => api.releaseReadiness(releaseId) })
  const { data: blocked } = useQuery({ queryKey: ['release-blocked', releaseId], queryFn: () => api.releaseBlocked(releaseId) })

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['release-scope-items', releaseId] })
    void qc.invalidateQueries({ queryKey: ['release-scope', releaseId] })
    void qc.invalidateQueries({ queryKey: ['release-candidates', releaseId] })
    void qc.invalidateQueries({ queryKey: ['release-readiness', releaseId] })
    void qc.invalidateQueries({ queryKey: ['release-blocked', releaseId] })
    void qc.invalidateQueries({ queryKey: ['release-current'] })
  }
  const commit = useMutation({
    mutationFn: () => api.commitManyToRelease(releaseId, [...selection.keys()], reason.trim()),
    onSuccess: (r) => {
      setOutcome(resultText(r.committed.length, r.alreadyCommitted.length))
      setSelection(new Map())
      setReason('')
      invalidate()
    },
    onError: () => setOutcome(undefined),
  })
  // VYB-0758: a real modal collecting the reason, not window.prompt: the same
  // pattern every other consequential action here uses.
  const remove = useMutation({
    mutationFn: (id: string) => api.removeFromRelease(releaseId, id, removeReason),
    onSuccess: () => { setRemoving(null); setRemoveReason(''); invalidate() },
  })
  const blockedReason = commitBlockedReason(selection.size, reason, locked)
  const lock = lockMessage(state)

  return (
    <>
      {readiness && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12, marginBottom: 16 }}>
          <div className="card"><div className="eyebrow">Committed</div><div style={{ fontSize: 24, fontWeight: 700 }}>{readiness.committed}</div></div>
          <div className="card">
            <div className="eyebrow">Verified</div>
            <div style={{ fontSize: 24, fontWeight: 700, color: 'var(--ok)' }}>
              {readiness.verified} ({Math.round(readiness.verifiedRatio * 100)}%)
            </div>
          </div>
          <div className="card">
            <div className="eyebrow">Critical open gaps</div>
            <div style={{ fontSize: 24, fontWeight: 700, color: readiness.criticalOpenGaps > 0 ? 'var(--crit)' : undefined }}>
              {readiness.criticalOpenGaps}
            </div>
          </div>
        </div>
      )}

      {blocked && blocked.length > 0 && (
        <div className="card" style={{ borderColor: 'var(--crit-bd)', marginBottom: 16 }}>
          <strong style={{ color: 'var(--crit)' }}>Blocked from release</strong>
          {blocked.map((b) => (
            <div key={b.requirementId} className="list-item">
              <span className="mono muted" style={{ fontSize: 10 }}>{b.key}</span>
              <span style={{ flex: 1 }}>{b.reason}</span>
            </div>
          ))}
        </div>
      )}

      <h4 className="section-h">Committed requirements</h4>
      {lock && <p className="hint muted" role="status">{lock}</p>}
      {scope && scope.length === 0 && <Empty title="Nothing committed yet" desc={locked ? 'This release has nothing committed.' : 'Find requirements below and commit them.'} />}
      {scope?.map((i) => (
        <div key={i.requirementId} className="list-item">
          <span className="mono muted" style={{ fontSize: 11 }}>{i.key}</span>
          <span style={{ flex: 1 }}>{i.title}</span>
          <span className="muted" style={{ fontSize: 11 }}>{i.status.replace('_', ' ').toLowerCase()} · {i.capabilityName}</span>
          <button className="btn" disabled={locked} title={locked ? 'The scope is locked' : undefined} onClick={() => setRemoving(i.requirementId)}>Remove</button>
        </div>
      ))}

      {removing && (
        <Modal onClose={() => setRemoving(null)} title="Remove from release">
          <h3>Remove this requirement from the release?</h3>
          <p className="hint muted">Recorded with your name, same as committing one.</p>
          <div className="field">
            <label className="label">Reason (required)</label>
            <input className="input" value={removeReason} onChange={(e) => setRemoveReason(e.target.value)} autoFocus />
          </div>
          <div className="actions">
            <button className="btn" onClick={() => setRemoving(null)}>Cancel</button>
            <button className="btn pri" disabled={!removeReason.trim() || remove.isPending} onClick={() => remove.mutate(removing)}>
              Remove
            </button>
          </div>
        </Modal>
      )}

      {!locked && (
        <section style={{ marginTop: 18 }} aria-label="Commit requirements">
          <h4 className="section-h">Add requirements</h4>
          <RequirementPicker releaseId={releaseId} selection={selection} onChange={(s) => { setSelection(s); setOutcome(undefined) }} />
          <div className="row" style={{ marginTop: 12 }}>
            <div className="field" style={{ flex: 3 }}>
              <label className="label" htmlFor="commit-reason">Reason (required; recorded with your name, once for all of them)</label>
              <input id="commit-reason" className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <div className="field" style={{ alignSelf: 'flex-end' }}>
              <button className="btn pri" disabled={!!blockedReason || commit.isPending} title={blockedReason} onClick={() => commit.mutate()}>
                {commitLabel(selection.size)}
              </button>
            </div>
          </div>
          {blockedReason && selection.size > 0 && <p className="hint muted">{blockedReason}</p>}
          {outcome && <p role="status" aria-live="polite" style={{ color: 'var(--ok)' }}>{outcome}</p>}
          {commit.isError && (
            <p className="err-text" role="alert">
              {commit.error instanceof ApiError ? (commit.error.detail ?? commit.error.title) : 'Could not commit. Nothing was committed.'}
            </p>
          )}
        </section>
      )}
    </>
  )
}

/** VYB-0516: additions/removals over a selectable window, each naming the actor and reason. */
function MovementTab({ releaseId }: { releaseId: string }) {
  const [days, setDays] = useState(90)
  const from = new Date(Date.now() - days * 86_400_000).toISOString()
  const { data } = useQuery({
    queryKey: ['release-movements', releaseId, days],
    queryFn: () => api.releaseMovements(releaseId, from, new Date().toISOString()),
  })

  return (
    <>
      <div className="field" style={{ maxWidth: 220 }}>
        <label className="label">Window</label>
        <select className="select" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
          <option value={365}>Last year</option>
        </select>
      </div>
      {data && data.length === 0 && <Empty title="No movement in this window" desc="Try a wider window." />}
      {data?.map((m, i) => (
        <div key={i} className="list-item">
          <span className="badge">{m.direction}</span>
          <span className="mono muted" style={{ flex: 1, fontSize: 11 }}>{m.requirementId}</span>
          <span className="muted" style={{ fontSize: 11 }}>{m.reason}</span>
          <span className="mono muted" style={{ fontSize: 10 }}>{m.movedBy?.slice(0, 8)}</span>
          <span className="muted" style={{ fontSize: 10 }}>{new Date(m.movedAt).toLocaleDateString()}</span>
        </div>
      ))}
    </>
  )
}

/** VYB-0517/0518/0470–0472. */
function BaselinesTab({ releaseId }: { releaseId: string }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [requirementIds, setRequirementIds] = useState('')
  const [compareA, setCompareA] = useState('')
  const [compareB, setCompareB] = useState('')
  const { data: baselines } = useQuery({ queryKey: ['baselines'], queryFn: api.baselines })
  const { data: diff } = useQuery({
    queryKey: ['baseline-diff', compareA, compareB],
    queryFn: () => api.baselineDiff(compareA, compareB),
    enabled: !!compareA && !!compareB && compareA !== compareB,
  })

  const freeze = useMutation({
    mutationFn: () => api.freezeBaseline({
      name, releaseId: releaseId || undefined,
      requirementIds: requirementIds.split(',').map((s) => s.trim()).filter(Boolean),
    }),
    onSuccess: () => { setName(''); setRequirementIds(''); void qc.invalidateQueries({ queryKey: ['baselines'] }) },
  })

  const current = baselines?.[0]; // most recently frozen — VYB-0517 AC1

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
      <div>
        <h4 className="section-h">Freeze a baseline</h4>
        <p className="hint muted">Requires step-up authentication — this environment may not have a step-up flow configured yet.</p>
        <div className="field">
          <label className="label">Name</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label className="label">Requirement IDs (comma-separated)</label>
          <input className="input" value={requirementIds} onChange={(e) => setRequirementIds(e.target.value)} />
        </div>
        <button className="btn pri" disabled={!name.trim() || !requirementIds.trim() || freeze.isPending} onClick={() => freeze.mutate()}>
          Freeze
        </button>
        {freeze.isError && (
          <p className="err-text">Could not freeze — this usually means step-up authentication was required and not achieved.</p>
        )}

        <h4 className="section-h" style={{ marginTop: 20 }}>Compare two baselines</h4>
        <div className="row">
          <select className="select" value={compareA} onChange={(e) => setCompareA(e.target.value)}>
            <option value="">— from —</option>
            {baselines?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
          <select className="select" value={compareB} onChange={(e) => setCompareB(e.target.value)}>
            <option value="">— to —</option>
            {baselines?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </div>
        {diff && (
          <div style={{ marginTop: 10 }}>
            <div className="mono muted" style={{ fontSize: 11 }}>+{diff.added.length} added · -{diff.removed.length} removed · {diff.changed.length} changed</div>
            {diff.added.map((i) => <div key={i.requirementId} className="list-item"><span className="badge" style={{ color: 'var(--ok)' }}>Added</span><span className="mono" style={{ fontSize: 11 }}>{i.key}</span></div>)}
            {diff.removed.map((i) => <div key={i.requirementId} className="list-item"><span className="badge" style={{ color: 'var(--crit)' }}>Removed</span><span className="mono" style={{ fontSize: 11 }}>{i.key}</span></div>)}
            {diff.changed.map((i) => (
              <div key={i.requirementId} className="list-item">
                <span className="badge">Changed</span>
                <span className="mono" style={{ fontSize: 11 }}>{i.key}</span>
                <span className="muted" style={{ fontSize: 11 }}>rev {i.fromRevision} → {i.toRevision}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div>
        <h4 className="section-h">All baselines</h4>
        {baselines && baselines.length === 0 && <Empty title="No baselines frozen yet" desc="" />}
        {baselines?.map((b) => (
          <div key={b.id} className="list-item" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <strong style={{ flex: 1 }}>{b.name}</strong>
              {b.id === current?.id && <span className="badge" style={{ color: 'var(--brand)' }}>Current</span>}
            </div>
            <div className="muted" style={{ fontSize: 11 }}>
              {new Date(b.frozenAt).toLocaleString()} · {b.gapsAtFreeze} gap(s) at freeze (as recorded, not recomputed)
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/** VYB-0519/0473. */
function VariantsTab() {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [requirementIds, setRequirementIds] = useState('')
  const [activeIds, setActiveIds] = useState<string[]>([])
  const { data: variants } = useQuery({ queryKey: ['variants'], queryFn: api.variants })
  const { data: matrix } = useQuery({
    queryKey: ['variant-matrix', activeIds], queryFn: () => api.variantMatrix(activeIds), enabled: activeIds.length > 0,
  })

  const createVariant = useMutation({
    mutationFn: () => api.createVariant(name),
    onSuccess: () => { setName(''); void qc.invalidateQueries({ queryKey: ['variants'] }) },
  })
  const toggle = useMutation({
    mutationFn: ({ variantId, requirementId, applies }: { variantId: string; requirementId: string; applies: boolean }) =>
      applies ? api.clearVariantApplicability(variantId, requirementId) : api.markVariantApplies(variantId, requirementId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['variant-matrix'] }),
  })
  // VYB-0767: col 0 is the requirement key, cols 1..n are one per edition — Enter
  // on an edition cell toggles it, same as the click handler.
  const grid = useRovingGrid(matrix?.length ?? 0, 1 + (variants?.length ?? 0), (row, col) => {
    if (col === 0) return
    const r = matrix?.[row]
    const v = variants?.[col - 1]
    if (r && v) toggle.mutate({ variantId: v.id, requirementId: r.requirementId, applies: r.variantIds.includes(v.id) })
  })

  return (
    <>
      <div className="row" style={{ marginBottom: 14 }}>
        <div className="field" style={{ flex: 1 }}>
          <label className="label">New edition</label>
          <div style={{ display: 'flex', gap: 6 }}>
            <input className="input" style={{ flex: 1 }} value={name} onChange={(e) => setName(e.target.value)} />
            <button className="btn" disabled={!name.trim() || createVariant.isPending} onClick={() => createVariant.mutate()}>
              <Plus /> Add edition
            </button>
          </div>
        </div>
        <div className="field" style={{ flex: 2 }}>
          <label className="label">Requirement IDs to show in the matrix (comma-separated)</label>
          <input
            className="input" value={requirementIds} onChange={(e) => setRequirementIds(e.target.value)}
            onBlur={() => setActiveIds(requirementIds.split(',').map((s) => s.trim()).filter(Boolean))}
          />
        </div>
      </div>

      {activeIds.length === 0 && <Empty title="Paste requirement IDs above" desc="A requirement missing from every column applies to all editions." />}
      {matrix && matrix.length > 0 && variants && (
        <div className="tbl-wrap">
          <table
            className="tbl" role="grid" aria-rowcount={matrix.length} aria-colcount={1 + variants.length}
            onKeyDown={grid.onKeyDown}
          >
            <thead><tr role="row"><th>Requirement</th>{variants.map((v) => <th key={v.id}>{v.name}</th>)}</tr></thead>
            <tbody>
              {matrix.map((row, rowIdx) => (
                <tr key={row.requirementId} role="row" style={{ cursor: 'default' }}>
                  <td className="mono" {...grid.cellProps(rowIdx, 0, { fontSize: 11 })}>{row.key}</td>
                  {variants.map((v, colIdx) => {
                    const applies = row.variantIds.length === 0 || row.variantIds.includes(v.id)
                    const specific = row.variantIds.length > 0
                    return (
                      <td key={v.id} {...grid.cellProps(rowIdx, colIdx + 1, { textAlign: 'center', cursor: 'pointer' })}
                        onClick={() => toggle.mutate({ variantId: v.id, requirementId: row.requirementId, applies: row.variantIds.includes(v.id) })}>
                        <span className={applies ? 'pip on' : 'pip'} title={specific ? 'Edition-specific' : 'Applies to all editions'}>
                          {applies ? (specific ? '●' : '○') : ''}
                        </span>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint muted" style={{ marginTop: 8 }}>○ applies to every edition by default · ● scoped explicitly to this edition. Click a cell to toggle.</p>
    </>
  )
}

/** VYB-0520/0480–0482. */
function DeploymentTab() {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [ordinal, setOrdinal] = useState(0)
  const [selectedEnv, setSelectedEnv] = useState('')
  const [selectedDeployment, setSelectedDeployment] = useState('')
  const { data: environments } = useQuery({ queryKey: ['environments'], queryFn: api.environments })
  const { data: deployments } = useQuery({
    queryKey: ['deployments', selectedEnv], queryFn: () => api.deploymentsFor(selectedEnv), enabled: !!selectedEnv,
  })
  const { data: presence } = useQuery({
    queryKey: ['presence', selectedDeployment], queryFn: () => api.deploymentPresence(selectedDeployment),
    enabled: !!selectedDeployment,
  })

  const createEnv = useMutation({
    mutationFn: () => api.createEnvironment(name, ordinal),
    onSuccess: () => { setName(''); void qc.invalidateQueries({ queryKey: ['environments'] }) },
  })

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
      <div>
        <h4 className="section-h">Environments</h4>
        {environments && environments.length === 0 && <Empty title="No environments yet" desc="Add one below." />}
        {environments?.map((e) => (
          <div key={e.id} className="list-item" style={{ cursor: 'pointer', borderColor: e.id === selectedEnv ? 'var(--brand)' : undefined }}
            onClick={() => { setSelectedEnv(e.id); setSelectedDeployment('') }}>
            <span className="mono muted" style={{ fontSize: 10 }}>{e.ordinal}</span>
            <span style={{ flex: 1 }}>{e.name}</span>
          </div>
        ))}
        <div className="row" style={{ marginTop: 8 }}>
          <input className="input" placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} />
          <input className="input" style={{ maxWidth: 70 }} type="number" value={ordinal} onChange={(e) => setOrdinal(Number(e.target.value))} />
          <button className="btn" disabled={!name.trim() || createEnv.isPending} onClick={() => createEnv.mutate()}><Plus /></button>
        </div>
      </div>
      <div>
        <h4 className="section-h">Builds</h4>
        <p className="hint muted">Build labels are borrowed from CI — ingested via the service-account-only endpoint, never entered here.</p>
        {selectedEnv && deployments && deployments.length === 0 && <Empty title="No deployments recorded" desc="" />}
        {deployments?.map((d) => (
          <div key={d.id} className="list-item" style={{ cursor: 'pointer', borderColor: d.id === selectedDeployment ? 'var(--brand)' : undefined }}
            onClick={() => setSelectedDeployment(d.id)}>
            <span className="mono" style={{ flex: 1, fontSize: 11 }}>{d.buildLabel}</span>
            {!d.succeeded && <span className="badge" style={{ color: 'var(--crit)' }}>Failed</span>}
            <span className="muted" style={{ fontSize: 10 }}>{new Date(d.deployedAt).toLocaleDateString()}</span>
          </div>
        ))}
      </div>
      <div>
        <h4 className="section-h">Requirements present</h4>
        {selectedDeployment && presence && presence.length === 0 && <Empty title="None derived from code links" desc="No commit in this build carried a Requirement trailer." />}
        {presence?.map((p) => (
          <div key={p.requirementId} className="list-item">
            <span className="mono muted" style={{ fontSize: 10 }}>{p.key}</span>
            <span className="mono muted" style={{ fontSize: 10 }}>rev {p.revision}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

/** VYB-0521/0484. */
function NotesTab({ releaseId }: { releaseId: string }) {
  const { data } = useQuery({ queryKey: ['release-notes', releaseId], queryFn: () => api.releaseNotes(releaseId) })
  const [exportError, setExportError] = useState<string | undefined>()
  const [exporting, setExporting] = useState<'markdown' | 'docx' | null>(null)
  if (!data) return <p className="eyebrow">Loading…</p>

  // VYB-0930: the same notes, as a file. The token cannot ride on a plain link, so the file is fetched and then saved.
  const download = async (format: 'markdown' | 'docx') => {
    setExporting(format)
    setExportError(undefined)
    try {
      const { blob, filename } = await api.downloadReleaseNotes(releaseId, format)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      setExportError(e instanceof ApiError ? (e.detail ?? e.title) : 'Could not export the release notes.')
    } finally {
      setExporting(null)
    }
  }

  const capabilities = Object.keys(data.approvedByCapability)
  return (
    <>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
        <button className="btn" disabled={exporting !== null} onClick={() => void download('markdown')}>
          {exporting === 'markdown' ? 'Exporting…' : 'Export as Markdown'}
        </button>
        <button className="btn" disabled={exporting !== null} onClick={() => void download('docx')}>
          {exporting === 'docx' ? 'Exporting…' : 'Export as Word'}
        </button>
        <span className="hint muted">Approved requirements by capability; anything held short of approval is listed separately.</span>
      </div>
      {exportError && <p className="err-text" role="alert">{exportError}</p>}
      {capabilities.length === 0 && data.held.length === 0 && <Empty title="Nothing committed to this release" desc="" />}
      {capabilities.map((cap) => (
        <div key={cap} style={{ marginBottom: 16 }}>
          <h4 className="section-h">{cap}</h4>
          {data.approvedByCapability[cap].map((item) => (
            <div key={item.requirementId} className="list-item">
              <span className="mono muted" style={{ fontSize: 10 }}>{item.key}</span>
              <span style={{ flex: 1 }}>{item.title}</span>
            </div>
          ))}
        </div>
      ))}
      {data.held.length > 0 && (
        <div className="card" style={{ borderColor: 'var(--high-bd)' }}>
          <strong style={{ color: 'var(--high)' }}>Held — not approved</strong>
          <p className="hint muted">Committed to this release but not yet approved — listed here, never silently omitted.</p>
          {data.held.map((item) => (
            <div key={item.requirementId} className="list-item">
              <span className="mono muted" style={{ fontSize: 10 }}>{item.key}</span>
              <span style={{ flex: 1 }}>{item.title}</span>
              <span className="muted" style={{ fontSize: 11 }}>{item.capabilityName}</span>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
