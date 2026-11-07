# Developer commands. Install just once: `uv tool install rust-just` (or your package manager).
# Run `just` to list them.

set shell := ["bash", "-euo", "pipefail", "-c"]

api := "apps/api-java"

# list the recipes
default:
    @just --list

# format the Java code (Spotless, google-java-format)
fmt:
    cd {{api}} && ./mvnw -B -ntp -q spotless:apply

# build, run the tests, then check formatting and style (what CI runs)
test:
    cd {{api}} && ./mvnw -B -ntp verify

# web app: lint, types, tests, production build (copied from the Python edition)
test-web:
    npm run lint
    npm run typecheck
    npm test
    npm run build

# everything CI checks
check: test test-web
