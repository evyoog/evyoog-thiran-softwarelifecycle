import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type BoardTemplate, type RequirementStatus, type Team } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { ALL_STATUSES, COLUMN_LABEL, moveColumn, toggleColumn } from './boardModel'

const TYPES = ['FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE', 'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE']
const PRIORITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

/**
 * VYB-0953: make or change a board template: its name, the columns (which statuses, in what order) and a scope. The scope is one of
 * a product, an application or a capability, chosen from the top down. A team's lead or an administrator may; the server decides.
 */
export function TemplateForm({ template, teams, onClose, onSaved }: {
  template?: BoardTemplate
  teams: Team[]
  onClose: () => void
  onSaved: (id: string) => void
}) {
  const qc = useQueryClient()
  const [teamId, setTeamId] = useState(template?.teamId ?? teams[0]?.id ?? '')
  const [name, setName] = useState(template?.name ?? '')
  const [columns, setColumns] = useState<RequirementStatus[]>(template?.columns ?? ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'APPROVED'])
  const [productId, setProductId] = useState(template?.scopeProductId ?? template?.productId ?? '')
  const [applicationId, setApplicationId] = useState(template?.scopeApplicationId ?? template?.applicationId ?? '')
  const [capabilityId, setCapabilityId] = useState(template?.capabilityId ?? '')
  const [type, setType] = useState(template?.type ?? '')
  const [priority, setPriority] = useState(template?.priority ?? '')

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  // The product to list applications of: the chosen one, or the product of the application a saved template names.
  const { data: applications } = useQuery({ queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId })
  const { data: capabilities } = useQuery({ queryKey: ['capabilities', applicationId], queryFn: () => api.capabilities(applicationId), enabled: !!applicationId })

  const save = useMutation({
    mutationFn: async () => {
      // The scope is the deepest thing chosen: a capability, else an application, else a product.
      const scope = capabilityId ? { capabilityId } : applicationId ? { applicationId } : productId ? { productId } : {}
      const body = { name: name.trim(), columns, ...scope, type: type || undefined, priority: priority || undefined }
      if (template) { await api.updateBoardTemplate(template.id, body); return template.id }
      return (await api.createBoardTemplate(teamId, body)).id
    },
    onSuccess: (id) => { void qc.invalidateQueries({ queryKey: ['board-templates'] }); onSaved(id); onClose() },
  })
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this template.' : undefined

  return (
    <Modal onClose={onClose} title={template ? 'Edit board template' : 'New board template'} maxWidth={600}>
      <h3 className="text-base font-semibold mb-2">{template ? `Edit ${template.name}` : 'New board template'}</h3>
      {!template && (
        <div className="field">
          <label className="label" htmlFor="bt-team">Team</label>
          <select id="bt-team" className="select" value={teamId} onChange={(e) => setTeamId(e.target.value)}>
            {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </div>
      )}
      <div className="field">
        <label className="label" htmlFor="bt-name">Name</label>
        <input id="bt-name" className="input" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
      </div>

      <fieldset style={{ border: 0, padding: 0, margin: '0 0 10px' }}>
        <legend className="label">Columns, left to right</legend>
        {columns.map((c, i) => (
          <div key={c} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '2px 0' }}>
            <span style={{ flex: 1 }}>{i + 1}. {COLUMN_LABEL[c]}</span>
            <button className="btn" style={{ padding: '1px 8px' }} disabled={i === 0} aria-label={`Move ${COLUMN_LABEL[c]} left`} onClick={() => setColumns(moveColumn(columns, i, -1))}>←</button>
            <button className="btn" style={{ padding: '1px 8px' }} disabled={i === columns.length - 1} aria-label={`Move ${COLUMN_LABEL[c]} right`} onClick={() => setColumns(moveColumn(columns, i, 1))}>→</button>
            <button className="btn" style={{ padding: '1px 8px' }} disabled={columns.length === 1} aria-label={`Remove ${COLUMN_LABEL[c]}`} onClick={() => setColumns(toggleColumn(columns, c))}>✕</button>
          </div>
        ))}
        {ALL_STATUSES.filter((s) => !columns.includes(s)).length > 0 && (
          <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
            <span className="hint muted">Add:</span>
            {ALL_STATUSES.filter((s) => !columns.includes(s)).map((s) => (
              <button key={s} className="btn" style={{ padding: '1px 8px' }} onClick={() => setColumns(toggleColumn(columns, s))}>+ {COLUMN_LABEL[s]}</button>
            ))}
          </div>
        )}
      </fieldset>

      <div className="row">
        <div className="field">
          <label className="label" htmlFor="bt-product">Product</label>
          <select id="bt-product" className="select" value={productId} onChange={(e) => { setProductId(e.target.value); setApplicationId(''); setCapabilityId('') }}>
            <option value="">All</option>
            {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="bt-app">Application</label>
          <select id="bt-app" className="select" value={applicationId} disabled={!productId} onChange={(e) => { setApplicationId(e.target.value); setCapabilityId('') }}>
            <option value="">All</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="bt-cap">Capability</label>
          <select id="bt-cap" className="select" value={capabilityId} disabled={!applicationId} onChange={(e) => setCapabilityId(e.target.value)}>
            <option value="">All</option>
            {capabilities?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
      </div>
      <div className="row">
        <div className="field">
          <label className="label" htmlFor="bt-type">Type</label>
          <select id="bt-type" className="select" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">Any</option>
            {TYPES.map((t) => <option key={t} value={t}>{t.toLowerCase().replace('_', ' ')}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="bt-priority">Priority</label>
          <select id="bt-priority" className="select" value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">Any</option>
            {PRIORITIES.map((p) => <option key={p} value={p}>{p.toLowerCase()}</option>)}
          </select>
        </div>
      </div>
      {problem && <p className="err-text" role="alert">{problem}</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!name.trim() || (!template && !teamId) || save.isPending} onClick={() => save.mutate()}>{template ? 'Save' : 'Create template'}</button>
      </div>
    </Modal>
  )
}
