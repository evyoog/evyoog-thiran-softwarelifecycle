import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type AppUserView } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { announce } from '@/shared/ui/Announcer'
import { claimCandidates } from './claimCandidates'

/**
 * VYB-0961 (D49): joins a prepared entry to the account its person actually signed in with, when the address Keycloak sent was not the one
 * typed (they then have a second, empty account and no access). Never automatic: the administrator picks the account and confirms. The
 * accounts offered hold no access (the server also refuses one that does, belongs to a team, or has done anything), likely matches first.
 */
export function ClaimAccountDialog({ prepared, directory, onClose }: { prepared: AppUserView; directory: AppUserView[]; onClose: () => void }) {
  const qc = useQueryClient()
  const candidates = claimCandidates(prepared, directory)
  const [accountId, setAccountId] = useState<string>('')
  const chosen = candidates.find((c) => c.entry.id === accountId)?.entry
  const join = useMutation({
    mutationFn: () => api.claimAccount(prepared.id, accountId),
    onSuccess: () => {
      announce(`${prepared.displayName} is now joined to ${chosen?.email ?? 'that account'}.`)
      void qc.invalidateQueries({ queryKey: ['admin-users'] })
      onClose()
    },
  })
  const problem = join.error instanceof ApiError ? (join.error.detail ?? join.error.title) : join.isError ? 'Could not join these.' : undefined

  return (
    <Modal onClose={onClose} title={`Join ${prepared.displayName} to a signed-in account`}>
      <h2 style={{ fontSize: 15, margin: '0 0 6px' }}>Join {prepared.displayName} to a signed-in account</h2>
      <p className="hint muted" style={{ fontSize: 12 }}>
        {prepared.displayName} was prepared with {prepared.email}, and has not signed in under that address. If they have signed in under
        another one, they have a second account with no access. Pick it: its sign-in moves onto this entry, so the access prepared here
        becomes theirs, and the empty account is removed. An account that holds access, belongs to a team, or has done anything is never
        merged away.
      </p>
      {candidates.length === 0 && <p className="muted" style={{ fontSize: 12.5 }}>No signed-in account without access is waiting. Ask them to sign in once, then come back.</p>}
      {candidates.length > 0 && (
        <fieldset style={{ border: 0, padding: 0, margin: '8px 0' }}>
          <legend className="eyebrow" style={{ padding: 0 }}>Signed in, with no access</legend>
          {candidates.map(({ entry, likely }) => (
            <label key={entry.id} className="list-item" style={{ display: 'flex', gap: 8, alignItems: 'center', cursor: 'pointer' }}>
              <input type="radio" name="claim-account" value={entry.id} checked={accountId === entry.id} onChange={() => setAccountId(entry.id)} />
              <span style={{ flex: 1 }}>
                <strong style={{ fontSize: 12.5 }}>{entry.displayName}</strong>
                <span className="muted" style={{ fontSize: 11.5 }}> {entry.email}</span>
              </span>
              {likely && <span className="badge">likely</span>}
            </label>
          ))}
        </fieldset>
      )}
      {chosen && (
        <p style={{ fontSize: 12.5 }} role="status">
          {prepared.displayName}&rsquo;s entry will take over the sign-in of {chosen.displayName} ({chosen.email}); the separate account for that sign-in is removed.
        </p>
      )}
      {problem && <p className="err-text" role="alert">{problem}</p>}
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8 }}>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!chosen || join.isPending} onClick={() => join.mutate()}>{join.isPending ? 'Joining…' : 'Join'}</button>
      </div>
    </Modal>
  )
}
