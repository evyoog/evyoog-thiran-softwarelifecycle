<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1430–1467). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

## 11. SDLC

Two distinct things share this name. Both are specified.

### 11.1 The SDLC that Vyoog implements (the product's own flow)

This is the process the tool enforces for its customers.

```
 ┌─────────┐   ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌────────┐   ┌────────┐
 │ INTAKE  │──▶│ AUTHOR  │──▶│  REVIEW  │──▶│ APPROVE │──▶│  BUILD │──▶│ VERIFY │──▶ RELEASE
 └─────────┘   └─────────┘   └──────────┘   └─────────┘   └────────┘   └────────┘
      │             │              │              │             │            │
   Import      New req         Review        Signature      Brief +      Test run
   queue       screen          round         (step-up)      developer    → verification
      │             │              │              │             │            │
      └─────────────┴──────────────┴──────────────┴─────────────┴────────────┘
                    every stage writes to the accountability timeline
                    every stage re-runs detection on the affected subgraph
```

| Stage | Entry | Who | Exit | Gate |
|---|---|---|---|---|
| Intake | A document or a stakeholder request | BA | Candidates accepted into the register | Nothing enters without explicit acceptance |
| Author | Candidate or new requirement | BA, Architect | Statement + ≥1 criterion + upstream link | Live lint; gap preview |
| Review | Submitted | Reviewers, Compliance | All signatures present | No open blocking clarification |
| Approve | Reviewed | Approver on scope | Signed | **SoD: not the owner.** Step-up auth |
| Build | Approved | Developer named on the brief | Commit trailers link code to the requirement | Untraced commits raise a finding |
| Verify | Code present | QA | Test passes **at the current revision** | Revision bump invalidates automatically. D16: this evidence (`is_verified`) is tracked for quality reporting, but does not gate Release below — `requirement.status` has no `VERIFIED` value to reach |
| Release | **Approved** (D16: the requirement lifecycle's terminal status — not "Verified") | Release manager | In a frozen baseline, deployed | Anything not Approved is listed separately, never dropped |

**Defect loop.** A defect found in build, verify or production names the requirement it
traces to and classifies the root cause as *requirement defect* or *coding error*. This
is the feedback that measures whether requirement quality is improving.

**Change loop.** Any change to an approved requirement is a change request with computed
impact volume, board approval, and automatic suspect-marking downstream.
