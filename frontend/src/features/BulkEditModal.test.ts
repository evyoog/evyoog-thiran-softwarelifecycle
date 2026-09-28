import { describe, it, expect } from 'vitest'
import type { Requirement, TraceLink } from '@/shared/api/client'
import {
  sharedValue, unselectedDependencyIds, STATUSES, ALLOWED_NEXT, reachableCount,
} from './BulkEditModal'

function link(over: Partial<TraceLink> = {}): TraceLink {
  return {
    id: 'l1', fromType: 'REQUIREMENT', fromId: 'r-source',
    toType: 'REQUIREMENT', toId: 'r-target', linkType: 'DERIVES',
    ...over,
  }
}

function req(over: Partial<Requirement> = {}): Requirement {
  return {
    id: 'r1', key: 'VY-1', type: 'FUNCTIONAL', status: 'DRAFT', priority: 'MEDIUM',
    title: 'T', statement: 'S', revision: 1, criteriaCount: 1, revisionCount: 0, version: 1,
    hasUpstream: false, hasDesign: false, hasCode: false, hasTest: false,
    ...over,
  }
}

describe('VYB-0666 — Bulk edit auto-loads fields the selection agrees on', () => {
  it('VYB0666_AC1_oneRowSharesTrivially', () => {
    expect(sharedValue([req({ priority: 'HIGH' })], (r) => r.priority)).toBe('HIGH')
  })

  it('VYB0666_AC1_allRowsAgreeingReturnsTheSharedValue', () => {
    const rows = [req({ priority: 'HIGH' }), req({ priority: 'HIGH' }), req({ priority: 'HIGH' })]
    expect(sharedValue(rows, (r) => r.priority)).toBe('HIGH')
  })

  it('VYB0666_AC1_oneDifferingRowMeansMixed', () => {
    const rows = [req({ priority: 'HIGH' }), req({ priority: 'HIGH' }), req({ priority: 'LOW' })]
    expect(sharedValue(rows, (r) => r.priority)).toBeUndefined()
  })

  it('VYB0666_AC1_noCapabilityOnAnyRowIsItselfASharedValueNotMixed', () => {
    // Every row genuinely has no capability — that is one shared answer (null), distinct
    // from a selection that actually disagrees. Collapsing it to "mixed" would make an
    // uncategorised import look no different from one spanning several capabilities.
    const rows = [req({ capabilityId: undefined }), req({ capabilityId: undefined })]
    expect(sharedValue(rows, (r) => r.capabilityId ?? null)).toBeNull()
  })

  it('VYB0666_AC1_emptySelectionHasNothingToShare', () => {
    expect(sharedValue([], (r) => r.priority)).toBeUndefined()
  })
})

describe('VYB-0666 — bulk edit flags a real dependency left out of the selection', () => {
  it('VYB0666_AC1_flagsADerivesLinkPointingOutsideTheSelection', () => {
    // SO-011 (in the selection) depends on SO-010's requirement, which is not.
    const outgoing = [link({ toId: 'req-so-010' })]
    expect(unselectedDependencyIds(outgoing, new Set(['req-so-011']))).toEqual(['req-so-010'])
  })

  it('VYB0666_AC1_bothSelectedIsNoWarning', () => {
    // The case the user confirmed already works: both ticked, nothing left to flag.
    const outgoing = [link({ toId: 'req-so-010' })]
    expect(unselectedDependencyIds(outgoing, new Set(['req-so-011', 'req-so-010']))).toEqual([])
  })

  it('VYB0666_AC1_onlyDerivesCountsAsADependency', () => {
    // SATISFIES/VERIFIES/etc. name a different relationship, not "needs this first".
    const outgoing = [link({ toId: 'req-x', linkType: 'SATISFIES' })]
    expect(unselectedDependencyIds(outgoing, new Set())).toEqual([])
  })

  it('VYB0666_AC1_onlyRequirementTargetsCount', () => {
    // A DERIVES link to a design node or test case is not a missing sibling requirement.
    const outgoing = [link({ toId: 'design-1', toType: 'DESIGN_NODE' })]
    expect(unselectedDependencyIds(outgoing, new Set())).toEqual([])
  })
})

describe('VYB-0802 — bulk edit mirrors the REVIEWED state machine', () => {
  it('VYB0802_AC1_statusesIncludesReviewed', () => {
    expect(STATUSES).toContain('REVIEWED')
  })

  it('VYB0802_AC1_inReviewMovesToDraftOrReviewedOnly', () => {
    // VYB-0813: rejecting directly from IN_REVIEW is gone too — a decision is only
    // made once a review is actually complete.
    expect(ALLOWED_NEXT.IN_REVIEW).toEqual(['DRAFT', 'REVIEWED'])
    expect(ALLOWED_NEXT.IN_REVIEW).not.toContain('APPROVED')
    expect(ALLOWED_NEXT.IN_REVIEW).not.toContain('REJECTED')
  })

  it('VYB0802_AC1_reachableCountFindsRowsReachingReviewed', () => {
    const rows = [req({ status: 'IN_REVIEW' }), req({ status: 'DRAFT' }), req({ status: 'APPROVED' })]
    // Only the IN_REVIEW row can reach REVIEWED — DRAFT and APPROVED cannot in one hop.
    expect(reachableCount(rows, 'REVIEWED')).toBe(1)
  })
})

describe('VYB-0813 (D17) — the replacement six-state machine', () => {
  it('VYB0813_AC1_statusesIncludesNeedsRevision', () => {
    expect(STATUSES).toContain('NEEDS_REVISION')
  })

  it('VYB0813_AC1_reviewedMovesToApprovedRejectedOrNeedsRevision', () => {
    expect(ALLOWED_NEXT.REVIEWED).toEqual(['APPROVED', 'REJECTED', 'NEEDS_REVISION'])
  })

  it('VYB0813_AC1_needsRevisionMovesOnlyToInReview', () => {
    expect(ALLOWED_NEXT.NEEDS_REVISION).toEqual(['IN_REVIEW'])
  })

  it('VYB0813_AC1_rejectedReopensIntoNeedsRevisionNotDraft', () => {
    expect(ALLOWED_NEXT.REJECTED).toEqual(['NEEDS_REVISION'])
    expect(ALLOWED_NEXT.REJECTED).not.toContain('DRAFT')
  })

  it('VYB0813_AC1_approvedIsFullyTerminal', () => {
    expect(ALLOWED_NEXT.APPROVED).toEqual([])
  })

  it('VYB0810_AC1_verifiedIsNoLongerAStatusAtAll', () => {
    // VERIFIED used to be banned-but-shown (disabled, with an explanation). Now it is
    // gone entirely — there is no RequirementStatus value left to construct it with.
    expect(STATUSES).not.toContain('VERIFIED')
  })
})
