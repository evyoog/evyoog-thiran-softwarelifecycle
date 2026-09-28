import {
  Box, Sun, Shuffle, Calendar, Database, Target, Layers, BarChart3,
  GitBranch, Shield, Bookmark, FileText, type LucideIcon,
} from 'lucide-react'
import type { ProductMark } from '@/shared/api/client'

/**
 * VYB-0788: the fixed icon+colour pair a product's `mark` resolves to. The set of
 * keys here must exactly match V011's CHECK constraint — this is the one place that
 * mapping lives, so a key can't drift between what the database will accept and what
 * the frontend can render (the same drift class this project keeps finding and fixing
 * elsewhere — see BUILD-REGISTER.md's `TenantExportService.TABLES` write-up).
 */
// VYB-0770/contrast.test.ts: reuses the exact --*/-dim/-bd token triads the app's own
// badges already use (session 16's contrast pass verified every one of these pairings
// for real, parsed straight out of tokens.css) — not a computed colour, which that
// checker has no way to see.
// `accent` is the class the Portfolio card sets to re-bind its --prod* trio to this
// mark (see tokens.css `.pf-card.acc-*`). It is empty for the marks already on --prod,
// since that's the default — and it exists as a class rather than an inline
// `--prod: var(--prod)` because that form is self-referential and CSS discards it,
// which silently flattened every card using the default mark.
export const MARKS: {
  key: ProductMark; label: string; icon: LucideIcon
  color: string; dim: string; bd: string; accent: string
}[] = [
  { key: 'box', label: 'Box', icon: Box, color: 'var(--prod)', dim: 'var(--prod-dim)', bd: 'var(--prod-bd)', accent: '' },
  { key: 'sun', label: 'Sun', icon: Sun, color: 'var(--high)', dim: 'var(--high-dim)', bd: 'var(--high-bd)', accent: 'acc-high' },
  { key: 'shuffle', label: 'Shuffle', icon: Shuffle, color: 'var(--info)', dim: 'var(--info-dim)', bd: 'var(--info-bd)', accent: 'acc-info' },
  { key: 'calendar', label: 'Calendar', icon: Calendar, color: 'var(--ok)', dim: 'var(--ok-dim)', bd: 'var(--ok-bd)', accent: 'acc-ok' },
  { key: 'database', label: 'Database', icon: Database, color: 'var(--brand)', dim: 'var(--brand-dim)', bd: 'var(--brand-bd)', accent: 'acc-brand' },
  { key: 'target', label: 'Target', icon: Target, color: 'var(--crit)', dim: 'var(--crit-dim)', bd: 'var(--crit-bd)', accent: 'acc-crit' },
  { key: 'layers', label: 'Layers', icon: Layers, color: 'var(--prod)', dim: 'var(--prod-dim)', bd: 'var(--prod-bd)', accent: '' },
  { key: 'bar-chart', label: 'Bar chart', icon: BarChart3, color: 'var(--info)', dim: 'var(--info-dim)', bd: 'var(--info-bd)', accent: 'acc-info' },
  { key: 'git-branch', label: 'Branch', icon: GitBranch, color: 'var(--ok)', dim: 'var(--ok-dim)', bd: 'var(--ok-bd)', accent: 'acc-ok' },
  { key: 'shield', label: 'Shield', icon: Shield, color: 'var(--brand)', dim: 'var(--brand-dim)', bd: 'var(--brand-bd)', accent: 'acc-brand' },
  { key: 'bookmark', label: 'Bookmark', icon: Bookmark, color: 'var(--high)', dim: 'var(--high-dim)', bd: 'var(--high-bd)', accent: 'acc-high' },
  { key: 'file-text', label: 'Document', icon: FileText, color: 'var(--crit)', dim: 'var(--crit-dim)', bd: 'var(--crit-bd)', accent: 'acc-crit' },
]

export const DEFAULT_MARK: ProductMark = 'box'

export function markOf(key: string | undefined): (typeof MARKS)[number] {
  return MARKS.find((m) => m.key === key) ?? MARKS[0]
}

export function MarkIcon({ mark, size = 22 }: { mark: string | undefined; size?: number }) {
  const m = markOf(mark)
  const Icon = m.icon
  return (
    <div
      style={{
        width: size + 22, height: size + 22, borderRadius: 12, display: 'flex',
        alignItems: 'center', justifyContent: 'center', flexShrink: 0,
        background: m.dim, border: `1px solid ${m.bd}`,
      }}
    >
      <Icon size={size} color={m.color} />
    </div>
  )
}

export const LIFECYCLE_LABEL: Record<string, string> = {
  IN_DEVELOPMENT: 'In development',
  LIVE: 'Live',
  MAINTENANCE: 'Maintenance',
  DEPRECATED: 'Deprecated',
  RETIRED: 'Retired',
}

// VYB-0770: colour is never the only signal — the label text above already says
// this; the colour is a reinforcing cue, not the only way to tell states apart.
export const LIFECYCLE_STYLE: Record<string, { color: string; bg: string; bd: string }> = {
  IN_DEVELOPMENT: { color: 'var(--info)', bg: 'var(--info-dim)', bd: 'var(--info-bd)' },
  LIVE: { color: 'var(--ok)', bg: 'var(--ok-dim)', bd: 'var(--ok-bd)' },
  MAINTENANCE: { color: 'var(--high)', bg: 'var(--high-dim)', bd: 'var(--high-bd)' },
  DEPRECATED: { color: 'var(--tx-3)', bg: 'var(--panel-3)', bd: 'var(--line-2)' },
  RETIRED: { color: 'var(--tx-3)', bg: 'var(--panel-3)', bd: 'var(--line-2)' },
}
