# Backend image. Build context is the REPOSITORY ROOT, because the Flyway migrations live in
# database/migrations and the Maven build packs them into the jar (see backend/vyoog-domain/pom.xml):
#
#   docker build -f deployment/docker/backend.Dockerfile -t vyoog-api .
#
# The image mirrors the repository layout (/app/backend, /app/database) so the relative
# path in vyoog-domain's pom (../../database/migrations) resolves the same way it does locally.

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app/backend

# Cache dependency layer separately — copy every module's pom.xml first (reactor:
# vyoog-testkit, vyoog-domain, vyoog-api, vyoog-worker) so a source-only change doesn't
# invalidate this layer.
COPY backend/pom.xml .
COPY backend/vyoog-testkit/pom.xml vyoog-testkit/
COPY backend/vyoog-domain/pom.xml vyoog-domain/
COPY backend/vyoog-api/pom.xml vyoog-api/
COPY backend/vyoog-worker/pom.xml vyoog-worker/
RUN mvn -pl vyoog-api -am dependency:go-offline -q

COPY backend/vyoog-testkit/src vyoog-testkit/src
COPY backend/vyoog-domain/src vyoog-domain/src
COPY backend/vyoog-api/src vyoog-api/src
COPY database/migrations /app/database/migrations
RUN mvn -pl vyoog-api -am clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# VYB-0909 (F33-F35): never run the application as root. A fixed uid/gid (10001) so an
# orchestrator that enforces runAsNonRoot / runAsUser can name it. Nothing the app does needs to
# write below /app; it writes to the database and to object storage only.
RUN addgroup -S -g 10001 vyoog && adduser -S -u 10001 -G vyoog -h /app -s /sbin/nologin vyoog

COPY --from=build --chown=vyoog:vyoog /app/backend/vyoog-api/target/vyoog-api-*.jar app.jar

USER vyoog:vyoog

EXPOSE 8080

# Liveness only (the process is up and serving), not readiness: a database outage should take the
# instance out of rotation, not restart it. /actuator/health/liveness is open without a token
# (SecurityConfig). wget is BusyBox's, already in this image; no extra package.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health/liveness" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
