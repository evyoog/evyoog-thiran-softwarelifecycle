<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1145–1177). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.8 Delivery

- **Implementation briefs** — the feature the whole product points at.
  - Select app → capability(ies); the app selector genuinely drives the capability list
  - Choose target: **Claude Code / Codex / Human-readable** — this actually branches the
    generated content, not just a label
  - Assign a **developer at generation time**; named in the header, Section 0 and the
    Definition of Done
  - Options: include acceptance criteria, NFRs, trace IDs, glossary, test skeletons
  - Generated markdown includes: Section 0 (context and who is doing this),
    **Section 2 — AI review across all requirement categories in scope** (counts
    Functional / Non-Functional / Business Rule / Other, plus test coverage, and flags
    when no NFRs are present), Section 5 grouped by requirement category, Definition of
    Done, and a commit-trailer block (omitted for the human-readable target)
  - **AI elaboration (VYB-0817), explicit opt-in, off by default** — a real OpenAI call
    expands each requirement's statement into 3-6 sentences of detailed prose for the
    developer, grounded only in the statement and its acceptance criteria (never a new
    fact, threshold or system, never effort/cost/duration). Rendered directly under the
    requirement's own statement in Section 5, clearly labelled and never in its place —
    the statement is what a human wrote and approved; this only expands on it. Requesting
    it while the AI provider is unconfigured refuses the whole generation rather than
    silently returning a brief without it.
  - Download as `.md`
  - **Persist the brief as an object with a baseline reference** so staleness is
    detectable: if requirements change after generation, the brief is marked stale
- **Scope signals** — eight exact counts over the graph (requirements, acceptance
  criteria, dependency depth, cross-app reach, ambiguity load, open gaps, change rate,
  novelty), each shown against the portfolio median, with the SQL that computed it
  viewable. **No combined effort estimate, ever** — that is the delivery tool's job.
  Borrowed signals render hatched with a source tag.
- **Impact analysis** — pick a requirement, see volume affected: requirements, tests,
  apps, capabilities, teams, briefs made stale. Volume only, never days.
