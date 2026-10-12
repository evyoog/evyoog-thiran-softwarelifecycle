import type { ChangeRequest, VersionRef, RequirementStatus, RequirementVersions } from '@/shared/api/client'

/**
 * VYB-0942 (D35): what the requirement detail says about a chain of versions and when it offers "Fork a new version". The server
 * decides every rule (and refuses a fork that breaks one); this only words them and hides what cannot work.
 */

/** A new version still counts as in progress unless it was rejected. */
export function openFork(versions: Pick<RequirementVersions, 'forks'>): VersionRef | undefined {
  return versions.forks.find((f) => f.status !== 'REJECTED')
}

/** Approved, not replaced, and no new version in progress. */
export function canFork(status: RequirementStatus, versions: RequirementVersions | undefined): boolean {
  if (status !== 'APPROVED' || !versions) return false
  return !versions.supersededBy && !openFork(versions)
}

/** The approved change requests that name this requirement: the ones a fork can be made for. */
export function eligibleChangeRequests(all: ChangeRequest[] | undefined, requirementId: string): ChangeRequest[] {
  return (all ?? []).filter((cr) => cr.state === 'APPROVED' && cr.scope.includes(requirementId))
}

export interface Note { glyph: string; text: string; target?: VersionRef }

/** The lines shown above the requirement about its versions: each has a glyph and words, never colour alone, and none is amber. */
export function versionNotes(versions: RequirementVersions | undefined): Note[] {
  if (!versions) return []
  const notes: Note[] = []
  if (versions.forkedFrom) {
    notes.push({
      glyph: '◆', target: versions.forkedFrom,
      text: `A new version of ${versions.forkedFrom.key}${versions.changeRequestKey ? `, made for ${versions.changeRequestKey}` : ''}.`,
    })
  }
  if (versions.supersededBy) {
    notes.push({
      glyph: '■', target: versions.supersededBy,
      text: `Replaced by ${versions.supersededBy.key}. It still reads Approved, and takes no new links and no release. Use the new version.`,
    })
  }
  const open = openFork(versions)
  if (open && !versions.supersededBy) {
    notes.push({ glyph: '●', target: open, text: `A new version is in progress: ${open.key}.` })
  }
  return notes
}

export const NO_ELIGIBLE_CR =
  'No approved change request names this requirement. Raise one that includes it and have it approved, then fork from here.'
