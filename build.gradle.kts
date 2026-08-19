plugins {
    java
    id("org.springframework.boot") version "3.4.1"
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

// Spring's dependency-management plugin decides Testcontainers' version and
// wins over Gradle platforms. Its own override channel is this property.
extra["testcontainers.version"] = "1.21.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker 29 refuses API versions older than it advertises. Tell docker-java
    // which one to use instead of letting it guess an old default.
    environment("DOCKER_API_VERSION", "1.44")
    testLogging {
        events("passed", "skipped", "failed")
    }
}
