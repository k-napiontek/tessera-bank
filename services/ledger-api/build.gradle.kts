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
