import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Modal } from '@/shared/ui/Modal'
import { api, type SearchResult, type SearchResultKind } from '@/shared/api/client'
import type { NavItem } from './nav'

const KIND_LABEL: Record<SearchResultKind, string> = {
  REQUIREMENT: 'Requirements', CAPABILITY: 'Capabilities', GLOSSARY_TERM: 'Glossary terms', FINDING: 'Findings',
}
// None of these four kinds has its own deep-link route yet — each search result opens
// the screen that owns it, not a single-record page that doesn't exist.
const KIND_ROUTE: Record<SearchResultKind, (id: string) => string> = {
  REQUIREMENT: (id) => `/requirements/${id}`,
  CAPABILITY: () => '/portfolio',
  GLOSSARY_TERM: () => '/portfolio',
  FINDING: () => '/analytics',
}

type Entry = { key: string; label: string; icon?: NavItem['icon']; onSelect: () => void }

/**
 * VYB-0765/0766: Ctrl/Cmd+K opens it from anywhere. AC1 (0765): navigation entries
 * come straight from the same {@code NAV} array the sidebar renders. AC1 (0766):
 * global search results render grouped by kind, in their own section below.
 */
export function CommandPalette({ items }: { items: NavItem[] }) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState(0)
  const navigate = useNavigate()

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen((o) => !o)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  const { data: searchResults } = useQuery({
    queryKey: ['global-search', query],
    queryFn: () => api.search(query),
    enabled: open && query.trim().length >= 2,
  })

  const navMatches = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? items.filter((i) => i.label.toLowerCase().includes(q)) : items
  }, [items, query])

  const close = () => { setOpen(false); setQuery('') }

  const groups = useMemo(() => {
    const g: { label: string; entries: Entry[] }[] = [
      { label: 'Modules', entries: navMatches.map((i) => ({ key: `nav-${i.key}`, label: i.label, icon: i.icon, onSelect: () => { navigate(i.path); close() } })) },
    ]
    if (searchResults && searchResults.length > 0) {
      const byKind = new Map<SearchResultKind, SearchResult[]>()
      for (const r of searchResults) byKind.set(r.kind, [...(byKind.get(r.kind) ?? []), r])
      for (const [kind, results] of byKind) {
        g.push({
          label: KIND_LABEL[kind],
          entries: results.map((r) => ({
            key: `${kind}-${r.id}`, label: r.label,
            onSelect: () => { navigate(KIND_ROUTE[kind](r.id)); close() },
          })),
        })
      }
    }
    return g.filter((grp) => grp.entries.length > 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navMatches, searchResults])

  const flat = useMemo(() => groups.flatMap((g) => g.entries), [groups])

  useEffect(() => setSelected(0), [query, flat.length])

  if (!open) return null

  return (
    <Modal onClose={close} title="Command palette">
      <input
        className="input" autoFocus placeholder="Jump to a module, or search requirements/capabilities/glossary/findings…"
        style={{ width: '100%', marginBottom: 10 }}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setSelected((s) => Math.min(s + 1, flat.length - 1)) }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setSelected((s) => Math.max(s - 1, 0)) }
          else if (e.key === 'Enter' && flat[selected]) flat[selected].onSelect()
        }}
      />
      {flat.length === 0 && <p className="muted" style={{ fontSize: 12.5 }}>No matches.</p>}
      {groups.map((g) => (
        <div key={g.label} style={{ marginBottom: 10 }}>
          <div className="eyebrow" style={{ fontSize: 10, marginBottom: 4 }}>{g.label}</div>
          {g.entries.map((entry) => {
            const idx = flat.indexOf(entry)
            return (
              <div
                key={entry.key} className="list-item" tabIndex={0}
                style={{ cursor: 'pointer', borderColor: idx === selected ? 'var(--brand)' : undefined }}
                onMouseEnter={() => setSelected(idx)}
                onClick={entry.onSelect}
                onKeyDown={(e) => { if (e.key === 'Enter') entry.onSelect() }}
              >
                {entry.icon && <entry.icon style={{ width: 16, height: 16 }} />}
                <span style={{ marginLeft: entry.icon ? 8 : 0 }}>{entry.label}</span>
              </div>
            )
          })}
        </div>
      ))}
      <p className="hint muted" style={{ marginTop: 8, fontSize: 10.5 }}>↑↓ to move · Enter to open · Esc to close</p>
    </Modal>
  )
}
