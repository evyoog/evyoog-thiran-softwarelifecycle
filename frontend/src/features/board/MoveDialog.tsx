import { useState } from 'react'
import type { Requirement, RequirementStatus } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { moveLabel, reasonRequired } from '../requirements/lifecycle'
import { COLUMN_LABEL } from './boardModel'

/**
 * VYB-0953: confirms a status move from the board, and asks for a reason where the lifecycle needs one (the same cases as the
 * requirement page). The server decides whether this person may make the move; its refusal is shown here, as written.
 */
export function MoveDialog({ requirement, target, pending, error, onCancel, onConfirm }: {
  requirement: Pick<Requirement, 'key' | 'title' | 'status' | 'criteriaCount'>
  target: RequirementStatus
  pending: boolean
  error?: string
  onCancel: () => void
  onConfirm: (reason?: string) => void
}) {
  const [reason, setReason] = useState('')
  const needed = reasonRequired(requirement.status, target, requirement.criteriaCount)
  const label = moveLabel(requirement.status, target)
  return (
    <Modal onClose={onCancel} title="Move this requirement" maxWidth={480}>
      <h3 className="text-base font-semibold mb-1">{label.button}?</h3>
      <p className="text-sm text-tx-2" style={{ margin: '0 0 4px' }}><span className="mono">{requirement.key}</span> {requirement.title}</p>
      <p className="text-sm text-tx-2" style={{ margin: '0 0 10px' }}>{COLUMN_LABEL[requirement.status]} → {COLUMN_LABEL[target]}</p>
      {needed && requirement.status === 'DRAFT' && (
        <p className="text-sm" style={{ margin: '0 0 8px' }}>This has no acceptance criteria. Submitting it anyway needs a reason.</p>
      )}
      <div className="field">
        <label className="label" htmlFor="move-reason">Reason {needed ? '(required)' : '(optional)'}</label>
        <input id="move-reason" className="input" value={reason} autoFocus maxLength={1000} onChange={(e) => setReason(e.target.value)} />
      </div>
      {error && <p className="err-text" role="alert">{error}</p>}
      <div className="actions">
        <button className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn pri" disabled={pending || (needed && !reason.trim())} onClick={() => onConfirm(reason.trim() || undefined)}>{pending ? 'Moving…' : 'Confirm'}</button>
      </div>
    </Modal>
  )
}
