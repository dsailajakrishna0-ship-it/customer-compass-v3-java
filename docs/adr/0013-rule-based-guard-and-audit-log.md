# ADR 0013: a rule-based guard that neutralises and audits (J6.1)

Status: accepted, 2026-11-13.

## Context

Retrieved documents, tool results and the user's own message are untrusted text that can contain instructions ("ignore all previous instructions"). The Python edition guards them with simple rules and writes every detection to Postgres (its Stage 8). The API already carries a `guardrail` field in every chat answer; until J6 it was always unflagged.

## Decision

- **Port the Python rules word for word** (`PromptGuard`): six input rules (override instructions, reveal the system prompt, fake "new instructions:", role override, jailbreak keyword, cross-customer exfiltration) and two output rules (prompt leak, jailbreak compliance), plus the cross-customer name check when a question is scoped to one company. The rule names are the contract: they are in the audit log and in `guardrail.reasons`.
- **Neutralise, never block.** A matched phrase is replaced by a redaction tag; the rest of the text and the request go on. A false positive costs one phrase. A detection is never silent: it comes back in `guardrail` and is written to `security_audit_log`.
- **Scan what reaches the model.** The question, every retrieved chunk and every tool result are scanned and the cleaned text is what the model sees. Retrieval still uses the original question (redacting first would hurt the embedding), and citations show the original chunk text so the user can see what was retrieved.
- **Scan the answer last** (`ChatGuard.finalizeAnswer`), merge it into the same report, write the audit rows, and return the safe answer. Every route ends with that one call.
- **The audit log never breaks a request.** A write failure is logged and swallowed. Rows older than `SECURITY_AUDIT_RETENTION_DAYS` (default 90, the Python edition's setting) are deleted at startup.
- **`GET /api/v1/security-audit-log`** with `limit` (1 to 500), `event_type` and `pattern`, answering FastAPI's 422 bodies for bad values (checked against the Python API).

## Alternatives considered

- **A guardrail framework** (NeMo Guardrails, Llama Guard): more coverage, more moving parts; the Python edition's guide discusses them. The optional LLM classifier arrives in J6.2 as a second layer, as in Python.
- **Blocking a flagged request:** simpler to reason about, but a planted sentence in one document would then block every question that retrieves it.

## Consequences

- Regexes are a floor, not a wall: paraphrased attacks pass. That is stated in the guide, and J6.2 adds the classifier.
- The agent (request, stored text, output, the note on confirm) is guarded in J6.2.
