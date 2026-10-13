/** VYB-0955: the small pure parts of the notification screens (grouping the kinds, and toggling a set of chosen kinds). */

/** Groups in the order each group first appears; the kinds keep their order inside it. */
export function groupByGroup<T extends { group: string }>(items: T[]): [string, T[]][] {
  const groups = new Map<string, T[]>()
  for (const item of items) groups.set(item.group, [...(groups.get(item.group) ?? []), item])
  return [...groups]
}

/** A new set with {@code kind} added or removed; the old set is left alone. */
export function toggled(chosen: ReadonlySet<string>, kind: string, checked: boolean): Set<string> {
  const next = new Set(chosen)
  if (checked) next.add(kind); else next.delete(kind)
  return next
}

/** True when the chosen kinds differ from the ones saved (order does not matter). */
export function differs(chosen: ReadonlySet<string>, saved: readonly string[]): boolean {
  return chosen.size !== saved.length || saved.some((k) => !chosen.has(k))
}
