/** VYB-0945: the arithmetic of dragging a step, kept apart from the component so it can be tested on its own. */

export const GRID = 10
/** The server refuses a place outside 0..100000; the screen never offers one. */
export const MAX_COORD = 100_000
/** A press that moves less than this is a click (select), not a drag. */
export const DRAG_THRESHOLD_PX = 4

export function snap(v: number, step = GRID): number {
  return Math.round(v / step) * step
}

export function clampCoord(v: number): number {
  return Math.min(MAX_COORD, Math.max(0, v))
}

/** Where a step lands when the pointer is at (px, py), given the pointer's offset inside the step when the press began. */
export function dragTarget(px: number, py: number, offsetX: number, offsetY: number): { x: number; y: number } {
  return { x: clampCoord(snap(px - offsetX)), y: clampCoord(snap(py - offsetY)) }
}

export function movedEnough(startClientX: number, startClientY: number, clientX: number, clientY: number): boolean {
  return Math.hypot(clientX - startClientX, clientY - startClientY) >= DRAG_THRESHOLD_PX
}

/** What to save after a drop: every step at the place it is drawn at now, so the first drag fixes the whole picture rather than just one step. */
export function placementsFor(
  nodeIds: string[], positions: Map<string, { x: number; y: number }>, moved: { id: string; x: number; y: number },
): { nodeId: string; x: number; y: number }[] {
  return nodeIds.flatMap((id) => {
    const p = id === moved.id ? moved : positions.get(id)
    return p ? [{ nodeId: id, x: clampCoord(Math.round(p.x)), y: clampCoord(Math.round(p.y)) }] : []
  })
}
