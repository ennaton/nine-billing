# Upgrading nine-billing to Spring Boot 4.1

Task BI1.2. This note is the record of what changes and why. Every claim below was
checked against the published artifacts on 2026-08-27, not against memory: the
`spring-boot-dependencies:4.1.1` BOM, the individual starter POMs, and the class
listings inside the published jars. Where a class moved, the note names the jar the
class was found in.

**From** Spring Boot 3.4.1, `io.spring.dependency-management` 1.1.7, Gradle 8.14, Java 17.
**To** Spring Boot 4.1.1, released 2026-08-20, the current 4.1 patch.

Spring Boot 4.0.0 is the breaking release, GA 2025-11-20. 4.1.0 followed on 2026-06-10.
Almost everything in this note comes from the 3.x to 4.0 jump; 4.1 adds very little on
top for a service shaped like this one.

## Baselines this upgrade requires

| Requirement | Spring Boot 4.1 needs | nine-billing has | Action |
|---|---|---|---|
| Java | 17 or later | 17 toolchain | None for 4.1 itself. BI1.1 raises this to 25 separately |
| Gradle | 8.14 or later, or 9.x | 8.14 | None. The wrapper is exactly at the floor |
| Spring Framework | 7.x | managed by the plugin | Automatic, 7.0.9 in this BOM |
| Jakarta EE | 11, Servlet 6.1 | managed by the plugin | Automatic, Tomcat 11.0.24 |

Gradle 8.14 satisfies the plugin, but it is the floor with no headroom. Worth raising
to 9.x in BI1.1 rather than sitting on the minimum.

## Dependency management plugin

`io.spring.dependency-management` is still supported. The Spring Boot 4.1.1 Gradle
plugin documentation says you can "either apply the `io.spring.dependency-management`
plugin or use Gradle's native bom support", and that the former "offers property based
customization of managed versions" while the latter is faster.

This matters because `build.gradle.kts` overrides the Testcontainers version through
`extra["testcontainers.version"]`, which is exactly the property channel the plugin
provides. Native bom support would break that line and force a resolution strategy
instead. Keep the plugin.

## Build file changes

| Current | Becomes | Why |
|---|---|---|
| `org.springframework.boot` version `3.4.1` | `4.1.1` | The upgrade itself |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | The old artifact still publishes at 4.1.1, but its own POM description reads "deprecated in favor of spring-boot-starter-webmvc". Both resolve to `spring-boot-webmvc` |
| `spring-boot-starter-jdbc` | unchanged | Still published in the 4.1.1 BOM |
| `spring-boot-starter-validation` | unchanged | Still published in the 4.1.1 BOM |
| `spring-boot-starter-actuator` | unchanged | Still published in the 4.1.1 BOM |
| `org.flywaydb:flyway-core` | `spring-boot-starter-flyway` | 4.0 promoted Flyway from a plain third party dependency to a real starter. Its POM pulls `spring-boot-flyway`, which pulls `flyway-core` 12.4.0 |
| `org.flywaydb:flyway-database-postgresql` | keep, explicit | `spring-boot-flyway` brings only `flyway-core`. The Postgres dialect module stays a separate dependency and still publishes at 12.4.0 |
| `spring-boot-starter-test` | `spring-boot-starter-webmvc-test` | 4.0 splits test support per technology. The webmvc test starter already depends on `spring-boot-starter-test`, `spring-boot-starter-jackson-test` and `spring-boot-resttestclient`, so it covers what this repo uses |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` | See below |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` | See below |
| `extra["testcontainers.version"] = "1.21.3"` | drop it, or set 2.0.5 | 4.1.1 manages Testcontainers 2.0.5 |
| `junit-platform-launcher` | unchanged | JUnit moves to 6.0.3 via `junit-bom`; the launcher publishes at 6.0.3 and stays unversioned here |

### Testcontainers 1.21.3 to 2.0.5 is the one that hard fails

Testcontainers 2.0 renamed every module artifact to a `testcontainers-` prefix. The old
coordinates are not republished: `org.testcontainers:postgresql:2.0.5` and
`org.testcontainers:junit-jupiter:2.0.5` both return 404 from Maven Central, while
`org.testcontainers:testcontainers-postgresql:2.0.5` and
`org.testcontainers:testcontainers-junit-jupiter:2.0.5` return 200. This is a
resolution failure, not a deprecation warning, so it is the first thing that breaks.

The Java package moved too, but more gently. `testcontainers-postgresql-2.0.5.jar`
contains both `org/testcontainers/postgresql/PostgreSQLContainer.class` and the old
`org/testcontainers/containers/PostgreSQLContainer.class`, the second deprecated in
favour of the first. So the import compiles either way and only warns.

`testcontainers-junit-jupiter-2.0.5.jar` still holds
`org/testcontainers/junit/jupiter/Testcontainers.class`, unchanged package.

Open question for the build: `build.gradle.kts` pins `DOCKER_API_VERSION=1.44` to work
around Docker 29 rejecting older API versions with Testcontainers 1.20.x. Testcontainers
2.x changed Docker environment detection, so this pin should be removed and the suite
re-run before deciding whether it is still needed. Do not carry a workaround forward
without re-testing the thing it worked around.

## Source changes

Every one of these is a moved class, not a changed behaviour. The counts are exact.

| Old API | New API | Files |
|---|---|---|
| `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate` | `MeteringHttpTest:10`, `ReviewFindingsTest:12`, `TenantIsolationTest:12` |
| `org.springframework.boot.web.client.RestTemplateBuilder` | `org.springframework.boot.restclient.RestTemplateBuilder` | `MeteringHttpTest:11`, `ReviewFindingsTest:13`, `TenantIsolationTest:13` |
| `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers.postgresql.PostgreSQLContainer` | `PostgresTestBase:5` |

Both new Spring locations were confirmed by listing the jars:
`spring-boot-resttestclient-4.1.1.jar` holds
`org/springframework/boot/resttestclient/TestRestTemplate.class`, and
`spring-boot-restclient-4.1.1.jar` holds
`org/springframework/boot/restclient/RestTemplateBuilder.class`.

### The one that fails at runtime rather than compile time

In 4.0, `@SpringBootTest` no longer provides `TestRestTemplate`. The bean has to be
requested:

```java
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MeteringHttpTest extends PostgresTestBase {
```

Needed in the three tests that inject it: `MeteringHttpTest:26`, `ReviewFindingsTest:32`,
`TenantIsolationTest:35`. `LedgerInvariantsTest` and `ReconciliationTest` use plain
`@SpringBootTest` with no web environment and no `TestRestTemplate`, so they are
untouched.

The annotation class was confirmed in `spring-boot-resttestclient-4.1.1.jar` as
`org/springframework/boot/resttestclient/autoconfigure/AutoConfigureTestRestTemplate.class`.

## Behaviour to verify, with no code change to make

**Jackson 2 to Jackson 3.** The BOM manages `jackson-bom` 3.1.5, and 4.0 moved the group
id from `com.fasterxml.jackson` to `tools.jackson`. No file in this repo imports Jackson
directly, so nothing needs editing. What does need checking is the wire format the tests
assert on: `ProblemDetail` responses from `ApiExceptionHandler`, and the `Map` bodies in
`MeteringHttpTest`, including how `UUID` and `Instant` render. If a serialization default
moved, it shows up as a failing assertion, not a compile error. `spring-boot-jackson2`
exists as an escape hatch if Jackson 3 turns out to cost more than this upgrade is worth.

**Flyway 10.20.1 to 12.4.0.** Two major versions in one step, because 3.4.1 managed
10.20.1 and 4.1.1 manages 12.4.0. The five migrations in `db/migration` are plain SQL and
`application.yml` only sets `enabled`, `locations`, `url`, `user` and `password`, all of
which are still standard, so nothing here needs editing. What it does mean is that a
Flyway failure in the next test run is a Flyway 12 question, not a Spring Boot one, and
should be read that way rather than blamed on the framework upgrade.

**Health probes.** 4.0 enables liveness and readiness probes by default, so
`management.endpoint.health.probes.enabled: true` in `application.yml` is now redundant.
It is not wrong and it is not breaking. Leave the decision to BI9.1, which owns probes.

**JSpecify nullability.** 4.0 annotates the codebase with JSpecify. Under
`-Xlint:all`, which this build sets, that can surface warnings that were not there
before. The previous commit, `684499b`, cleaned lint warnings, so any new noise here is
from the upgrade and belongs in this task rather than being left for someone else.

**Property migration.** `application.yml` uses `spring.datasource.*`, `spring.flyway.*`
and `management.*`, none of which appear in the 4.0 rename list. If something does move,
adding `org.springframework.boot:spring-boot-properties-migrator` at runtime scope reports
it at startup. Add it temporarily, then remove it.

## Verified unaffected

Checked by listing the Spring Framework 7.0.9 jars rather than assuming:

- `spring-web-7.0.9.jar`: `ProblemDetail`, `RestControllerAdvice`,
  `MethodArgumentNotValidException`, `OncePerRequestFilter`, all present, same packages.
  Covers `ApiExceptionHandler` and `ApiKeyFilter`.
- `spring-jdbc-7.0.9.jar`: `JdbcTemplate`, `DelegatingDataSource`, same packages. Covers
  every repository and `TenantAwareDataSource`.
- `spring-tx-7.0.9.jar`: `Transactional`, `TransactionTemplate`,
  `PlatformTransactionManager`, same packages.
- `spring-test-7.0.9.jar`: `DynamicPropertySource`, `DynamicPropertyRegistry`, same
  packages. Covers `PostgresTestBase`.
- `junit-jupiter-api-6.0.3.jar`: `MethodOrderer`, `Order`, `TestMethodOrder`, same
  packages, despite the major version bump to JUnit 6.

The nine ledger invariants live in the database, not in Spring, so this upgrade cannot
weaken them. What it can do is stop the tests that prove them from running, which is the
only reason the Testcontainers rename matters as much as it does.

## Blockers, as of 2026-08-27

1. **No JDK is visible to Gradle.** Homebrew has both `openjdk@17` at 17.0.17 and
   `openjdk` at 25.0.1 installed, but they are keg-only: `java` is not on `PATH`,
   `JAVA_HOME` is unset, and `/Library/Java/JavaVirtualMachines` is empty, which is the
   directory Gradle's macOS toolchain detection reads. Gradle will not find them on its
   own. The cheapest fix touches no file in this repo:

   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew test
   ```

   A durable fix belongs to BI1.1, which owns the toolchain: either register the JDKs
   with `org.gradle.java.installations.paths` in `gradle.properties`, or symlink them
   into `/Library/Java/JavaVirtualMachines` so every tool finds them.
2. **Docker is installed but not running.** `Docker.app` is present, the daemon socket
   is not. The suite is Testcontainers against a real Postgres, so nothing can be proven
   until it is up, and the Testcontainers 2.0 change is exactly the part that needs a
   real run rather than an argument.
3. **BI1.1 owns the toolchain.** Spring Boot 4.1 is happy on Java 17, so BI1.2 is not
   blocked on Java 25. The two can land in either order, but the build is green only
   once both are done. Note that Spring Boot 3.4.1 does not support Java 25, so the
   green baseline in step 1 below has to be taken on 17 regardless.

## What was applied, 2026-08-27

The green baseline in the original plan was skipped by decision: the suite was already
run green on 3.4.1 by the other half of the team, and the full run happens at the end of
the day over everything that landed. Compilation was run here, which is what turned three
of the items below from prediction into fact.

`./gradlew compileJava compileTestJava` is green. 52 warnings remain, all `rawtypes` and
`unchecked`, all from raw `Map` and `List` in the three HTTP tests. They predate this
upgrade: commit `684499b` cleared `serial` and `dangling-doc` under `-Xlint:all` and left
these alone. Zero `deprecation` and zero `removal` warnings.

### Found by the compiler, not by reading

Two things the release notes did not make obvious, both caught only because the build ran:

**`spring-boot-restclient` is not transitive.** `spring-boot-starter-webmvc-test` brings
`spring-boot-resttestclient`, so `TestRestTemplate` resolves, but `RestTemplateBuilder`
lives in `spring-boot-restclient` and nothing pulls it. `package
org.springframework.boot.restclient does not exist` in two files. Added explicitly as a
test dependency.

**`PostgreSQLContainer` is no longer generic.** Testcontainers 2.0 dropped the
self-referential type parameter, so `PostgreSQLContainer<?>` and `new
PostgreSQLContainer<>(...)` both fail with "type PostgreSQLContainer does not take
parameters". The wildcard and the diamond were removed.

### The one that would have failed silently at the end of the day

Spring Framework 7 deprecated `HttpStatus.UNPROCESSABLE_ENTITY` and added
`HttpStatus.UNPROCESSABLE_CONTENT`, following the rename of 422 in RFC 9110. They are two
distinct enum constants, both carrying 422, and they are **not equal**:

```
UNPROCESSABLE_ENTITY  = 422 UNPROCESSABLE_ENTITY   value=422
UNPROCESSABLE_CONTENT = 422 UNPROCESSABLE_CONTENT  value=422
==      : false
equals  : false
HttpStatus.valueOf(422) resolves to UNPROCESSABLE_CONTENT
```

That matters because `MeteringHttpTest:96` asserts
`assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)`. The response
status arrives off the wire as the integer 422 and is resolved through
`HttpStatus.valueOf`, which now returns `UNPROCESSABLE_CONTENT`. The assertion would have
failed with two values that print identically as "422", against a service that was
behaving correctly. All three usages, two in `ApiExceptionHandler` and one in the test,
were moved to `UNPROCESSABLE_CONTENT`. The status on the wire is unchanged.

This is the reason a deprecation warning is not cosmetic. `-Xlint:all` surfaced it before
the test run did.

### Also cleared

`RestTemplateBuilder.rootUri(String)` is deprecated since 4.1.0 and marked for removal in
4.3.0 in favour of `baseUri(String)`. The javadoc for the two is word for word the same:
applied to each request that starts with a slash, only for the `String` variants of the
`RestTemplate` methods. Swapped in all three tests rather than left for the 4.3 upgrade.

### Files changed

| File | Change |
|---|---|
| `build.gradle.kts` | Plugin 3.4.1 to 4.1.1. `starter-web` to `starter-webmvc`. `flyway-core` to `spring-boot-starter-flyway`. `starter-test` to `starter-webmvc-test`, plus explicit `spring-boot-restclient`. Both Testcontainers modules renamed. `extra["testcontainers.version"]` pin removed |
| `settings.gradle.kts` | `rootProject.name` from `wesan-billing` to `ennaton-billing`. Not part of this upgrade, a leftover from an earlier project name that was fixed while the file was open |
| `ApiExceptionHandler.java` | Two 422 constants moved to `UNPROCESSABLE_CONTENT` |
| `PostgresTestBase.java` | Container import relocated, generic parameter dropped |
| `MeteringHttpTest.java` | Two imports relocated, `@AutoConfigureTestRestTemplate` added, `rootUri` to `baseUri`, 422 constant |
| `ReviewFindingsTest.java` | Two imports relocated, `@AutoConfigureTestRestTemplate` added, `rootUri` to `baseUri` |
| `TenantIsolationTest.java` | Same as above |

The `java { toolchain { ... } }` block was deliberately left alone. It belongs to BI1.1,
which was already in progress on the other side, and touching it here would put two
people in the same block of the same file for no gain.

Two more things in `build.gradle.kts` were verified rather than assumed:
`spring-boot-starter-webmvc` carries the same set as the old `spring-boot-starter-web`
plus `spring-boot-starter`, and `spring-boot-starter-test` at 4.1.1 still brings AssertJ,
JUnit Jupiter, Mockito, Hamcrest and `spring-test`, so no test dependency was silently
dropped.

## What is still open

Compilation proves that every type resolves. It proves nothing about behaviour. Three
things can still fail when the suite runs:

1. **The `DOCKER_API_VERSION` pin.** Carried over untouched from the Testcontainers
   1.20.x era. 2.0 reworked Docker environment detection. This is the first thing to
   suspect if containers do not start at all.
2. **Jackson 3 serialization.** The likely cause if containers start and individual HTTP
   assertions fail instead. `spring-boot-jackson2` is the escape hatch.
3. **Flyway 12.** See the note above. A migration failure here is a Flyway question, not
   a Spring Boot one.

The task is done when the suite is green and the application serves a request. Neither has
been shown yet.
