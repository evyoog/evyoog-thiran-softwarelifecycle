import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type AuthoringSignals, type RequirementPriority, type RequirementType, type RewriteSuggestion, type SimilarMatch } from '@/shared/api/client'
import { Page } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'

const TYPES: RequirementType[] = [
  'FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE', 'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE',
]
const PRIORITIES: RequirementPriority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

/**
 * VYB-0200/0201: cascading placement (product → application → capability) and live
 * lint while typing. VYB-0204: a pg_trgm similarity check on the same debounce.
 * VYB-0202/0203: a live gap preview and quality score, from the same debounce, via
 * {@code /requirements/authoring-signals} — a save-nothing preview computed fresh on
 * every pause, mirroring the real detectors' own rules rather than a second copy.
 * VYB-0205/0206: three save paths, and a submit confirm that only fires when
 * submitting would actually waste a reviewer's time.
 */
export function RequirementNew() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [statement, setStatement] = useState('')
  const [type, setType] = useState<RequirementType>('FUNCTIONAL')
  const [priority, setPriority] = useState<RequirementPriority>('MEDIUM')
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [capabilityId, setCapabilityId] = useState('')
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const [dismissedSimilar, setDismissedSimilar] = useState(false)
  const [confirmSubmit, setConfirmSubmit] = useState(false)
  const [addedAnotherMessage, setAddedAnotherMessage] = useState<string | null>(null)

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({
    queryKey: ['applications', productId],
    queryFn: () => api.applications(productId),
    enabled: !!productId,
  })
  const { data: capabilities } = useQuery({
    queryKey: ['capabilities', applicationId],
    queryFn: () => api.capabilities(applicationId),
    enabled: !!applicationId,
  })

  const [lintFindings, setLintFindings] = useState<{ term: string; suggestion: string }[]>([])
  useEffect(() => {
    if (!statement.trim()) { setLintFindings([]); return }
    const handle = setTimeout(() => {
      api.lint(statement).then((r) => setLintFindings(r.findings)).catch(() => setLintFindings([]))
    }, 300)
    return () => clearTimeout(handle)
  }, [statement])

  const [similar, setSimilar] = useState<SimilarMatch[]>([])
  useEffect(() => {
    setDismissedSimilar(false)
    if (statement.trim().length < 20) { setSimilar([]); return }
    const handle = setTimeout(() => {
      api.similarRequirements(statement).then(setSimilar).catch(() => setSimilar([]))
    }, 300)
    return () => clearTimeout(handle)
  }, [statement])

  // VYB-0202/0203: the live signals panel — quality score and which gaps would fire.
  const [signals, setSignals] = useState<AuthoringSignals | null>(null)
  useEffect(() => {
    if (!statement.trim()) { setSignals(null); return }
    const handle = setTimeout(() => {
      api.authoringSignals({ statement, criteriaCount: 0, hasCapability: !!capabilityId, hasUpstream: false })
        .then(setSignals).catch(() => setSignals(null))
    }, 300)
    return () => clearTimeout(handle)
  }, [statement, capabilityId])

  // VYB-0794 (Part 2): an explicit, button-triggered AI rewrite — never fired on a
  // keystroke, unlike the signals panel above.
  const [rewrite, setRewrite] = useState<RewriteSuggestion | null>(null)
  const rewriteMut = useMutation({
    mutationFn: () => api.rewriteSuggestion({ statement, criteriaCount: 0, hasUpstream: false }),
    onSuccess: setRewrite,
  })
  const applyRewrite = () => {
    if (!rewrite) return
    setStatement(rewrite.rewrittenStatement)
    setRewrite(null)
  }

  const create = useMutation({
    mutationFn: () =>
      api.createRequirement({
        title, statement, type, priority,
        capabilityId: capabilityId || undefined,
      }, idempotencyKey),
  })

  const canSubmit = title.trim().length > 0 && statement.trim().length > 0 && !create.isPending

  // VYB-0205 AC1: add-another retains placement, clears content. AC2: a fresh
  // idempotency key per save, so the allocator never sees the same key twice.
  const resetForAnother = () => {
    setTitle(''); setStatement(''); setLintFindings([]); setSimilar([]); setSignals(null); setRewrite(null)
    setIdempotencyKey(crypto.randomUUID())
  }

  const saveAsDraft = () => create.mutate(undefined, { onSuccess: (r) => navigate(`/requirements/${r.id}`) })

  const saveAndAddAnother = () => create.mutate(undefined, {
    onSuccess: (r) => { setAddedAnotherMessage(`${r.key} saved.`); resetForAnother() },
  })

  const submitForReview = () => create.mutate(undefined, {
    onSuccess: (r) => api.transitionRequirement(r.id, r.revision, 'IN_REVIEW').then(() => navigate(`/requirements/${r.id}`)),
  })

  // VYB-0206 AC1/AC2: confirm only when submitting would waste a reviewer's time —
  // an avoidable gap or a lint finding still open. AC3: the count still renders on
  // the button regardless of whether a confirm fires.
  const avoidableCount = (signals?.avoidableGaps.length ?? 0) + lintFindings.length
  const handleSubmitClick = () => { if (avoidableCount > 0) setConfirmSubmit(true); else submitForReview() }

  return (
    <Page
      eyebrow="Requirements"
      title="New requirement"
      desc="Placement follows product → application → capability. Saving unplaced is allowed."
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 24 }}>
        <div style={{ maxWidth: 640 }}>
          {addedAnotherMessage && <p className="hint" style={{ marginBottom: 10 }}>{addedAnotherMessage}</p>}
          <div className="row">
            <div className="field">
              <label className="label">Product</label>
              <select className="select" value={productId}
                onChange={(e) => { setProductId(e.target.value); setApplicationId(''); setCapabilityId('') }}>
                <option value="">— unplaced —</option>
                {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
            <div className="field">
              <label className="label">Application</label>
              <select className="select" value={applicationId} disabled={!productId}
                onChange={(e) => { setApplicationId(e.target.value); setCapabilityId('') }}>
                <option value="">—</option>
                {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </div>
            <div className="field">
              <label className="label">Capability</label>
              <select className="select" value={capabilityId} disabled={!applicationId}
                onChange={(e) => setCapabilityId(e.target.value)}>
                <option value="">—</option>
                {capabilities?.filter((c) => !c.archived).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </div>
          </div>

          <div className="field">
            <label className="label">Title</label>
            <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>

          <div className="field">
            <label className="label">Statement</label>
            <textarea
              className="textarea"
              rows={4}
              value={statement}
              onChange={(e) => setStatement(e.target.value)}
              placeholder="Shall…"
            />
            {lintFindings.length > 0 && (
              <div className="hint" style={{ color: 'var(--ai-tx)' }}>
                {lintFindings.map((f) => (
                  <div key={f.term}>
                    <strong>'{f.term}'</strong> — {f.suggestion}
                  </div>
                ))}
              </div>
            )}
          </div>

          {!dismissedSimilar && similar.length > 0 && (
            <div className="card" style={{ borderColor: 'var(--high-bd)', marginBottom: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <strong style={{ color: 'var(--high)' }}>This looks like it might already exist</strong>
                <button className="btn" style={{ padding: 4 }} onClick={() => setDismissedSimilar(true)}>Dismiss</button>
              </div>
              {similar.map((s) => (
                <div key={s.id} className="list-item">
                  <span className="mono muted" style={{ fontSize: 10 }}>{s.key}</span>
                  <span style={{ flex: 1 }}>{s.title}</span>
                  <span className="mono muted" style={{ fontSize: 10 }}>{Math.round(s.score * 100)}% similar</span>
                  <button className="btn" style={{ padding: 4 }} onClick={() => navigate(`/requirements/${s.id}`)}>Open</button>
                </div>
              ))}
              <p className="hint muted" style={{ marginTop: 6 }}>
                This is advisory only — saving is never blocked on it.
              </p>
            </div>
          )}

          <div className="row">
            <div className="field">
              <label className="label">Type</label>
              <select className="select" value={type} onChange={(e) => setType(e.target.value as RequirementType)}>
                {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div className="field">
              <label className="label">Priority</label>
              <select className="select" value={priority} onChange={(e) => setPriority(e.target.value as RequirementPriority)}>
                {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
          </div>

          {create.isError && <p className="err-text">Could not save. Check the fields and try again.</p>}

          <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
            <button className="btn" disabled={!canSubmit} onClick={saveAsDraft}>
              Save as draft
            </button>
            <button className="btn" disabled={!canSubmit} onClick={saveAndAddAnother}>
              Save &amp; add another
            </button>
            <button className="btn pri" disabled={!canSubmit} onClick={handleSubmitClick}>
              Submit for review{avoidableCount > 0 ? ` (${avoidableCount} gap${avoidableCount === 1 ? '' : 's'})` : ''}
            </button>
            <button className="btn" onClick={() => navigate('/requirements')}>Cancel</button>
          </div>
        </div>

        {/* VYB-0202/0203: the live signals panel. */}
        <div>
          <div className="card">
            <div className="eyebrow" style={{ marginBottom: 6 }}>Quality score</div>
            {signals ? (
              <>
                <div style={{ fontSize: 28, fontWeight: 700 }}>{signals.qualityScore}</div>
                <div className="mono muted" style={{ fontSize: 10.5, lineHeight: 1.6 }}>
                  {Object.entries(signals.qualityBreakdown).map(([k, v]) => (
                    <div key={k}>{k}: {v >= 0 ? '+' : ''}{v}</div>
                  ))}
                </div>
                <button
                  className="btn" style={{ marginTop: 10, width: '100%' }}
                  disabled={rewriteMut.isPending || !statement.trim()}
                  onClick={() => { setRewrite(null); rewriteMut.mutate() }}
                >
                  {rewriteMut.isPending ? 'Asking AI…' : 'Suggest rewrite (AI)'}
                </button>
                {rewriteMut.isError && (
                  <p className="hint" style={{ color: 'var(--crit)', fontSize: 11, marginTop: 6 }}>
                    Could not get a suggestion — the AI provider may be unreachable or not configured.
                  </p>
                )}
                {rewrite && (
                  <div className="card" style={{ marginTop: 10, background: 'var(--bg-2)' }}>
                    <div className="eyebrow" style={{ marginBottom: 4 }}>Suggested rewrite ({rewrite.model})</div>
                    <p style={{ fontSize: 12.5, marginBottom: 6 }}>{rewrite.rewrittenStatement}</p>
                    {rewrite.changes.length > 0 && (
                      <ul className="muted" style={{ fontSize: 11, margin: '0 0 8px 16px' }}>
                        {rewrite.changes.map((c, i) => <li key={i}>{c}</li>)}
                      </ul>
                    )}
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button className="btn pri" style={{ flex: 1 }} onClick={applyRewrite}>Use this</button>
                      <button className="btn" style={{ flex: 1 }} onClick={() => setRewrite(null)}>Dismiss</button>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <p className="muted" style={{ fontSize: 12 }}>Start typing a statement to see a score.</p>
            )}
          </div>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="eyebrow" style={{ marginBottom: 6 }}>Gaps on save</div>
            {signals && signals.avoidableGaps.length > 0 && (
              <>
                <div className="muted" style={{ fontSize: 11, marginBottom: 4 }}>Avoidable — fixable now</div>
                {signals.avoidableGaps.map((g) => (
                  <p key={g.ruleKey} style={{ fontSize: 12, color: 'var(--high)', margin: '0 0 6px' }}>{g.reason}</p>
                ))}
              </>
            )}
            {signals && signals.expectedGaps.length > 0 && (
              <>
                <div className="muted" style={{ fontSize: 11, marginBottom: 4, marginTop: 8 }}>Expected — not a defect yet</div>
                {signals.expectedGaps.map((g) => (
                  <p key={g.ruleKey} className="muted" style={{ fontSize: 12, margin: '0 0 6px' }}>{g.reason}</p>
                ))}
              </>
            )}
            {!signals && <p className="muted" style={{ fontSize: 12 }}>Nothing to preview yet.</p>}
          </div>
        </div>
      </div>

      {confirmSubmit && (
        <Modal onClose={() => setConfirmSubmit(false)} title="Submit with open gaps?">
          <h3>Submit with {avoidableCount} open gap{avoidableCount === 1 ? '' : 's'}?</h3>
          <p className="muted" style={{ fontSize: 12.5 }}>
            A reviewer will see these too. Fixing them first usually saves a review round-trip.
          </p>
          <div className="actions">
            <button className="btn" onClick={() => setConfirmSubmit(false)}>Go back and fix</button>
            <button className="btn pri" onClick={() => { setConfirmSubmit(false); submitForReview() }}>Submit anyway</button>
          </div>
        </Modal>
      )}
    </Page>
  )
}
