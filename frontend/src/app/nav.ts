import {
  LayoutDashboard, ListChecks, Boxes, Table2, Workflow,
  BarChart3, ClipboardCheck, Code2, Rocket, Settings,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface NavItem {
  key: string
  label: string
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
  { key: 'home',       label: 'Home',           icon: LayoutDashboard, path: '/' },
  { key: 'mywork',     label: 'My Work',        icon: ListChecks,      path: '/my-work' },
  { key: 'portfolio',  label: 'Portfolio',      icon: Boxes,           path: '/portfolio' },
  { key: 'reqs',       label: 'Requirements',   icon: Table2,          path: '/requirements' },
  { key: 'design',     label: 'Design',         icon: Workflow,        path: '/design' },
  { key: 'analytics',  label: 'Analytics',      icon: BarChart3,       path: '/analytics' },
  { key: 'quality',    label: 'Quality',        icon: ClipboardCheck,  path: '/quality' },
  { key: 'delivery',   label: 'Delivery',       icon: Code2,           path: '/delivery' },
  { key: 'releases',   label: 'Releases',       icon: Rocket,          path: '/releases' },
  { key: 'admin',      label: 'Administration', icon: Settings,        path: '/admin', divideBefore: true, requiresAdmin: true },
]
