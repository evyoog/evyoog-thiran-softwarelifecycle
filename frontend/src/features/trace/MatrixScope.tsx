import { useQuery } from '@tanstack/react-query'
import { NavLink } from 'react-router-dom'
import { api } from '@/shared/api/client'

/** VYB-0943: the three requirement matrices, one strip so each is one click from the others. */
export function MatrixTabs() {
  const tabs = [
    { to: '/requirements/coverage', label: 'Tests' },
    { to: '/requirements/release-matrix', label: 'Releases' },
    { to: '/requirements/defect-matrix', label: 'Defects' },
  ]
  return (
    <nav aria-label="Matrices" style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
      <span className="muted" style={{ alignSelf: 'center', fontSize: 12 }}>Requirement ×</span>
      {tabs.map((t) => (
        <NavLink key={t.to} to={t.to} className={({ isActive }) => `btn${isActive ? ' pri' : ''}`} aria-current={undefined}>{t.label}</NavLink>
      ))}
    </nav>
  )
}

/** VYB-0943: the product and application a matrix is for, as the coverage matrix has always chosen them. */
export function ApplicationScope({ productId, applicationId, onProduct, onApplication }: {
  productId: string; applicationId: string; onProduct: (id: string) => void; onApplication: (id: string) => void
}) {
  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({ queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId })
  return (
    <>
      <select className="select" value={productId} onChange={(e) => { onProduct(e.target.value); onApplication('') }} aria-label="Product">
        <option value="">Product…</option>
        {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
      <select className="select" value={applicationId} onChange={(e) => onApplication(e.target.value)} disabled={!productId} aria-label="Application">
        <option value="">Application…</option>
        {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
      </select>
    </>
  )
}
