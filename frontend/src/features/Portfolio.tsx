import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, Archive, Pencil, Download } from 'lucide-react'
import { api, type ProductSummary } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog'
import { Modal } from '@/shared/ui/Modal'
import { NewProductModal, type EditingProduct } from './portfolio/NewProductModal'
import { ProductDetail } from './portfolio/ProductDetail'
import { ApplicationDetail } from './portfolio/ApplicationDetail'
import { invalidateCapabilities } from './portfolio/invalidate'
import { markOf, LIFECYCLE_LABEL, LIFECYCLE_STYLE } from './portfolio/marks'
import { covColor } from './portfolio/coverage'

/**
 * VYB-0100/0101/0788: the hierarchy, with create/read/rename/archive at each level,
 * behind a Dashboard tab that's the real landing view — one card per product, real
 * requirement/application/gap counts and a real verified-ratio bar, not a static
 * mockup. There's no delete — archive is the only way something stops appearing, and
 * it's soft, and it's confirmed first (VYB-0183) since it cascades to hide everything
 * beneath it. VYB-0102/0103: a third tab for the cross-app glossary — shared terms,
 * optional per-application definitions, and where those definitions disagree.
 */
export function Portfolio() {
  const qc = useQueryClient()
  const [tab, setTab] = useState<'dashboard' | 'hierarchy' | 'glossary'>('dashboard')
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [showNewProduct, setShowNewProduct] = useState(false)
  const [editingProduct, setEditingProduct] = useState<EditingProduct | null>(null)
  // OPEN on a product card leads to the prototype's own product state — head, stat
  // box, one card per app — not to the Hierarchy tab's three columns, which is a
  // different design that happened to be the only drill-down this view had.
  const [drillProductId, setDrillProductId] = useState('')
  // The prototype's third portfolio state: one app, its metadata and capabilities.
  const [drillAppId, setDrillAppId] = useState('')
  const navigate = useNavigate()

  const [appName, setAppName] = useState('')
  /** The app card being renamed in the product drill-down, and the draft name. */
  const [renamingApp, setRenamingApp] = useState<{ id: string; productId: string; name: string } | null>(null)
  const [capName, setCapName] = useState('')

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: dashboard, isLoading: loadingDashboard } = useQuery({
    queryKey: ['product-dashboard'], queryFn: api.productDashboard, enabled: tab === 'dashboard',
  })
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

  const archiveProduct = useMutation({
    mutationFn: (id: string) => api.archiveProduct(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['products'] })
      void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
    },
  })

  const createApplication = useMutation({
    mutationFn: () => api.createApplication(productId, { name: appName }),
    onSuccess: () => { setAppName(''); void qc.invalidateQueries({ queryKey: ['applications', productId] }) },
  })
  const renameApplication = useMutation({
    mutationFn: ({ id, name, productId: pid }: { id: string; name: string; productId?: string }) => {
      const a = applications?.find((x) => x.id === id)
      return api.updateApplication(pid ?? productId, id, { name, description: a?.description })
    },
    onSuccess: (_r, v) => {
      void qc.invalidateQueries({ queryKey: ['applications', v.productId ?? productId] })
      void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
    },
  })
  const archiveApplication = useMutation({
    mutationFn: ({ id, productId: pid }: { id: string; productId: string }) => api.archiveApplication(pid, id),
    onSuccess: (_r, v) => {
      void qc.invalidateQueries({ queryKey: ['applications', v.productId] })
      // The drill-down reads the dashboard, not the applications list, so it needs its
      // own invalidation or the card stays on screen after being archived.
      void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
    },
  })

  const createCapability = useMutation({
    mutationFn: () => api.createCapability(applicationId, { name: capName }),
    onSuccess: () => { setCapName(''); invalidateCapabilities(qc, applicationId) },
  })
  const renameCapability = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) => {
      const c = capabilities?.find((x) => x.id === id)
      return api.updateCapability(applicationId, id, { name, code: c?.code, description: c?.description })
    },
    onSuccess: () => invalidateCapabilities(qc, applicationId),
  })
  const archiveCapability = useMutation({
    mutationFn: (id: string) => api.archiveCapability(applicationId, id),
    onSuccess: () => invalidateCapabilities(qc, applicationId),
  })

  // VYB-0183: archive cascades to hide everything beneath it — name the exact item
  // before doing it, not a bare "are you sure?".
  const [pendingArchive, setPendingArchive] = useState<
    { level: 'product' | 'application' | 'capability'; id: string; label: string
      /** Applications are archived through their product, and the drill-down is on a
          different product from whatever the Manage tab happens to have selected. */
      productId?: string } | null
  >(null)
  const confirmArchive = () => {
    if (!pendingArchive) return
    const { level, id } = pendingArchive
    if (level === 'product') archiveProduct.mutate(id)
    else if (level === 'application') archiveApplication.mutate({ id, productId: pendingArchive.productId ?? productId })
    else archiveCapability.mutate(id)
    setPendingArchive(null)
  }

  const downloadReport = async () => {
    const blob = await api.productDashboardReport()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'portfolio-report.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  // The prototype's eyebrow reads "five verticals"; this one counts the products
  // actually rendered below it, so the two can never disagree.
  const liveProducts = (products ?? []).filter((p) => !p.archived).length

  // Drilled into one product: the prototype replaces the whole view — no page title,
  // no tab row, a crumb back to the grid — so this returns before either of those.
  // It reads the product out of the dashboard payload already in cache, so opening a
  // product costs no request and cannot show numbers that differ from the card.
  const drillProduct = dashboard?.products.find((p) => p.id === drillProductId)

  // Drilled into one app. OPEN APP used to jump to the Hierarchy tab's three columns,
  // which is a different screen answering a different question; the prototype opens the
  // app's own view, and this is it.
  const drillApp = drillProduct?.apps.find((a) => a.id === drillAppId)
  if (drillProduct && drillApp) {
    return (
      <ApplicationDetail
        product={drillProduct}
        app={drillApp}
        onBack={() => { setDrillAppId(''); setDrillProductId('') }}
        onBackToProduct={() => setDrillAppId('')}
        onOpenRequirements={(capability) => navigate(capability
          ? `/requirements?capabilityId=${capability.id}&applicationId=${drillApp.id}`
            + `&label=${encodeURIComponent(capability.name)}`
          : '/requirements')}
      />
    )
  }

  // Both dialogs, in one place. The drill-down returns before the main tree, so without
  // this an app archived from a card would open a confirmation that never rendered.
  function Dialogs() {
    return (
      <>
        {renamingApp && (
          <RenameApplicationModal
            id={renamingApp.id} productId={renamingApp.productId} fallbackName={renamingApp.name}
            onClose={() => setRenamingApp(null)}
          />
        )}
        {pendingArchive && (
          <ConfirmDialog
            title={`Archive "${pendingArchive.label}"?`}
            description={
              pendingArchive.level === 'product'
                ? 'Every application and capability beneath this product stops appearing in pickers. This is reversible only by editing the database directly — there is no un-archive action yet.'
                : pendingArchive.level === 'application'
                ? 'Every capability beneath this application stops appearing in pickers. This is reversible only by editing the database directly — there is no un-archive action yet.'
                : 'Requirements already assigned to this capability keep their assignment, but it stops appearing for new placement. This is reversible only by editing the database directly — there is no un-archive action yet.'
            }
            confirmLabel={`Archive ${pendingArchive.level}`}
            onConfirm={confirmArchive}
            onCancel={() => setPendingArchive(null)}
          />
        )}
      </>
    )
  }

  if (drillProduct) {
    return (
      <>
        <ProductDetail
          p={drillProduct}
          onBack={() => setDrillProductId('')}
          onOpenApp={(aid) => setDrillAppId(aid)}
          onOpenRequirements={() => navigate('/requirements')}
          onOpenGaps={() => navigate('/requirements/coverage')}
          onEditApp={(aid, name) => setRenamingApp({ id: aid, productId: drillProduct.id, name })}
          onArchiveApp={(aid, label) =>
            setPendingArchive({ level: 'application', id: aid, label, productId: drillProduct.id })}
        />
        <Dialogs />
      </>
    )
  }

  return (
    <Page
      eyebrow={`Enterprise Intelligence · ${liveProducts} vertical${liveProducts === 1 ? '' : 's'}, one unified platform`}
      title="Product Portfolio"
      desc="Every requirement in the platform belongs to exactly one capability, every capability to one app, and every app to one product. Pick a product to work through its apps, or jump straight to an app."
      actions={
        <div className="pf-actions" style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={downloadReport}><Download /> Portfolio report</button>
          <button className="btn pri" onClick={() => setShowNewProduct(true)}><Plus /> New product</button>
        </div>
      }
    >
      <div style={{ display: 'flex', gap: 6, marginBottom: 16 }}>
        <button className={`btn${tab === 'dashboard' ? ' pri' : ''}`} onClick={() => setTab('dashboard')}>Dashboard</button>
        <button className={`btn${tab === 'hierarchy' ? ' pri' : ''}`} onClick={() => setTab('hierarchy')}>Hierarchy</button>
        <button className={`btn${tab === 'glossary' ? ' pri' : ''}`} onClick={() => setTab('glossary')}>Glossary</button>
      </div>

      {tab === 'dashboard' && (
        <>
          {loadingDashboard && <p className="eyebrow">Loading…</p>}
          {dashboard && (() => {
            // Exact, not averaged-per-product — weighted by requirement count, the
            // same "everything here is derived live" standard the rest of this
            // dashboard already holds to, using the verified count each app already
            // reports rather than a second query.
            const totalVerified = dashboard.products.reduce(
              (sum, p) => sum + p.apps.reduce((s, a) => s + a.verifiedCount, 0), 0)
            const verifiedPct = dashboard.totals.reqCount > 0
              ? Math.round((totalVerified / dashboard.totals.reqCount) * 100) : 0
            return (
            <>
              <div className="pf-sum">
                <SumCell label="Products" value={dashboard.totals.productCount} />
                <SumCell label="Apps" value={dashboard.totals.appCount} />
                <SumCell label="Requirements" value={dashboard.totals.reqCount} />
                <SumCell label="Approved" value={`${verifiedPct}%`} color={covColor(verifiedPct)} />
                <SumCell label="Open gaps" value={dashboard.totals.gapCount} color={dashboard.totals.gapCount > 0 ? 'var(--crit)' : 'var(--ok)'} />
                <SumCell label="Apps below 75%" value={dashboard.totals.appsBelowThreshold} color={dashboard.totals.appsBelowThreshold > 0 ? 'var(--high)' : 'var(--ok)'} />
              </div>

              {dashboard.products.length === 0 ? (
                <Empty title="No products yet" desc="Create the first one above." />
              ) : (
                <div className="pf-grid">
                  {dashboard.products.map((p) => (
                    <ProductCard
                      key={p.id} p={p}
                      onOpen={() => setDrillProductId(p.id)}
                      onOpenApp={(aid) => { setDrillProductId(p.id); setDrillAppId(aid) }}
                      onEdit={() => setEditingProduct({
                        id: p.id, key: p.key, name: p.name, vertical: p.vertical, purpose: p.purpose,
                        ownerId: p.ownerId, ownerName: p.ownerName, lifecycleStatus: p.lifecycleStatus, mark: p.mark,
                      })}
                      onArchive={() => setPendingArchive({ level: 'product', id: p.id, label: p.name })}
                    />
                  ))}
                  <button className="add-card" onClick={() => setShowNewProduct(true)}>
                    <span className="ring"><Plus size={16} /></span>
                    <span className="t">New product</span>
                    <span className="s">Add a vertical alongside the {liveProducts} existing product{liveProducts === 1 ? '' : 's'}</span>
                  </button>
                </div>
              )}
            </>
            )
          })()}
        </>
      )}

      {/* The prototype's crumb belongs to its product/app drill-down states; this
          Portfolio is tabbed instead, so it renders here once a product is picked,
          with each ancestor a real link back up. */}
      {tab === 'hierarchy' && productId && (
        <div className="crumb">
          <button onClick={() => { setProductId(''); setApplicationId(''); setTab('dashboard') }}>Product Portfolio</button>
          <span className="sep">▸</span>
          {applicationId ? (
            <>
              <button onClick={() => setApplicationId('')}>
                {products?.find((p) => p.id === productId)?.name ?? 'Product'}
              </button>
              <span className="sep">▸</span>
              <span className="cur">{applications?.find((a) => a.id === applicationId)?.name ?? 'Application'}</span>
            </>
          ) : (
            <span className="cur">{products?.find((p) => p.id === productId)?.name ?? 'Product'}</span>
          )}
        </div>
      )}

      {tab === 'hierarchy' && (
        <div style={{ display: 'grid', gridTemplateColumns: '260px 1fr 1fr', gap: 16, marginTop: 10 }}>
          <div>
            <h4 className="section-h">Product</h4>
            {(products ?? []).filter((p) => !p.archived).length === 0 && <Empty title="No products yet" desc="Create one from the Dashboard tab." />}
            {(products ?? []).filter((p) => !p.archived).map((p) => (
              <div
                key={p.id} className="list-item" style={{ cursor: 'pointer', borderColor: p.id === productId ? 'var(--brand)' : undefined }}
                onClick={() => { setProductId(p.id); setApplicationId('') }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600 }}>{p.name}</div>
                  <div className="mono muted" style={{ fontSize: 10 }}>{p.key}</div>
                </div>
              </div>
            ))}
          </div>
          <Column
            title="Applications"
            items={(applications ?? []).filter((a) => !a.archived).map((a) => ({ id: a.id, label: a.name }))}
            selectedId={applicationId}
            onSelect={setApplicationId}
            onArchive={(aid, label) => setPendingArchive({ level: 'application', id: aid, label })}
            onRename={(aid, name) => renameApplication.mutate({ id: aid, name })}
            disabled={!productId}
            disabledHint="Pick a product first"
            newValue={appName}
            onNewValueChange={setAppName}
            onCreate={() => createApplication.mutate()}
            creating={createApplication.isPending}
          />
          <Column
            title="Capabilities"
            items={(capabilities ?? []).filter((c) => !c.archived).map((c) => ({ id: c.id, label: c.name, sub: c.code }))}
            selectedId=""
            onSelect={() => {}}
            onArchive={(cid, label) => setPendingArchive({ level: 'capability', id: cid, label })}
            onRename={(cid, name) => renameCapability.mutate({ id: cid, name })}
            disabled={!applicationId}
            disabledHint="Pick an application first"
            newValue={capName}
            onNewValueChange={setCapName}
            onCreate={() => createCapability.mutate()}
            creating={createCapability.isPending}
          />
        </div>
      )}

      {tab === 'glossary' && (
        <GlossarySection
          selectedApplicationId={applicationId}
          selectedApplicationName={applications?.find((a) => a.id === applicationId)?.name}
        />
      )}

      {showNewProduct && <NewProductModal onClose={() => setShowNewProduct(false)} />}
      {editingProduct && <NewProductModal editing={editingProduct} onClose={() => setEditingProduct(null)} />}

      <Dialogs />
    </Page>
  )
}

/** One cell of the joined platform strip (the prototype's `.pf-sum-c`). */
function SumCell({ label, value, color }: { label: string; value: number | string; color?: string }) {
  return (
    <div className="pf-sum-c">
      <div className="pf-sum-v" style={{ color }}>
        {typeof value === 'number' ? value.toLocaleString() : value}
      </div>
      <div className="pf-sum-l">{label}</div>
    </div>
  )
}

function ProductCard({ p, onOpen, onOpenApp, onEdit, onArchive }: {
  p: ProductSummary
  onOpen: () => void
  onOpenApp: (applicationId: string) => void
  onEdit: () => void
  onArchive: () => void
}) {
  const pct = Math.round(p.verifiedRatio * 100)
  const cov = covColor(pct)
  const m = markOf(p.mark)
  const Icon = m.icon
  const lifecycle = LIFECYCLE_STYLE[p.lifecycleStatus]

  return (
    <div className={`pf-card${m.accent ? ` ${m.accent}` : ''}`}>
      <div className="pf-acts">
        <button className="btn" title="Edit product" aria-label={`Edit ${p.name}`} onClick={onEdit}><Pencil /></button>
        <button className="btn" title="Archive product" aria-label={`Archive ${p.name}`} onClick={onArchive}><Archive /></button>
      </div>

      <div className="pf-top">
        <span className="pf-glyph"><Icon size={22} color={m.color} /></span>
        <span className="pf-name">{p.name}</span>
        <span className="pf-tag">{p.vertical || 'No vertical recorded'}</span>
      </div>

      <div className="pf-meta">
        <span
          className="badge"
          style={{ color: lifecycle?.color, background: lifecycle?.bg, borderColor: lifecycle?.bd }}
        >
          {LIFECYCLE_LABEL[p.lifecycleStatus] ?? p.lifecycleStatus}
        </span>
        <span>{p.ownerName ? `Owner: ${p.ownerName}` : 'No owner assigned'}</span>
      </div>

      <p className="pf-desc" style={{ margin: 0 }}>{p.purpose || 'No description recorded.'}</p>

      <div className="pf-stats">
        <div className="pf-stat">
          <span className="pf-stat-v">{p.reqCount.toLocaleString()}</span>
          <span className="pf-stat-l">Reqs</span>
        </div>
        <div className="pf-stat">
          <span className="pf-stat-v">{p.appCount}</span>
          <span className="pf-stat-l">Apps</span>
        </div>
        <div className="pf-stat">
          <span className="pf-stat-v" style={{ color: p.gapCount > 0 ? 'var(--crit)' : 'var(--ok)' }}>{p.gapCount}</span>
          <span className="pf-stat-l">Gaps</span>
        </div>
      </div>

      <div className="pf-apps">
        <div className="pf-apps-h">Apps</div>
        {p.apps.length === 0 && (
          <div style={{ padding: '2px 15px', fontSize: 11.5, color: 'var(--tx-3)' }}>No apps yet</div>
        )}
        {p.apps.map((a) => (
          <button key={a.id} className="pf-app" onClick={() => onOpenApp(a.id)}>
            <span className="sq" />
            <span className="nm" title={a.name}>{a.name}</span>
            <span className="rc">{a.reqCount}</span>
            {/* The prototype chips a gap count only above 5; every non-zero count is
                chipped here — hiding a live figure to quieten the card hides real work. */}
            {a.gapCount > 0 && <span className="gc">{a.gapCount}</span>}
          </button>
        ))}
      </div>

      <div className="pf-foot">
        <span className="pf-bar"><i style={{ width: `${pct}%`, background: cov }} /></span>
        <span className="pf-pct" style={{ color: cov }}>{pct}%</span>
        <button className="btn" onClick={onOpen}>Open</button>
      </div>
    </div>
  )
}

/**
 * VYB-0102 (shared terms), VYB-0103 (per-app definitions + conflicts). Recording a
 * definition for a specific application needs one selected in the Hierarchy tab
 * first — there's no separate application picker here, to avoid building a second
 * one that could drift from the real hierarchy.
 */
function GlossarySection({
  selectedApplicationId, selectedApplicationName,
}: { selectedApplicationId: string; selectedApplicationName?: string }) {
  const qc = useQueryClient()
  const [term, setTerm] = useState('')
  const [definition, setDefinition] = useState('')
  const [usageDefinition, setUsageDefinition] = useState<Record<string, string>>({})

  const { data: terms, isLoading } = useQuery({ queryKey: ['glossary-terms'], queryFn: api.glossaryTerms })
  const { data: conflicts } = useQuery({ queryKey: ['glossary-conflicts'], queryFn: api.glossaryConflicts })

  const createTerm = useMutation({
    mutationFn: () => api.createGlossaryTerm(term, definition),
    onSuccess: () => {
      setTerm(''); setDefinition('')
      void qc.invalidateQueries({ queryKey: ['glossary-terms'] })
    },
  })
  const recordUsage = useMutation({
    mutationFn: ({ termId, def }: { termId: string; def: string }) =>
      api.recordGlossaryUsage(termId, selectedApplicationId, def || undefined),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['glossary-conflicts'] })
    },
  })

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
      <div>
        <h4 className="section-h">Terms</h4>
        {isLoading && <p className="eyebrow">Loading…</p>}
        {terms && terms.length === 0 && <Empty title="No terms yet" desc="Add the first one below." />}
        {terms?.map((t) => (
          <div key={t.id} className="list-item" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
            <div style={{ display: 'flex', gap: 10 }}>
              <div style={{ fontWeight: 600, flex: 1 }}>{t.term}</div>
            </div>
            <div className="muted" style={{ fontSize: 12 }}>{t.definition}</div>
            <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
              <input
                className="input" style={{ flex: 1 }}
                placeholder={selectedApplicationId ? `Override for ${selectedApplicationName ?? 'this app'} (blank = use canonical)` : 'Pick an application in Hierarchy to set an app-specific definition'}
                disabled={!selectedApplicationId}
                value={usageDefinition[t.id] ?? ''}
                onChange={(e) => setUsageDefinition((prev) => ({ ...prev, [t.id]: e.target.value }))}
              />
              <button
                className="btn" disabled={!selectedApplicationId || recordUsage.isPending}
                onClick={() => recordUsage.mutate({ termId: t.id, def: usageDefinition[t.id] ?? '' })}
              >
                Record usage
              </button>
            </div>
          </div>
        ))}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8 }}>
          <input className="input" placeholder="New term" value={term} onChange={(e) => setTerm(e.target.value)} />
          <input className="input" placeholder="Canonical definition" value={definition} onChange={(e) => setDefinition(e.target.value)} />
          <button
            className="btn pri" disabled={!term.trim() || !definition.trim() || createTerm.isPending}
            onClick={() => createTerm.mutate()}
          >
            <Plus /> Add term
          </button>
        </div>
      </div>

      <div>
        <h4 className="section-h">Conflicts</h4>
        {conflicts && conflicts.length === 0 && (
          <Empty title="No conflicts" desc="Every application that uses a term agrees with the canonical definition, or doesn't override it." />
        )}
        {conflicts?.map((c) => (
          <div key={c.termId} className="card" style={{ borderColor: 'var(--high-bd)', marginBottom: 10 }}>
            <strong style={{ color: 'var(--high)' }}>{c.term}</strong>
            {c.variants.map((v, i) => (
              <div key={i} className="muted" style={{ fontSize: 12, marginTop: 6 }}>
                "{v.definition}" — used by {v.applicationIds.length} application{v.applicationIds.length === 1 ? '' : 's'}
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * VYB-0832: the pencil on an app card, unlike the Hierarchy tab's quick inline rename,
 * edits description too — previously it silently round-tripped whatever description the
 * Hierarchy tab's own (differently-scoped) applications query happened to have cached,
 * which was `undefined` whenever this was opened from a product reached through the
 * Dashboard rather than the Hierarchy tab, quietly blanking the description on save.
 * Fetches this application fresh, scoped to the product actually being edited, so the
 * field it edits is never stale or missing.
 */
function RenameApplicationModal({ id, productId, fallbackName, onClose }: {
  id: string; productId: string; fallbackName: string; onClose: () => void
}) {
  const qc = useQueryClient()
  const { data: applications } = useQuery({
    queryKey: ['applications', productId],
    queryFn: () => api.applications(productId),
  })
  const record = applications?.find((a) => a.id === id)

  const rename = useMutation({
    mutationFn: (v: { name: string; description: string }) =>
      api.updateApplication(productId, id, { name: v.name, description: v.description.trim() || undefined }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['applications', productId] })
      void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
      onClose()
    },
  })

  if (!applications) {
    return (
      <Modal onClose={onClose} title="Rename application">
        <p className="eyebrow">Loading…</p>
      </Modal>
    )
  }

  return (
    <RenameApplicationForm
      name={record?.name ?? fallbackName} description={record?.description ?? ''} saving={rename.isPending}
      onCancel={onClose} onSave={(name, description) => rename.mutate({ name, description })}
    />
  )
}

/** Its own component so `useState` only ever initialises once real data has arrived — see {@link RenameApplicationModal}. */
function RenameApplicationForm({ name: initialName, description: initialDescription, saving, onCancel, onSave }: {
  name: string; description: string; saving: boolean; onCancel: () => void
  onSave: (name: string, description: string) => void
}) {
  const [name, setName] = useState(initialName)
  const [description, setDescription] = useState(initialDescription)

  return (
    <Modal onClose={onCancel} title="Rename application">
      <div className="field">
        <label className="label">Name</label>
        <input className="input" autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Description</label>
        <textarea
          className="textarea" style={{ minHeight: 56 }} value={description}
          onChange={(e) => setDescription(e.target.value)} placeholder="What does this app cover?"
        />
      </div>
      <div className="actions">
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn pri" disabled={!name.trim() || saving} onClick={() => onSave(name.trim(), description)}>
          {saving ? 'Saving…' : 'Save changes'}
        </button>
      </div>
    </Modal>
  )
}

function Column({
  title, items, selectedId, onSelect, onArchive, onRename, disabled, disabledHint,
  newValue, onNewValueChange, onCreate, creating,
}: {
  title: string
  items: { id: string; label: string; sub?: string }[]
  selectedId: string
  onSelect: (id: string) => void
  onArchive: (id: string, label: string) => void
  onRename: (id: string, name: string) => void
  disabled?: boolean
  disabledHint?: string
  newValue: string
  onNewValueChange: (v: string) => void
  onCreate: () => void
  creating: boolean
}) {
  const [editingId, setEditingId] = useState('')
  const [editValue, setEditValue] = useState('')

  const commitRename = (id: string) => {
    const trimmed = editValue.trim()
    if (trimmed) onRename(id, trimmed)
    setEditingId('')
  }

  return (
    <div>
      <h4 className="section-h">{title}</h4>
      {disabled ? (
        <Empty title={disabledHint ?? 'Unavailable'} desc="" />
      ) : (
        <>
          {items.length === 0 && <Empty title={`No ${title.toLowerCase()} yet`} desc="Add one below." />}
          {items.map((item) => (
            <div
              key={item.id}
              className="list-item"
              style={{ cursor: editingId === item.id ? 'default' : 'pointer', borderColor: item.id === selectedId ? 'var(--brand)' : undefined }}
              onClick={() => { if (editingId !== item.id) onSelect(item.id) }}
            >
              {editingId === item.id ? (
                <input
                  className="input" style={{ flex: 1 }} autoFocus
                  value={editValue}
                  onClick={(e) => e.stopPropagation()}
                  onChange={(e) => setEditValue(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commitRename(item.id)
                    if (e.key === 'Escape') setEditingId('')
                  }}
                  onBlur={() => commitRename(item.id)}
                />
              ) : (
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600 }}>{item.label}</div>
                  {item.sub && <div className="mono muted" style={{ fontSize: 10 }}>{item.sub}</div>}
                </div>
              )}
              <button
                className="btn" style={{ padding: 4 }} title="Rename"
                onClick={(e) => { e.stopPropagation(); setEditingId(item.id); setEditValue(item.label) }}
              >
                <Pencil />
              </button>
              <button
                className="btn" style={{ padding: 4 }} title="Archive"
                onClick={(e) => { e.stopPropagation(); onArchive(item.id, item.label) }}
              >
                <Archive />
              </button>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
            <input
              className="input" style={{ flex: 1 }} placeholder="New name"
              value={newValue} onChange={(e) => onNewValueChange(e.target.value)}
            />
            <button className="btn pri" style={{ padding: 6 }} disabled={!newValue.trim() || creating} onClick={onCreate}>
              <Plus />
            </button>
          </div>
        </>
      )}
    </div>
  )
}
