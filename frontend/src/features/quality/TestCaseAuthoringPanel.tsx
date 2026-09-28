import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { BulkTestCaseReviewPanel } from './BulkTestCaseReviewPanel'

type Mode = 'manual' | 'ai'

/**
 * VYB-0824/0830: replaces the old title-only "Draft test case" modal. Manual and AI
 * modes both end up marking the requirement(s) touched via {@code onGenerated} — an
 * accepted AI suggestion is never a separate write path, just a prefilled (and possibly
 * edited) manual one. The AI mode is {@link BulkTestCaseReviewPanel} seeded with just
 * this one requirement, so opening it here goes through the same full-dependency-cluster
 * preview a bulk selection does — "add a test case for this requirement" and "generate
 * for a selection that happens to contain one requirement" are the same flow.
 */
export function TestCaseAuthoringPanel({
  requirement, onClose, onGenerated,
}: { requirement: { id: string; key: string }; onClose: () => void; onGenerated: (touchedRequirementIds: string[]) => void }) {
  const [mode, setMode] = useState<Mode>('manual')

  if (mode === 'ai') {
    return <BulkTestCaseReviewPanel requirementIds={[requirement.id]} onClose={onClose} onGenerated={onGenerated} />
  }

  return (
    <Modal onClose={onClose} title="Add a test case">
      <h3>Add a test case</h3>
      <p className="hint muted">
        For <span className="mono">{requirement.key}</span>. This creates the test case and links it as VERIFIES —
        a proposal, not a run: it stays unverified until a real test run reports a pass against it.
      </p>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <button className={`btn${mode === 'manual' ? ' pri' : ''}`} onClick={() => setMode('manual')}>Manual</button>
        <button className="btn" onClick={() => setMode('ai')}>AI-generated</button>
      </div>

      <ManualEntry requirementId={requirement.id} onClose={onClose} onGenerated={onGenerated} />
    </Modal>
  )
}

function ManualEntry({
  requirementId, onClose, onGenerated,
}: { requirementId: string; onClose: () => void; onGenerated: (touchedRequirementIds: string[]) => void }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const draft = useMutation({
    mutationFn: () => api.draftTestCase(title, description.trim() || undefined, undefined, requirementId),
    onSuccess: () => { onGenerated([requirementId]); onClose() },
  })

  return (
    <>
      <div className="field">
        <label className="label">Title</label>
        <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
      </div>
      <div className="field">
        <label className="label">Description / steps (optional)</label>
        <textarea className="textarea" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {draft.isError && <p className="err-text">Could not add the test case.</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!title.trim() || draft.isPending} onClick={() => draft.mutate()}>
          Add
        </button>
      </div>
    </>
  )
}
