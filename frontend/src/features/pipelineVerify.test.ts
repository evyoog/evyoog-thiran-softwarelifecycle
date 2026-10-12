import { describe, it, expect } from 'vitest'
import { STAGES } from './MyWork'
import { verifyDestination } from './RequirementDetail'

describe('VYB-0946 — no placeholders on the Pipeline lanes or Verify', () => {
  it('VYB0946_AC1_thePipelineHasExactlyTheLifecycleStatusesAndNothingAfterApproved', () => {
    expect([...STAGES]).toEqual(['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED'])
  })

  it('VYB0946_AC2_verifyGoesStraightToTheDesignScreenFocusedOnTheRequirement', () => {
    expect(verifyDestination('r-1')).toBe('/design?requirementId=r-1')
    expect(verifyDestination('a b&c')).toBe('/design?requirementId=a%20b%26c')
  })
})
