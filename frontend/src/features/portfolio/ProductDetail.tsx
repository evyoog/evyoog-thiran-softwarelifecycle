import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Plus, Pencil, Archive } from 'lucide-react'
import { ApiError, api, type ProductSummary } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { markOf } from './marks'
import { covColor, verifiedPctOf } from './coverage'

/**
 * The layout prototype's second portfolio state — one product, its apps — which is
 * what its OPEN button leads to. Previously OPEN dropped straight into the Hierarchy
 * tab's three columns, a different design entirely.
 *
 * Every figure comes from the same `/products/dashboard` payload the card grid already
 * has, so opening a product costs no extra request and the numbers here cannot
 * disagree with the numbers on the card that was clicked.
 */
export function ProductDetail({ p, onBack, onOpenApp, onOpenRequirements, onOpenGaps, onEditApp, onArchiveApp }: {
  p: ProductSummary
  onBack: () => void
  onOpenApp: (applicationId: string) => void
  onOpenRequirements: () => void
  onOpenGaps: () => void
  /** VYB-0666: rename and archive, reachable from the card itself rather than only from
      the Manage tab's three-column editor — Portfolio → Product → App is where the user
      is already looking at the app, so the action belongs here. */
  onEditApp: (applicationId: string, currentName: string) => void
  onArchiveApp: (applicationId: string, name: string) => void
}) {
  const [showNewApp, setShowNewApp] = useState(false)
  const m = markOf(p.mark)
  const Icon = m.icon
  const pct = Math.round(p.verifiedRatio * 100)

  return (
    <>
      <div className="pd-bar">
        <div style={{ minWidth: 0 }}>
          <div className="crumb">
            <button onClick={onBack}>Product Portfolio</button>
            <span className="sep">▸</span>
            <span className="cur">{p.name}</span>
          </div>
        </div>
        <div className="sp" />
        <div className="pf-actions" style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={onBack}>All products</button>
          <button className="btn pri" onClick={() => setShowNewApp(true)}><Plus /> New app</button>
        </div>
      </div>

      <div className={`pd-head${m.accent ? ` ${m.accent}` : ''}`}>
        <span className="pd-glyph"><Icon size={27} color={m.color} /></span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="pd-name">{p.name}</div>
          <div className="pd-tag">{p.vertical || 'No vertical recorded'}</div>
          <p className="pd-desc">{p.purpose || 'No description recorded.'}</p>
        </div>
        <div className="pd-stats">
          <div className="pf-stat">
            <span className="pf-stat-v">{p.reqCount.toLocaleString()}</span>
            <span className="pf-stat-l">Reqs</span>
          </div>
          <div className="pf-stat">
            <span className="pf-stat-v" style={{ color: covColor(pct) }}>{pct}%</span>
            <span className="pf-stat-l">Verified</span>
          </div>
          <div className="pf-stat">
            <span className="pf-stat-v" style={{ color: p.gapCount > 0 ? 'var(--crit)' : 'var(--ok)' }}>{p.gapCount}</span>
            <span className="pf-stat-l">Gaps</span>
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '0 0 10px' }}>
        <span className="eyebrow">Apps in this product</span>
        <div className="sp" />
        <span className="mono" style={{ fontSize: 10.5, color: 'var(--tx-3)' }}>
          {p.appCount} app{p.appCount === 1 ? '' : 's'} · {p.reqCount.toLocaleString()} requirement{p.reqCount === 1 ? '' : 's'} · {p.gapCount} open gap{p.gapCount === 1 ? '' : 's'}
        </span>
      </div>

      {p.apps.length === 0 ? (
        <div className="empty">
          <h4>{p.name} has no apps yet</h4>
          <p>A product is a container. Add its first app to start filing requirements.</p>
          <button className="btn pri" style={{ marginTop: 12 }} onClick={() => setShowNewApp(true)}>
            <Plus /> Add the first app
          </button>
        </div>
      ) : (
        <div className="app-grid">
          {p.apps.map((a) => {
            const vpct = verifiedPctOf(a)
            return (
              <div key={a.id} className={`app-card${m.accent ? ` ${m.accent}` : ''}`}>
                <div className="app-h">
                  <span className="app-nm">{a.name}</span>
                  <span className="app-h-acts">
                    <button className="icon-btn" title="Rename this app" onClick={() => onEditApp(a.id, a.name)}>
                      <Pencil />
                    </button>
                    <button className="icon-btn" title="Archive this app" onClick={() => onArchiveApp(a.id, a.name)}>
                      <Archive />
                    </button>
                  </span>
                  {/* Same three states as the prototype — red above five, grey at one
                      to five, green at none — and none of them amber, which this
                      codebase reserves for AI output. */}
                  {a.gapCount === 0 ? (
                    <span className="app-bdg b-ok"><span className="d" />clear</span>
                  ) : (
                    <span className={`app-bdg ${a.gapCount > 5 ? 'b-crit' : 'b-some'}`}>
                      <span className="d" />{a.gapCount} gap{a.gapCount === 1 ? '' : 's'}
                    </span>
                  )}
                </div>
                <div className="app-row">
                  <span className="app-lbl">Requirements</span>
                  {/* Full width, as in the prototype: this row is a count, not a ratio
                      — there is no denominator an app's requirement total is a share
                      of. The number to its right is the figure being reported. */}
                  <span className="app-mini"><i style={{ width: '100%', background: 'var(--prod)' }} /></span>
                  <span className="app-val">{a.reqCount.toLocaleString()}</span>
                </div>
                <div className="app-row">
                  <span className="app-lbl">Verified</span>
                  <span className="app-mini"><i style={{ width: `${vpct}%`, background: covColor(vpct) }} /></span>
                  <span className="app-val" style={{ color: covColor(vpct) }}>{vpct}%</span>
                </div>
                <div className="app-foot">
                  <button className="btn" onClick={() => onOpenApp(a.id)}>Open app</button>
                  {/* The requirements list filters by capability, not application, so
                      neither of these can be scoped to this app yet. They open the real
                      views unfiltered, and say so on hover rather than implying a scope
                      they don't apply. */}
                  <button
                    className="btn" onClick={onOpenRequirements}
                    title="Opens the requirements list. It cannot be filtered to a single app yet — only to a capability."
                  >
                    Requirements
                  </button>
                  {a.gapCount > 0 && (
                    <button
                      className="btn" onClick={onOpenGaps}
                      title="Opens the coverage matrix, where gaps are listed. It is not scoped to this app yet."
                    >
                      Gaps
                    </button>
                  )}
                </div>
              </div>
            )
          })}
          <button className="add-card" onClick={() => setShowNewApp(true)}>
            <span className="ring"><Plus size={16} /></span>
            <span className="t">New app</span>
            <span className="s">Add an application under {p.name}</span>
          </button>
        </div>
      )}

      {showNewApp && <NewAppModal productId={p.id} productName={p.name} onClose={() => setShowNewApp(false)} />}
    </>
  )
}

/**
 * Creating an app was previously only possible from the Hierarchy tab's inline field.
 * The drill-down's own "New app" button needs the same call, so it gets a dialog
 * through {@link Modal} (focus trap + restore, VYB-0768) rather than a second inline
 * field, and it surfaces the real failure instead of a generic one.
 */
function NewAppModal({ productId, productName, onClose }: {
  productId: string; productName: string; onClose: () => void
}) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState('')

  const create = useMutation({
    mutationFn: () => api.createApplication(productId, { name: name.trim(), description: description.trim() || undefined }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
      void qc.invalidateQueries({ queryKey: ['applications', productId] })
      onClose()
    },
    onError: (e: unknown) => {
      setError(e instanceof ApiError ? `Not saved — ${e.detail ?? e.title} (${e.status}).` : 'Not saved — the request failed before reaching the server.')
    },
  })

  return (
    <Modal title={`New app in ${productName}`} onClose={onClose} maxWidth={420}>
      <h3>New app</h3>
      <p className="muted" style={{ fontSize: 12, margin: '0 0 14px' }}>
        Under {productName}. Capabilities and requirements are filed beneath the app.
      </p>
      <div className="field">
        <label className="label" htmlFor="new-app-name">Name</label>
        <input
          id="new-app-name" className="input" autoFocus value={name}
          onChange={(e) => { setName(e.target.value); setError('') }}
          onKeyDown={(e) => { if (e.key === 'Enter' && name.trim()) create.mutate() }}
        />
      </div>
      <div className="field">
        <label className="label" htmlFor="new-app-description">Description (optional)</label>
        <textarea
          id="new-app-description" className="textarea" style={{ minHeight: 56 }} value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="What does this app cover?"
        />
      </div>
      {error && <p className="err-text">{error}</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>
          {create.isPending ? 'Saving…' : 'Create app'}
        </button>
      </div>
    </Modal>
  )
}