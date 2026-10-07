import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type AiProposal } from '@/shared/api/client'
import { acceptLabel, acceptProblem, detailOf, draftedText, editsFor, statusText } from './elaborationReview'

function messageOf(e: unknown): string {
  return e instanceof ApiError ? (e.detail ?? e.title) : 'Something went wrong.'
}

/**
 * VYB-0938 (F30): AI elaboration for the brief's scope, reviewed before it can reach a brief. "Draft elaborations" asks the AI
 * and records each answer as a pending proposal; nothing is in a brief yet. Each draft is accepted (as drafted or with the
 * person's own edit) or rejected here; "Include reviewed elaborations" in the brief then carries only the accepted ones, for
 * each requirement as it is now. The AI text is marked as AI throughout.
 */
export function ElaborationReview({ applicationId, capabilityIds }: { applicationId: string; capabilityIds: string[] }) {
  const qc = useQueryClient()
  const key = ['elaboration-status', applicationId, capabilityIds]
  const { data: status } = useQuery({ queryKey: key, queryFn: () => api.elaborationStatus(applicationId, capabilityIds), enabled: !!applicationId })
  const [note, setNote] = useState<string | undefined>()

  const draft = useMutation({
    mutationFn: () => api.draftElaborations(applicationId, capabilityIds),
    onSuccess: (d) => { setNote(draftedText(d.proposals, d.requirementsInScope)); void qc.invalidateQueries({ queryKey: ['elaboration-status'] }) },
    onError: () => setNote(undefined),
  })

  if (!applicationId) return null
  return (
    <div className="card" role="group" aria-label="AI elaboration review" style={{ marginTop: 10, borderColor: 'var(--ai-bd)' }}>
      <div className="eyebrow" style={{ marginBottom: 4, color: 'var(--ai-tx)' }}>AI elaboration · reviewed before it reaches a brief</div>
      <p className="hint" style={{ margin: '0 0 8px' }}>{statusText(status)}</p>
      <button className="btn" disabled={draft.isPending} onClick={() => { setNote(undefined); draft.mutate() }}>
        {draft.isPending ? 'Asking AI…' : 'Draft elaborations (AI)'}
      </button>
      {note && <p className="hint" role="status" style={{ marginTop: 6 }}>{note}</p>}
      {draft.isError && <p className="err-text" role="alert">{messageOf(draft.error)}</p>}
      {(status?.pending ?? []).map((p) => (
        <DraftRow key={p.id} proposal={p} onDecided={() => void qc.invalidateQueries({ queryKey: ['elaboration-status'] })} />
      ))}
    </div>
  )
}

function DraftRow({ proposal, onDecided }: { proposal: AiProposal; onDecided: () => void }) {
  const [text, setText] = useState(detailOf(proposal))
  const [error, setError] = useState<string | undefined>()
  const decide = useMutation({
    mutationFn: (accept: boolean) => api.decideProposal(proposal.id,
      accept ? { decision: 'ACCEPT', edits: editsFor(proposal, text) } : { decision: 'REJECT' }),
    onSuccess: onDecided,
    onError: (e) => setError(messageOf(e)),
  })
  const problem = acceptProblem(proposal, text)

  return (
    <div style={{ background: 'var(--ai-dim)', border: '1px solid var(--ai-bd)', borderRadius: 6, padding: 10, marginTop: 10 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span className="mono muted" style={{ fontSize: 10 }}>{proposal.requirementKey}</span>
        <strong style={{ fontSize: 12.5 }}>{proposal.requirementTitle}</strong>
        <span className="mono" style={{ fontSize: 10, color: 'var(--ai-tx)' }}>AI draft · {proposal.model}</span>
      </div>
      <textarea className="textarea" rows={4} value={text} onChange={(e) => { setText(e.target.value); setError(undefined) }}
        aria-label={`Elaboration for ${proposal.requirementKey}`} style={{ margin: '6px 0' }} />
      {problem && <p className="hint" style={{ color: 'var(--crit)' }}>{problem}</p>}
      {error && <p className="err-text" role="alert">{error}</p>}
      <div style={{ display: 'flex', gap: 6 }}>
        <button className="btn pri" disabled={!!problem || decide.isPending} onClick={() => decide.mutate(true)}>{acceptLabel(proposal, text)}</button>
        <button className="btn" disabled={decide.isPending} onClick={() => decide.mutate(false)}>Reject</button>
      </div>
    </div>
  )
}
