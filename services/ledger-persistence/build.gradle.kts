// ledger-persistence - the PostgreSQL adapters behind the WP-06 ports.
//
// This module exists so that ledger-core does not. The domain module has no framework on its compile
// classpath at all, which means a Spring import there fails to compile rather than merely failing a
// rule - and that guarantee is worth more than the convenience of one module. Infrastructure depends
// on the domain; the dependency below points that way and never the other.
//
// Spring's versions come from the Boot 3.2 BOM as a platform, without the Boot plugin. There is no
// application to package until WP-08, and bootJar on a library module is noise.
plugins {
    `java-library`
}

group = "bank.tessera"
version = "1.0.0-SNAPSHOT"

java {
    // Stratum 3 is pinned to Java 17, matching ledger-core. See CLAUDE.md.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":services:ledger-core"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.12"))
    implementation("org.springframework.data:spring-data-jdbc")
    // The audit trail's before and after state, and the outbox payload, are jsonb columns. Jackson is
    // an adapter concern only: HexagonalBoundariesTest forbids it in ..ledger.domain.. and
    // ..ledger.port.., which is where it would actually do damage.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // Instants and dates in the outbox payload are ISO-8601 strings, which the contract declares and
    // Jackson does not do without this module.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework:spring-jdbc")
    // The relay logs a refused publish. slf4j only - the binding is the application's choice.
    implementation("org.slf4j:slf4j-api")
    // Flyway 9, which Boot 3.2 manages. PostgreSQL support lives in flyway-core there; the separate
    // flyway-database-postgresql artefact only exists from Flyway 10 and is not in this BOM.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.12"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.postgresql:postgresql")
    testImplementation("com.zaxxer:HikariCP")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
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
