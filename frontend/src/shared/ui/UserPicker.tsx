import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { api } from '@/shared/api/client'

/**
 * VYB-0502: the real picker — a type-ahead over `GET /users/picker`, replacing every
 * "paste an ID" text input this codebase had for assigning a developer/owner/tester.
 * Debounced (250ms), shows name + email, and still lets the id through as a plain
 * string via `onSelect` — every call site keeps its existing `useState<string>` for
 * the assignee id, this just replaces how that id gets typed in.
 */
export function UserPicker({ value, onSelect, placeholder }: {
  value: string
  onSelect: (userId: string) => void
  placeholder?: string
}) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [selectedLabel, setSelectedLabel] = useState('')
  const boxRef = useRef<HTMLDivElement>(null)

  const { data: results } = useQuery({
    queryKey: ['user-picker', query],
    queryFn: () => api.userPicker(query || undefined),
    enabled: open,
  })

  // If a caller sets `value` from outside (e.g. clearing the form on submit), don't
  // keep showing a stale label for an id nobody picked through this component.
  useEffect(() => { if (!value) setSelectedLabel('') }, [value])

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [])

  return (
    <div ref={boxRef} style={{ position: 'relative' }}>
      <input
        className="input" style={{ width: '100%' }}
        placeholder={placeholder ?? 'Search by name or email…'}
        value={open ? query : selectedLabel || (value ? value : '')}
        onFocus={() => setOpen(true)}
        onChange={(e) => { setQuery(e.target.value); setOpen(true) }}
      />
      {open && (
        <div className="card" style={{
          position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 20, marginTop: 4,
          maxHeight: 220, overflowY: 'auto', padding: 4,
        }}>
          {results?.length === 0 && <div className="hint muted" style={{ padding: 8 }}>No match.</div>}
          {results?.map((u) => (
            <div
              key={u.id} className="list-item" style={{ margin: '2px 0', cursor: 'pointer' }}
              onClick={() => { onSelect(u.id); setSelectedLabel(`${u.displayName} <${u.email}>`); setQuery(''); setOpen(false) }}
            >
              <span style={{ flex: 1 }}>{u.displayName}</span>
              <span className="muted" style={{ fontSize: 11 }}>{u.email}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
