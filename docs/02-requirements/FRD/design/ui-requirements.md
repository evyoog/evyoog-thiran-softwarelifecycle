<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1095–1122). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.5 Design

- Product → App selectors; one workflow per app
- Auto-laid-out flow diagram (layered by longest path from root; branches share a column)
- Node kinds: start, step, decision, integration, end, **testing, deployment**
  (VYB-0816). Integrations render dashed (Principle 4)
- **Node colour derives from the requirements behind it** — green when all approved
  (D16: APPROVED is the pipeline's terminal status), red when the node has no
  requirement at all. **Testing/Deployment are the exception**: their colour comes from
  real evidence, not requirement status — `requirement_verification_state.is_verified`
  for Testing, presence in `deployment_requirement` for Deployment — since an APPROVED
  requirement with no passing test is not "tested." Always drawn, never hidden; colour
  and the `n/total` sublabel carry how far along it is (Principle 8)
- **Generate from requirements** (VYB-0666) draws one node per approved-or-better
  requirement and wires edges from real trace links only — never an invented ordering
  (the flow stays authored, not inferred, per the rule below). It additionally ensures
  one shared Testing node and one shared Deployment node per flow, fed by every
  requirement node in turn (VYB-0816) — additive and idempotent, like the rest of this
  action
- Click a node: linked requirements, attach/detach, connections, delete
- Add step: label, kind, predecessor, branch label, requirement links
- **Coverage tab** — requirements with no design (feeds detector 4) and steps with no
  requirement (design nobody asked for)
- Export the diagram as SVG

The flow is **authored**, not inferred. Vyoog cannot derive a diagram from requirement
prose honestly; what is automatic is layout, colouring and coverage analysis.
