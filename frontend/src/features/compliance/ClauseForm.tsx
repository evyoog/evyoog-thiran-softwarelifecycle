import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type ClauseRegisterRow } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'

/** VYB-0947: add a clause, or edit one. Administrators only; the server decides and its refusal is shown as written. */
export function ClauseForm({ clause, onClose, onSaved }: {
  clause?: Pick<ClauseRegisterRow, 'id' | 'standard' | 'section' | 'text'>
  onClose: () => void
  onSaved: (id: string) => void
}) {
  const qc = useQueryClient()
  const [standard, setStandard] = useState(clause?.standard ?? '')
  const [section, setSection] = useState(clause?.section ?? '')
  const [text, setText] = useState(clause?.text ?? '')

  const save = useMutation({
    mutationFn: () => {
      const body = { standard: standard.trim(), section: section.trim() || undefined, text: text.trim() }
      return clause ? api.updateClause(clause.id, body) : api.createClause(body)
    },
    onSuccess: (saved) => {
      for (const key of ['clause-register', 'clause-mapping']) void qc.invalidateQueries({ queryKey: [key] })
      onSaved(saved.id)
      onClose()
    },
  })
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this clause.' : undefined
  const ready = standard.trim() !== '' && text.trim() !== ''

  return (
    <Modal onClose={onClose} title={clause ? 'Edit clause' : 'Add a clause'} maxWidth={560}>
      <h3 className="text-base font-semibold mb-2">{clause ? 'Edit clause' : 'Add a clause'}</h3>
      <div className="field">
        <label className="label" htmlFor="cl-standard">Standard</label>
        <input id="cl-standard" className="input" value={standard} maxLength={100} placeholder="ISO 27001" onChange={(e) => setStandard(e.target.value)} />
      </div>
      <div className="field">
        <label className="label" htmlFor="cl-section">Section (optional)</label>
        <input id="cl-section" className="input" value={section} maxLength={100} placeholder="A.5.1" onChange={(e) => setSection(e.target.value)} />
      </div>
      <div className="field">
        <label className="label" htmlFor="cl-text">The control, as worded</label>
        <textarea id="cl-text" className="input" rows={5} value={text} maxLength={4000} onChange={(e) => setText(e.target.value)} />
      </div>
      {problem && <p className="err-text" role="alert">{problem}</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!ready || save.isPending} onClick={() => save.mutate()}>{clause ? 'Save' : 'Add clause'}</button>
      </div>
    </Modal>
  )
}
