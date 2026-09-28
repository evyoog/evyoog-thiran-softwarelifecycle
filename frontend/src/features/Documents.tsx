import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Plus, FileDown, FileText } from 'lucide-react'
import { api } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { useRovingGrid } from '@/shared/ui/useRovingGrid'

/**
 * VYB-0210–0215: the document register, a prose view, Word/ReqIF export, and the
 * coverage matrix — filed under "Documents" in the spec but, like Import Queue, kept
 * as a sub-route of Requirements rather than an eleventh sidebar entry.
 */
export function Documents() {
  const [selected, setSelected] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['documents'], queryFn: api.documents })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['documents'] })
  // VYB-0767: Enter on a focused row opens it, same as the row's own onClick.
  const grid = useRovingGrid(data?.length ?? 0, 7, (row) => {
    const d = data?.[row]
    if (d) setSelected(d.id)
  })

  return (
    <Page
      title="Documents"
      desc="A curated, ordered subset of the register — a document register, a prose view, and export to Word or ReqIF."
      actions={<button className="btn pri" onClick={() => setShowCreate(true)}><Plus /> New document</button>}
    >
      {isLoading && <p className="eyebrow">Loading…</p>}
      {data && data.length === 0 && <Empty title="No documents yet" desc="Create the first one above." />}

      <div className="tbl-wrap">
        <table className="tbl" role="grid" aria-rowcount={data?.length ?? 0} aria-colcount={7} onKeyDown={grid.onKeyDown}>
          <thead><tr role="row"><th>Key</th><th>Title</th><th>Items</th><th>Revision</th><th>State</th><th>Gaps</th><th /></tr></thead>
          <tbody>
            {data?.map((d, rowIdx) => (
              <tr key={d.id} role="row" style={{ cursor: 'pointer' }} onClick={() => setSelected(d.id)}>
                <td className="mono key" {...grid.cellProps(rowIdx, 0)}>{d.key}</td>
                <td {...grid.cellProps(rowIdx, 1)}>{d.title}</td>
                <td className="mono" {...grid.cellProps(rowIdx, 2)}>{d.itemCount}</td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 3)}>{d.revision}</td>
                <td {...grid.cellProps(rowIdx, 4)}>{d.state}</td>
                <td className="mono" {...grid.cellProps(rowIdx, 5, { color: d.gapCount > 0 ? 'var(--high)' : undefined })}>{d.gapCount}</td>
                <td {...grid.cellProps(rowIdx, 6)}>
                  <button className="btn" onClick={(e) => { e.stopPropagation(); setSelected(d.id) }}>Open</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showCreate && <CreateDocumentModal onClose={() => setShowCreate(false)} onCreated={invalidate} />}
      {selected && <DocumentDetail id={selected} onClose={() => setSelected(null)} onChanged={invalidate} />}
    </Page>
  )
}

function CreateDocumentModal({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [key, setKey] = useState('')
  const [title, setTitle] = useState('')
  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const [productId, setProductId] = useState('')

  const create = useMutation({
    mutationFn: () => api.createDocument({ key, title, productId: productId || undefined }),
    onSuccess: () => { onCreated(); onClose() },
  })

  return (
    <Modal onClose={onClose} title="New document">
      <h3>New document</h3>
      <div className="field">
        <label className="label">Key</label>
        <input className="input" value={key} onChange={(e) => setKey(e.target.value)} placeholder="e.g. SPEC-1" />
      </div>
      <div className="field">
        <label className="label">Title</label>
        <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Product (optional)</label>
        <select className="select" value={productId} onChange={(e) => setProductId(e.target.value)}>
          <option value="">— none —</option>
          {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </div>
      {create.isError && <p className="err-text">Could not create — check the key is unique.</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!key.trim() || !title.trim() || create.isPending} onClick={() => create.mutate()}>
          Create
        </button>
      </div>
    </Modal>
  )
}

function DocumentDetail({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const [tab, setTab] = useState<'prose' | 'add'>('prose')
  const [addId, setAddId] = useState('')
  const qc = useQueryClient()
  const { data: reqs, isLoading } = useQuery({ queryKey: ['document-reqs', id], queryFn: () => api.documentRequirements(id) })

  const invalidate = () => { void qc.invalidateQueries({ queryKey: ['document-reqs', id] }); onChanged() }
  const add = useMutation({ mutationFn: (reqId: string) => api.addDocumentRequirement(id, reqId), onSuccess: () => { setAddId(''); invalidate() } })
  const remove = useMutation({ mutationFn: (reqId: string) => api.removeDocumentRequirement(id, reqId), onSuccess: invalidate })
  const move = useMutation({
    mutationFn: (orderedIds: string[]) => api.reorderDocumentRequirements(id, orderedIds), onSuccess: invalidate,
  })

  const moveItem = (index: number, dir: -1 | 1) => {
    if (!reqs) return
    const next = [...reqs]
    const swapWith = index + dir
    if (swapWith < 0 || swapWith >= next.length) return
    ;[next[index], next[swapWith]] = [next[swapWith], next[index]]
    move.mutate(next.map((r) => r.id))
  }

  const download = async (kind: 'word' | 'reqif') => {
    const blob = kind === 'word' ? await api.downloadDocumentWord(id) : await api.downloadDocumentReqIf(id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = kind === 'word' ? 'document.docx' : 'document.reqif'
    a.click()
    URL.revokeObjectURL(url)
  }

  // VYB-0211 AC1: numbering follows the capability structure — grouped here
  // client-side from each requirement's own capabilityId. Shown by id, truncated —
  // there's no single "all capabilities, globally" endpoint to resolve a name from
  // without knowing the application first, so the label is honestly the id, not a
  // guessed name.
  const byCapability = new Map<string, typeof reqs>()
  reqs?.forEach((r) => {
    const key = r.capabilityId ?? 'Unplaced'
    byCapability.set(key, [...(byCapability.get(key) ?? []), r])
  })

  return (
    <Modal onClose={onClose} title="Document">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <h3 style={{ margin: 0 }}>Document contents</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={() => download('word')}><FileDown /> Word</button>
          <button className="btn" onClick={() => download('reqif')}><FileText /> ReqIF</button>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        <button className={`btn${tab === 'prose' ? ' pri' : ''}`} onClick={() => setTab('prose')}>Prose view</button>
        <button className={`btn${tab === 'add' ? ' pri' : ''}`} onClick={() => setTab('add')}>Add / remove</button>
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {reqs && reqs.length === 0 && <Empty title="No requirements in this document yet" desc="Add one from the Add/remove tab." />}

      {tab === 'prose' && (
        <div style={{ maxHeight: 400, overflowY: 'auto' }}>
          {[...byCapability.entries()].map(([capId, items], secIdx) => (
            <div key={capId} style={{ marginBottom: 16 }}>
              <h4 className="section-h">{secIdx + 1}. {capId === 'Unplaced' ? 'Unplaced' : capId.slice(0, 8)}</h4>
              {items?.map((r, i) => (
                <div key={r.id} style={{ marginBottom: 10 }}>
                  <div style={{ fontWeight: 600, fontSize: 13 }}>{secIdx + 1}.{i + 1} {r.title} <span className="mono muted" style={{ fontSize: 10 }}>({r.key})</span></div>
                  <p style={{ fontSize: 12.5, margin: '4px 0', whiteSpace: 'pre-wrap' }}>{r.statement}</p>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {tab === 'add' && (
        <>
          {reqs?.map((r, i) => (
            <div key={r.id} className="list-item">
              <span className="mono muted" style={{ fontSize: 10 }}>{r.key}</span>
              <span style={{ flex: 1 }}>{r.title}</span>
              <button className="btn" style={{ padding: 4 }} disabled={i === 0} onClick={() => moveItem(i, -1)}>↑</button>
              <button className="btn" style={{ padding: 4 }} disabled={i === (reqs?.length ?? 0) - 1} onClick={() => moveItem(i, 1)}>↓</button>
              <button className="btn" onClick={() => remove.mutate(r.id)}>Remove</button>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
            <input className="input" style={{ flex: 1 }} placeholder="Requirement ID" value={addId} onChange={(e) => setAddId(e.target.value)} />
            <button className="btn pri" disabled={!addId.trim()} onClick={() => add.mutate(addId.trim())}>Add</button>
          </div>
        </>
      )}
    </Modal>
  )
}
