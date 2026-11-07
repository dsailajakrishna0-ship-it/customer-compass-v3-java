# Testing guide

One section per PR, newest first. Each says how to start, what to run, what you should see, and how to stop.

## J0.3: build tooling and CI

Needs: Java 21 and Node 22. No keys, no Docker.

```bash
git pull
just test            # Maven: compile, run the tests, check formatting and style (about a minute the first time)
just test-web        # web app: lint, types, 16 tests, build
```

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `just test` | `Tests run: 1, Failures: 0` and `BUILD SUCCESS` | The empty app starts and the checks pass |
| 2 | Break the format on purpose: add three blank lines and odd spacing to `apps/api-java/src/main/java/com/customercompass/api/Application.java`, then `just test` | `BUILD FAILURE` from `spotless:check`. Then `just fmt` and `just test` pass again | The formatter is enforced |
| 3 | Break the style on purpose: add `import java.util.*;` to the same file, run `just fmt` then `just test` | `BUILD FAILURE` from Checkstyle (star import) | The style rules are enforced. Undo with `git checkout apps/api-java` |
| 4 | `cat apps/api-java/.mvn/wrapper/maven-wrapper.properties` | The address starts with `https://repo.maven.apache.org/` | The wrapper does not point at a private mirror |
| 5 | `just test-web` | 16 passed | The shared web app still builds here |
| 6 | Open the pull request's Checks tab | api, web, docs and security all green | CI runs the same commands |

**Stop:** nothing is left running.
