import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type NotificationChannelStats } from '@/shared/api/client'
import { announce } from '@/shared/ui/Announcer'
import { differs, groupByGroup, toggled } from '@/app/notificationPrefs'

/**
 * VYB-0955 (D47): how notifications leave the app. Whether email and chat are set up (they are set in the deployment's environment,
 * never here: the mail password and the chat address are secrets), which kinds of event are posted to the team chat channel, a test
 * message to each channel, and how the queue is doing. The chat address is never shown.
 */
export function NotificationsTab() {
  const qc = useQueryClient()
  const { data, isLoading, isError } = useQuery({ queryKey: ['notification-settings'], queryFn: api.notificationSettings, refetchInterval: 30_000 })
  const [chosen, setChosen] = useState<Set<string> | undefined>()
  const [testNote, setTestNote] = useState<{ ok: boolean; text: string }>()

  const save = useMutation({
    mutationFn: (kinds: string[]) => api.setNotificationChatKinds(kinds),
    onSuccess: (view) => {
      qc.setQueryData(['notification-settings'], view)
      setChosen(undefined)
      announce('Saved')
    },
  })
  const test = useMutation({
    mutationFn: (channel: 'EMAIL' | 'CHAT') => api.testNotificationChannel(channel),
    onSuccess: (r) => { setTestNote({ ok: r.ok, text: r.detail }); void qc.invalidateQueries({ queryKey: ['notification-settings'] }) },
    onError: () => setTestNote({ ok: false, text: 'Could not send the test.' }),
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) return <p className="err-text" role="alert">Could not load the notification settings.</p>

  const on = chosen ?? new Set(data.chatKinds.filter((k) => k.enabled).map((k) => k.kind))
  const changed = chosen !== undefined && differs(chosen, data.chatKinds.filter((k) => k.enabled).map((k) => k.kind))
  const groups = groupByGroup(data.chatKinds)
  const problem = save.error instanceof ApiError ? (save.error.detail ?? save.error.title) : save.isError ? 'Could not save this change.' : undefined

  function toggle(kind: string, checked: boolean) {
    setChosen(toggled(on, kind, checked))
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <div className="card">
        <div className="eyebrow" style={{ marginBottom: 4 }}>Email</div>
        <ChannelStatus configured={data.emailConfigured} stats={data.email}
          off="Email is not set up. Set MAIL_HOST, MAIL_FROM (and, if the server needs it, MAIL_USERNAME and MAIL_PASSWORD) in the deployment and restart. Until then nobody is emailed; the inbox works as before." />
        {data.emailConfigured && !data.linksConfigured && (
          <p className="hint" style={{ fontSize: 11.5 }}>PUBLIC_BASE_URL is not set, so emails and chat posts carry no link back to Vyoog.</p>
        )}
        <button className="btn" disabled={!data.emailConfigured || test.isPending} onClick={() => test.mutate('EMAIL')}>Send a test email to me</button>
      </div>

      <div className="card">
        <div className="eyebrow" style={{ marginBottom: 4 }}>Team chat channel (Microsoft Teams)</div>
        <ChannelStatus configured={data.chatConfigured} stats={data.chat}
          off="Chat is not set up. Set NOTIFY_TEAMS_WEBHOOK_URL (a Teams incoming webhook address) in the deployment and restart. The choices below are kept until then." />
        <p className="hint muted" style={{ fontSize: 11.5 }}>
          One post per event goes to the channel, for the kinds ticked below. A post carries the title and a link, never a reason or a
          comment. Kinds that are personal to one person (mentions, clarifications, defects) cannot be posted to a shared channel.
        </p>
        {groups.map(([group, kinds]) => (
          <fieldset key={group} style={{ border: 0, padding: 0, margin: '0 0 8px' }}>
            <legend className="eyebrow" style={{ padding: 0 }}>{group}</legend>
            {kinds.map((k) => (
              <label key={k.kind} style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5, padding: '2px 0' }}>
                <input type="checkbox" checked={on.has(k.kind)} onChange={(e) => toggle(k.kind, e.target.checked)} />
                {k.label}
              </label>
            ))}
          </fieldset>
        ))}
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button className="btn pri" disabled={!changed || save.isPending} onClick={() => save.mutate([...on])}>
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
          <button className="btn" disabled={!data.chatConfigured || test.isPending} onClick={() => test.mutate('CHAT')}>Post a test message</button>
        </div>
        {problem && <p className="err-text" role="alert">{problem}</p>}
      </div>

      {testNote && <p className={testNote.ok ? 'hint' : 'err-text'} role={testNote.ok ? 'status' : 'alert'}>{testNote.text}</p>}
    </div>
  )
}

function ChannelStatus({ configured, stats, off }: { configured: boolean; stats: NotificationChannelStats; off: string }) {
  if (!configured) return <p className="hint" style={{ fontSize: 11.5 }}>{off}</p>
  return (
    <>
      <p className="hint" style={{ fontSize: 11.5 }}>
        Set up. Waiting to be sent: {stats.pending}. Sent in the last 24 hours: {stats.sentLast24h}. Failed in the last 24 hours: {stats.failedLast24h}.
      </p>
      {stats.lastError && <p className="muted" style={{ fontSize: 11.5 }}>Last problem: {stats.lastError}</p>}
    </>
  )
}
