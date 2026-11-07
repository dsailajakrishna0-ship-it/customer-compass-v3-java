# ADR 0001: Build tooling (Maven wrapper, Spotless, Checkstyle)

Status: Accepted (J0.3, 2026-11-07)

## Context

The Java edition needs a build that a newcomer can run with only a JDK, a formatter nobody argues about, and a CI that fails on the same things a developer can run locally.

## Decision

- **Maven with the wrapper** (`apps/api-java/mvnw`, Maven 3.9.11 from Maven Central). No Maven install is needed. Gradle was not chosen: the Python edition's Java tool server already uses Maven, so both Java projects read the same way.
- **Spotless with google-java-format 1.28.0** formats the code; `mvn verify` fails on a difference and `just fmt` fixes it. Version 1.37.0 and later failed on JDK 21 inside Spotless (an `InvocationTargetException` from the formatter), so the version is pinned to one that works. Revisit when Spotless documents the newer ones.
- **Checkstyle with a small rule set** (`apps/api-java/config/checkstyle.xml`): unused and star imports, empty catch blocks, `equals` without `hashCode`, string comparison with `==`, a missing `default` in a switch. The formatter owns layout; Checkstyle only catches real mistakes. Error Prone was left out: it needs extra JVM flags on JDK 21 and would add setup cost for a learning project.
- **CI** (`.github/workflows/ci.yml`) runs the same `./mvnw verify`, the web checks, a docs link check, a secret scan (gitleaks), an npm audit and Trivy on the repository files. Actions are pinned by commit, as in the Python edition.

## One thing to know

`./mvnw` writes its download address into `.mvn/wrapper/maven-wrapper.properties` from the local Maven settings. On a machine whose settings point at a company mirror, that address would be committed. This repo's file points at Maven Central; check it after regenerating the wrapper.

## Consequences

A formatting or style difference fails the build, so run `just fmt` before pushing. Dependency update bots are not set up yet; add them when the first real dependencies arrive in J1.
