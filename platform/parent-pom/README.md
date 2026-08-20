# Corporate parent POM

**Cross-cutting** | **Built by WP-10**

The parent POM that Maven-built modules inherit: pinned plugin versions, dependency management, the
compiler configuration and the toolchain gate.

Authentic detail - every bank has one, and it is how a platform team enforces standards across dozens
of application teams without touching their code. It is also why a 2011 module's `pom.xml` is twenty
lines rather than two hundred.

## What it pins

| | |
|---|---|
| Coordinates | `bank.tessera:tessera-parent:1.0.0-SNAPSHOT`, packaging `pom` |
| Compiler | `-Xlint:all -Werror`, source and target from `tessera.java.level` |
| Toolchain gate | `maven-enforcer-plugin` refuses any JDK outside `tessera.jdk.range` |
| Test tooling | JUnit 4.13.2, Testcontainers 1.19.8, `ojdbc8` 21.11.0.0, SLF4J 1.7.36, Cargo 1.10.15 |
| Runtime | JAX-WS RI 2.2.10, Servlet API 3.0.1 |
| Plugins | compiler, surefire, failsafe, war, jar, install, resources, clean, `jaxws`, `cargo-maven3`, `build-helper` |

## Two pins worth explaining

**`cargo.version` is one property, used twice.** The `cargo-maven3-plugin` and the
`cargo-core-uberjar` dependency that a deployment test drives it through are the same release of the
same project. Two properties would let a container that boots Tomcat and the library that configures
it drift a release apart, and that fails as a `NoSuchMethodError` a long way from its cause.

**`jaxws-rt` is pinned to 2.2.10 and the minor version is load-bearing.** JDK 8 carries the JAX-WS
2.2 API in `rt.jar`, on the bootclasspath, where no webapp classloader can override it. A 2.3
runtime in `WEB-INF/lib` therefore runs its implementation against 2.2's interfaces and fails at
deployment with a `LinkageError` naming neither version. 2.2.10 is also what a 2011 estate shipped,
so the correct answer and the period-correct one agree here - they do not always.

## The level is per module, not per estate

Strata 1 and 2 pin Java 8 while stratum 3 pins Java 17, so the parent expresses the level as two
properties a child may override together:

```xml
<tessera.java.level>1.8</tessera.java.level>
<tessera.jdk.range>[1.8,1.9)</tessera.jdk.range>
```

Both, never one. `javac -source 1.8` running on a JDK 17 compiles against JDK 17's class library and
accepts APIs that did not exist in 2011 - the bytecode version says 8 and the code is not. Enforcing
which JDK the build *runs on* is what makes the target level an honest statement, which is why the
enforcer rule is bound in `<plugins>` rather than offered in `<pluginManagement>`: a child cannot
inherit the parent and quietly skip it.

Demonstrated rather than asserted: `mvn validate` here exits 0 under JDK 8 and exits 1 under JDK 17,
naming the legacy-strata rule in the failure.

Stratum 3 builds with Gradle and does not consume this file. It is written to accept a Maven-built
Java 17 module because the estate may grow one, not because one exists.

## Consumers

- [`legacy/customer-master`](../../legacy/customer-master/) - Java 8, WAR (WP-10)
- `integration/esb-adapter` - Java 8, Spring Boot 2.7.18 (WP-11)
