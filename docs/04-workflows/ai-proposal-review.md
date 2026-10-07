# AI proposal review

Added by VYB-0938 (Phase 6, Sprint 8, F30). Migration `database/migrations/V049__ai_proposal.sql`. Code: `com.vyoog.proposal` (`AiProposalService`, `ProposalKind`, `ProposalState`), `AiProposalController`, `BriefElaborationDrafter`. Decision: D31. CLAUDE.md rule 6 ("AI proposes, the human decides") is now enforced by one path.

## The rule

**An AI proposal is a row, and a person's decision on it is the only way its text reaches a requirement, a test case or a brief.** The AI producers record what the AI proposed (`PENDING`) and apply nothing. Accepting a proposal applies the change through the ordinary service for that kind, so every rule and audit event of a hand-made change still applies.

## What is covered

| Kind | Recorded when | Accepting it | Who may decide (the rule that would let them do it by hand) |
|---|---|---|---|
| `REWRITE` | `POST /requirements/rewrite-suggestion` (optional `requirementId`) | edits the requirement's statement through `RequirementService.update` (a new revision, `requirement.revised`). With no requirement (one still being drafted) the decision is recorded and nothing else happens; the form takes the text. | Business Analyst or Architect (`CREATE_EDIT_REQ`), at the requirement; anywhere if there is none |
| `TEST_CASE` | `POST /requirements/{id}/test-case-suggestions` and `.../bulk`, one proposal per suggestion | drafts the test case through `TestCaseService.draft` (linked to the requirement, status DRAFT, `test-case.drafted`) | Tester (`VERIFY`), at the requirement |
| `BRIEF_ELABORATION` | `POST /briefs/elaborations` (asks the AI for every requirement a brief for the scope would carry) | makes it eligible for briefs of that requirement's current revision | Business Analyst or Architect (`CREATE_EDIT_REQ`), at the requirement |

Not covered here, and unchanged: import candidate proposals (type, acceptance criteria, trace links) and document analysis findings. They already need a person per item and keep their own tables; they move onto this endpoint in a later row.

## The endpoints

- `GET /api/v1/ai-proposals?state=PENDING|ACCEPTED|REJECTED|SUPERSEDED|ALL&kind=&requirementId=&page=&size=`: any signed-in person. Default `PENDING`. An unknown state or kind is a 400.
- `GET /api/v1/ai-proposals/{id}`.
- `POST /api/v1/ai-proposals/{id}/decision` with `{"decision":"ACCEPT"|"REJECT","edits":{...},"reason":"..."}`. A person, not a service account; the role above, checked against the proposal's requirement.
- `POST /api/v1/briefs/elaborations` (`{applicationId, capabilityIds}`; same rule as generating a brief) and `GET /api/v1/briefs/elaborations?applicationId=&capabilityIds=` (requirements in scope, how many have an accepted elaboration at their current revision, and the pending drafts).

## Deciding

- **Accept** may carry `edits`: only `statement` (rewrite), `title` and `description` (test case) or `detail` (elaboration); anything else is a 400, and so is blank text. The AI's original stays in `payload`; what was applied is in `acceptedPayload` when the person edited it.
- **Reject** takes an optional `reason`, kept either way, and no edits.
- **Decided once.** A second decision is a 409 ("already accepted"). The row is locked while it is decided, so two people accepting the same proposal at once draft exactly one test case.
- **Refused if the requirement has changed** since the proposal was made (409, naming the revisions); the proposal stays pending and can still be rejected. The list marks such a proposal `stale`.
- **Refused if the apply is refused.** An approved requirement still needs a change request, so accepting a rewrite for one is a 409 and the proposal stays pending.
- Audit: `ai-proposal.proposed`, `ai-proposal.accepted` (with whether it was edited and what it applied), `ai-proposal.rejected`.

## Briefs

Generating a brief **never calls the AI**. The Delivery screen has two separate steps: "Draft elaborations (AI)" records pending proposals, each reviewed beside it (accept as drafted, accept with an edit, reject); then "Reviewed AI elaboration" in the brief's options adds, per requirement, the elaboration a person accepted **for its current revision** (`includeReviewedElaborations`, replacing `includeAiElaboration`). Only the latest accepted one counts (an older accepted one is superseded), a new pending draft supersedes an older pending one, and an elaboration written for earlier words is not offered again once the requirement has a new revision. The text is labelled "AI elaboration, accepted by a person" in the brief.

## Stated limits

- A person can still type or paste any text, including text an AI wrote elsewhere, into `POST /test-cases` or a requirement edit. What this closes is the path from an AI suggestion in this system to a stored artifact.
- A rewrite for a requirement not yet created is decided and recorded, then the form takes the text; the requirement that is created afterwards is not linked back to the proposal.
- No screen lists all proposals; they are reviewed where they are made (the new-requirement form, the bulk test-case panel, Delivery).
- Pending proposals are not expired.

## Tests

`AiProposalIT` (`VYB0938_AC9` to `AC19`: recording applies nothing, accept and edit, stale, approved requirement, test case, decide once and concurrently, editable fields, brief eligibility, who may decide, the list), `ModelGatewayWiringIT` (`AC20` to `AC22`: the three producers end to end against a stub provider), `BriefElaborationDrafterTest` (`AC5` to `AC8`), `BriefElaborationTest` (`AC1` to `AC4`), `elaborationReview.test.ts` (`AC23`), `SuggestionCard.test.ts` (`AC24`), `AccessPolicyTest`.
