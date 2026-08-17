# Corporate parent POM

**Cross-cutting** | **Built by WP-10**

The parent POM that Java modules inherit: pinned dependency versions, plugin configuration, compiler source and target levels, and the quality-gate plugin wiring.

Authentic detail - every bank has one, and it is how a platform team enforces standards across dozens of application teams without touching their code.

**Note:** strata 1 and 2 pin Java 8 while stratum 3 pins Java 17. The parent must express that per-module rather than forcing one level across the estate.

