plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "co.nine"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // 25, the current LTS, and the version audit-chain is compiled against:
        // its class files are major 69, so a 17 toolchain cannot consume it and
        // BI2 waits on this line. Gradle downloads the toolchain if the machine
        // does not have one, so no local install is required.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Testcontainers is no longer pinned here. Spring Boot 4.1.1 manages 2.0.5,
// which is the first line that carries the renamed testcontainers-* artifacts.
// If a pin is ever needed again, the dependency-management plugin's override
// channel is extra["testcontainers.version"].

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Flyway became a first-class starter in Boot 4.0. The starter brings
    // flyway-core; the Postgres dialect stays an explicit dependency because
    // the starter does not pull it.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Compile scope, not runtime. ConstraintRules reads the constraint name that
    // the server reports as its own field on PSQLException, which is the only way
    // to tell two 23505s apart without matching on message text. The dependency
    // was already total at runtime; declaring it is honest rather than new.
    implementation("org.postgresql:postgresql")

    // Boot 4.0 split test support per technology. This starter brings
    // spring-boot-starter-test and spring-boot-resttestclient with it, which
    // is where TestRestTemplate now lives.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // The webmvc test starter brings spring-boot-resttestclient but not
    // spring-boot-restclient, and RestTemplateBuilder lives in the latter.
    testImplementation("org.springframework.boot:spring-boot-restclient")
    // Testcontainers 2.0 renamed every module to a testcontainers- prefix and
    // did not republish the old coordinates, so these names are not cosmetic.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
