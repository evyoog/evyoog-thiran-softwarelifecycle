/**
 * VYB-0961 (D49): which signed-in accounts could a prepared directory entry belong to? An account qualifies only if its person has signed in
 * (it is not a prepared entry itself) and it holds no access: an account with access is never merged away (the server refuses it as well).
 * Likely matches come first: accounts whose address or name shares a word with the prepared entry's, then the most recent sign-ins. This only
 * orders the list; a person decides, and the server decides what is allowed.
 */
export interface DirectoryEntry {
  id: string
  email: string
  displayName: string
  source: string
  lastSeenAt?: string | null
  activeGrantCount: number
}

function words(entry: { email: string; displayName: string }): Set<string> {
  const local = entry.email.split('@')[0] ?? ''
  return new Set(`${local} ${entry.displayName}`.toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length >= 3))
}

export function sharedWords(a: DirectoryEntry, b: DirectoryEntry): number {
  const other = words(b)
  let n = 0
  for (const w of words(a)) if (other.has(w)) n++
  return n
}

export function claimCandidates(prepared: DirectoryEntry, directory: DirectoryEntry[]): { entry: DirectoryEntry; likely: boolean }[] {
  return directory
    .filter((u) => u.id !== prepared.id && u.source !== 'LOCAL' && u.activeGrantCount === 0 && u.lastSeenAt != null)
    .map((entry) => ({ entry, score: sharedWords(prepared, entry) }))
    .sort((a, b) => b.score - a.score || String(b.entry.lastSeenAt).localeCompare(String(a.entry.lastSeenAt)))
    .map(({ entry, score }) => ({ entry, likely: score > 0 }))
}

/** A prepared entry nobody has signed into yet (the only kind an account can be joined to). */
export function isAwaitingFirstSignIn(u: { source: string; lastSeenAt?: string | null }): boolean {
  return u.source === 'LOCAL' && u.lastSeenAt == null
}
