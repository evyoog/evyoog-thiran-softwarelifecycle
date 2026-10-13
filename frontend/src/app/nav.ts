import {
  LayoutDashboard, ListChecks, Boxes, Table2, Workflow,
  BarChart3, ClipboardCheck, Code2, Rocket, Settings, ShieldCheck,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { MessageKey } from '@/shared/i18n/catalog.en'

export interface NavItem {
  key: string
  /** VYB-0958: the menu text is a catalogue key, so it follows the person's language. */
  labelKey: MessageKey
  icon: LucideIcon
  path: string
  divideBefore?: boolean
  /** VYB-0765 AC2: hidden from the sidebar and the command palette unless the caller is a platform administrator. */
  requiresAdmin?: boolean
}

/**
 * Ten modules —
 * Delivery, which is where it sits in the flow: a requirement is written, then dated,
 * then briefed. The divider sits before Administration: everything above it is the
 * work, everything below is configuration.
 *
 * different question with a different audience.
 *
 * Visibility is filtered by the caller's grants — a module the user cannot reach is
 * hidden entirely rather than shown and refused. In practice today (see
 * RoleCapabilityRegistry in the backend) that only actually restricts Administration
 * — the other nine modules have never had a role check retrofitted onto them, so
 * everything short of {@code requiresAdmin} stays visible to any signed-in user.
 */
export const NAV: NavItem[] = [
  { key: 'home',       labelKey: 'nav.home',           icon: LayoutDashboard, path: '/' },
  { key: 'mywork',     labelKey: 'nav.mywork',        icon: ListChecks,      path: '/my-work' },
  { key: 'portfolio',  labelKey: 'nav.portfolio',      icon: Boxes,           path: '/portfolio' },
  { key: 'reqs',       labelKey: 'nav.reqs',   icon: Table2,          path: '/requirements' },
  { key: 'design',     labelKey: 'nav.design',         icon: Workflow,        path: '/design' },
  { key: 'analytics',  labelKey: 'nav.analytics',      icon: BarChart3,       path: '/analytics' },
  { key: 'quality',    labelKey: 'nav.quality',        icon: ClipboardCheck,  path: '/quality' },
  { key: 'compliance', labelKey: 'nav.compliance',     icon: ShieldCheck,     path: '/compliance' },
  { key: 'delivery',   labelKey: 'nav.delivery',       icon: Code2,           path: '/delivery' },
  { key: 'releases',   labelKey: 'nav.releases',       icon: Rocket,          path: '/releases' },
  { key: 'admin',      labelKey: 'nav.admin', icon: Settings,        path: '/admin', divideBefore: true, requiresAdmin: true },
]
