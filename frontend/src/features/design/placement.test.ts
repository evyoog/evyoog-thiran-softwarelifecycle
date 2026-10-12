import { describe, it, expect } from 'vitest'
import { clampCoord, dragTarget, movedEnough, placementsFor, snap, MAX_COORD } from './placement'

describe('VYB-0945 — dragging a step', () => {
  it('VYB0945_AC1_aStepSnapsToTheGridAndNeverLeavesTheDiagram', () => {
    expect(snap(104)).toBe(100)
    expect(snap(105)).toBe(110)
    expect(clampCoord(-30)).toBe(0)
    expect(clampCoord(MAX_COORD + 5)).toBe(MAX_COORD)
    // pointer at (203, 97), grabbed 40 across and 20 down inside the step
    expect(dragTarget(203, 97, 40, 20)).toEqual({ x: 160, y: 80 })
    expect(dragTarget(10, 5, 40, 20)).toEqual({ x: 0, y: 0 })
  })

  it('VYB0945_AC2_aSmallMovementIsAClickNotADrag', () => {
    expect(movedEnough(100, 100, 102, 101)).toBe(false)
    expect(movedEnough(100, 100, 104, 100)).toBe(true)
    expect(movedEnough(100, 100, 103, 103)).toBe(true)
  })

  it('VYB0945_AC3_theFirstDropSavesEveryStepWhereItIsDrawnWithTheMovedOneAtItsNewPlace', () => {
    const drawn = new Map([['a', { x: 30, y: 30 }], ['b', { x: 240, y: 30.4 }], ['c', { x: 240, y: 114 }]])
    expect(placementsFor(['a', 'b', 'c'], drawn, { id: 'b', x: 300, y: 60 })).toEqual([
      { nodeId: 'a', x: 30, y: 30 }, { nodeId: 'b', x: 300, y: 60 }, { nodeId: 'c', x: 240, y: 114 },
    ])
  })

  it('VYB0945_AC3_aStepWithNoDrawnPositionIsLeftOutRatherThanInvented', () => {
    expect(placementsFor(['a', 'ghost'], new Map([['a', { x: 1, y: 2 }]]), { id: 'a', x: 5, y: 6 })).toEqual([{ nodeId: 'a', x: 5, y: 6 }])
  })
})
