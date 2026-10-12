import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, api } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { appliedLine, canApply, templateLine } from './complianceModel'

/**
 * VYB-0950 (D41): start the register from a standard template. A template is a starter set of clauses for one framework, in our own
 * words; applying it adds the clauses the register does not have and changes nothing that is there. Administrators only (the server
 * decides); afterwards they are ordinary clauses.
 */
export function TemplatePicker({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const { data: templates, isLoading, isError } = useQuery({ queryKey: ['clause-templates'], queryFn: api.clauseTemplates })
  const apply = useMutation({
    mutationFn: (id: string) => api.applyClauseTemplate(id),
    onSuccess: () => { for (const key of ['clause-register', 'clause-templates']) void qc.invalidateQueries({ queryKey: [key] }) },
  })
  const problem = apply.error instanceof ApiError ? (apply.error.detail ?? apply.error.title) : apply.isError ? 'Could not add the template.' : undefined

  return (
    <Modal onClose={onClose} title="Add clauses from a template" maxWidth={620}>
      <h3 className="text-base font-semibold mb-2">Add clauses from a template</h3>
      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && <p className="err-text" role="alert">The templates could not be loaded.</p>}
      {templates?.length === 0 && <p className="hint muted">There are no templates.</p>}
      {templates?.map((t) => (
        <div key={t.id} className="card" style={{ marginBottom: 10 }}>
          <strong>{t.name}</strong>
          <p className="hint muted" style={{ margin: '2px 0 6px' }}>{templateLine(t)}</p>
          <p className="text-sm text-tx-2" style={{ margin: '0 0 8px' }}>{t.note}</p>
          <button className="btn pri" disabled={!canApply(t) || apply.isPending} onClick={() => apply.mutate(t.id)}>
            {apply.isPending && apply.variables === t.id ? 'Adding…' : canApply(t) ? 'Add to the register' : 'Already in the register'}
          </button>
        </div>
      ))}
      {apply.data && <p role="status" className="text-sm" style={{ marginTop: 6 }}>{appliedLine(apply.data)}</p>}
      {problem && <p className="err-text" role="alert">{problem}</p>}
      <div className="actions"><button className="btn" onClick={onClose}>Close</button></div>
    </Modal>
  )
}
