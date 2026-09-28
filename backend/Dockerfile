# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Cache dependencies layer separately — copy every module's pom.xml first (reactor:
# vyoog-testkit, vyoog-domain, vyoog-api, vyoog-worker) so a source-only change doesn't
# invalidate this layer.
COPY pom.xml .
COPY vyoog-testkit/pom.xml vyoog-testkit/
COPY vyoog-domain/pom.xml vyoog-domain/
COPY vyoog-api/pom.xml vyoog-api/
COPY vyoog-worker/pom.xml vyoog-worker/
RUN mvn -pl vyoog-api -am dependency:go-offline -q

COPY vyoog-testkit/src vyoog-testkit/src
COPY vyoog-domain/src vyoog-domain/src
COPY vyoog-api/src vyoog-api/src
RUN mvn -pl vyoog-api -am clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/vyoog-api/target/vyoog-api-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
