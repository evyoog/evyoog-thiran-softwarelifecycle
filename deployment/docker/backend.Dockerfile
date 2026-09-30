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

COPY --from=build /app/backend/vyoog-api/target/vyoog-api-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
