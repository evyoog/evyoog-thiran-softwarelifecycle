<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 950–973). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.2 My Work

**Tasks are derived, never authored** (Principle 6). This module is a query.

- **Today** — overdue / today / this week / blocked, grouped. Each row states *why* it
  is a task ("Approved 6 days ago, no code linked yet"). Check-off is optimistic.
- **Pipeline** — the requirement's own lifecycle as lanes (Draft, In review, Approval,
  Development, Verification, Deployed), not a sprint board. Cards mark themselves stalled
  past a per-stage threshold.
- **Calendar** — month view; personal task due dates merged with release milestones.

Derivation rules (implement as one SQL view per rule):

| Task | Rule |
|---|---|
| Author: fix wording | Requirement owned by user with an open `ambig` finding |
| Reviewer: review | Requirement in review with user as participant |
| Approver: sign off | Requirement `IN_REVIEW`, user holds Approver on its scope |
| Developer: implement | Requirement `APPROVED`, assigned developer, no code link |
| Developer: re-implement | Code links to revision N, requirement now at N+1 |
| Tester: verify | Requirement `APPROVED` with no passing test at current revision |
| Tester: re-verify | Evidence stale (`has_stale_evidence`) |
| Anyone: unblock | Open clarification blocking a task the user owns |
