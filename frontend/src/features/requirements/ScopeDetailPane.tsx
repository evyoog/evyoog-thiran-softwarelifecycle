import { useQuery } from '@tanstack/react-query'
import { api, type Application } from '@/shared/api/client'
import { LIFECYCLE_LABEL } from '../portfolio/marks'
import { covColor, verifiedPctOf } from '../portfolio/coverage'
import type { DetailTarget, Scope } from './ScopeTree'

/**
 * VYB-0833/0834: what a product or app click in the sidebar shows, in place of the
 * grid. Deliberately plain — a centered block of text and numbers, not the Portfolio
 * screen's card-grid drill-down (the product owner explicitly asked for "normal view"
 * over the card layout an earlier pass of this used) — this screen is about
 * requirements, and a product/app click here is a quick "what is this" look, not a
 * management surface. Data comes from the same `['product-dashboard']` query
 * `ScopeTree` already fetches (shared cache, no extra request), plus one plain-entity
 * fetch for whichever app's own description the rollup doesn't carry.
 */
export function ScopeDetailPane({
  target, onPickDetail, onPickScope, onClear, onOpenGaps,
}: {
  target: DetailTarget
  onPickDetail: (detail: DetailTarget) => void
  onPickScope: (scope: Scope) => void
  onClear: () => void
  onOpenGaps: () => void
}) {
  const { data: dashboard, isLoading, isError } = useQuery({
    queryKey: ['product-dashboard'],
    queryFn: () => api.productDashboard(),
  })

  if (isLoading) return <p className="eyebrow" style={{ padding: 16 }}>Loading…</p>
  if (isError) return <p className="err-text" style={{ padding: 16 }}>Could not load.</p>

  const productId = target.type === 'product' ? target.id : target.productId
  const product = dashboard?.products.find((p) => p.id === productId)
  if (!product) return <p className="eyebrow" style={{ padding: 16 }}>Not found — it may have been archived.</p>

  if (target.type === 'product') {
    const pct = Math.round(product.verifiedRatio * 100)
    return (
      <div className="sdp">
        <div className="eyebrow">Product</div>
        <h2 className="sdp-t">{product.name}</h2>
        <div className="muted" style={{ fontStyle: 'italic', fontSize: 12.5 }}>{product.vertical || 'No vertical recorded'}</div>
        <p className="sdp-desc">{product.purpose || 'No description recorded.'}</p>

        <div className="sdp-stats">
          <Stat label="Requirements" value={product.reqCount.toLocaleString()} />
          <Stat label="Verified" value={`${pct}%`} color={covColor(pct)} />
          <Stat label="Gaps" value={product.gapCount} color={product.gapCount > 0 ? 'var(--crit)' : 'var(--ok)'} />
          <Stat label="Apps" value={product.appCount} />
        </div>

        <div className="muted" style={{ fontSize: 11.5, marginTop: 10 }}>
          {product.ownerName ? `Owner: ${product.ownerName}` : 'No owner assigned'}
          {' · '}{LIFECYCLE_LABEL[product.lifecycleStatus] ?? product.lifecycleStatus}
        </div>

        {product.gapCount > 0 && (
          <button className="btn" style={{ marginTop: 14 }} onClick={onOpenGaps}>Open coverage matrix</button>
        )}

        <div className="sdp-sec">
          <div className="eyebrow" style={{ marginBottom: 8 }}>Apps</div>
          {product.apps.length === 0 ? (
            <p className="muted" style={{ fontSize: 12.5 }}>No apps yet.</p>
          ) : (
            <div>
              {product.apps.map((a) => (
                <div
                  key={a.id} className="list-item" style={{ cursor: 'pointer', justifyContent: 'space-between' }}
                  onClick={() => onPickDetail({ type: 'application', id: a.id, productId: product.id, label: `${product.name} ▸ ${a.name}` })}
                >
                  <span>{a.name}</span>
                  <span className="mono muted" style={{ fontSize: 10.5 }}>
                    {a.reqCount} req{a.reqCount === 1 ? '' : 's'}{a.gapCount > 0 && ` · ${a.gapCount} gap${a.gapCount === 1 ? '' : 's'}`}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Never able to scope by app — only by capability (see ScopeTree's own doc
            comment) — so this is the same unscoped grid "All requirements" opens. */}
        <button className="btn" style={{ marginTop: 18 }} onClick={onClear}>All requirements</button>
      </div>
    )
  }

  const app = product.apps.find((a) => a.id === target.id)
  if (!app) return <p className="eyebrow" style={{ padding: 16 }}>Not found — it may have been archived.</p>

  return <AppDetail product={product} app={app} onPickScope={onPickScope} onClear={onClear} onBackToProduct={onPickDetail} />
}

function AppDetail({
  product, app, onPickScope, onClear, onBackToProduct,
}: {
  product: { id: string; name: string }
  app: { id: string; name: string; reqCount: number; gapCount: number; verifiedCount: number }
  onPickScope: (scope: Scope) => void
  onClear: () => void
  onBackToProduct: (detail: DetailTarget) => void
}) {
  // The description lives on the application record itself, which the dashboard
  // rollup does not carry — same reasoning ApplicationDetail.tsx's own fetch has.
  const { data: applications } = useQuery({
    queryKey: ['applications', product.id],
    queryFn: () => api.applications(product.id),
  })
  const record: Application | undefined = applications?.find((a) => a.id === app.id)
  const { data: caps, isLoading } = useQuery({
    queryKey: ['capability-summary', app.id],
    queryFn: () => api.capabilitySummary(app.id),
  })
  const pct = verifiedPctOf(app)

  return (
    <div className="sdp">
      <div className="eyebrow">Application</div>
      <div className="muted" style={{ fontSize: 11.5 }}>{product.name}</div>
      <h2 className="sdp-t">{app.name}</h2>
      <p className="sdp-desc">{record?.description?.trim() || 'No description recorded.'}</p>

      <div className="sdp-stats">
        <Stat label="Requirements" value={app.reqCount.toLocaleString()} />
        <Stat label="Approved" value={`${pct}%`} color={covColor(pct)} />
        <Stat label="Gaps" value={app.gapCount} color={app.gapCount > 0 ? 'var(--crit)' : 'var(--ok)'} />
      </div>

      <div className="sdp-sec">
        <div className="eyebrow" style={{ marginBottom: 8 }}>Capabilities</div>
        {isLoading && <p className="muted" style={{ fontSize: 12.5 }}>Loading…</p>}
        {caps && caps.length === 0 && <p className="muted" style={{ fontSize: 12.5 }}>No capabilities yet.</p>}
        {caps && caps.length > 0 && (
          <div>
            {caps.map((c) => (
              <div
                key={c.id} className="list-item" style={{ cursor: 'pointer', justifyContent: 'space-between' }}
                onClick={() => onPickScope({ capabilityId: c.id, applicationId: app.id, label: `${app.name} ▸ ${c.name}` })}
              >
                <span>{c.name}{c.code && <span className="mono muted" style={{ fontSize: 10, marginLeft: 6 }}>{c.code}</span>}</span>
                <span className="mono muted" style={{ fontSize: 10.5 }}>
                  {c.reqCount} req{c.reqCount === 1 ? '' : 's'}{c.gapCount > 0 && ` · ${c.gapCount} gap${c.gapCount === 1 ? '' : 's'}`}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 18 }}>
        <button className="btn" onClick={() => onBackToProduct({ type: 'product', id: product.id, label: product.name })}>
          Back to {product.name}
        </button>
        <button className="btn" onClick={onClear}>All requirements</button>
      </div>
    </div>
  )
}

function Stat({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <div className="sdp-stat">
      <div className="sdp-stat-v" style={{ color }}>{value}</div>
      <div className="sdp-stat-l">{label}</div>
    </div>
  )
}
