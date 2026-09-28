import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { RelatedGraph } from '../design/RelatedGraph'

/**
 * VYB-0825: which requirements depend on each other, inside Quality rather than only
 * reachable from the Design screen. Reuses {@link RelatedGraph} as-is — it already
 * groups requirements by their trace links into connected components and opens
 * `/requirements/{id}` on click — rather than building a second graph with the same
 * shape; scoping by product/application mirrors exactly how the Design screen's own
 * "Relationships" tab already asks for one, since the requirements endpoint has no
 * platform-wide "give me every dependency" query to draw from otherwise.
 */
export function DependenciesTab() {
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({
    queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId,
  })

  return (
    <>
      <div className="row toolbar" style={{ marginBottom: 14 }}>
        <div className="field">
          <label className="label">Product</label>
          <select className="select" value={productId} onChange={(e) => { setProductId(e.target.value); setApplicationId('') }}>
            <option value="">—</option>
            {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label">Application</label>
          <select className="select" value={applicationId} disabled={!productId} onChange={(e) => setApplicationId(e.target.value)}>
            <option value="">—</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
      </div>

      {!applicationId ? (
        <Empty title="Pick a product and application" desc="Requirements are grouped by the links between them, one cluster per group." />
      ) : (
        <RelatedGraph applicationId={applicationId} />
      )}
    </>
  )
}
