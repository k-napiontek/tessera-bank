// ledger-api - the REST adapter of the ledger, and the first Spring Boot application here.
//
// The Boot plugin appears in this repository for the first time, and only here. ledger-core and
// ledger-persistence are libraries: the persistence module takes Spring's versions from the Boot BOM
// as a platform and deliberately does not apply the plugin, because bootJar on a library is noise.
// This module is an application, so it gets the plugin and the executable jar that comes with it.
//
// The dependency direction is the architecture: this module knows ledger-core and ledger-persistence,
// and neither of them knows this one. ledger-core has no framework on its compile classpath at all,
// which is what makes DomainPurityTest a compiler-enforced rule rather than a convention - so every
// annotated request or response type belongs here and nowhere else.
plugins {
    java
    id("org.springframework.boot") version "3.2.12"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "bank.tessera"
version = "1.0.0-SNAPSHOT"

java {
    // Stratum 3 is pinned to Java 17, the same as the two modules below it. See CLAUDE.md.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":services:ledger-core"))
    implementation(project(":services:ledger-persistence"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    // The contract test validates real exchanges against contracts/openapi/ledger-core.yaml.
    //
    // OpenAPI 3.1 schemas ARE JSON Schema 2020-12 - that is the headline change of 3.1 - and the
    // document uses it: `type: [string, 'null']` is 2020-12 and is not expressible in 3.0. So the
    // validator is a JSON Schema 2020-12 implementation rather than an OpenAPI-specific one, which
    // would have to downgrade the document to 3.0 before it could read it. A contract test that
    // silently validates against a downgraded copy of the contract is not a contract test.
    testImplementation("com.networknt:json-schema-validator:1.5.1")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // The contract lives at the repository root, and a test's working directory is its own module.
    systemProperty("tessera.contracts.dir", rootProject.projectDir.resolve("contracts").absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
