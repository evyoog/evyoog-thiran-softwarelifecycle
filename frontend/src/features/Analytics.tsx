import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, RefreshCw } from 'lucide-react'
import { api, type ChangeImpact, type Finding, type FindingState, type SweepSummary } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { SeverityBadge } from '@/shared/ui/Badges'
import { Modal } from '@/shared/ui/Modal'
import { useRovingGrid } from '@/shared/ui/useRovingGrid'

const STATES: FindingState[] = ['OPEN', 'ACCEPTED', 'DISMISSED', 'RESOLVED']
type Tab = 'findings' | 'suspect' | 'rules' | 'change-requests' | 'spine'

/**
 * VYB-0220/0221/0223/0224 and VYB-0214 (suspect links, filed under "documents/matrix/
 * graph" in the spec but really just findings filtered to one rule — reusing the
 * findings screen's own accept/dismiss/reopen plumbing rather than building a second
 * copy of it). VYB-0222 (the lifecycle coverage spine) is the 'spine' tab below.
 */
export function Analytics() {
  const [tab, setTab] = useState<Tab>('findings')
  const sweep = useMutation({ mutationFn: () => api.triggerSweep() })

  return (
    <Page
      title="Analytics"
      desc="Every place the requirement chain breaks. Findings are proposals; nothing changes until you accept it."
      actions={
        <button className="btn pri" disabled={sweep.isPending} onClick={() => sweep.mutate()}>
          <RefreshCw /> Run sweep
        </button>
      }
    >
      {sweep.isError && (
        <p className="err-text">
          Could not run — a sweep may already be in progress, or one finished too recently. Try again shortly.
        </p>
      )}
      {sweep.data && <SweepResultCard result={sweep.data} />}

      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        {(['findings', 'suspect', 'rules', 'change-requests', 'spine'] as Tab[]).map((t) => (
          <button key={t} className={`btn${t === tab ? ' pri' : ''}`} onClick={() => setTab(t)}>
            {t === 'findings' ? 'Findings' : t === 'suspect' ? 'Suspect links' : t === 'rules' ? 'Rules'
              : t === 'change-requests' ? 'Change requests' : 'Lifecycle spine'}
          </button>
        ))}
      </div>

      {tab === 'findings' && <FindingsTab />}
      {tab === 'suspect' && <SuspectLinksTab />}
      {tab === 'rules' && <RulesTab />}
      {tab === 'change-requests' && <ChangeRequestsTab />}
      {tab === 'spine' && <SpineTab />}
    </Page>
  )
}

/**
 * VYB-0222: how many requirements have reached each of the six lifecycle stages,
 * across the whole register — a real rollup query (LifecycleHistoryService.spine),
 * not the per-requirement /lifecycle endpoint called once per row.
 */
function SpineTab() {
  const { data: spine, isLoading } = useQuery({ queryKey: ['lifecycle-spine'], queryFn: api.lifecycleSpine })
  const STAGE_LABEL: Record<string, string> = {
    AUTHORING: 'Authored', REVIEW: 'Reviewed', APPROVAL: 'Approved',
    DEVELOPMENT: 'Implemented', VERIFICATION: 'Verified', DEPLOYMENT: 'Deployed',
  }
  const total = spine?.[0]?.count ?? 0

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (!spine || total === 0) {
    return <Empty title="No requirements yet" desc="The spine has nothing to roll up until requirements exist." />
  }

  return (
    <div>
      <p className="hint muted" style={{ marginBottom: 14 }}>
        Each stage counts requirements that have reached it at all — not a snapshot of current status. A requirement
        can be far along and still show in an earlier bar too (it passed through that stage on the way).
      </p>
      {spine.map((s, i) => {
        const pct = total > 0 ? Math.round((s.count / total) * 100) : 0
        const dropFromPrev = i > 0 ? spine[i - 1].count - s.count : 0
        return (
          <div key={s.stage} style={{ marginBottom: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, marginBottom: 4 }}>
              <span>{STAGE_LABEL[s.stage] ?? s.stage}</span>
              <span className="mono">{s.count} <span className="muted">({pct}%)</span></span>
            </div>
            <div style={{ background: 'var(--bg-2)', borderRadius: 4, height: 18, overflow: 'hidden' }}>
              <div style={{ background: 'var(--ai)', height: '100%', width: `${pct}%`, transition: 'width .2s' }} />
            </div>
            {i > 0 && dropFromPrev > 0 && (
              <div className="hint muted" style={{ fontSize: 11, marginTop: 2 }}>
                {dropFromPrev} fewer than {STAGE_LABEL[spine[i - 1].stage] ?? spine[i - 1].stage}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

function SweepResultCard({ result }: { result: SweepSummary[] }) {
  return (
    <div className="card" style={{ marginBottom: 14 }}>
      <div className="eyebrow" style={{ marginBottom: 6 }}>Last sweep</div>
      {result.map((r) => (
        <div key={r.ruleKey} className="mono" style={{ fontSize: 11.5 }}>
          {r.unavailable ? (
            <span style={{ color: 'var(--high)' }}>
              {r.ruleKey}: unavailable this cycle — existing findings left untouched, not resolved
            </span>
          ) : (
            <>{r.ruleKey}: {r.opened} opened · {r.refreshed} refreshed · {r.reopened} reopened · {r.resolved} resolved</>
          )}
        </div>
      ))}
    </div>
  )
}

/** VYB-0650: an amber "AI" pill with the confidence figure — click to reveal VYB-0651's provenance line. */
function AiBadge({ confidence, model }: { confidence: number; model?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <button
        type="button"
        className="badge"
        style={{ background: 'var(--ai-dim)', color: 'var(--ai-tx)', border: '1px solid var(--ai-tx)', cursor: model ? 'pointer' : 'default' }}
        title={model ? 'Click to see the model and prompt version' : 'AI-derived finding'}
        onClick={() => model && setOpen((o) => !o)}
      >
        AI · {Math.round(confidence * 100)}%
      </button>
      {open && model && <span className="mono muted" style={{ fontSize: 10 }}>{model}</span>}
    </span>
  )
}

/**
 * VYB-0654: for a duplicate/conflict finding, the "other side" the detector compared
 * against — fetched and shown next to the finding's own object so both texts are visible
 * without navigating away first.
 */
function DuplicateCompare({ f }: { f: Finding }) {
  const otherId = f.ruleKey === 'dup' ? f.discriminator?.split('|')[1] : f.discriminator
  const navigate = useNavigate()
  const mine = useQuery({ queryKey: ['requirement', f.objectId], queryFn: () => api.requirement(f.objectId), enabled: !!otherId })
  const other = useQuery({ queryKey: ['requirement', otherId], queryFn: () => api.requirement(otherId as string), enabled: !!otherId })

  if (!otherId) return null
  if (mine.isLoading || other.isLoading) return <p className="eyebrow">Loading both sides…</p>
  if (!mine.data || !other.data) return null

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 8 }}>
      {[mine.data, other.data].map((r, i) => (
        <div key={r.id} className="card" style={{ background: 'var(--surface-2)' }}>
          <div className="mono muted" style={{ fontSize: 10, marginBottom: 4 }}>{r.key}{i === 1 ? ' (other side)' : ''}</div>
          <div style={{ fontWeight: 600, fontSize: 12.5, marginBottom: 4 }}>{r.title}</div>
          <p style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{r.statement}</p>
          {i === 1 && (
            <button className="btn" style={{ marginTop: 8 }} onClick={() => navigate(`/requirements/${r.id}`)}>
              Open this one instead
            </button>
          )}
        </div>
      ))}
    </div>
  )
}

function FindingsTab() {
  const qc = useQueryClient()
  const [state, setState] = useState<FindingState>('OPEN')
  const [ruleKey, setRuleKey] = useState<string | null>(null)
  const [dismissing, setDismissing] = useState<string | null>(null)
  const [dismissReason, setDismissReason] = useState('')

  // Same query the Rules tab already runs — React Query dedupes/shares it rather
  // than issuing a second request. Rendered as a persistent set of category tiles
  // (real names, real counts — 0 where nothing's fired yet) rather than only ever
  // showing the taxonomy once something has, so this tab teaches what's being
  // watched for even on a clean register.
  const { data: rules } = useQuery({ queryKey: ['rules'], queryFn: api.rules })

  const { data, isLoading } = useQuery({
    queryKey: ['findings', state, ruleKey],
    queryFn: () => api.findings({ state, ruleKey: ruleKey ?? undefined, size: 100 }),
  })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['findings'] })
  const accept = useMutation({ mutationFn: (id: string) => api.acceptFinding(id), onSuccess: invalidate })
  const reopen = useMutation({ mutationFn: (id: string) => api.reopenFinding(id), onSuccess: invalidate })
  // VYB-0758: a real modal collecting the reason, not window.prompt.
  const dismiss = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.dismissFinding(id, reason),
    onSuccess: () => { setDismissing(null); setDismissReason(''); invalidate() },
  })

  return (
    <>
      {rules && rules.length > 0 && (
        <div
          style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(180px,1fr))', gap: 8, marginBottom: 14 }}
        >
          {rules.map((r) => (
            <button
              key={r.key}
              className="card"
              style={{
                textAlign: 'left', cursor: 'pointer', padding: 12,
                borderColor: ruleKey === r.key ? 'var(--brand-bd)' : undefined,
                background: ruleKey === r.key ? 'var(--brand-dim)' : undefined,
              }}
              onClick={() => setRuleKey((k) => (k === r.key ? null : r.key))}
              title={r.description}
            >
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 6 }}>
                <span className="mono" style={{ fontSize: 20, color: ruleKey === r.key ? 'var(--brand)' : undefined }}>
                  {r.totalFindings}
                </span>
                <span className="mono muted" style={{ fontSize: 9, marginLeft: 'auto' }}>{r.technique}</span>
              </div>
              <div style={{ fontSize: 11.5, lineHeight: 1.35, marginBottom: 6 }}>{r.name}</div>
              <SeverityBadge severity={r.severity} />
            </button>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, marginBottom: 14, alignItems: 'center' }}>
        {STATES.map((s) => (
          <button key={s} className={`btn${s === state ? ' pri' : ''}`} onClick={() => setState(s)}>
            {s}
          </button>
        ))}
        {ruleKey && (
          <span className="badge" style={{ marginLeft: 4 }}>
            {rules?.find((r) => r.key === ruleKey)?.name ?? ruleKey}
            <button
              onClick={() => setRuleKey(null)}
              style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', padding: 0, marginLeft: 4 }}
            >
              ✕
            </button>
          </span>
        )}
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {data && data.content.length === 0 && (
        <Empty
          title={`No ${state.toLowerCase()} findings${ruleKey ? ' for this rule' : ''}`}
          desc={ruleKey ? 'Clear the rule filter above, or check another state.' : 'Run a sweep, or check another state above.'}
        />
      )}

      {data?.content.map((f) => (
        <div key={f.id} className="card" style={{ marginBottom: 10 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
            <SeverityBadge severity={f.severity} />
            {f.confidence != null && <AiBadge confidence={f.confidence} model={f.model} />}
            <strong>{f.title}</strong>
            <span className="mono muted" style={{ fontSize: 10 }}>{f.ruleKey}</span>
          </div>
          {f.detail && <p className="muted" style={{ fontSize: 12, margin: '0 0 6px', whiteSpace: 'pre-wrap' }}>{f.detail}</p>}
          {f.suggestion && (
            <p style={{ fontSize: 12, margin: '0 0 8px', color: 'var(--ai-tx)', whiteSpace: 'pre-wrap' }}>
              {f.suggestion}
            </p>
          )}
          {(f.ruleKey === 'dup' || f.ruleKey === 'conflict') && <DuplicateCompare f={f} />}
          {f.dismissReason && <p className="muted" style={{ fontSize: 11.5 }}>Dismissed: {f.dismissReason}</p>}

          {f.state === 'OPEN' && (
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn" onClick={() => accept.mutate(f.id)}>Accept</button>
              <button className="btn" onClick={() => setDismissing(f.id)}>Dismiss</button>
            </div>
          )}
          {f.state === 'DISMISSED' && (
            <button className="btn" onClick={() => reopen.mutate(f.id)}>Reopen</button>
          )}
        </div>
      ))}

      {dismissing && (
        <Modal onClose={() => setDismissing(null)} title="Dismiss finding">
          <h3>Dismiss this finding?</h3>
          <div className="field">
            <label className="label">Reason (required)</label>
            <input className="input" value={dismissReason} onChange={(e) => setDismissReason(e.target.value)} autoFocus />
          </div>
          <div className="actions">
            <button className="btn" onClick={() => setDismissing(null)}>Cancel</button>
            <button
              className="btn pri" disabled={!dismissReason.trim() || dismiss.isPending}
              onClick={() => dismiss.mutate({ id: dismissing, reason: dismissReason })}
            >
              Dismiss
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}

/** VYB-0214: links whose upstream moved after review — the "suspect" detector's findings, oldest first. */
function SuspectLinksTab() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({
    queryKey: ['findings', 'suspect-tab'],
    queryFn: () => api.findings({ ruleKey: 'suspect', state: 'OPEN', size: 100 }),
  })
  const review = useMutation({
    // objectId on a "suspect" finding is the trace_link's own id (SuspectLinkDetector).
    mutationFn: (linkId: string) => api.reviewLink(linkId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['findings'] }),
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (!data || data.content.length === 0) {
    return <Empty title="No suspect links" desc="Nothing has gone stale since it was last reviewed." />
  }

  return (
    <>
      {data.content.map((f) => (
        <div key={f.id} className="list-item">
          <span style={{ flex: 1 }}>{f.detail}</span>
          <button className="btn" disabled={review.isPending} onClick={() => review.mutate(f.objectId)}>
            Mark reviewed
          </button>
        </div>
      ))}
      <p className="hint muted" style={{ marginTop: 8 }}>
        Reviewing a link clears its own staleness immediately; the finding above resolves on the next sweep.
      </p>
    </>
  )
}

/**
 * VYB-0223/0617/0618/0652/0653: every detector, its technique and state, plus — for the
 * threshold-bearing AI rules — a real dismissal rate computed from actual dismissals,
 * top dismissal reasons, and threshold editing that previews its effect before it commits.
 */
function RulesTab() {
  const qc = useQueryClient()
  const [expanded, setExpanded] = useState<string | null>(null)
  const [draftThreshold, setDraftThreshold] = useState('')
  const [preview, setPreview] = useState<number | null>(null)
  const { data, isLoading } = useQuery({ queryKey: ['rules'], queryFn: api.rules })

  const invalidateRules = () => void qc.invalidateQueries({ queryKey: ['rules'] })
  const toggle = useMutation({
    mutationFn: ({ key, enabled }: { key: string; enabled: boolean }) => api.setRuleEnabled(key, enabled),
    onSuccess: invalidateRules,
  })
  const applyThreshold = useMutation({
    mutationFn: ({ key, threshold }: { key: string; threshold: number }) => api.setRuleThreshold(key, threshold),
    onSuccess: invalidateRules,
  })
  const previewMut = useMutation({
    mutationFn: ({ key, threshold }: { key: string; threshold: number }) => api.previewThresholdEffect(key, threshold),
    onSuccess: setPreview,
  })
  const dismissalReasons = useQuery({
    queryKey: ['dismissal-reasons', expanded],
    queryFn: () => api.dismissalReasons(expanded as string),
    enabled: !!expanded,
  })
  const noisyDefaults = useMutation({ mutationFn: api.applyNoisyDefaults, onSuccess: invalidateRules })

  const openRow = (key: string) => {
    setExpanded(expanded === key ? null : key)
    setDraftThreshold('')
    setPreview(null)
  }
  // VYB-0767: 8 columns incl. the trailing enable/disable-button cell; Enter
  // activates the same expand/collapse `openRow` the click handler already does.
  const grid = useRovingGrid(data?.length ?? 0, 8, (row) => {
    const key = data?.[row]?.key
    if (key) openRow(key)
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>

  return (
    <div className="tbl-wrap">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <p className="hint muted" style={{ margin: 0 }}>
          Dismissal rate is real dismissals over total findings raised, per rule.
        </p>
        <button className="btn" disabled={noisyDefaults.isPending} onClick={() => noisyDefaults.mutate()}>
          Apply noisy-detector defaults
        </button>
      </div>
      {noisyDefaults.data && (
        <div className="hint" style={{ marginBottom: 10 }}>
          {noisyDefaults.data.length === 0
            ? 'No detector is over the noisy ceiling — nothing changed.'
            : noisyDefaults.data.map((d) => (
                <div key={d.ruleKey} className="mono" style={{ fontSize: 11.5 }}>
                  {d.ruleKey}: {(d.rate * 100).toFixed(0)}% dismissed{d.disabled ? ' — disabled' : ''}
                </div>
              ))}
        </div>
      )}
      <table className="tbl" role="grid" aria-rowcount={data?.length ?? 0} aria-colcount={8} onKeyDown={grid.onKeyDown}>
        <thead>
          <tr role="row"><th>Rule</th><th>Technique</th><th>Phase</th><th>Severity</th><th>Findings</th><th>Dismissal rate</th><th>State</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((r, rowIdx) => (
            <Fragment key={r.key}>
              <tr role="row" style={{ cursor: 'pointer' }} onClick={() => openRow(r.key)}>
                <td {...grid.cellProps(rowIdx, 0)}>
                  <div style={{ fontWeight: 600 }}>{r.name}</div>
                  <div className="muted" style={{ fontSize: 11 }}>{r.description}</div>
                </td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 1)}>{r.technique}</td>
                <td className="muted" {...grid.cellProps(rowIdx, 2)}>{r.phase}</td>
                <td {...grid.cellProps(rowIdx, 3)}><SeverityBadge severity={r.severity} /></td>
                <td className="mono" {...grid.cellProps(rowIdx, 4)}>{r.totalFindings}</td>
                <td className="mono" {...grid.cellProps(rowIdx, 5, { color: r.dismissalRate > 0.5 ? 'var(--high)' : undefined })}>
                  {(r.dismissalRate * 100).toFixed(0)}%
                </td>
                <td {...grid.cellProps(rowIdx, 6)}>{r.enabled ? 'Enabled' : 'Disabled'}</td>
                <td {...grid.cellProps(rowIdx, 7)}>
                  <button
                    className="btn" disabled={toggle.isPending}
                    onClick={(e) => { e.stopPropagation(); toggle.mutate({ key: r.key, enabled: !r.enabled }) }}
                  >
                    {r.enabled ? 'Disable' : 'Enable'}
                  </button>
                </td>
              </tr>
              {expanded === r.key && (
                <tr role="row">
                  <td colSpan={8} style={{ background: 'var(--surface-2)' }}>
                    <div style={{ padding: '10px 4px' }}>
                      {r.threshold != null && (
                        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 10 }}>
                          <span className="muted" style={{ fontSize: 12 }}>Current threshold: {r.threshold}</span>
                          <input
                            className="input" style={{ width: 90 }} placeholder="new value" value={draftThreshold}
                            onChange={(e) => { setDraftThreshold(e.target.value); setPreview(null) }}
                          />
                          <button
                            className="btn" disabled={!draftThreshold || previewMut.isPending}
                            onClick={() => previewMut.mutate({ key: r.key, threshold: Number(draftThreshold) })}
                          >
                            Preview effect
                          </button>
                          {preview != null && (
                            <span className="hint" style={{ fontSize: 11.5 }}>
                              Would leave {preview} of {r.totalFindings} current finding(s) standing.
                            </span>
                          )}
                          <button
                            className="btn pri" disabled={!draftThreshold || applyThreshold.isPending}
                            onClick={() => applyThreshold.mutate({ key: r.key, threshold: Number(draftThreshold) })}
                          >
                            Apply
                          </button>
                        </div>
                      )}
                      <div className="eyebrow" style={{ marginBottom: 4 }}>Top dismissal reasons</div>
                      {dismissalReasons.isLoading && <p className="eyebrow">Loading…</p>}
                      {dismissalReasons.data && dismissalReasons.data.length === 0 && (
                        <p className="muted" style={{ fontSize: 12 }}>Nothing's been dismissed for this rule yet.</p>
                      )}
                      {dismissalReasons.data?.map((dr) => (
                        <div key={dr.reason} className="mono muted" style={{ fontSize: 11.5 }}>
                          {dr.count}× — {dr.reason}
                        </div>
                      ))}
                    </div>
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** VYB-0390–0393: scope, impact volume and state — impact is fetched before the approve action, not after. */
function ChangeRequestsTab() {
  const qc = useQueryClient()
  const [showRaise, setShowRaise] = useState(false)
  const [impacts, setImpacts] = useState<Record<string, ChangeImpact>>({})
  const { data, isLoading } = useQuery({ queryKey: ['change-requests'], queryFn: api.changeRequests })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['change-requests'] })
  const impact = useMutation({
    mutationFn: (id: string) => api.changeRequestImpact(id),
    onSuccess: (result, id) => setImpacts((prev) => ({ ...prev, [id]: result })),
  })
  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) => api.decideChangeRequest(id, approve),
    onSuccess: invalidate,
  })
  const apply = useMutation({ mutationFn: (id: string) => api.applyChangeRequest(id), onSuccess: invalidate })

  if (isLoading) return <p className="eyebrow">Loading…</p>

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
        <button className="btn pri" onClick={() => setShowRaise(true)}><Plus /> Raise a change request</button>
      </div>

      {data && data.length === 0 && <Empty title="No change requests" desc="Raise one above against an approved requirement." />}

      {data?.map((cr) => (
        <div key={cr.id} className="card" style={{ marginBottom: 10 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
            <span className="mono muted" style={{ fontSize: 10 }}>{cr.key}</span>
            <strong style={{ flex: 1 }}>{cr.title}</strong>
            <span className="badge">{cr.state}</span>
          </div>
          <p className="muted" style={{ fontSize: 12, marginBottom: 6 }}>{cr.rationale}</p>
          <div className="mono muted" style={{ fontSize: 10, marginBottom: 8 }}>
            Scope: {cr.scope.map((s) => s.slice(0, 8)).join(', ')}
          </div>

          {impacts[cr.id] && (
            <div className="hint" style={{ marginBottom: 8 }}>
              Impact: {impacts[cr.id].requirements} requirement(s) · {impacts[cr.id].tests} test(s) ·{' '}
              {impacts[cr.id].applications} application(s) · {impacts[cr.id].briefs} brief(s)
            </div>
          )}

          <div style={{ display: 'flex', gap: 8 }}>
            <button className="btn" disabled={impact.isPending} onClick={() => impact.mutate(cr.id)}>
              Compute impact
            </button>
            {cr.state === 'OPEN' && (
              <>
                <button
                  className="btn pri" disabled={!impacts[cr.id] || decide.isPending}
                  title={!impacts[cr.id] ? 'Compute impact first' : undefined}
                  onClick={() => decide.mutate({ id: cr.id, approve: true })}
                >
                  Approve
                </button>
                <button className="btn" disabled={decide.isPending} onClick={() => decide.mutate({ id: cr.id, approve: false })}>
                  Reject
                </button>
              </>
            )}
            {cr.state === 'APPROVED' && (
              <button className="btn pri" disabled={apply.isPending} onClick={() => apply.mutate(cr.id)}>
                Mark applied
              </button>
            )}
          </div>
        </div>
      ))}

      <p className="hint muted">
        "Mark applied" records that every scoped requirement has already been edited through its own PATCH with
        this change request's id — it doesn't apply content itself, since nothing here stages what the new text
        should be.
      </p>

      {showRaise && <RaiseChangeRequestModal onClose={() => setShowRaise(false)} onRaised={invalidate} />}
    </>
  )
}

function RaiseChangeRequestModal({ onClose, onRaised }: { onClose: () => void; onRaised: () => void }) {
  const [title, setTitle] = useState('')
  const [rationale, setRationale] = useState('')
  const [requirementIds, setRequirementIds] = useState('')

  const raise = useMutation({
    mutationFn: () => api.raiseChangeRequest({
      title, rationale, requirementIds: requirementIds.split(',').map((s) => s.trim()).filter(Boolean),
    }),
    onSuccess: () => { onRaised(); onClose() },
  })

  return (
    <Modal onClose={onClose} title="Raise a change request">
        <h3>Raise a change request</h3>
        <div className="field">
          <label className="label">Title</label>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="field">
          <label className="label">Rationale</label>
          <textarea className="textarea" rows={3} value={rationale} onChange={(e) => setRationale(e.target.value)} />
        </div>
        <div className="field">
          <label className="label">Requirement IDs (comma-separated — a picker arrives later)</label>
          <input className="input" value={requirementIds} onChange={(e) => setRequirementIds(e.target.value)} />
        </div>
        {raise.isError && <p className="err-text">Could not raise — check the requirement IDs.</p>}
        <div className="actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button
            className="btn pri" disabled={!title.trim() || !rationale.trim() || !requirementIds.trim() || raise.isPending}
            onClick={() => raise.mutate()}
          >
            Raise
          </button>
        </div>
    </Modal>
  )
}
