import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type AcceptanceCriterion, type PlannerProposal, type Requirement } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { acceptBlockedReason, diffProposal, splitProposals, stateNote } from './plannerProposals'

/**
 * VYB-0919 (D24, D51): the changes the Agile Planner proposed to this requirement, and a person's decision on each. The Planner's words
 * are hatched and say whose they are (they are not this requirement's until accepted); accepting makes a new revision by the person who
 * accepts, adds the criteria the requirement lacks and removes none; rejecting changes nothing. Each state is a glyph and words, never
 * colour alone, and nothing here is amber (amber means AI). The server refuses anyone who may not edit the requirement.
 */
export function PlannerProposalsPanel({ requirement, criteria }: { requirement: Requirement; criteria: AcceptanceCriterion[] | undefined }) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['planner-proposals', requirement.id], queryFn: () => api.plannerProposals(requirement.id) })
  const [deciding, setDeciding] = useState<{ proposal: PlannerProposal; action: 'accept' | 'reject' } | null>(null)
  const [note, setNote] = useState('')

  const decide = useMutation({
    mutationFn: ({ proposal, action }: { proposal: PlannerProposal; action: 'accept' | 'reject' }) =>
      action === 'accept' ? api.acceptPlannerProposal(requirement.id, proposal.id) : api.rejectPlannerProposal(requirement.id, proposal.id, note),
    onSuccess: () => {
      setDeciding(null)
      setNote('')
      for (const key of ['planner-proposals', 'requirement', 'requirements', 'acceptance-criteria', 'requirement-history', 'lifecycle']) {
        void qc.invalidateQueries({ queryKey: [key] })
      }
    },
  })
  const problem = decide.error instanceof ApiError ? (decide.error.detail ?? decide.error.title) : decide.isError ? 'Could not record the decision.' : undefined

  const { pending, decided } = splitProposals(data)
  if (pending.length === 0 && decided.length === 0) return null
  const texts = (criteria ?? []).map((c) => c.text)
  const blocked = acceptBlockedReason(requirement.status)

  return (
    <section className="mb-5 space-y-3" aria-label="Changes proposed by the Agile Planner">
      {pending.map((p) => {
        const d = diffProposal(requirement, texts, p)
        return (
          <div key={p.id} className="bg-panel-2 border border-line rounded-lg p-4" role="group" aria-label={`Change proposed by the Agile Planner for Function ${p.sourceFunctionId}`}>
            <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
              <span aria-hidden="true">{stateNote(p.state).glyph}</span>
              <span>The Agile Planner changed Function {p.sourceFunctionId}</span>
              <span className="text-tx-3 font-normal">· {stateNote(p.state).text} · received {new Date(p.receivedAt).toLocaleString()}</span>
            </div>
            <p className="text-sm text-tx-2 mt-1">
              Nothing has been changed here. Accepting makes a new revision with the Planner's words; rejecting keeps this requirement as it is.
            </p>
            {d.nothingChanges ? (
              <p className="text-sm text-tx-2 mt-2">This says what the requirement already says, so accepting would change nothing.</p>
            ) : (
              <div className="cn-borrowed mt-2 space-y-2">
                <div className="cn-src">reported by Agile Planner</div>
                {d.title && <Change label="Title" now={d.title.now} proposed={d.title.proposed} />}
                {d.statement && <Change label="Statement" now={d.statement.now} proposed={d.statement.proposed} />}
                {d.priority && <Change label="Priority" now={d.priority.now} proposed={d.priority.proposed} />}
                {d.criteriaAdded.length > 0 && (
                  <div className="text-sm">
                    <div className="text-tx-3">Acceptance criteria that would be added</div>
                    <ul className="list-disc pl-5">{d.criteriaAdded.map((c) => <li key={c}>{c}</li>)}</ul>
                  </div>
                )}
                {d.criteriaKept.length > 0 && (
                  <p className="text-xs text-tx-3">
                    {d.criteriaKept.length} existing criteri{d.criteriaKept.length === 1 ? 'on is' : 'a are'} not in the Planner's list and would stay;
                    accepting never removes one.
                  </p>
                )}
              </div>
            )}
            {blocked && <p className="text-sm text-tx-2 mt-2" role="note">{blocked}</p>}
            <div className="flex gap-2 justify-end mt-3">
              <button className="btn" onClick={() => { decide.reset(); setDeciding({ proposal: p, action: 'reject' }) }}>Reject</button>
              <button className="btn pri" disabled={!!blocked} onClick={() => { decide.reset(); setDeciding({ proposal: p, action: 'accept' }) }}>Accept</button>
            </div>
          </div>
        )
      })}

      {decided.length > 0 && (
        <details className="text-sm">
          <summary className="cursor-pointer text-tx-2">Earlier proposals from the Agile Planner ({decided.length})</summary>
          <ul className="mt-2 space-y-1">
            {decided.map((p) => (
              <li key={p.id} className="flex flex-wrap gap-x-2">
                <span aria-hidden="true">{stateNote(p.state).glyph}</span>
                <span>{stateNote(p.state).text}{p.decidedBy ? ` by ${p.decidedBy}` : ''}{p.decidedAt ? ` on ${new Date(p.decidedAt).toLocaleDateString()}` : ''}</span>
                <span className="text-tx-3">· “{p.title}”{p.appliedRevision ? ` · revision ${p.appliedRevision}` : ''}{p.decisionNote ? ` · ${p.decisionNote}` : ''}</span>
              </li>
            ))}
          </ul>
        </details>
      )}

      {deciding && (
        <Modal onClose={() => setDeciding(null)} title={deciding.action === 'accept' ? 'Accept the Planner\'s change' : 'Reject the Planner\'s change'}>
          <h2 className="text-base font-semibold mb-2">{deciding.action === 'accept' ? 'Accept the Planner\'s change' : 'Reject the Planner\'s change'}</h2>
          {deciding.action === 'accept' ? (
            <p className="text-sm text-tx-2">
              {requirement.key} becomes revision {requirement.revision + 1}, with the title, statement and priority the Planner sent, made by you.
              The criteria it lacks are added and none is removed. The type and where it sits stay as they are.
            </p>
          ) : (
            <label className="block text-sm">
              <span className="text-tx-3">Why (optional, kept with the decision)</span>
              <textarea className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
            </label>
          )}
          {problem && <p className="text-sm text-crit mt-2" role="alert">{problem}</p>}
          <div className="flex gap-2 justify-end mt-4">
            <button className="btn" onClick={() => setDeciding(null)}>Cancel</button>
            <button className="btn pri" disabled={decide.isPending} onClick={() => decide.mutate(deciding)}>
              {deciding.action === 'accept' ? 'Accept the change' : 'Reject the change'}
            </button>
          </div>
        </Modal>
      )}
    </section>
  )
}

function Change({ label, now, proposed }: { label: string; now: string; proposed: string }) {
  return (
    <div className="text-sm">
      <div className="text-tx-3">{label}</div>
      <div><span className="text-tx-3">Now: </span>{now}</div>
      <div><span className="text-tx-3">Proposed: </span>{proposed}</div>
    </div>
  )
}
