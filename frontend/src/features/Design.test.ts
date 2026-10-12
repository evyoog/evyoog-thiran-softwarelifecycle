import { describe, it, expect } from 'vitest'
import { canApproveFromDesignScreen, canVerifyFromDesignScreen, layout, nodeState, testCoverage } from './Design'
import type { DesignNode, Requirement } from '@/shared/api/client'

describe('VYB-0803 — approving a requirement from the design screen', () => {
  it('VYB0803_AC1_reviewedRequirementCanBeApprovedFromDesign', () => {
    expect(canApproveFromDesignScreen('REVIEWED')).toBe(true)
  })

  it('VYB0803_AC1_anyOtherStatusCannotBeApprovedFromDesign', () => {
    expect(canApproveFromDesignScreen('DRAFT')).toBe(false)
    expect(canApproveFromDesignScreen('IN_REVIEW')).toBe(false)
    expect(canApproveFromDesignScreen('APPROVED')).toBe(false)
    // VERIFIED (VYB-0810) is not even a real status any more — this just pins that a
    // stale/unexpected string still resolves to false rather than throwing.
    expect(canApproveFromDesignScreen('VERIFIED')).toBe(false)
    expect(canApproveFromDesignScreen('REJECTED')).toBe(false)
  })

  it('VYB0803_AC1_aRequirementWhoseStatusHasNotLoadedYetCannotBeApproved', () => {
    expect(canApproveFromDesignScreen(undefined)).toBe(false)
  })
})

describe('VYB-0811 — verifying a requirement from the design screen', () => {
  it('VYB0811_AC1_inReviewRequirementCanBeVerifiedFromDesign', () => {
    expect(canVerifyFromDesignScreen('IN_REVIEW')).toBe(true)
  })

  it('VYB0811_AC1_anyOtherStatusCannotBeVerifiedFromDesign', () => {
    expect(canVerifyFromDesignScreen('DRAFT')).toBe(false)
    expect(canVerifyFromDesignScreen('REVIEWED')).toBe(false)
    expect(canVerifyFromDesignScreen('APPROVED')).toBe(false)
    expect(canVerifyFromDesignScreen('REJECTED')).toBe(false)
  })

  it('VYB0811_AC1_aRequirementWhoseStatusHasNotLoadedYetCannotBeVerified', () => {
    expect(canVerifyFromDesignScreen(undefined)).toBe(false)
  })
})

describe('VYB-0816 — Testing/Deployment milestone nodes read real evidence, not requirement.status', () => {
  const milestone = (kind: 'testing' | 'deployment'): DesignNode => ({
    id: 'n1', flowId: 'f1', kind, label: kind, requirementIds: ['r1', 'r2'],
  })

  it('VYB0816_AC1_testingIsSatisfiedOnlyWhenEveryRequirementIsActuallyVerified', () => {
    const allVerified = { totalRequirements: 2, verifiedRequirements: 2, verifiedPct: 100, deployedRequirements: 0, deployedPct: 0 }
    expect(nodeState(milestone('testing'), {}, allVerified)).toBe('satisfied')
  })

  it('VYB0816_AC1_anApprovedRequirementWithNoPassingTestDoesNotMakeTestingSatisfied', () => {
    // Both requirements APPROVED (statuses map), but zero real verification evidence —
    // the old "all approved" rule would wrongly call this satisfied.
    const noneVerified = { totalRequirements: 2, verifiedRequirements: 0, verifiedPct: 0, deployedRequirements: 0, deployedPct: 0 }
    expect(nodeState(milestone('testing'), { r1: 'APPROVED', r2: 'APPROVED' }, noneVerified)).toBe('problem')
  })

  it('VYB0816_AC1_partiallyVerifiedIsPartial', () => {
    const half = { totalRequirements: 2, verifiedRequirements: 1, verifiedPct: 50, deployedRequirements: 0, deployedPct: 0 }
    expect(nodeState(milestone('testing'), {}, half)).toBe('partial')
  })

  it('VYB0816_AC1_deploymentReadsDeployedCountNotVerifiedCount', () => {
    const verifiedButNotDeployed = { totalRequirements: 2, verifiedRequirements: 2, verifiedPct: 100, deployedRequirements: 0, deployedPct: 0 }
    expect(nodeState(milestone('deployment'), {}, verifiedButNotDeployed)).toBe('problem')
  })

  it('VYB0816_AC1_missingProgressDataIsNeverTreatedAsSatisfied', () => {
    expect(nodeState(milestone('testing'), {}, undefined)).toBe('problem')
  })
})

describe('VYB-0816 — layout handles many requirements fanning into one shared node', () => {
  const stepNode = (id: string): DesignNode => ({ id, flowId: 'f1', kind: 'step', label: id, requirementIds: [id] })
  const milestoneNode = (id: string, kind: 'testing' | 'deployment'): DesignNode =>
    ({ id, flowId: 'f1', kind, label: kind, requirementIds: [] })

  it('VYB0816_AC2_manyIndependentSourcesShareOneColumnInsteadOfOneRootStrandingTheRest', () => {
    // Five requirement nodes with no edges between them, each feeding a shared Testing
    // node, which feeds Deployment — the exact shape generateFrom now produces. The old
    // single-fallback-root BFS would only reach whichever node happened to be first,
    // stranding the other four as "disconnected" in their own overflow column.
    const nodes = [stepNode('r1'), stepNode('r2'), stepNode('r3'), stepNode('r4'), stepNode('r5'),
      milestoneNode('testing', 'testing'), milestoneNode('deployment', 'deployment')]
    const edges = ['r1', 'r2', 'r3', 'r4', 'r5'].map((r) => ({ fromNode: r, toNode: 'testing' }))
      .concat([{ fromNode: 'testing', toNode: 'deployment' }])

    const { positions } = layout(nodes, edges)
    const reqXs = ['r1', 'r2', 'r3', 'r4', 'r5'].map((id) => positions.get(id)!.x)
    const distinctReqColumns = new Set(reqXs)

    // All five requirements land in the same (first) column, not spread across five
    // separate ones — proving they were recognised as five parallel roots.
    expect(distinctReqColumns.size).toBe(1)
    const testingX = positions.get('testing')!.x
    const deploymentX = positions.get('deployment')!.x
    expect(testingX).toBeGreaterThan(reqXs[0])
    expect(deploymentX).toBeGreaterThan(testingX)
  })

  it('VYB0816_AC2_anExplicitStartNodeIsStillHonoured', () => {
    const nodes = [
      { id: 'start', flowId: 'f1', kind: 'start' as const, label: 'start', requirementIds: [] },
      stepNode('r1'), stepNode('r2'),
    ]
    const edges = [{ fromNode: 'start', toNode: 'r1' }, { fromNode: 'start', toNode: 'r2' }]
    const { positions } = layout(nodes, edges)
    expect(positions.get('start')!.x).toBeLessThan(positions.get('r1')!.x)
    expect(positions.get('r1')!.x).toBe(positions.get('r2')!.x)
  })
})

describe('VYB-0826 — a step\'s test-case coverage badge', () => {
  const withTest = (hasTest: boolean) => ({ hasTest }) as Requirement

  it('VYB0826_AC1_everyRequirementTestedIsFull', () => {
    const byId = { r1: withTest(true), r2: withTest(true) }
    expect(testCoverage(['r1', 'r2'], byId)).toEqual({ tested: 2, total: 2, full: true })
  })

  it('VYB0826_AC1_someUntestedIsNotFull', () => {
    const byId = { r1: withTest(true), r2: withTest(false) }
    expect(testCoverage(['r1', 'r2'], byId)).toEqual({ tested: 1, total: 2, full: false })
  })

  it('VYB0826_AC1_noneTestedIsNotFull', () => {
    const byId = { r1: withTest(false) }
    expect(testCoverage(['r1'], byId)).toEqual({ tested: 0, total: 1, full: false })
  })

  it('VYB0826_AC1_aRequirementNotYetLoadedCountsAsUntestedNotThrown', () => {
    expect(testCoverage(['unloaded'], {})).toEqual({ tested: 0, total: 1, full: false })
  })

  it('VYB0826_AC1_emptyListIsNotFull', () => {
    // No requirements behind the step at all — "full" would be vacuously true by
    // every()/length-equality logic; explicitly false instead, since there is nothing
    // to call tested.
    expect(testCoverage([], {})).toEqual({ tested: 0, total: 0, full: false })
  })
})

describe('VYB-0945 — a dragged layout', () => {
  const placedNode = (id: string, x?: number, y?: number): DesignNode => ({
    id, flowId: 'f1', kind: 'step', label: id, requirementIds: [id], x, y,
  })

  it('VYB0945_AC6_aSavedPlaceIsWhereTheStepIsDrawn', () => {
    const nodes = [placedNode('a', 400, 300), placedNode('b', 50, 70)]
    const { positions } = layout(nodes, [{ fromNode: 'a', toNode: 'b' }])
    expect(positions.get('a')).toEqual({ x: 400, y: 300 })
    expect(positions.get('b')).toEqual({ x: 50, y: 70 })
  })

  it('VYB0945_AC6_aStepWithNoSavedPlaceIsLaidOutBelowEverythingPlacedNotOnTopOfIt', () => {
    const nodes = [placedNode('a', 400, 300), placedNode('b', 50, 70), placedNode('new')]
    const { positions } = layout(nodes, [])
    const fresh = positions.get('new')!
    expect(fresh.y).toBeGreaterThan(300 + 52)
  })

  it('VYB0945_AC6_withNoSavedPlacesTheAutomaticLayoutIsUnchanged', () => {
    const nodes = [placedNode('a'), placedNode('b')]
    const auto = layout(nodes, [{ fromNode: 'a', toNode: 'b' }])
    expect(auto.positions.get('a')).toEqual({ x: 30, y: 30 })
    expect(auto.positions.get('b')!.x).toBeGreaterThan(30)
  })

  it('VYB0945_AC6_theDrawingGrowsToHoldAStepDraggedOutward', () => {
    const nodes = [placedNode('a', 30, 30), placedNode('b', 2000, 900)]
    const { width, height } = layout(nodes, [])
    expect(width).toBeGreaterThanOrEqual(2000 + 150)
    expect(height).toBeGreaterThanOrEqual(900 + 52)
  })
})
