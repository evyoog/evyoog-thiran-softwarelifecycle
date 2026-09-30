<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 1178–1191). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.9 Releases

- **Scope** — what is committed per product; verified percentage; open gaps; scope
  movement in and out over the last 30 days with who moved it
- **Baselines** — immutable frozen snapshots; created date, revision, item count, gaps at
  freeze, signed off by. Compare any two baselines
- **Variants** — which requirements apply to which product edition
- **Deployment** — which requirements are present in which environment, at which build.
  Build numbers arrive from CI hatched; what Vyoog owns is the link between requirement,
  revision and build
- **Release notes** — generated from requirements that reached Approved (D16: the
  pipeline's terminal status). Anything still held short of it is **listed separately,
  never silently dropped**
