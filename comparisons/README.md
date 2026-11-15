# Comparisons (J8)

The same module of an AI application built several ways, one folder per approach, so a reader opens one folder and reads exactly one approach. The plan and the folder names are in [`docs/roadmap.md`](../docs/roadmap.md) ("Stage J8"). These modules are **separate from the application** (`apps/api-java`): they have their own build, their own dependencies, and talk to the application only over HTTP.

```
comparisons/
  pom.xml                       the build of everything below (`just comparisons-test`)
  common/                       shared by every approach: the HTTP face, the CRM tools, the CRM client
  tool-calling/
    handWritten/                J8.1a: no AI framework, our own loop (port 18101)
    withSpringAi/               J8.1b: Spring AI @Tool and ChatClient (port 18102)
    withLangchain4j/            J8.1c: LangChain4j @Tool and AiServices (port 18103)
  rag-ingestion/
    handWritten/                J8.2a: no AI framework, REST calls to Gemini and Qdrant (port 18111)
```

Every backend serves the same route as the application's tool-calling chat, `POST /api/v1/chat/tools`, and `GET /health`, so the web app can talk to any of them: pick one with the **Approach** buttons in the web app's tool-calling mode. Ports are listed in `apps/web/src/api/approaches.ts`.

| Command | What it does |
| --- | --- |
| `just comparisons-test` | Builds and tests every module; no keys, no network |
| `just comparisons-test-live` | The same, plus a test that reads the real CRM routes (needs `just db` and `just run`) |
| `just comparisons-hand-written` | Starts the handWritten backend on port 18101 (needs `OPENROUTER_API_KEY` to answer) |
| `just comparisons-with-spring-ai` | Starts the withSpringAi backend on port 18102 (same settings) |
| `just comparisons-with-langchain4j` | Starts the withLangchain4j backend on port 18103 (same settings) |
| `just comparisons-ingest-hand-written` | Starts the handWritten ingestion backend on port 18111 (needs `GOOGLE_API_KEY` and `just qdrant` to run) |

Read [`docs/stages/J8-approaches-compared.md`](../docs/stages/J8-approaches-compared.md) for what is compared and how to try it.
