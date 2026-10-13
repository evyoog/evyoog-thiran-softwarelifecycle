# Kanban board

Added by VYB-0953 (Phase 6, Sprint 11, F42). Migration `V056__board_template.sql` (`board_template`). Code: `BoardTemplateService` (`com.vyoog.board`), `BoardController`, the `applicationId` and `productId` filters on `GET /api/v1/requirements`, and under `frontend/src/features/board/`: `BoardPage`, `MoveDialog`, `TemplateForm`, `boardModel.ts`; the lifecycle table the requirement page and the board share is `frontend/src/features/requirements/lifecycle.ts`. Decision: D42.

The board is the requirement lifecycle laid out as columns. **A card's column is its status and nothing else**; the board keeps no state of its own, so it cannot disagree with the requirement page. It is the **Board** button on the Requirements screen (`/requirements/board`). My Work's Pipeline is unchanged.

## Moving a card

Drag a card onto another column, or use the **Move…** list on the card (the keyboard way). Either asks the server to make the ordinary status transition (`POST /requirements/{id}/transition`), so **the roles, separation of duties and blocking clarifications are the server's**, exactly as on the requirement page. A refusal from the server is shown as written in the confirm dialog.

- A drop the lifecycle never allows (draft to approved, anything out of approved) is refused on the board with the reason in words, and the server is not asked.
- A move that needs a reason (reject, send a reviewed requirement back for revision, submit one with no acceptance criteria) asks for it in the dialog, and Confirm stays disabled until one is given.
- **In review to Reviewed is not a plain move.** It is a person's verification (VYB-0811, VYB-0946), so a drop there, or Verify… in the list, opens the Design page on that requirement instead of moving the card.
- The card list is refreshed after a move; a requirement changed by someone else in between is refused by the server's revision check, like any edit.

## Templates

A template is **which columns, in what order, over which requirements**: a name, the statuses to show (at least one, none twice), and an optional scope and filters: **one of a product, an application or a capability**, a type and a priority. Each column lists the requirements in that status that match, by key, up to 50 (the column says when there are more, and the template is how to narrow them).

- **Default** belongs to no team and is for everyone: all six statuses in lifecycle order. Only an Administrator changes it, and it is never deleted.
- Any other template belongs to a team. **A lead of that team or an Administrator** makes, changes and deletes it; any signed-in person may open any template. A name is unique within a team (case ignored). Statuses are checked in the service, not in a database list, so a status added to the lifecycle later does not need a migration here.
- The scope is chosen from the top down in the form (product, then application, then capability); the stored scope is the deepest one chosen. The template also reports the application and product a capability sits under, so editing it comes up filled in.
- The chosen template is in the address (`?template=`), so a board can be shared as a link; a deleted template falls back to the default.

## API

`GET /api/v1/boards/templates` (any signed-in person), `POST /api/v1/boards/templates`, `PUT` and `DELETE /api/v1/boards/templates/{id}`. The writes are guarded in the handler (an Administrator, or a lead of the template's team), not with `@RequiresAccess`, because the rule depends on the template's team rather than on a scope; `AccessPolicyTest` accepts a `guard.` call, and `BoardTemplateIT` proves 403 for a member, an outsider and a lead of another team. Every change is audited: `board.template_created`, `board.template_updated` (with the old name and columns), `board.template_deleted`.

## Not in this row

No saved position of a card within a column (cards are ordered by key), no work-in-progress limits, no swimlanes, no cross-team sharing of a template, and no board over anything but requirements (defects and tests are not cards). Dragging needs a pointer; the Move… list is the keyboard path.
