plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "co.nine"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // 17 is what is installed locally. 21 is the newer LTS and the build
        // moves there as soon as a 21 toolchain is available; nothing in the
        // code depends on 17-only behaviour.
        languageVersion = JavaLanguageVersion.of(17)
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

    runtimeOnly("org.postgresql:postgresql")

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
    // Docker 29 refuses API versions older than it advertises. Tell docker-java
    // which one to use instead of letting it guess an old default. This pin was
    // added against Testcontainers 1.20.x. 2.0 reworked Docker environment
    // detection, so it is carried over unchanged on purpose and has to be
    // re-checked against a real run before it is trusted or dropped.
    environment("DOCKER_API_VERSION", "1.44")
    testLogging {
        events("passed", "skipped", "failed")
    }
}
