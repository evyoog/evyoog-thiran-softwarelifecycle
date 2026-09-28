import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { AlertTriangle } from 'lucide-react'
import { ApiError, api, type ProductLifecycleStatus, type ProductMark } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { UserPicker } from '@/shared/ui/UserPicker'
import { MARKS, LIFECYCLE_LABEL, DEFAULT_MARK } from './marks'

const LIFECYCLE_OPTIONS: ProductLifecycleStatus[] = ['IN_DEVELOPMENT', 'LIVE', 'MAINTENANCE', 'DEPRECATED', 'RETIRED']

/**
 * Surfaces the real reason a save failed instead of guessing one — an earlier version
 * of this form hard-coded "check the code is unique" for every failure, which was
 * actively wrong the moment the real cause was anything else (a 401 with no session,
 * a validation error, a network failure) and no more informative than silence.
 */
function saveErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return 'Not saved — you are not signed in (401). VITE_SKIP_AUTH does not create a real backend session; sign in through eVyoog Keycloak to save.'
    if (e.status === 409) return 'Not saved — that code is already in use by another product.'
    return `Not saved — ${e.detail ?? e.title} (${e.status}).`
  }
  return 'Not saved — the request failed before reaching the server.'
}

export type EditingProduct = {
  id: string
  key: string
  name: string
  vertical?: string
  purpose?: string
  ownerId?: string
  ownerName?: string
  lifecycleStatus: ProductLifecycleStatus
  mark: ProductMark
}

/**
 * VYB-0788: the product every application/capability/requirement in the portfolio
 * ultimately sits under — same fields whether creating one or editing an existing
 * card (`editing` prefills and switches the mutation and footer copy; nothing else
 * about the form changes shape between the two).
 */
export function NewProductModal({ editing, onClose }: { editing?: EditingProduct; onClose: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState(editing?.name ?? '')
  const [code, setCode] = useState(editing?.key ?? '')
  const [vertical, setVertical] = useState(editing?.vertical ?? '')
  const [purpose, setPurpose] = useState(editing?.purpose ?? '')
  const [ownerId, setOwnerId] = useState(editing?.ownerId ?? '')
  const [lifecycleStatus, setLifecycleStatus] = useState<ProductLifecycleStatus>(editing?.lifecycleStatus ?? 'IN_DEVELOPMENT')
  const [mark, setMark] = useState<ProductMark>(editing?.mark ?? DEFAULT_MARK)

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['products'] })
    void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
  }
  const create = useMutation({
    mutationFn: () => api.createProduct({ key: code.trim().toUpperCase(), name: name.trim(), vertical, purpose, ownerId: ownerId || undefined, lifecycleStatus, mark }),
    onSuccess: () => { invalidate(); onClose() },
  })
  const update = useMutation({
    mutationFn: () => api.updateProduct(editing!.id, { name: name.trim(), vertical, purpose, ownerId: ownerId || undefined, lifecycleStatus, mark }),
    onSuccess: () => { invalidate(); onClose() },
  })
  const saving = create.isPending || update.isPending
  const canSubmit = name.trim().length > 0 && code.trim().length > 0 && vertical.trim().length > 0 && purpose.trim().length > 0

  const selectedMark = MARKS.find((m) => m.key === mark) ?? MARKS[0]
  const MarkPreviewIcon = selectedMark.icon

  return (
    <Modal title={editing ? 'Edit product' : 'New product'} onClose={onClose} maxWidth={860}>
      <h3>{editing ? 'Edit product' : 'New product'}</h3>
      <p className="muted" style={{ fontSize: 12.5, marginTop: 0, marginBottom: 16 }}>
        A product is a vertical in the portfolio — the level every application, capability and requirement sits
        beneath. {editing ? 'Renaming it here does not move anything underneath it.' : 'Creating one gives you an empty container; add its applications next, from the Hierarchy tab.'}
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 260px', gap: 24 }}>
        <div>
          <div className="row">
            <div className="field">
              <label className="label">Product name *</label>
              <input className="input" autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Nirvaham" />
              <span className="hint">Shown in the serif brand face across the tool.</span>
            </div>
            <div className="field" style={{ maxWidth: 160 }}>
              <label className="label">Code *</label>
              <input
                className="input mono" value={code} disabled={!!editing}
                onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))}
                placeholder="NIR" maxLength={8}
              />
              <span className="hint">{editing ? "A product's code can't change once other IDs use it as a prefix." : 'Prefixes every ID in this product.'}</span>
            </div>
          </div>

          <div className="field">
            <label className="label">Vertical *</label>
            <input className="input" value={vertical} onChange={(e) => setVertical(e.target.value)} placeholder="e.g. Service Intelligence" />
            <span className="hint">The italic line under the name. Existing products end in "Intelligence".</span>
          </div>

          <div className="field">
            <label className="label">Description *</label>
            <textarea
              className="textarea" style={{ minHeight: 56 }} value={purpose} onChange={(e) => setPurpose(e.target.value)}
              placeholder="What does this product optimise, manage, or enable?"
            />
          </div>

          <div className="row">
            <div className="field">
              <label className="label">Product owner</label>
              <UserPicker value={ownerId} onSelect={setOwnerId} placeholder="Unassigned" />
            </div>
            <div className="field">
              <label className="label">Lifecycle status</label>
              <select className="select" value={lifecycleStatus} onChange={(e) => setLifecycleStatus(e.target.value as ProductLifecycleStatus)}>
                {LIFECYCLE_OPTIONS.map((s) => <option key={s} value={s}>{LIFECYCLE_LABEL[s]}</option>)}
              </select>
            </div>
          </div>

          <div className="field" style={{ marginBottom: 0 }}>
            <label className="label">Mark</label>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(8, 1fr)', gap: 6, maxWidth: 340 }}>
              {MARKS.map((m) => {
                const Icon = m.icon
                const selected = m.key === mark
                return (
                  <button
                    key={m.key} type="button" title={m.label} onClick={() => setMark(m.key)}
                    style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'center', height: 34,
                      borderRadius: 8, cursor: 'pointer',
                      background: selected ? 'var(--brand-dim)' : 'var(--panel-2)',
                      border: `1px solid ${selected ? 'var(--brand)' : 'var(--line-2)'}`,
                    }}
                  >
                    <Icon size={16} color={selected ? 'var(--brand)' : 'var(--tx-2)'} />
                  </button>
                )
              })}
            </div>
          </div>
        </div>

        <div>
          <div className="eyebrow" style={{ marginBottom: 8 }}>Preview</div>
          <div className="card" style={{ textAlign: 'center', padding: '22px 16px' }}>
            <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}>
              <div style={{
                width: 52, height: 52, borderRadius: 14, display: 'flex', alignItems: 'center', justifyContent: 'center',
                background: selectedMark.dim, border: `1px solid ${selectedMark.bd}`,
              }}>
                <MarkPreviewIcon size={26} color={selectedMark.color} />
              </div>
            </div>
            <div className="serif" style={{ fontSize: 20, fontWeight: 700 }}>{name.trim() || 'Product name'}</div>
            <div className="muted" style={{ fontStyle: 'italic', fontSize: 12.5, marginTop: 2 }}>{vertical.trim() || 'Vertical'}</div>
            <div className="muted" style={{ fontSize: 11.5, marginTop: 8, lineHeight: 1.5 }}>
              {purpose.trim() || 'Description appears here.'}
            </div>
          </div>

          {(!name.trim() || !purpose.trim()) && (
            <div className="hint" style={{
              marginTop: 12, padding: '10px 12px', borderRadius: 8, display: 'flex', gap: 8,
              background: 'var(--high-dim)', border: '1px solid var(--high-bd)', color: 'var(--high)', fontSize: 11.5,
            }}>
              <AlertTriangle style={{ flexShrink: 0, marginTop: 1 }} />
              <span>Enter a name and description before creating — both are used everywhere this product is referenced.</span>
            </div>
          )}
        </div>
      </div>

      {create.isError && <p className="err-text">{saveErrorMessage(create.error)}</p>}
      {update.isError && <p className="err-text">{saveErrorMessage(update.error)}</p>}

      <div className="actions" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <span className="hint muted mono" style={{ fontSize: 10.5 }}>
          {editing ? `${editing.key} — created before this edit` : 'No requirements are created yet'}
        </span>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn pri" disabled={!canSubmit || saving} onClick={() => (editing ? update.mutate() : create.mutate())}>
            {editing ? 'Save changes' : 'Create product'}
          </button>
        </div>
      </div>
    </Modal>
  )
}
