# Design: editing a flow, dragging the layout, exporting the picture

Added by VYB-0945 (Phase 6, Sprint 9, F20). Migration `V054__design_node_position.sql`. Code: `DesignService` (`updateNode`, `saveLayout`, `resetLayout`, `relabelEdge`, `deleteEdge`), `DesignController`, and under `frontend/src/features/`: `Design.tsx`, `design/placement.ts`, `design/diagramFile.ts`. Decisions: the product owner chose, in the VYB-0945 session, the position saved on the step (shared), edge editing limited to label and delete, and a one-page PDF fitted to the diagram.

The flow itself is unchanged (one per application, steps, connections, steps linked to requirements: VYB-0490 to 0495, 0530 to 0538, 0666, 0816). This adds what a person could not do before: change a step or a connection, put steps where they read best, and take the picture away.

## 1. Editing a step

Click a step, then **Edit**: label (required, at most 200 characters), kind and note. `PATCH /api/v1/design/nodes/{id}`; audited as `design.node_edited` with what it was before.

The two pipeline milestones (**Testing**, **Deployment**) that a generate run keeps one of per flow **may be renamed or noted but keep their kind**, and no other step may become one. Generate finds them by kind, and they read real verification and deployment records, so a person cannot make a step into one by naming it.

## 2. Editing a connection

Click a connection (a wide invisible stroke makes it easy to hit, and it is reachable by keyboard): change its **label** (blank clears it, at most 100 characters) or **remove** it. `PATCH` and `DELETE /api/v1/design/edges/{id}`; audited as `design.edge_relabelled` (with the old label) and `design.edge_deleted`. Removing a connection leaves both steps. To join different steps, remove it and add another: re-pointing an edge was offered and not chosen.

## 3. Dragging the layout

Drag a step; it snaps to a 10 px grid and cannot leave the diagram (0 to 100000). A press that moves under 4 px is a click and selects. On drop the screen saves **every step where it is drawn now**, so the first drag fixes the whole picture rather than only the moved step (`PUT /api/v1/design/flows/{id}/layout`, one audited `design.layout_saved` with the count). The place is stored on the step (`design_node.x`, `y`, both or neither, checked by the database), so **everyone sees the same picture and the exports match**.

- A step with no saved place is laid out automatically, **in a band below everything placed**, so a step added or generated later never lands on top of a saved one. Generating again keeps the dragged layout.
- **Reset layout** (`DELETE .../layout`, audited as `design.layout_reset` with how many had a place) forgets every saved place, for everyone, and the automatic layout draws the flow again. It asks first.
- A save that is wrong in any way (a step from another flow, a step twice, a place out of range, nothing to save) is refused whole; nothing moves.
- The server decides who may; the screen shows its refusal in words.

## 4. Export

**SVG**, **PNG** and **PDF**, from the picture on the screen, named `<application>-workflow-<date>.<ext>`:

- The SVG is standalone: namespace, size, a solid background and a font that exists outside the app, with the screen's own click targets left out (VYB-0538 keeps its fixed colours).
- The PNG is the SVG drawn on a canvas at twice the size (less for a very large diagram, so the browser does not refuse it).
- The PDF is **one page the size of the diagram** (96 px per inch, so a page is 0.75 pt per pixel; scaled down only if it would pass the 14400 pt limit readers must accept) holding that picture as a JPEG. It is written in the browser with no library; the picture is not selectable text.

## Who may

The same role as authoring a design (`CREATE_EDIT_REQ`); reading is open to any signed-in person. All five new write endpoints are `@RequiresAccess` and in `AccessPolicyTest`.
