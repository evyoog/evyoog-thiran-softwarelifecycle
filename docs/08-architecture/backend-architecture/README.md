# Backend architecture

A modular monolith, Java 21 / Spring Boot 3.3, in four Maven modules under `backend/`: `vyoog-domain` (entities, services, detectors), `vyoog-api` (controllers, security, startup checks), `vyoog-worker` (reserved, empty), `vyoog-testkit` (test fixtures). Modules may not import each other's `internal` packages; ArchUnit enforces it. Role checks go through `PrincipalGuard`. The system architecture is [`../system-architecture/architecture.md`](../system-architecture/architecture.md); the backend README has module and runner details: [`../../../backend/README.md`](../../../backend/README.md).

AI: every model call goes through one gateway, [`ai-model-gateway.md`](ai-model-gateway.md) (VYB-0936).
