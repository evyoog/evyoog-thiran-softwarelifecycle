<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1–39). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

# Vyoog — Complete Build Specification

**Stack:** Spring Boot (Java 21) · React 18 + TypeScript · PostgreSQL 16 · Keycloak 25
**Status of this document:** authoritative build source. Where this document and the
React prototype (`VyoogApp.jsx`) disagree, this document wins — the prototype validated
the interaction model, not the system.

---

## 0. How to use this document

This is the master specification. It is written to be handed to an AI coding agent
(Claude Code) or to a human team, section by section. It is deliberately long because
the instruction was *do not miss any feature*.

Companion files:

This specification used to be one file, `vyoog-build-specification.md`. It is now split, verbatim, into the topic folders of `docs/`; [`SPECIFICATION-INDEX.md`](SPECIFICATION-INDEX.md) maps every section number to its file, so "§4.4" still finds its home.

| File | Purpose |
|---|---|
| [`SPECIFICATION-INDEX.md`](SPECIFICATION-INDEX.md) | Section number → file. |
| [`../archive/vyoog-claude-code-kickoff.md`](../archive/vyoog-claude-code-kickoff.md) | Paste-ready prompts: project kickoff and the per-session protocol (historical). |
| Flyway migrations, `database/migrations` | The database schema. A `vyoog-schema.sql` once shipped here as design-time DDL; it was removed (VYB-0905) because it described the multi-tenant model dropped in D3, and the migrations are the only schema that runs. |

**Do not attempt to build this in one pass.** Section 12 breaks the work into phases and
sessions. The single most common failure mode for a system this size is an agent that
generates 60% of everything and finishes nothing.

### Reading order for the build team

1. §1 Product definition — what this is and what it refuses to be
2. §2 Architecture, §3 Multi-tenancy, §4 Keycloak — decide these before writing code
3. §5 Data model — the shape everything else assumes
4. §6 Gap detection — the differentiator
5. §7 Feature inventory — the work itself
6. §11 SDLC — how work flows, both in the product and in building it
7. §12 Build sequence — what to do first

---
