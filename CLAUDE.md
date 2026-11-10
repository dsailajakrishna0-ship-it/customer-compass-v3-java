# Working rules for this repo (read first)

This is the Java edition of `customer-compass-v3` (an AI learning project, synthetic data only). The owner reviews and merges every PR; you push branches and open PRs.

## Identity and secrets (never break these)
- Git user for everything in this folder: **`dsailajakrishna0-ship-it`**, email `dsailajakrishna0@gmail.com` (set repo-locally). Never the owner's company account.
- The `gh` CLI may have another account active. Use `GH_TOKEN=$(gh auth token --user dsailajakrishna0-ship-it)` per command, and push with
  `git -c credential.helper= -c "http.extraheader=AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 -w0)" push`. Never run `gh auth switch`.
- Never print `.env` values. Never put real keys in `.env.example`; before every commit check `git diff -- .env.example`, stage with `git add -A -- . ':!.env.example'`, and grep the staged diff for `AIza|AQ\.A|sk-or-v1-`.
- Never `docker compose down -v` or `docker volume prune`. Test against a scratch Postgres on another port (for example `postgres:17.11-alpine` on 55432). Do not start the owner's compose stack without asking: their Docker is often stopped for other work.
- Never `pkill -f` a pattern that appears in your own command line.
- Creating repos, publishing, or anything outward-facing: ask first.

## How work is done
- One PR per sub-stage in `docs/roadmap.md`, branched from `main`, squash-merged by the owner. Every PR has a **"How to try this PR"** section (commands, what to type, what to notice; "without a key" and "with a key"), repeated in `docs/testing-guide.md`.
- Every stage ships a guide in `docs/stages/NN-*.md`; diagrams are Mermaid in `docs/diagrams/*.mmd` rendered to committed PNGs, and every image is viewed before commit.
- The API contract is shared with the Python edition (see `docs/port-status.md`). Do not change it here: change it first in `customer-compass-v3` in a PR.
- Before each stage, read the Python edition's `main` (`docs/port-status.md`, `docs/api-reference.md`, `contracts/`). Port only what is merged there.
- Decisions go in `docs/adr/`. Check tool versions and free-tier limits on the project's own page before adopting anything.
- `gh pr edit` can fail on a classic Projects error; update a PR body with `gh api -X PATCH repos/<owner>/<repo>/pulls/<n> -F body=@file`.
- Prefer small tools: no subagents or workflows for questions or exploration.

## Stack
Java 21 LTS, Spring Boot 4.1.x, Spring AI 2.0.x, Maven, Flyway, Postgres 17, Qdrant, React/Vite web app (copied from the Python edition), Google `gemini-embedding-001` (GOOGLE_API_KEY) and OpenRouter `:free` chat models (OPENROUTER_API_KEY).
