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
  multi-agent/
    supervisor/
      withLangGraph4jSpringAi/  J8.7b: the supervisor pattern: LangGraph4j routes, Spring AI makes the model calls (port 18151)
    hierarchical/
      withLangGraph4jSpringAi/  J8.7c: the hierarchical pattern: a top supervisor over two teams, each a LangGraph4j child graph (port 18152)
    network/
      withLangGraph4jSpringAi/  J8.7d: the network pattern: fully connected peers, each names who works next (port 18153)
  agent-graph/
    handWritten/                J8.5a: a simple agent with an approval pause, no AI framework (port 18141)
    withLangGraph4j/            J8.5b: the same agent as a LangGraph4j graph with its checkpoint and interrupt (port 18142)
    withLangchain4jAgentic/     J8.5c: the same agent with LangChain4j's agentic module, a beta (port 18143)
  observability/
    handWritten/                J8.4a: the hand-written tool loop with an observer, OpenTelemetry spans and metrics (port 18131)
    withSpringAi/               J8.4b: Spring AI's own observations turned into the same signals (port 18132)
    withLangchain4j/            J8.4c: the LangChain4j tool chat observed through its own listeners and events (port 18133)
  rag-retrieval/
    handWritten/                J8.3a: no AI framework, REST calls to Gemini and Qdrant, the floor in our code (port 18121)
    withSpringAi/               J8.3b: Spring AI QdrantVectorStore search over the Gemini embedding model (port 18122)
    withLangchain4j/            J8.3c: LangChain4j embedding model and Qdrant store, the floor on the cosine in our code (port 18123)
  rag-ingestion/
    handWritten/                J8.2a: no AI framework, REST calls to Gemini and Qdrant (port 18111)
    withSpringAi/               J8.2b: Spring AI reader, splitter, embedding model and Qdrant store (port 18112)
    withLangchain4j/            J8.2c: LangChain4j loader, splitter, ingestor and Qdrant store (port 18113)
```

Every backend serves the same route as the application's tool-calling chat, `POST /api/v1/chat/tools`, and `GET /health`, so the web app can talk to any of them: pick one with the **Approach** buttons in the web app's tool-calling mode. Ports are listed in `apps/web/src/api/approaches.ts`.

| Command | What it does |
| --- | --- |
| `just comparisons-test` | Builds and tests every module; no keys, no network |
| `just comparisons-test-live` | The same, plus a test that reads the real CRM routes (needs `just db` and `just run`) |
| `just comparisons-hand-written` | Starts the handWritten backend on port 18101 (needs `OPENROUTER_API_KEY` to answer) |
| `just comparisons-with-spring-ai` | Starts the withSpringAi backend on port 18102 (same settings) |
| `just comparisons-with-langchain4j` | Starts the withLangchain4j backend on port 18103 (same settings) |
| `just comparisons-ingest-with-langchain4j` | Starts the withLangchain4j ingestion backend on port 18113 (needs `GOOGLE_API_KEY` and `just qdrant` to run; Qdrant's gRPC port 26334 by default) |
| `just comparisons-retrieve-with-spring-ai` | Starts the withSpringAi retrieval backend on port 18122 (needs `GOOGLE_API_KEY`, `just qdrant` and a prior withSpringAi ingestion) |
| `just comparisons-retrieve-with-langchain4j` | Starts the withLangchain4j retrieval backend on port 18123 (needs `GOOGLE_API_KEY`, `just qdrant` and a prior withLangchain4j ingestion; Qdrant gRPC port 26334 by default) |
| `just comparisons-observe-with-spring-ai` | Starts the withSpringAi tool-calling backend with observation on port 18132 (`TELEMETRY_ENABLED=true`; needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-observe-with-langchain4j` | Starts the withLangchain4j tool-calling backend with observation on port 18133 (`TELEMETRY_ENABLED=true`; needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-agent-with-langgraph4j` | Starts the withLangGraph4j agent backend on port 18142 (needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-agent-with-langchain4j-agentic` | Starts the withLangchain4jAgentic agent backend on port 18143 (needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-multi-agent-hierarchical` | Starts the hierarchical-pattern backend on port 18152 (`TELEMETRY_ENABLED=true` to record signals; needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-multi-agent-supervisor` | Starts the supervisor-pattern backend on port 18151 (`TELEMETRY_ENABLED=true` to record signals; needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-multi-agent-network` | Starts the network-pattern backend on port 18153 (`TELEMETRY_ENABLED=true` to record signals; needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-agent-hand-written` | Starts the handWritten agent backend on port 18141 (needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-observe-hand-written` | Starts the handWritten tool-calling backend with observation on port 18131 (`TELEMETRY_ENABLED=true`; needs `OPENROUTER_API_KEY` and the application up) |
| `just comparisons-retrieve-hand-written` | Starts the handWritten retrieval backend on port 18121 (needs `GOOGLE_API_KEY`, `just qdrant` and a prior handWritten ingestion) |
| `just comparisons-ingest-hand-written` | Starts the handWritten ingestion backend on port 18111 (needs `GOOGLE_API_KEY` and `just qdrant` to run) |
| `just comparisons-ingest-with-spring-ai` | Starts the withSpringAi ingestion backend on port 18112 (same needs; talks to Qdrant over gRPC on port 26334) |

Read [`docs/stages/J8-approaches-compared.md`](../docs/stages/J8-approaches-compared.md) for what is compared and how to try it.
