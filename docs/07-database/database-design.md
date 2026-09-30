# Database design

- **Engine:** PostgreSQL 16 with `pgvector` (similarity search), `pg_trgm` and `pgcrypto`.
- **Schema:** one application schema, `vyg_requirement`, on the company's shared database instance (`vygmicroservice`). Vyoog is single-tenant (D3): no `tenant_id`, no row-level security.
- **Source of truth:** the Flyway migrations in `database/migrations`, named `Vnnn__short_description.sql`. The Maven build packs them into the application jar (`backend/vyoog-domain/pom.xml`), and Flyway applies them at startup (`spring.flyway.locations: classpath:db/migration`). `V001__baseline.sql` is the baseline.
- **Rules:** migrations are forward-only and are never edited once merged; a schema change is a new migration in the same pull request as the code that needs it; every migration must run against a populated database. Hibernate only validates (`ddl-auto: validate`).
- **Search path:** the connection lists both schemas, `currentSchema=vyg_requirement,public`, because the `pgvector` and `pg_trgm` operators are registered unqualified in `public`.
- **Local database:** `database/init/01-schema.sql` creates the schema when the docker-compose Postgres first starts.
- **Seed data, views, functions, procedures:** created by migrations (for example the seeded gap-rule templates and integration connections), not by separate scripts; the `database/seed`, `views`, `functions` and `procedures` folders are for assets that should not be migrations, and are empty today.
