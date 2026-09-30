<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1135–1144). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.7 Quality

- **Reviews** — review rounds (active / awaiting my signature / closed). Participants,
  progress, blocked state. Electronic signature on approval with step-up auth (§4.5).
- **Verification** — test cases linked to requirements; last run result; unverified
  requirements; **stale evidence** (changed after last pass). "Draft test case" action.
- **Defects** — every defect names the requirement it traces to **and why**: root cause
  classified as *requirement defect* (ambiguity, omission) versus *coding error*. This
  linkage is the loop that proves requirement quality affects production outcomes.
