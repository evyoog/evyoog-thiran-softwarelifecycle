import { useEffect, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { api, type Application, type Capability } from '@/shared/api/client'

/**
 * VYB-0801: the mirror image of the VYB-0666 lookup below. That one starts from a known
 * application and searches every product for the one that owns it; this starts from a
 * known capability and searches every application in the portfolio for the one that owns
 * *it*. Once found, the application itself carries both answers this picker needs —
 * {@link Application#productId} and its own id — so there is no second index to walk,
 * unlike the product lookup which has to search `candidateProducts` by position.
 *
 * <p>Returns `null` while unresolved: either nothing has loaded yet, or — a portfolio
 * that has moved on since — nothing in it owns this capability id at all.
 */
export function resolveApplicationFromCapability(
  applications: Application[],
  capabilitiesByApplication: (Capability[] | undefined)[],
  capabilityId: string,
): { productId: string; applicationId: string } | null {
  const hit = capabilitiesByApplication.findIndex((caps) => caps?.some((c) => c.id === capabilityId))
  if (hit < 0) return null
  const app = applications[hit]
  return { productId: app.productId, applicationId: app.id }
}

/**
 * Product ▸ Application ▸ Capability, cascading.
 *
 * <p>A capability id is meaningless on its own — it is a UUID, and the same capability
 * name ("Lead Management") can exist under several applications — so choosing one always
 * means walking the hierarchy to it. This is the control the import queue has had all
 * along and the requirements screen never did: capability could be confirmed while a
 * candidate was still in the queue, and after commit there was no way to set or change
 * it short of the API. Bulk edit could only *unassign*, which is the one direction that
 * needs no picker.
 *
 * <p>Each level loads only once its parent is chosen, so opening the control costs one
 * request rather than one per application in the portfolio.
 */
export function CapabilityPicker({ value, onChange, allowUnassign = false, initialApplicationId }: {
  /** The chosen capability id, `''` for "not chosen yet", or null for an explicit unassign. */
  value: string | null
  onChange: (capabilityId: string | null) => void
  /** Offers "— unassign —" as a choice. Only worth it where clearing is a real action. */
  allowUnassign?: boolean
  /** Pre-selects this application, e.g. the one the grid is already scoped to. */
  initialApplicationId?: string
}) {
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState(initialApplicationId ?? '')

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })

  // VYB-0666: a bulk edit opened from a scoped view pre-selects the application, but only
  // the application id is known there — never which product owns it. Without resolving
  // that, the Application <select> below has no option matching applicationId (its own
  // list only loads once productId is set), so it silently rendered blank: not "no
  // application chosen", but a real id with nothing in the list to display it as. Product
  // stayed blank for the same reason, one level up.
  //
  // Resolved by checking every live product's applications in parallel — bounded by how
  // many products the portfolio has, not by anything user-controlled — and taking the one
  // that actually lists initialApplicationId. Once productId is set from this, the
  // Application query below re-runs keyed the same way and TanStack Query serves it from
  // this same cache entry rather than fetching it twice.
  //
  // VYB-0801: a bulk edit whose selection shares one capability, but whose grid was not
  // scoped to any single application (the "All requirements" view — scope is null), has
  // no initialApplicationId to resolve from either: `value` alone is the only clue, and
  // it names a capability, not an application. That needs every application in the
  // portfolio probed, not just every product, so this path shares candidateProducts'
  // application lookups below (same queryKey, so TanStack Query serves one fetch to
  // both) and then probes each application's capabilities in turn.
  const needsCapabilityLookup = !!value && !initialApplicationId && !applicationId

  const candidateProducts = (products ?? []).filter((p) => !p.archived)
  const productLookups = useQueries({
    queries: candidateProducts.map((p) => ({
      queryKey: ['applications', p.id],
      queryFn: () => api.applications(p.id),
      enabled: (!!initialApplicationId && !productId) || needsCapabilityLookup,
    })),
  })
  useEffect(() => {
    if (!initialApplicationId || productId) return
    const hit = productLookups.findIndex((q) => q.data?.some((a) => a.id === initialApplicationId))
    if (hit >= 0) setProductId(candidateProducts[hit].id)
    // Only re-run as more lookups resolve; re-deriving from productLookups' identity on
    // every render would re-run this for no reason once nothing is left unresolved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialApplicationId, productId, productLookups.map((q) => q.dataUpdatedAt).join()])

  // VYB-0801: every application any product lookup above has turned up so far — a
  // superset that only grows as productLookups resolve — each checked for whether it
  // owns `value`. Bounded by the portfolio's total application count, not by anything
  // user-controlled; the accepted cost of resolving from a capability id alone, same
  // tradeoff the product lookup above already made one level up.
  const allApplications = productLookups.flatMap((q) => q.data ?? [])
  const capabilityLookups = useQueries({
    queries: allApplications.map((a) => ({
      queryKey: ['capabilities', a.id],
      queryFn: () => api.capabilities(a.id),
      enabled: needsCapabilityLookup,
    })),
  })
  useEffect(() => {
    // The `value` check is redundant with needsCapabilityLookup at runtime, but it is
    // what tells TypeScript this is the non-null string resolveApplicationFromCapability
    // takes, since the two are separate variables to the type checker.
    if (!needsCapabilityLookup || !value) return
    const hit = resolveApplicationFromCapability(allApplications, capabilityLookups.map((q) => q.data), value)
    if (hit) { setProductId(hit.productId); setApplicationId(hit.applicationId) }
    // Same reasoning as the product-lookup effect above: only re-run as more capability
    // lookups resolve, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needsCapabilityLookup, capabilityLookups.map((q) => q.dataUpdatedAt).join()])

  const { data: applications } = useQuery({
    queryKey: ['applications', productId],
    queryFn: () => api.applications(productId),
    enabled: !!productId,
  })
  const { data: capabilities, isLoading: loadingCaps } = useQuery({
    queryKey: ['capabilities', applicationId],
    queryFn: () => api.capabilities(applicationId),
    enabled: !!applicationId,
  })

  const liveCaps = capabilities?.filter((c) => !c.archived) ?? []

  return (
    <>
      <div className="row">
        <div className="field">
          <label className="label">Product</label>
          <select
            className="select" value={productId}
            onChange={(e) => { setProductId(e.target.value); setApplicationId(''); onChange('') }}
          >
            <option value="">—</option>
            {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label">Application</label>
          <select
            className="select" value={applicationId} disabled={!productId}
            onChange={(e) => { setApplicationId(e.target.value); onChange('') }}
          >
            <option value="">—</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
      </div>
      <div className="field">
        <label className="label">Capability</label>
        <select
          className="select" value={value ?? '__unassign__'} disabled={!applicationId && !allowUnassign}
          onChange={(e) => onChange(e.target.value === '__unassign__' ? null : e.target.value)}
        >
          <option value="">— leave unchanged —</option>
          {allowUnassign && <option value="__unassign__">— unassign —</option>}
          {liveCaps.map((c) => <option key={c.id} value={c.id}>{c.name}{c.code ? ` (${c.code})` : ''}</option>)}
        </select>
        {/* Principle 8: an application with no capabilities says so rather than
            presenting an empty dropdown that looks like a loading state. */}
        {applicationId && !loadingCaps && liveCaps.length === 0 && (
          <span className="hint">This application has no capabilities yet. Add one in Portfolio first.</span>
        )}
      </div>
    </>
  )
}
