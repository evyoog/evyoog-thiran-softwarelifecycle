import type { ChangeImpact } from '@/shared/api/client'

/**
 * VYB-0943 (F19, D36): the items behind a change request's four impact counts, as sections each count expands to. A list is
 * as long as its count (they come from one walk of the trace graph); a count of zero says "none", not a blank.
 */

export type ImpactKind = 'requirements' | 'tests' | 'applications' | 'briefs'

export interface ImpactItem { id: string; label: string; detail?: string; href?: string }
export interface ImpactSection { kind: ImpactKind; label: string; count: number; items: ImpactItem[] }

const dateOf = (iso: string) => iso.slice(0, 10)

export function impactSections(impact: ChangeImpact): ImpactSection[] {
  const items = impact.items
  return [
    {
      kind: 'requirements', label: 'Requirement', count: impact.requirements,
      items: (items?.requirements ?? []).map((r) => ({
        id: r.id, label: `${r.key} — ${r.title}`, detail: `${r.inScope ? 'named by this change request' : 'reached downstream'}, ${r.status.toLowerCase().replace('_', ' ')}`,
        href: `/requirements/${r.id}`,
      })),
    },
    {
      kind: 'tests', label: 'Test', count: impact.tests,
      items: (items?.tests ?? []).map((t) => ({ id: t.id, label: `${t.key} — ${t.title}` })),
    },
    {
      kind: 'applications', label: 'Application', count: impact.applications,
      items: (items?.applications ?? []).map((a) => ({ id: a.id, label: a.name, detail: a.productName })),
    },
    {
      kind: 'briefs', label: 'Brief', count: impact.briefs,
      items: (items?.briefs ?? []).map((b) => ({
        id: b.id, label: `${b.target.toLowerCase().replace('_', ' ')} brief for ${b.applicationName}`, detail: `${dateOf(b.generatedAt)}${b.stale ? ', out of date' : ''}`,
      })),
    },
  ]
}

/** "3 requirements", "1 test": the count as the toggle reads it. */
export function countLabel(s: Pick<ImpactSection, 'label' | 'count'>): string {
  return `${s.count} ${s.label.toLowerCase()}${s.count === 1 ? '' : 's'}`
}

/** True when the items really are as many as the count says (a list that disagrees with its count is shown as such, not hidden). */
export function listMatchesCount(s: ImpactSection): boolean {
  return s.items.length === s.count
}
