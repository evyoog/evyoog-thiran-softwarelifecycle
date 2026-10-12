import type { RequirementHistoryEntry } from '@/shared/api/client'

/**
 * VYB-0941 (F18): the comparison the History section makes between two stored revisions. Pure functions over the snapshots the
 * server sends; nothing is fetched here. A word is a run of non-space characters; spacing is kept so the text reads as written.
 */

export type DiffOp = 'same' | 'add' | 'del'
export interface Segment { op: DiffOp; text: string }

/** Above this many token pairs the exact comparison is skipped and the whole text is shown replaced (a pathological size). */
export const MAX_DIFF_CELLS = 4_000_000

function tokens(text: string): string[] {
  return text.match(/\s+|\S+/g) ?? []
}

function merged(parts: Segment[]): Segment[] {
  const out: Segment[] = []
  for (const p of parts) {
    if (p.text === '') continue
    const last = out[out.length - 1]
    if (last && last.op === p.op) last.text += p.text
    else out.push({ ...p })
  }
  return out
}

/** Word-level difference from `from` to `to`: what stayed, what was added, what was removed, in reading order. */
export function diffWords(from: string, to: string): Segment[] {
  if (from === to) return from === '' ? [] : [{ op: 'same', text: from }]
  const a = tokens(from)
  const b = tokens(to)
  if (a.length * b.length > MAX_DIFF_CELLS) return merged([{ op: 'del', text: from }, { op: 'add', text: to }])

  // longest common subsequence of tokens, as a table of suffix lengths
  const w = b.length + 1
  const lcs = new Uint32Array((a.length + 1) * w)
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i * w + j] = a[i] === b[j] ? lcs[(i + 1) * w + j + 1]! + 1 : Math.max(lcs[(i + 1) * w + j]!, lcs[i * w + j + 1]!)
    }
  }
  const parts: Segment[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) { parts.push({ op: 'same', text: a[i]! }); i++; j++ }
    else if (lcs[(i + 1) * w + j]! >= lcs[i * w + j + 1]!) { parts.push({ op: 'del', text: a[i]! }); i++ }
    else { parts.push({ op: 'add', text: b[j]! }); j++ }
  }
  while (i < a.length) parts.push({ op: 'del', text: a[i++]! })
  while (j < b.length) parts.push({ op: 'add', text: b[j++]! })
  return merged(parts)
}

/** How many words were added and removed (spacing is not counted). */
export function diffStats(segments: Segment[]): { added: number; removed: number } {
  const count = (op: DiffOp) =>
    segments.filter((s) => s.op === op).reduce((n, s) => n + (s.text.match(/\S+/g)?.length ?? 0), 0)
  return { added: count('add'), removed: count('del') }
}

export type Revision = RequirementHistoryEntry & { kind: 'REVISION'; revision: number }

/** The revisions of a timeline, oldest first. */
export function revisionsOf(entries: RequirementHistoryEntry[]): Revision[] {
  return entries
    .filter((e): e is Revision => e.kind === 'REVISION' && typeof e.revision === 'number')
    .sort((x, y) => x.revision - y.revision)
}

export interface FieldChange { field: 'title' | 'statement' | 'type' | 'priority' | 'capability'; label: string; from: string; to: string }

const FIELD_LABEL: Record<FieldChange['field'], string> = {
  title: 'Title', statement: 'Statement', type: 'Type', priority: 'Priority', capability: 'Capability',
}

function capabilityText(r: Revision): string {
  return r.capabilityName ?? (r.capabilityId ? 'a capability that is no longer listed' : 'not placed')
}

/** The fields that differ between two revisions, in a fixed order. The statement's own words are compared by {@link diffWords}. */
export function fieldChanges(from: Revision, to: Revision): FieldChange[] {
  const out: FieldChange[] = []
  const add = (field: FieldChange['field'], a: string, b: string) => {
    if (a !== b) out.push({ field, label: FIELD_LABEL[field], from: a, to: b })
  }
  add('title', from.title ?? '', to.title ?? '')
  add('statement', from.statement ?? '', to.statement ?? '')
  add('type', from.type ?? '', to.type ?? '')
  add('priority', from.priority ?? '', to.priority ?? '')
  add('capability', capabilityText(from), capabilityText(to))
  return out
}

/** For each revision, what it changed against the one before it, in words. The first is "Created". */
export function changeSummaries(revisions: Revision[]): Map<number, string> {
  const out = new Map<number, string>()
  revisions.forEach((r, i) => {
    if (i === 0) { out.set(r.revision, 'Created'); return }
    const changed = fieldChanges(revisions[i - 1]!, r).map((c) => c.label)
    out.set(r.revision, changed.length === 0 ? 'No field changed' : changed.join(', '))
  })
  return out
}

/** The pair compared when the section opens: the revision before the latest, and the latest. A single revision compares with itself. */
export function defaultPair(revisions: Revision[]): { from: number; to: number } | null {
  if (revisions.length === 0) return null
  const latest = revisions[revisions.length - 1]!.revision
  const before = revisions.length > 1 ? revisions[revisions.length - 2]!.revision : latest
  return { from: before, to: latest }
}

/** How many rows the section shows before "Show all". */
export const COLLAPSED_ROWS = 6

export function visibleEntries(entries: RequirementHistoryEntry[], showAll: boolean): RequirementHistoryEntry[] {
  return showAll || entries.length <= COLLAPSED_ROWS ? entries : entries.slice(0, COLLAPSED_ROWS)
}

/** "Ada Author", or "an unknown person" when the person is not on record, or "the system" when nobody acted. */
export function actorText(e: Pick<RequirementHistoryEntry, 'actorId' | 'actorName'>): string {
  if (e.actorName) return e.actorName
  return e.actorId ? 'a person no longer on record' : 'the system'
}

export function statusMoveText(e: Pick<RequirementHistoryEntry, 'fromStatus' | 'toStatus'>): string {
  return `${e.fromStatus ?? '?'} → ${e.toStatus ?? '?'}`
}

/** The note under the list when older status changes may be missing from the audit log. */
export const INCOMPLETE_NOTE =
  'Status changes older than the audit log keeps may be missing here. Revisions are never removed.'
