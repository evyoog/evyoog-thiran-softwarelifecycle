-- Local dev only, mirrors the real shared RDS instance:
-- CREATE SCHEMA vyg_requirement AUTHORIZATION postgres;
--
-- No separate migrator/app/relay roles here (see docs/DECISIONS.md D3) — the
-- connecting role owns the schema, same as vyg-pms and the pricing tool on the
-- real instance. Flyway and the application share one set of credentials.
CREATE SCHEMA IF NOT EXISTS vyg_requirement AUTHORIZATION postgres;
