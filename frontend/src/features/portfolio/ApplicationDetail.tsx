import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Plus, Pencil, Archive } from 'lucide-react'
import {
  ApiError, api, type Application, type Capability, type ProductAppSummary, type ProductSummary,
} from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { Empty } from '@/shared/ui/Page'
import { markOf } from './marks'
import { covColor, verifiedPctOf } from './coverage'
import { invalidateCapabilities } from './invalidate'
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog'

/**
 * The layout prototype's third portfolio state — one application, its metadata and its
 * capabilities — which is what OPEN APP leads to. It previously dropped into the
 * Hierarchy tab's three columns, a different design entirely.
 *
 * <p>Every figure comes from the same `/products/dashboard` payload the card that was
 * clicked already had, plus one capability rollup computed by the same query shape, so
 * the cards cannot disagree with the header above them.
 *
 * <p>The prototype's metadata strip carries eight fields. Only the ones this product
 * genuinely stores are filled; the rest render "not recorded" rather than a plausible
 * blank (Principle 8) — inventing an owner or a target release here would put a fact on
 * screen that nothing in the database backs.
 */
export function ApplicationDetail({ product, app, onBack, onBackToProduct, onOpenRequirements }: {
  product: ProductSummary
  app: ProductAppSummary
  onBack: () => void
  onBackToProduct: () => void
  onOpenRequirements: (capability?: { id: string; name: string }) => void
}) {
  const qc = useQueryClient()
  const [showNewCap, setShowNewCap] = useState(false)
  // VYB-0666: rename and archive, reachable from the capability card itself — the same
  // pair the app card in the product drill-down now has, so both levels behave alike.
  const [renamingCap, setRenamingCap] = useState<{ id: string; name: string; code?: string; description?: string } | null>(null)
  const [archivingCap, setArchivingCap] = useState<{ id: string; name: string } | null>(null)
  const m = markOf(product.mark)
  const Icon = m.icon
  const pct = verifiedPctOf(app)

  const { data: caps, isLoading } = useQuery({
    queryKey: ['capability-summary', app.id],
    queryFn: () => api.capabilitySummary(app.id),
  })
  // The description and archived flag live on the application record itself, which the
  // dashboard rollup does not carry.
  const { data: applications } = useQuery({
    queryKey: ['applications', product.id],
    queryFn: () => api.applications(product.id),
  })
  const record: Application | undefined = applications?.find((a) => a.id === app.id)
  // Same reasoning: capability description lives on the real record, not the
  // req-count/gap-count rollup `caps` carries.
  const { data: capabilityRecords } = useQuery({
    queryKey: ['capabilities', app.id],
    queryFn: () => api.capabilities(app.id),
  })
  const capabilityRecord = (id: string): Capability | undefined => capabilityRecords?.find((c) => c.id === id)

  const renameCap = useMutation({
    mutationFn: (v: { id: string; name: string; code?: string; description?: string }) =>
      api.updateCapability(app.id, v.id, { name: v.name, code: v.code, description: v.description }),
    onSuccess: () => { setRenamingCap(null); invalidateCapabilities(qc, app.id) },
  })
  const archiveCap = useMutation({
    mutationFn: (id: string) => api.archiveCapability(app.id, id),
    onSuccess: () => { setArchivingCap(null); invalidateCapabilities(qc, app.id) },
  })

  const totalReqs = (caps ?? []).reduce((s, c) => s + c.reqCount, 0)

  return (
    <>
      <div className="pd-bar">
        <div style={{ minWidth: 0 }}>
          <div className="crumb">
            <button onClick={onBack}>Product Portfolio</button>
            <span className="sep">▸</span>
            <button onClick={onBackToProduct}>{product.name}</button>
            <span className="sep">▸</span>
            <span className="cur">{app.name}</span>
          </div>
        </div>
        <div className="sp" />
        <div className="pf-actions" style={{ display: 'flex', gap: 6 }}>
          <button className="btn" onClick={() => setShowNewCap(true)}><Plus /> New capability</button>
          <button className="btn" onClick={() => onOpenRequirements()}>Requirements</button>
        </div>
      </div>

      <div className="ad-head">
        <span className="pd-glyph"><Icon style={{ width: 22, height: 22, color: 'var(--prod)' }} /></span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="ad-prod">{product.name}</div>
          <div className="ad-nm">{app.name}</div>
          <p className="ad-desc">
            {record?.description?.trim()
              || 'No description recorded yet. Add one when editing this application.'}
          </p>
        </div>
        <div style={{ display: 'flex', border: '1px solid var(--line)', borderRadius: 7, overflow: 'hidden', flexShrink: 0 }}>
          <div className="pf-stat" style={{ minWidth: 74 }}>
            <span className="pf-stat-v">{app.reqCount}</span><span className="pf-stat-l">Reqs</span>
          </div>
          <div className="pf-stat" style={{ minWidth: 74 }}>
            <span className="pf-stat-v" style={{ color: covColor(pct) }}>{pct}%</span>
            <span className="pf-stat-l">Approved</span>
          </div>
          <div className="pf-stat" style={{ minWidth: 74 }}>
            <span className="pf-stat-v" style={{ color: app.gapCount ? 'var(--crit)' : 'var(--ok)' }}>{app.gapCount}</span>
            <span className="pf-stat-l">Gaps</span>
          </div>
        </div>
      </div>

      {/* Principle 8: a field this product does not store says so, rather than rendering
          an empty cell that reads as "none". */}
      <div className="meta-grid">
        <Meta label="Product">{product.name}</Meta>
        <Meta label="Owner">{product.ownerName ?? <NotRecorded />}</Meta>
        <Meta label="Status">
          <span className={`pill ${product.lifecycleStatus === 'LIVE' ? 'pill-live'
            : product.lifecycleStatus === 'IN_DEVELOPMENT' ? 'pill-dev' : 'pill-plan'}`}>
            {product.lifecycleStatus.replace(/_/g, ' ').toLowerCase()}
          </span>
        </Meta>
        <Meta label="Vertical">{product.vertical ?? <NotRecorded />}</Meta>
        <Meta label="Capabilities" mono>{caps ? String(caps.length) : '·'}</Meta>
        <Meta label="Requirements filed" mono>{String(app.reqCount)}</Meta>
        <Meta label="Approved" mono>{`${app.verifiedCount} of ${app.reqCount}`}</Meta>
        <Meta label="Open gaps" mono>{String(app.gapCount)}</Meta>
      </div>

      <div className="row" style={{ margin: '16px 0 10px', alignItems: 'center' }}>
        <span className="eyebrow">Capabilities</span>
        <span className="sp" />
        <span className="mono" style={{ fontSize: 10.5, color: 'var(--tx-3)' }}>
          {caps?.length
            ? `${caps.length} capabilit${caps.length === 1 ? 'y' : 'ies'} · ${totalReqs} requirement${totalReqs === 1 ? '' : 's'} filed`
            : 'none defined yet'}
        </span>
      </div>

      {isLoading ? <p className="eyebrow">Loading…</p> : (
        <div className="cap-grid">
          {(caps ?? []).map((c) => {
            const cpct = c.reqCount > 0 ? Math.round((c.verifiedCount / c.reqCount) * 100) : 0
            return (
              <div className="cap-card" key={c.id}>
                <div className="cap-top">
                  <span className="cap-nm">{c.name}</span>
                  {c.code && <span className="cap-code">{c.code}</span>}
                  <span className="cap-acts">
                    <button
                      className="icon-btn" title="Rename this capability"
                      onClick={() => setRenamingCap({ id: c.id, name: c.name, code: c.code, description: capabilityRecord(c.id)?.description })}
                    >
                      <Pencil />
                    </button>
                    <button
                      className="icon-btn" title="Archive this capability"
                      onClick={() => setArchivingCap({ id: c.id, name: c.name })}
                    >
                      <Archive />
                    </button>
                  </span>
                </div>
                <p className="cap-desc">
                  {capabilityRecord(c.id)?.description?.trim() || 'No description recorded.'}
                </p>
                <div className="cap-nums">
                  <span className="cap-num"><b>{c.reqCount}</b><span>Reqs</span></span>
                  <span className="cap-num">
                    <b style={{ color: c.gapCount ? 'var(--crit)' : 'var(--ok)' }}>{c.gapCount}</b>
                    <span>Gaps</span>
                  </span>
                </div>
                <div className="cap-cov">
                  {/* A capability with no requirements has no coverage to show — an empty
                      bar reading 0% would look like a failing one. */}
                  <span className="bar">
                    <i style={{ width: `${c.reqCount ? cpct : 0}%`, background: covColor(cpct) }} />
                  </span>
                  <span className="pct" style={{ color: c.reqCount ? covColor(cpct) : 'var(--tx-3)' }}>
                    {c.reqCount ? `${cpct}%` : '—'}
                  </span>
                </div>
                <div className="cap-foot">
                  <button className="btn" onClick={() => onOpenRequirements({ id: c.id, name: c.name })}>
                    Requirements
                  </button>
                </div>
              </div>
            )
          })}
          <button className="add-card" onClick={() => setShowNewCap(true)}>
            <span className="ring"><Plus style={{ width: 16, height: 16 }} /></span>
            <span className="t">New capability</span>
            <span className="s">Group this app's requirements into a functional area</span>
          </button>
        </div>
      )}

      {renamingCap && (
        <Modal onClose={() => setRenamingCap(null)} title="Rename capability">
          <div className="field">
            <label className="label">Name</label>
            <input
              className="input" autoFocus value={renamingCap.name}
              onChange={(e) => setRenamingCap({ ...renamingCap, name: e.target.value })}
            />
          </div>
          <div className="field">
            <label className="label">Code (optional)</label>
            <input
              className="input" value={renamingCap.code ?? ''}
              onChange={(e) => setRenamingCap({ ...renamingCap, code: e.target.value })}
            />
          </div>
          <div className="field">
            <label className="label">Description (optional)</label>
            <textarea
              className="textarea" style={{ minHeight: 56 }} value={renamingCap.description ?? ''}
              onChange={(e) => setRenamingCap({ ...renamingCap, description: e.target.value })}
              placeholder="What does this capability cover?"
            />
          </div>
          {renameCap.isError && <p className="err-text">Could not rename that capability.</p>}
          <div className="actions">
            <button className="btn" onClick={() => setRenamingCap(null)}>Cancel</button>
            <button
              className="btn pri" disabled={!renamingCap.name.trim() || renameCap.isPending}
              onClick={() => renameCap.mutate({
                id: renamingCap.id, name: renamingCap.name.trim(), code: renamingCap.code?.trim() || undefined,
                description: renamingCap.description?.trim() || undefined,
              })}
            >
              Rename
            </button>
          </div>
        </Modal>
      )}

      {archivingCap && (
        <ConfirmDialog
          title={`Archive "${archivingCap.name}"?`}
          description="Requirements already filed under this capability keep their placement, but it stops appearing for new placement — in this app's picker and everywhere else the capability list is read from. This is reversible only by editing the database directly."
          confirmLabel="Archive capability"
          onConfirm={() => archiveCap.mutate(archivingCap.id)}
          onCancel={() => setArchivingCap(null)}
        />
      )}

      {caps && caps.length === 0 && (
        <div style={{ marginTop: 14 }}>
          <Empty
            title="This app has no capabilities yet"
            desc="Capability-level requirements cannot be filed until at least one exists. Add one here, or declare them on the Capabilities tab of the upload template and import in bulk."
          />
        </div>
      )}

      {showNewCap && (
        <NewCapabilityModal
          applicationId={app.id}
          onClose={() => setShowNewCap(false)}
          onCreated={() => {
            setShowNewCap(false)
            invalidateCapabilities(qc, app.id)
          }}
        />
      )}
    </>
  )
}

function NotRecorded() {
  return <span className="muted" style={{ fontSize: 11.5 }}>not recorded</span>
}

function Meta({ label, children, mono }: { label: string; children: React.ReactNode; mono?: boolean }) {
  return (
    <div className="meta-c">
      <div className="meta-l">{label}</div>
      <span className={`meta-v${mono ? ' mono' : ''}`}>{children}</span>
    </div>
  )
}

function NewCapabilityModal({ applicationId, onClose, onCreated }: {
  applicationId: string
  onClose: () => void
  onCreated: () => void
}) {
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')

  const create = useMutation({
    mutationFn: () => api.createCapability(applicationId, {
      name: name.trim(), code: code.trim() || undefined, description: description.trim() || undefined,
    }),
    onSuccess: onCreated,
  })

  return (
    <Modal title="New capability" onClose={onClose}>
      <div className="field">
        <label className="label">Name</label>
        <input className="input" value={name} onChange={(e) => setName(e.target.value)}
          placeholder="Lead Management" autoFocus />
      </div>
      <div className="field">
        <label className="label">Short code (optional)</label>
        <input className="input" value={code} onChange={(e) => setCode(e.target.value)} placeholder="LMG" />
        <span className="hint">Shown on the capability card and in requirement keys.</span>
      </div>
      <div className="field">
        <label className="label">Description (optional)</label>
        <textarea
          className="textarea" style={{ minHeight: 56 }} value={description}
          onChange={(e) => setDescription(e.target.value)} placeholder="What does this capability cover?"
        />
      </div>
      {create.isError && (
        <p className="err-text">
          {create.error instanceof ApiError ? (create.error.detail ?? create.error.title) : 'Could not create it.'}
        </p>
      )}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>
          {create.isPending ? 'Creating…' : 'Create'}
        </button>
      </div>
    </Modal>
  )
}
