import type { LinkedWork, ReleaseState, TraceLinkType, VyoogRelease } from '@/shared/api/client'

/**
 * VYB-0943 (F19, D36): the words and rules of the Link dialog and the Linked work list on the requirement detail. Each kind of
 * link keeps the mechanism it already had (a test is a VERIFIES trace link, design is the design-node mapping, a release is
 * the release scope with a reason, a defect has one requirement); the server enforces every rule, and this only says what is
 * needed before the button works and what each choice does.
 */

export type LinkKind = 'requirement' | 'test' | 'design' | 'release' | 'defect'

export interface KindInfo { kind: LinkKind; label: string; how: string }

export const KINDS: KindInfo[] = [
  { kind: 'requirement', label: 'Requirement', how: 'Another requirement above or below this one, with a link type.' },
  { kind: 'test', label: 'Test case', how: 'A test case that verifies this requirement.' },
  { kind: 'design', label: 'Design node', how: "A node in this application's design that covers this requirement." },
  { kind: 'release', label: 'Release', how: 'Commits this requirement to a release. It is in one release at a time, and the reason is recorded.' },
  { kind: 'defect', label: 'Defect', how: 'A defect that has no requirement yet. A defect has one requirement and is never moved from here.' },
]

export interface Draft {
  kind: LinkKind
  direction: 'upstream' | 'downstream'
  linkType: TraceLinkType
  /** The id of the chosen requirement, test case, design node, release or defect. */
  targetId: string
  reason: string
}

export const emptyDraft = (kind: LinkKind = 'requirement'): Draft =>
  ({ kind, direction: 'upstream', linkType: 'SATISFIES', targetId: '', reason: '' })

const NEEDS: Record<LinkKind, string> = {
  requirement: 'Choose the requirement to link.',
  test: 'Choose the test case.',
  design: 'Choose the design node.',
  release: 'Choose the release.',
  defect: 'Choose the defect.',
}

/** What is still needed before the dialog can save; null when it can. */
export function missing(d: Draft): string | null {
  if (d.targetId === '') return NEEDS[d.kind]
  if (d.kind === 'release' && d.reason.trim() === '') return 'Give a reason for committing this requirement to the release.'
  return null
}

/** Releases whose scope can still change: not frozen and not released. A frozen one has to be reopened first. */
export function scopeEditable(state: ReleaseState): boolean {
  return state === 'PLANNED' || state === 'OPEN'
}

export function releaseChoice(r: Pick<VyoogRelease, 'name' | 'state'>): { label: string; disabled: boolean } {
  if (scopeEditable(r.state)) return { label: `${r.name} (${r.state.toLowerCase()})`, disabled: false }
  return {
    label: `${r.name} (${r.state.toLowerCase()}${r.state === 'FROZEN' ? ', reopen it to change its scope' : ', its scope is closed'})`,
    disabled: true,
  }
}

export interface Group { kind: Exclude<LinkKind, 'requirement'> | 'risk'; title: string; empty: string; items: { id: string; label: string; detail?: string; href?: string }[] }

/** The linked work as five groups, each with words for when it is empty, never a blank. */
export function groupsOf(w: LinkedWork | undefined): Group[] {
  if (!w) return []
  return [
    {
      kind: 'test', title: 'Tests', empty: 'No test case verifies this requirement.',
      items: w.tests.map((t) => ({ id: t.id, label: `${t.key} — ${t.title}` })),
    },
    {
      kind: 'design', title: 'Design', empty: 'No design node covers this requirement.',
      items: w.designNodes.map((n) => ({ id: n.id, label: n.label, detail: n.kind })),
    },
    {
      kind: 'release', title: 'Release', empty: 'Not in any release.',
      items: w.releases.map((r) => ({ id: r.id, label: r.name, detail: r.state.toLowerCase(), href: `/releases/${r.id}` })),
    },
    {
      kind: 'defect', title: 'Defects', empty: 'No defect is linked to this requirement.',
      items: w.defects.map((d) => ({ id: d.id, label: `${d.key} — ${d.title}`, detail: `${d.severity.toLowerCase()}, ${d.state.toLowerCase()}`, href: `/defects/${d.id}` })),
    },
    {
      // VYB-0949: the open risks that name this requirement (a closed risk is history and is not shown here).
      kind: 'risk', title: 'Risks', empty: 'No open risk names this requirement.',
      items: (w.risks ?? []).map((r) => ({ id: r.id, label: `${r.key} — ${r.title}`, detail: `${r.band.toLowerCase()} (${r.rating}), ${r.status.toLowerCase()}`, href: `/compliance?tab=risks&risk=${r.id}` })),
    },
  ]
}

/** A one-line summary for the group header: how many, in words. */
export function countText(g: Pick<Group, 'items'>): string {
  return g.items.length === 0 ? 'none' : String(g.items.length)
}
