# ADR 0021: an answer team and supervisor-written search words (E3.2)

Status: accepted, 2026-11-13. A Java-edition extension; builds on ADR 0020.

## Context

E3.1's supervisor could only reorder two optional steps of one workflow (the account follow-up). The owner's concept is a supervisor with specialist agents, and the first slice left two things for later: a specialist that **answers a question** directly, and more genuine decisions for the supervisor. Parallel specialists were the third idea.

## Decision

- **Two kinds of request, two teams, one supervisor.** `POST /agent/supervise` no longer redirects a plain question to the chat modes. The same keyword rule that already separated an account follow-up from a simple question (`AgentIntent`) now chooses the team in code: a follow-up runs the E3.1 team (crm_facts, researcher, analyst, reviewer, drafter, with the approval gate); a plain question runs the **answer team** (crm_facts, researcher, answerer) and ends with an answer, no task and no gate. The mode is a code decision, recorded in the state; the supervisor's prompt tells it which kind of request it is handling.
- **The answerer** is a new specialist (a model call with a fixed prompt): it answers from the labelled evidence (the CRM facts when a customer was named, and the knowledge-base chunks the researcher found) and cites labels like `[E2]`. **A code rule fails closed**: an answer that cites no label that exists is replaced by the fixed text "I could not find this in the CRM facts or the knowledge bases." and the response says `"grounded": false`. The output guard scans what is shown (leaks, another customer's name when a customer was named) and detections are audited (endpoint `agent`).
- **No customer is fine for a question.** `crm_facts` still identifies a customer if one is named, but a missing one does not stop the run (it does for a follow-up, as in E3.1).
- **The supervisor writes its own search words.** When it chooses the researcher it may add a `query` to its JSON; the researcher searches with those words instead of the request. The query is model-written text used as a search string: trimmed, collapsed, capped at 200 characters, honoured only when the choice is the researcher, recorded on the turn, and the retrieved chunks pass through the same guard as always. This is the supervisor's first real **decision with content**, not just a pick from a list.
- **The supervisor still chooses only from an allowed list.** For a question: the researcher first (one allowed choice, so no model call), then the answerer, with the researcher also allowed once more (a real choice, so the supervisor is asked): "search again with different words, or answer now". An unreadable or disallowed answer falls back to the answerer.
- **Response shape for a question:** `route`, `status` (`answered`), `trace`, `message`, `grounded`, `evidence`, `citations` (the evidence items the answer cited), `guardrail`, `llmCalls`, `supervisor`. There is no `proposedTask`.
- **Not done: parallel specialists.** Running the CRM facts and the researcher at the same time needs a state merge (reducers) for the evidence list in LangGraph4j; it is left for a later slice, because the work each does is short (one SQL read and one retrieval) and the gain is latency only.

## Alternatives considered

- **Let the supervisor choose the team too** (a "task or question?" decision): more autonomy, but a wrong first choice sends a follow-up request down a path with no approval gate. The gate must not depend on a model's mood, so the team is chosen in code.
- **Reuse the multi-source RAG route (`/chat/multi`) as the answerer's tool:** the answer team would then be a thin wrapper. Keeping the researcher as a specialist shows the supervisor deciding about retrieval.
- **No code rule on citations:** simplest, and a small model then answers confidently from nothing. The fixed text is the safety net, the same idea as the reviewer's rule for recommendations.

## Consequences

- Plain questions to `/agent/supervise` now get an answer instead of the redirect message; the chat modes are still the better route for a quick question (one model call). The supervisor adds a decision and a research step.
- A question costs one supervisor call (the second-visit choice) and one answerer call, plus nothing for the other turns.
- `supervisor.turns` shows the model's search words on the turn where it chose them.
