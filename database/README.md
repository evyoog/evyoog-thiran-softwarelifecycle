# database

Version-controlled database assets.

| Path | What |
|---|---|
| [`migrations/`](migrations/) | **The schema.** Flyway migrations `Vnnn__description.sql`, forward-only. Packed into the backend jar by Maven and applied at startup. |
| [`init/`](init/) | run once when the local docker-compose Postgres first starts (creates the `vyg_requirement` schema) |
| `seed/`, `views/`, `functions/`, `procedures/` | for assets that should not be migrations; empty today |

Rules and background: [`docs/07-database/database-design.md`](../docs/07-database/database-design.md). Every schema change is a new migration in the same pull request as the code that needs it; never edit a merged migration.
