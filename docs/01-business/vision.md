<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 40–67). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

## 1. Product definition

### 1.1 What Vyoog is

A requirements management platform that treats the **requirement graph** as the primary
asset and continuously detects where that graph is broken. It combines the register
discipline of DOORS/Polarion/Jama with continuous cross-application gap detection, and
it generates implementation briefs that a developer or an AI coding agent can execute.

### 1.2 Hierarchy

```
Platform → Product → App → Capability → Requirement
```

Every requirement belongs to exactly one capability. Every capability belongs to exactly
one app. This is rigid on purpose — it is what makes cross-app duplicate detection and
scope arithmetic possible.

### 1.3 The lifecycle Vyoog manages

```
Author → Review → Approve → Develop → Test → Deploy
```

Full accountability at every stage: who created, who reviewed, who approved, who
developed, who tested, who deployed. Recorded as events, never as assignments.
