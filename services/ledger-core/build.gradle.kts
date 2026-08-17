// ledger-core - the double-entry ledger.
//
// This module is the domain only: pure Java, no framework on the compile classpath at all. That is
// not a preference, it is the constraint that makes the ledger testable in milliseconds and
// survivable across the framework rewrite that will eventually come. WP-07 adds the persistence
// adapters and an ArchUnit rule that enforces this permanently.
plugins {
    `java-library`
}

group = "bank.tessera"
version = "1.0.0-SNAPSHOT"

java {
    // Stratum 3 is pinned to Java 17. Not the newest release - even the modern tier of a real bank
    // runs a few years behind, and making it current would undo the point of the repository.
    // See CLAUDE.md and docs/plan/master-plan.md.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // No implementation dependencies, and that is deliberate. If this list ever grows a framework,
    // something has gone wrong with the design rather than with the build file.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("net.jqwik:jqwik:1.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
