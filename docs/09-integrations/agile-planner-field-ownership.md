# Agile Planner: who owns which field (VYB-0914)

The field-by-field ownership the Planner's own documents never gave (`agile-planner-contract-analysis.md`, section 4). Written from the product owner's answers of 2026-10-15 (D24) and the defaults chosen in the VYB-0915 session (D50). **A default is marked; change any on request.** Nothing here has been confirmed with the Planner team.

| Field | Owner after the Function is first sent | What this application does |
|---|---|---|
| Function id, Feature id, Capability id, Goal id | **Agile Planner / Macro Planner** | Stored as text in `planner_function_link`; never edited here; never used to place the requirement yet. |
| Title (the Function's name) | **This application** once created | Seeded from the Planner once. A later Planner change is a **proposal** (D24, D51) that a person accepts or rejects; accepting is an edit by that person. |
| Description (becomes the statement) | **This application** | As the title. |
| Acceptance criteria | **This application** | Seeded as one criterion per string; own lifecycle after that. Accepting a proposal adds criteria the requirement lacks and **never removes one**. |
| Priority | **This application** | Seeded by name (`critical`, `high`, `low`; anything else MEDIUM). *Default (D50).* |
| Type | **This application** | Always FUNCTIONAL on creation. *Default (D50).* |
| Placement (capability) | **This application** | Unplaced on creation; an administrator places it. *Default (D50): needs the Macro Planner mapping to do better.* |
| Requirement status | **This application** | Draft on creation. The Planner never sets it. Sent back to the Planner as `requirement.status_changed` (not built). |
| Designs, test cases, test results, evidence | **This application** | Sent back as the other three webhooks (not built). The Planner derives its own `Function.test_status` from them. |
| Function status, sprint, team, assignee, story points, board column | **Agile Planner** | Never sent, never stored here. Its board column labels are not mapped to this application's statuses, in either direction. *Default (D50).* |
| Who may edit a seeded requirement | **This application's normal rules** | A person with the right role edits it as any requirement; an approved one changes only through a change request. |
