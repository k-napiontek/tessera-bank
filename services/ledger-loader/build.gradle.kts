// ledger-loader - the bulk loader that stands a production-shaped ledger up from a workload model.
//
// It is a test fixture, not a component of the bank: it sits in the category workload/ and
// walkthrough.sh already occupy, and nothing in the estate depends on it. What makes it belong in
// services/ rather than in workload/ is what it must not restate. The audit chain's canonical form
// is AuditEntry's, the sign convention is AccountType.signedEffect's, and the rows are the shapes
// WP-07's migrations declare - a second copy of any of them, in another language, would drift the
// day either side was edited and nothing would notice until a report came up short.
//
// The application plugin rather than a library: installDist writes a launcher with the whole
// classpath on it, which is what lets a stream be piped into this from a shell script.
plugins {
    application
}

group = "bank.tessera"
version = "1.0.0-SNAPSHOT"

java {
    // Stratum 3 is pinned to Java 17, the same as the three modules beside it. See CLAUDE.md.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("bank.tessera.ledger.loader.Main")
}

dependencies {
    implementation(project(":services:ledger-core"))
    implementation(project(":services:ledger-persistence"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.12"))
    // The stream is NDJSON and the audit state maps are jsonb. Both are adapter concerns, which is
    // the whole of what this module is.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // BalanceReconciliation and AuditChain are given a NamedParameterJdbcTemplate, so verification
    // runs the ledger's own controls rather than a second implementation of them.
    implementation("org.springframework:spring-jdbc")
    // A compile dependency rather than a runtime one, deliberately: CopyManager is the API this
    // module is built around, and loading through anything else would take days and measure itself.
    implementation("org.postgresql:postgresql")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.12"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
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
