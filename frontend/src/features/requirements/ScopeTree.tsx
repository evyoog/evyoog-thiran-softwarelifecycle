import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type RequirementPriority, type RequirementStatus, type RequirementType } from '@/shared/api/client'

/**
 * The prototype's `Product ▸ App` tree, extended by one level to `Product ▸ App ▸
 * Capability` — deliberately, not decoratively. `GET /requirements` scopes by
 * `capabilityId` and nothing coarser (see api.requirements's own params), so a
 * capability is the shallowest node that can actually narrow the server's result set.
 * Selecting a product or app expands it; only a capability filters. Filtering the
 * grid at app level would mean discarding rows from the loaded page after the fact,
 * which reports a wrong count against a paginated total — the prototype's tree has no
 * pagination behind it and can afford what this one can't.
 */
export interface Scope {
  capabilityId: string
  /** The capability's application — a capability id alone can't be walked back up the tree. */
  applicationId: string
  label: string
}

/**
 * VYB-0833: unlike a capability, a product or app never narrows `GET /requirements` —
 * picking one shows its own details in the main pane instead (read-only, via {@link
 * ../ScopeDetailPane}), rather than pretending to filter a grid it can't actually scope.
 */
export type DetailTarget =
  | { type: 'product'; id: string; label: string }
  | { type: 'application'; id: string; productId: string; label: string }

/**
 * A view is a real, server-executable filter combination — every field is a parameter
 * `GET /requirements` genuinely accepts. The prototype's own four ("Untested criticals",
 * "Changed since 3.1", "AI-flagged wording", "Cross-app dependencies") are not
 * reproduced: each needs a coverage, revision-diff or finding predicate the requirements
 * endpoint has no parameter for, and a view that silently filtered only the loaded page
 * would report a count that doesn't match what it claims to show.
 *
 * <p>{@link SAVED_VIEWS} below are fixed presets the product ships — they are not saved
 * by anyone and cannot be edited. (VYB-0823: a "My views" section for the caller's own,
 * server-saved views used to sit alongside these — removed at the product owner's
 * request; the `saved_view` table and its API endpoints are unaffected, only this
 * screen's UI for them is gone.)
 */
export interface SavedView {
  label: string
  status?: RequirementStatus
  priority?: RequirementPriority
  type?: RequirementType
  titleContains?: string
  capabilityId?: string
}

/** Shipped presets, not user data — the heading in the tree says "Standard" for that reason. */
export const SAVED_VIEWS: SavedView[] = [
  // Approved is the gate into Delivery — only approved requirements reach a brief — so
  // it leads, and the views below it are the queue of work on the way there.
  { label: 'Ready for delivery (approved)', status: 'APPROVED' },
  // Reviewed — a human (or, later, AI) has signed off on the requirement's content but
  // it has not yet been approved for delivery. This is the queue an Approver works from.
  { label: 'Reviewed — awaiting approval', status: 'REVIEWED' },
  // VYB-0813 (D17): its own bucket, separate from Drafts — a requirement here has
  // already been through a review and been sent back with a reason to act on, which
  // is a different queue of work than one nobody has looked at yet.
  { label: 'Needs revision', status: 'NEEDS_REVISION' },
  { label: 'Awaiting review', status: 'IN_REVIEW' },
  { label: 'Critical, in review', status: 'IN_REVIEW', priority: 'CRITICAL' },
  { label: 'Drafts — not yet submitted', status: 'DRAFT' },
]

export function ScopeTree({
  scope, onPickScope, detail, onPickDetail, activeView, onPickView,
}: {
  scope: Scope | null
  onPickScope: (scope: Scope | null) => void
  detail: DetailTarget | null
  onPickDetail: (detail: DetailTarget) => void
  activeView: SavedView | null
  onPickView: (view: SavedView) => void
}) {
  const [filter, setFilter] = useState('')
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const { data, isLoading, isError } = useQuery({
    queryKey: ['product-dashboard'],
    queryFn: () => api.productDashboard(),
  })

  const toggle = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })

  const q = filter.trim().toLowerCase()
  const matches = (name: string) => !q || name.toLowerCase().includes(q)
  const products = (data?.products ?? []).filter(
    (p) => matches(p.name) || p.apps.some((a) => matches(a.name)),
  )

  return (
    <aside className="tree" aria-label="Requirement scope">
      <div className="tree-srch">
        <input
          placeholder="Filter products and apps…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label="Filter products and apps"
        />
      </div>

      <div className="tree-grp">Portfolio</div>

      {isLoading && <div className="tree-grp" style={{ textTransform: 'none', letterSpacing: 0 }}>Loading…</div>}
      {isError && <div className="tree-grp" style={{ textTransform: 'none', letterSpacing: 0, color: 'var(--crit)' }}>Could not load</div>}

      <button
        className={`tnode${scope === null && detail === null ? ' on' : ''}`}
        onClick={() => onPickScope(null)}
      >
        <span className="cr" />
        <span className="lbl">All requirements</span>
        {data && <span className="ct">{data.totals.reqCount}</span>}
      </button>

      {products.map((p) => {
        const open = expanded.has(p.id)
        const isDetail = detail?.type === 'product' && detail.id === p.id
        return (
          <div key={p.id}>
            <button
              className={`tnode${open ? '' : ' cl'}${isDetail ? ' on' : ''}`}
              onClick={() => { toggle(p.id); onPickDetail({ type: 'product', id: p.id, label: p.name }) }}
              aria-expanded={open}
            >
              <span className="cr">▾</span>
              <span className="lbl">{p.name}</span>
              {p.gapCount > 0 && <span className="gp" title={`${p.gapCount} coverage gaps`}>{p.gapCount}</span>}
              <span className="ct">{p.reqCount}</span>
            </button>
            {open && (
              <div className="tkids">
                {p.apps.filter((a) => matches(a.name) || matches(p.name)).map((a) => (
                  <AppNode
                    key={a.id} appId={a.id} name={a.name} reqCount={a.reqCount} gapCount={a.gapCount}
                    productId={p.id} productName={p.name}
                    open={expanded.has(a.id)} onToggle={() => toggle(a.id)}
                    scope={scope} onPickScope={onPickScope}
                    detail={detail} onPickDetail={onPickDetail}
                  />
                ))}
                {p.apps.length === 0 && (
                  <div className="tree-grp" style={{ paddingLeft: 29, textTransform: 'none', letterSpacing: 0 }}>
                    No apps yet
                  </div>
                )}
              </div>
            )}
          </div>
        )
      })}

      <div className="tree-grp" style={{ marginTop: 12 }}>Standard views</div>
      {SAVED_VIEWS.map((v) => (
        <button
          key={v.label}
          className={`tnode${activeView?.label === v.label ? ' on' : ''}`}
          onClick={() => onPickView(v)}
        >
          <span className="cr" />
          <span className="lbl">{v.label}</span>
        </button>
      ))}

    </aside>
  )
}

/** Capabilities are fetched only once its app is expanded — a portfolio of 40 apps shouldn't cost 40 requests to render a collapsed tree. */
function AppNode({
  appId, name, reqCount, gapCount, productId, productName, open, onToggle, scope, onPickScope, detail, onPickDetail,
}: {
  appId: string
  name: string
  reqCount: number
  gapCount: number
  productId: string
  productName: string
  open: boolean
  onToggle: () => void
  scope: Scope | null
  onPickScope: (scope: Scope) => void
  detail: DetailTarget | null
  onPickDetail: (detail: DetailTarget) => void
}) {
  const { data: caps, isLoading } = useQuery({
    queryKey: ['capabilities', appId],
    queryFn: () => api.capabilities(appId),
    enabled: open,
  })
  // Archived capabilities are still returned by the API; the tree shows what you can pick.
  // Computed once so the empty state below counts the same list that is rendered — using
  // the unfiltered length there showed neither rows nor a message when every capability
  // in an app had been archived.
  const liveCaps = caps?.filter((c) => !c.archived)
  const isDetail = detail?.type === 'application' && detail.id === appId

  return (
    <>
      <button
        className={`tnode${open ? '' : ' cl'}${isDetail ? ' on' : ''}`}
        onClick={() => { onToggle(); onPickDetail({ type: 'application', id: appId, productId, label: `${productName} ▸ ${name}` }) }}
        aria-expanded={open}
      >
        <span className="cr">▾</span>
        <span className="lbl">{name}</span>
        {gapCount > 0 && <span className="gp" title={`${gapCount} coverage gaps`}>{gapCount}</span>}
        <span className="ct">{reqCount}</span>
      </button>
      {open && (
        <div className="tkids">
          {isLoading && (
            <div className="tree-grp" style={{ paddingLeft: 44, textTransform: 'none', letterSpacing: 0 }}>Loading…</div>
          )}
          {liveCaps?.map((c) => (
            <button
              key={c.id}
              className={`tnode${scope?.capabilityId === c.id ? ' on' : ''}`}
              onClick={() => onPickScope({ capabilityId: c.id, applicationId: appId, label: `${name} ▸ ${c.name}` })}
            >
              <span className="lbl">{c.name}</span>
              {c.code && <span className="ct">{c.code}</span>}
            </button>
          ))}
          {liveCaps?.length === 0 && (
            <div className="tree-grp" style={{ paddingLeft: 44, textTransform: 'none', letterSpacing: 0 }}>
              No capabilities yet
            </div>
          )}
        </div>
      )}
    </>
  )
}
