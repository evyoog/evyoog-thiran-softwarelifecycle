# Macro Planner hierarchy sync: what its repository says (analysis for VYB-0932)

**Status: analysis, not a decision.** VYB-0932 to VYB-0935 (Sprint 7) are BLOCKED on it. Nothing was built.

**Source read:** `evyoog/evyoog-thittam-macro`, branch `main`, commit `06b5abe` (a shallow read-only clone; nothing was changed there). Paths below are inside that repository.

## 1. What VYB-0932 asks for

"Read-only import of Product, Application, Capability, Feature from Macro Planner with local mapping" (F40). The Agile Planner analysis (`agile-planner-contract-analysis.md` section 4) records the same assumption from the Planner side: "Macro Planner: strategy: Goals, and the Application, Capability and Feature levels (read-only in the Planner)".

## 2. What the Macro Planner repository actually contains

The application in that repository is **Vyoog PMS**, a multi-tenant project-management system (`CLAUDE.md`: "Vyoog PMS ... Artifact/package names must not be renamed").

| Question | Answer from the repository |
|---|---|
| Entities for Product, Application, Capability or Feature | **None.** `backend/src/main/java/com/vyoog/pms/entity/` has Project, Phase, Activity, Task, Objective, KeyResult, Milestone, OrgNode, Department, Team, Customer and others; no class or table is named for any of the four levels. The only hits for "feature" in entities are a feature-gate comment on `Project`. |
| Endpoints for them | **None.** The inventory (`docs/08-architecture/api/api-requirements/README.md`) lists 38 controllers (projects, phases, activities, tasks, objectives, org nodes, teams, reports and so on); none returns a product, application, capability or feature. |
| Where "Application", "Capability", "Feature" do appear | In its **documentation structure** (`docs/04-capabilities`, `05-features`, `06-functions`, `docs/03-product/macro-planner-overview.md`: "Application: 01 Macro Planner", capabilities 01.01 to 01.15). That is how the Macro Planner's own product is described, not data it holds. |
| Its own hierarchy | Organisation nodes (division, unit, team) for people; Project, Phase, Activity, Task for work; Objective and Key Result for goals. |
| An integration with this application (ALM) | **None.** `docs/04-capabilities/01.11-integration-api.md` lists "ALM" only as a business purpose of a capability that is not built. |
| The one integration that exists | **MCP** with the Agile Planner (`docs/08-architecture/integrations/INT-agile-planner-mcp.md`): Macro serves `/api/mcp` with tools `list_projects`, `list_objectives`, `update_key_result_progress`, `list_linked_tasks`, `update_task_status`, and calls the Agile Planner's MCP server to create cards. Only the Agile Planner's Keycloak client (`agile-planner-sync`) is allowed. |
| Tenancy and access | One PostgreSQL schema per tenant, chosen by the request's host (`TenantHostFilter`); an unmapped host is refused. Keycloak, the same realm as this application. A caller must be an allowed client. |

## 3. Consequences for the Sprint 7 rows

| Row | Consequence |
|---|---|
| VYB-0932 import of the four levels | There is nothing at the source to import. Mapping Project, Phase or Activity onto Product, Application, Capability, Feature would be an invented business rule (CLAUDE.md: never invent a requirement). |
| VYB-0933 show upstream source, lock edited fields | Depends on 0932; which fields are locked is the field-ownership question D24 is already waiting on. |
| VYB-0934 conflict queue and drift for renamed or removed nodes | Depends on 0932. Needs stable upstream ids, which do not exist for these levels. |
| VYB-0935 map existing local hierarchy to upstream records | Depends on 0932. |

## 4. What is needed before Sprint 7 can start

One of:

A. **Macro Planner defines and serves the four levels** (a read feed with stable ids and a parent for each node). This application then builds the importer and mapping against that contract. The register's sizing assumed this exists.
B. **A stated mapping** from Macro Planner's real objects (organisation node, Project, Phase, Activity, Objective) onto Product, Application, Capability and Feature, decided by the product owner.
C. **A different source of the hierarchy** (for example the ProdOps workbook, or a file an administrator uploads), with Macro Planner left out of it.

Also open, as in D24: which credentials and which of the two transports (MCP, as Macro and the Agile Planner use between them, or REST) to read with. The Macro Planner's MCP server currently refuses every client but the Agile Planner's.

## 5. Observation, not a finding to act on

The Agile Planner analysis recorded a REST contract (`POST /v1/requirements`, signed webhooks). The Macro Planner repository documents that Macro and Agile talk **MCP, with no REST between them**. Whether this application should speak MCP to the Planners too belongs with decision A of the Agile Planner analysis (whose direction and vocabulary win).
