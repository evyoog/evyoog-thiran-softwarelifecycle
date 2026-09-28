import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Bell } from 'lucide-react'
import { api } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { announce } from '@/shared/ui/Announcer'

/**
 * VYB-0355: the inbox itself — built alongside its own backend in an earlier session
 * but never actually rendered anywhere until now. VYB-0357: {@code occurrenceCount}
 * is shown per item, so "3 defects routed to you" reads as one line, not three.
 * VYB-0791: a real SSE push replaces the old 30s poll — the count/inbox refresh the
 * moment the relay actually delivers something, not up to 30s later. The poll isn't
 * kept as a "just in case" fallback underneath it — if the stream is down, retrying
 * the stream is the fix; a hidden second polling loop would just mask that.
 */
export function NotificationBell() {
  const [open, setOpen] = useState(false)
  const qc = useQueryClient()
  const { data: unread } = useQuery({ queryKey: ['unread-count'], queryFn: api.unreadNotificationCount })
  const { data: inbox } = useQuery({ queryKey: ['notifications'], queryFn: api.notifications, enabled: open })

  useEffect(() => {
    return api.subscribeToNotifications((event) => {
      void qc.invalidateQueries({ queryKey: ['unread-count'] })
      void qc.invalidateQueries({ queryKey: ['notifications'] })
      announce(event.title || 'New notification')
    })
  }, [qc])

  const markRead = useMutation({
    mutationFn: (id: string) => api.markNotificationRead(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['notifications'] })
      void qc.invalidateQueries({ queryKey: ['unread-count'] })
    },
  })

  return (
    <>
      <button className="btn" style={{ padding: 5, position: 'relative' }} title="Notifications" onClick={() => setOpen(true)}>
        <Bell />
        {!!unread && unread > 0 && (
          <span
            className="mono"
            style={{
              position: 'absolute', top: -4, right: -4, background: 'var(--crit)', color: '#fff',
              borderRadius: 8, fontSize: 9, padding: '1px 4px', minWidth: 14, textAlign: 'center',
            }}
          >
            {unread}
          </span>
        )}
      </button>
      {open && (
        <Modal onClose={() => setOpen(false)} title="Notifications">
          <h3>Notifications</h3>
          {inbox && inbox.length === 0 && <p className="muted" style={{ fontSize: 12.5 }}>Nothing here.</p>}
          {inbox?.map((n) => (
            <div
              key={n.id} className="list-item" style={{ alignItems: 'flex-start', opacity: n.read ? 0.6 : 1 }}
            >
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  <strong style={{ fontSize: 12.5 }}>{n.title}</strong>
                  {n.occurrenceCount > 1 && <span className="badge">×{n.occurrenceCount}</span>}
                </div>
                {n.subtitle && <div className="muted" style={{ fontSize: 11.5 }}>{n.subtitle}</div>}
                <div className="mono muted" style={{ fontSize: 10 }}>{new Date(n.createdAt).toLocaleString()}</div>
              </div>
              {!n.read && <button className="btn" style={{ padding: 4 }} onClick={() => markRead.mutate(n.id)}>Mark read</button>}
            </div>
          ))}
        </Modal>
      )}
    </>
  )
}
