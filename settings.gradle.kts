plugins {
    // Gradle finds no local JDK 25 on a fresh machine and cannot build without one.
    // This resolver lets it download the toolchain itself, so the build states its
    // Java version rather than the machine dictating it. CI pins the same version
    // through setup-java, which is faster there because it caches.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ennaton-billing"
