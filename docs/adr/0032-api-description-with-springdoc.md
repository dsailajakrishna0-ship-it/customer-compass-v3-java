# ADR 0032: the API description, generated with springdoc

Status: accepted, 2026-11-15. The Java edition had no API description; the Python edition gets one for free from FastAPI (`/docs`).

## Decision

- **springdoc-openapi 3.2.0** (`springdoc-openapi-starter-webmvc-ui`), the 3.x line that is documented for Spring Boot 4. It is the newest release on Maven Central at the time; the build and all tests pass with it on Spring Boot 4.1.2.
- **Served at** `/v3/api-docs` (JSON) and `/swagger-ui.html` (Swagger UI). `OPENAPI_ENABLED=false` switches both off.
- **Generated from the controllers**, so it cannot drift from the routes: one `@Tag` per controller (Chat, CRM, Knowledge bases, Documents, Agent, Guards, Models, Tools (MCP), Health), one `@Operation` with a summary and a sentence for each of the 23 routes.
- **Request examples are written by hand.** The chat, agent, guard and search routes read the raw body as text (so every bad input gets the same FastAPI-style 422), so springdoc cannot see the shape. Each such route carries an `@RequestBody` example in its `@Operation`. The `Content-Type` header those methods read is removed from the description by a customizer.

## What it does not do

- **Responses are not described.** Most routes return `Map<String, Object>`, so the schema is a generic object; the fields are in the stage guides. Typed response records would be a separate change.
- **The examples are not checked against the validators.** A test checks that every route has a summary and a tag, that the body routes show an example and that Swagger UI is served, not that the example is accepted.
- **No authentication is described**, because the application has none.

## Consequences

- A new route without `@Operation` still appears (springdoc lists every mapping), but `OpenApiTests` fails because it counts 23 routes and requires a summary and a tag on each.
- Swagger UI's "Try it out" calls the running application, including routes that spend model or embedding quota.
