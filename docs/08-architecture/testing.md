# Testing

| Kind | Where | Runs in | Needs |
|---|---|---|---|
| Unit tests | `*Test.java` next to the code; frontend `*.test.ts` | `mvn verify` (Surefire), `npm test` | nothing |
| Integration tests | `backend/vyoog-api/src/test/java/com/vyoog/api/it/*IT.java` | `mvn verify` (Failsafe), CI | Docker, or a local PostgreSQL (below) |
| Verification runners | `*VerificationRunner.java` in `com.vyoog.api` | by name only, never in CI | a local database, sometimes MinIO |

All tests that name a requirement are called `VYBnnnn_ACn_shortDescription`, which is how `test-cases/automated-tests-index.md` traces them.

## Integration tests (VYB-0907)

They start the whole Spring application against a real **PostgreSQL 16 with pgvector**, apply every Flyway migration from `database/migrations`, and call the real services: nothing in the database path is mocked. The object store is stubbed. Everything extends `IntegrationTestBase`.

- **Where the database comes from** (`LocalDatabase`, VYB-0903): with `DB_URL` unset, a throwaway Testcontainers `pgvector/pgvector:pg16` container (needs Docker). With `DB_URL` set it must point at localhost; anything else is refused.
- **Isolation:** tests commit, as production does (several services write with both JPA and JdbcTemplate and depend on separate transactions), and each test makes its own uniquely named product, people and requirements and asserts only on those. Nothing is cleaned up; against a throwaway container there is nothing to clean. Against a local database the leftovers are harmless test rows.
- **Why not roll back each test:** a test-wide transaction hides exactly the class of bug these tests exist to find (JPA defers an INSERT that a following JdbcTemplate statement needs; the first real run found four such defects, listed in the register's session 67 log).
- **Startup configuration:** the six required settings (D22) are provided as system properties by a static initializer (`TestDatabaseProperties`), not `@DynamicPropertySource`, because the startup check runs before dynamic properties exist.

### Run them

```bash
# With Docker running:
cd backend && ./mvnw -B verify

# Without Docker: any local PostgreSQL 16 with the pgvector extension installed
#   (apt: postgresql-16-pgvector). Create an empty database and the schema, then:
createdb vygmicroservice && psql vygmicroservice -c "CREATE SCHEMA vyg_requirement"
DB_URL='jdbc:postgresql://localhost:5432/vygmicroservice?currentSchema=vyg_requirement,public' \
DB_USER=postgres DB_PASSWORD=... \
  ./mvnw -B verify

# One class:
./mvnw -B -pl vyoog-api -am verify -Dit.test=TraceIT -Dtest=NoSuchTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

### What is covered

`FoundationSmokeIT` (migrations applied, extensions, search path), `RequirementIT` (keys, revisions, optimistic concurrency, the state machine and who may move it, soft delete), `TraceIT` (links, closure table, traversal and cycles, suspect links, coverage), `ReleaseIT` (scope, one release at a time, movements, readiness, blocked, notes), `ReviewIT` (frozen revisions, participants, signing, separation of duties, closing), `BaselineIT` (freeze, immutability, diff), `ChangeRequestApplyIT` (raise, impact, decide, edit through the change request, apply, suspect links).

Not covered yet: briefs, import, design, defects and test cases, detection sweeps end to end, the HTTP layer with a real database. Add an `*IT` for any new SQL.
