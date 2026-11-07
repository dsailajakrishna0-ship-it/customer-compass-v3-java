# ADR 0002: How the Java CRM API reads data (Flyway, JdbcClient, Testcontainers)

Status: Accepted (J1, 2026-11-07)

## Context

J1 gives the Java edition its database and the CRM read routes. The Python edition uses Alembic migrations written as plain SQL and the `asyncpg` driver with hand-written queries (no ORM). The contract is its behaviour, byte for byte.

## Decision

- **Flyway** with two SQL files (`V1__baseline_schema.sql`, `V2__demo_seed_data.sql`) copied from Alembic revisions 0001 and 0002. Revision 0003 (drop an unused pgvector column) is left out: a Java database never had that column. Flyway runs on application start, as the Compose `migrate` job does in the Python edition. Alternatives: Liquibase (XML or YAML changelogs, more to learn, no gain here).
- **Spring `JdbcClient` with plain SQL**, the same statements as the Python routes. Alternatives: Spring Data JPA (an ORM would hide the SQL the two editions are meant to share, and entities add mapping work for read-only routes); jOOQ (code generation for little gain at this size).
- **Records for the JSON**, with `@JsonProperty` for the snake_case names the web app already uses. Money stays a string (`"48000.00"`), dates stay text, timestamps are written the way Python's `isoformat()` writes them (`+00:00`, not `Z`).
- **The contract is what the Python edition does, including its gaps.** The Java API copies two behaviours the Python reference does not document: an unknown route is `{"detail": "Not Found"}` (no `error` key) and a non-numeric id is FastAPI's validation list. Both are listed in `port-status.md` ("Contract gaps") to be fixed in the Python edition first, after which the Java side follows.
- **Tests:** JUnit over real HTTP and a real Postgres started by **Testcontainers** (Docker needed), plus `contracts/http/crm_contract.py`, a standard-library script that runs against any base URL. The same script passed against both editions, and the raw JSON of every route was byte-identical (a manual diff recorded in the J1 PR).
- **Port 8001**, the same as the Python edition, because the web app has the address fixed. Do not run both together.

## Consequences

The schema has two sources (Alembic and Flyway) that must be kept in step by hand; a table change is made in the Python edition first and ported. Testcontainers makes `just test` need Docker.
