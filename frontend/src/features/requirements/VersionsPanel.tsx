import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { GitBranch } from 'lucide-react'
import { ApiError, api, type RequirementStatus } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { StatusBadge } from '@/shared/ui/Badges'
import { NO_ELIGIBLE_CR, canFork, eligibleChangeRequests, versionNotes } from './versions'

/**
 * VYB-0942 (D35): the versions of a requirement and "Fork a new version". A fork is a new requirement (a new key, a draft) copied
 * from an approved one for an approved change request; approving it replaces the original. Each line below says what it is in
 * words with a glyph, links to the other requirement, and none is amber. Editing in place under a change request is the other way
 * to act on one and is unchanged (the box in the banner above).
 */
export function VersionsPanel({ requirementId, status, canEdit = true }: { requirementId: string; status: RequirementStatus; canEdit?: boolean }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { data: versions } = useQuery({ queryKey: ['requirement-versions', requirementId], queryFn: () => api.requirementVersions(requirementId) })
  const { data: changeRequests } = useQuery({
    queryKey: ['change-requests'], queryFn: api.changeRequests, enabled: status === 'APPROVED',
  })
  const [open, setOpen] = useState(false)
  const [crId, setCrId] = useState('')
  const eligible = eligibleChangeRequests(changeRequests, requirementId)
  const chosen = crId || eligible[0]?.id || ''

  const fork = useMutation({
    mutationFn: () => api.forkRequirement(requirementId, chosen),
    onSuccess: (created) => {
      void qc.invalidateQueries({ queryKey: ['requirement-versions'] })
      void qc.invalidateQueries({ queryKey: ['requirements'] })
      setOpen(false)
      navigate(`/requirements/${created.id}`)
    },
  })
  const problem = fork.error instanceof ApiError ? (fork.error.detail ?? fork.error.title) : fork.isError ? 'Could not fork this requirement.' : undefined

  const notes = versionNotes(versions)
  const showFork = canEdit && canFork(status, versions)
  if (notes.length === 0 && !showFork) return null

  return (
    <div className="mb-5 space-y-2" aria-label="Versions">
      {notes.map((n) => (
        <div key={n.text} className="flex flex-wrap items-center gap-2 bg-panel-2 border border-line rounded-lg px-4 py-2.5 text-sm" role="note">
          <span aria-hidden="true">{n.glyph}</span>
          <span>{n.text}</span>
          {n.target && (
            <>
              <button className="underline text-brand" onClick={() => navigate(`/requirements/${n.target!.id}`)}>Open {n.target.key}</button>
              <StatusBadge status={n.target.status} />
            </>
          )}
        </div>
      ))}
      {showFork && (
        <button className="btn" onClick={() => setOpen(true)}><GitBranch size={14} /> Fork a new version</button>
      )}
      {open && (
        <Modal onClose={() => setOpen(false)} title="Fork a new version">
          <h3 className="text-base font-semibold mb-2">Fork a new version</h3>
          <p className="text-sm text-tx-2">
            This makes a new requirement, a draft with its own key, copied from this one (wording, criteria, and the links to its parents).
            This one stays approved until the new version is approved, and is then marked replaced. Tests, design and code stay with this one.
          </p>
          {eligible.length === 0 ? (
            <p className="text-sm text-tx-3 mt-3" role="status">{NO_ELIGIBLE_CR}</p>
          ) : (
            <label className="block mt-3 text-sm">
              <span className="text-tx-3">For which approved change request</span>
              <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm" value={chosen} onChange={(e) => setCrId(e.target.value)}>
                {eligible.map((c) => <option key={c.id} value={c.id}>{c.key} — {c.title}</option>)}
              </select>
            </label>
          )}
          {problem && <p className="text-sm text-crit mt-2" role="alert">{problem}</p>}
          <div className="flex gap-2 justify-end mt-4">
            <button className="btn" onClick={() => setOpen(false)}>Cancel</button>
            <button className="btn pri" disabled={eligible.length === 0 || fork.isPending} onClick={() => fork.mutate()}>
              {fork.isPending ? 'Forking…' : 'Fork a new version'}
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}
