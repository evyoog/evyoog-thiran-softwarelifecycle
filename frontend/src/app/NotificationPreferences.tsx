import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, api, type NotificationPreferenceChange, type NotificationPreferenceKindView } from '@/shared/api/client'
import { announce } from '@/shared/ui/Announcer'
import { groupByGroup } from './notificationPrefs'

/**
 * VYB-0955 (D47): a person's own choice of which kinds of notification they get in the inbox and by email. Each box saves as it is
 * changed. Urgent kinds (something you must act on) are emailed at once by default; the rest are off for email until chosen, and
 * then arrive batched. Chat is not here: it is a team channel the administrator sets up.
 */
export function NotificationPreferences() {
  const qc = useQueryClient()
  const { data, isLoading, isError } = useQuery({ queryKey: ['notification-preferences'], queryFn: api.notificationPreferences })
  const save = useMutation({
    mutationFn: (changes: NotificationPreferenceChange[]) => api.setNotificationPreferences(changes),
    onSuccess: (view) => {
      qc.setQueryData(['notification-preferences'], view)
      announce('Saved')
    },
  })

  if (isLoading) return <p className="muted" style={{ fontSize: 12.5 }}>Loading…</p>
  if (isError || !data) return <p className="err-text" role="alert">Could not load your notification choices.</p>

  const groups = groupByGroup<NotificationPreferenceKindView>(data.kinds)
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this change.' : undefined

  return (
    <div>
      <p className="hint muted" style={{ fontSize: 11.5, marginTop: 0 }}>
        Choose what reaches you. Email marked <strong>at once</strong> is for things you have to act on; the rest are sent together, a few
        minutes after the first, if you switch them on.
      </p>
      {!data.emailAvailable && (
        <p className="hint" style={{ fontSize: 11.5 }} role="note">
          Email is not set up on this deployment, so nothing is sent by email yet. Your choices are kept for when it is.
        </p>
      )}
      {groups.map(([group, kinds]) => (
        <fieldset key={group} style={{ border: 0, padding: 0, margin: '0 0 12px' }}>
          <legend className="eyebrow" style={{ padding: 0, marginBottom: 4 }}>{group}</legend>
          {kinds.map((k) => (
            <div key={k.kind} className="list-item" style={{ alignItems: 'flex-start', gap: 10 }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <strong style={{ fontSize: 12.5 }}>{k.label}</strong>
                <div className="muted" style={{ fontSize: 11.5 }}>{k.description}</div>
                {(!k.inboxIsDefault || !k.emailIsDefault) && (
                  <button className="btn" style={{ padding: '2px 6px', fontSize: 11, marginTop: 4 }} disabled={save.isPending}
                    onClick={() => save.mutate([{ kind: k.kind, channel: 'INBOX', enabled: null }, { kind: k.kind, channel: 'EMAIL', enabled: null }])}>
                    Use the default for “{k.label}”
                  </button>
                )}
              </div>
              <label style={{ display: 'flex', gap: 4, alignItems: 'center', fontSize: 12, minWidth: 62 }}>
                <input type="checkbox" checked={k.inbox} disabled={save.isPending}
                  onChange={(e) => save.mutate([{ kind: k.kind, channel: 'INBOX', enabled: e.target.checked }])} />
                Inbox
              </label>
              <label style={{ display: 'flex', gap: 4, alignItems: 'center', fontSize: 12, minWidth: 112 }}>
                <input type="checkbox" checked={k.email} disabled={save.isPending}
                  onChange={(e) => save.mutate([{ kind: k.kind, channel: 'EMAIL', enabled: e.target.checked }])} />
                Email{k.urgent ? ' (at once)' : ''}
              </label>
            </div>
          ))}
        </fieldset>
      ))}
      {problem && <p className="err-text" role="alert">{problem}</p>}
    </div>
  )
}
