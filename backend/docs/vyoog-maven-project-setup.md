# Vyoog — Maven project setup

Companion to `vyoog-build-specification.md` §2.4. Copy these POMs as-is for Phase 0.

Four modules, each with a reason to exist. The ten *domain* modules from §2.1 are Java
packages inside `vyoog-domain`, not Maven artifacts — enforced by ArchUnit, not by the
reactor. Splitting them into jars buys nothing and costs a slow build and version churn.

```
vyoog/
├── pom.xml                 parent · dependencyManagement, plugins, Java 21
├── mvnw, mvnw.cmd, .mvn/   Maven Wrapper, committed
├── vyoog-domain/           entities, repositories, services, the twelve detectors
├── vyoog-api/              controllers, security, OpenAPI, the Boot application
├── vyoog-worker/           detection scheduler, outbox relay, embedding jobs
└── vyoog-testkit/          Testcontainers fixtures, tenant-isolation harness
```

---

## Parent `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>

  <groupId>com.vyoog</groupId>
  <artifactId>vyoog</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>Vyoog</name>

  <modules>
    <module>vyoog-domain</module>
    <module>vyoog-api</module>
    <module>vyoog-worker</module>
    <module>vyoog-testkit</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <testcontainers.version>1.20.2</testcontainers.version>
    <archunit.version>1.3.0</archunit.version>
    <springdoc.version>2.6.0</springdoc.version>
    <jooq.version>3.19.11</jooq.version>
    <pgvector.version>0.1.6</pgvector.version>
    <restassured.version>5.5.0</restassured.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Every version lives here. A child POM with a <version> on a managed
           dependency fails review. -->
      <dependency>
        <groupId>com.vyoog</groupId><artifactId>vyoog-domain</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.vyoog</groupId><artifactId>vyoog-testkit</artifactId>
        <version>${project.version}</version><scope>test</scope>
      </dependency>

      <dependency>
        <groupId>org.testcontainers</groupId><artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type><scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId>
        <version>${archunit.version}</version><scope>test</scope>
      </dependency>
      <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>${springdoc.version}</version>
      </dependency>
      <dependency>
        <groupId>com.pgvector</groupId><artifactId>pgvector</artifactId>
        <version>${pgvector.version}</version>
      </dependency>
      <dependency>
        <groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId>
        <version>${restassured.version}</version><scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- Applies to every module -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <pluginManagement>
      <plugins>
        <!-- Unit tests -->
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <configuration>
            <excludes><exclude>**/*IT.java</exclude></excludes>
          </configuration>
        </plugin>

        <!-- Integration tests. Named *IT so they run here, not in Surefire.
             This split is the most common Maven mistake on this project. -->
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId>
          <configuration>
            <includes><include>**/*IT.java</include></includes>
          </configuration>
          <executions>
            <execution>
              <goals><goal>integration-test</goal><goal>verify</goal></goals>
            </execution>
          </executions>
        </plugin>

        <plugin>
          <groupId>org.flywaydb</groupId>
          <artifactId>flyway-maven-plugin</artifactId>
        </plugin>
      </plugins>
    </pluginManagement>

    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>enforce</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[21,)</version></requireJavaVersion>
                <dependencyConvergence/>
                <banDuplicatePomDependencyVersions/>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

---

## `vyoog-domain/pom.xml`

The core. **Must not depend on `spring-boot-starter-web`.** If a domain class needs an
HTTP type, the design is wrong.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.vyoog</groupId><artifactId>vyoog</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>vyoog-domain</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jooq</artifactId>
      <!-- recursive CTEs for the trace graph; not expressible in JPQL -->
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId><artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.pgvector</groupId><artifactId>pgvector</artifactId>
    </dependency>

    <dependency>
      <groupId>com.vyoog</groupId><artifactId>vyoog-testkit</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

---

## `vyoog-api/pom.xml`

The deployable.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.vyoog</groupId><artifactId>vyoog</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>vyoog-api</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.vyoog</groupId><artifactId>vyoog-domain</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Keycloak is reached as a standard OIDC provider.
         No Keycloak adapter: those are deprecated and unnecessary. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>com.vyoog</groupId><artifactId>vyoog-testkit</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <image><name>vyoog/api:${project.version}</name></image>
          <excludes>
            <exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

`vyoog-worker` is the same shape with `vyoog-domain` plus
`spring-boot-starter-quartz`, and no `-web`. It ships as its own image and runs the
detection sweep, the outbox relay and the embedding queue.

---

## `vyoog-testkit/pom.xml`

Test fixtures shared by domain and api. Compile scope inside this module so other
modules can depend on it at test scope.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.vyoog</groupId><artifactId>vyoog</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>vyoog-testkit</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>compile</scope>
    </dependency>
  </dependencies>
</project>
```

---

## The Postgres container the tests need

RLS only works when the application connects as a **non-owner, non-superuser** role.
The default Testcontainers user is the owner, so a naive fixture will pass while
production leaks. Set it up correctly once, in `vyoog-testkit`:

```java
public abstract class PostgresFixture {

  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
              .asCompatibleSubstituteFor("postgres"))
          .withUsername("vyoog_migrator")      // owns the schema, runs Flyway
          .withPassword("test")
          .withDatabaseName("vyoog");

  @BeforeAll
  static void createApplicationRole() throws Exception {
    try (var c = DB.createConnection(""); var st = c.createStatement()) {
      st.execute("CREATE ROLE vyoog_app LOGIN PASSWORD 'test'");
      st.execute("GRANT USAGE ON SCHEMA public TO vyoog_app");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE "
               + "ON ALL TABLES IN SCHEMA public TO vyoog_app");
    }
  }

  // The application DataSource connects as vyoog_app, never vyoog_migrator.
  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", () -> "vyoog_app");
    r.add("spring.datasource.password", () -> "test");
    r.add("spring.flyway.user", () -> "vyoog_migrator");
    r.add("spring.flyway.password", () -> "test");
  }
}
```

## The test that gates Phase 0

```java
class TenantIsolationIT extends PostgresFixture {

  @Autowired JdbcTemplate jdbc;

  @Test
  void unboundQueryReturnsZeroRowsNotAllRows() {
    // two tenants, one requirement each, inserted with context bound
    insertAs(TENANT_A, "VY-1001");
    insertAs(TENANT_B, "VY-2001");

    // no app.tenant_id bound
    jdbc.execute("RESET app.tenant_id");
    Integer visible = jdbc.queryForObject(
        "SELECT count(*) FROM requirement", Integer.class);

    // The whole point: not 2, and not "it threw". Zero.
    assertThat(visible).isZero();
  }

  @Test
  void tenantSeesOnlyItsOwnRows() {
    insertAs(TENANT_A, "VY-1001");
    insertAs(TENANT_B, "VY-2001");

    bind(TENANT_A);
    assertThat(jdbc.queryForList("SELECT key FROM requirement"))
        .extracting(m -> m.get("key")).containsExactly("VY-1001");
  }

  @Test
  void cannotInsertIntoAnotherTenant() {
    bind(TENANT_A);
    assertThatThrownBy(() -> insertRaw(TENANT_B, "VY-9999"))
        .hasMessageContaining("row-level security");   // WITH CHECK refuses it
  }
}
```

Run it with `cd backend && ./mvnw -B verify`. **Do not start Phase 1 until all three pass.**

---

## `application.yml` starting point

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/vyoog
    username: vyoog_app            # never the owner
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      # SET LOCAL is transaction-scoped, so a pooled connection cannot leak
      # tenant context. Never use SET (without LOCAL) anywhere.
  jpa:
    open-in-view: false            # non-negotiable
    hibernate.ddl-auto: validate   # Flyway owns the schema
    properties:
      hibernate.jdbc.batch_size: 50
  flyway:
    enabled: true
    user: vyoog_migrator           # owns the schema, bypasses RLS by design
    password: ${DB_MIGRATOR_PASSWORD}
    locations: classpath:db/migration
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_URL}/realms/vyoog
          audiences: vyoog-api

vyoog:
  tenancy:
    claim: vyoog_tenant            # §4.3
    allow-multi-tenant-users: false   # decide before Phase 0 — see §3.6 item 4
  detection:
    nightly-cron: "0 0 2 * * *"
    manual-rescan-cooldown: 5m
```

---

## Migrating an existing Gradle build

If `vyg-pms` is already Gradle and you want one build tool across both products, that is
a real decision rather than a formality. Converting a working Spring Boot Gradle build to
Maven costs roughly a day and gains nothing technically — the reason to do it is
consistency across the product family, which is a legitimate reason on its own. If
`vyg-pms` is already Maven, this setup matches it and there is nothing to decide.

I will confirm which it is when I can see the repository.
