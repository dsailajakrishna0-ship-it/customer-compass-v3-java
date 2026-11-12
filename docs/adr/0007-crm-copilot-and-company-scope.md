# ADR 0007: the CRM copilot and the company scope (J3.4)

Status: accepted, 2026-11-12.

## Context

`POST /chat/crm` (Python mode 3) answers a question from CRM facts (SQL), document sources (retrieval) or both. The choice is made by plain keywords, not a model, so it is fast, free and easy to follow. A question that names a company must never retrieve another customer's documents: only that company's documents and the company-agnostic ones (product overviews) are eligible.

## Decision

- **Port the router as it is.** The keyword lists, the "significant word" company match (a word of a company name longer than three letters, found anywhere in the question, case-insensitive) and the fallback (a question that matches neither list goes to documents) are the Python edition's. The match is a plain substring test, so "pineapple" names Harbor & Pine; that quirk is kept and has a test, because changing it belongs in the Python edition first.
- **CRM facts are plain SQL** (`CrmFacts`): the company row, its open deals ordered by close date, and won and lost counts. Facts are shown to the model as "already correct, do not cite"; documents are numbered sources that must be cited.
- **The scope filter uses `company_id = X OR company_id = 0`.** Python filters on `company_id` equal to X or *null*. Spring AI's Qdrant filter converter supports equality, ranges and `IN`, but not a null or missing-value test, so company-agnostic documents are stored with `company_id` 0 (real ids start at 1) and the filter asks for "this company or 0". This is a convention of the Java edition's own index, not something the API shows.
- **The prompts are the Python edition's, word for word:** the CRM system prompt and the facts block (`Verified CRM facts (already correct, do not cite):` with company, industry, status, open deals and the won and lost counts), then `Source documents:` and the wrapped, numbered context, then `Question:`.
- **A question with nothing to show does not call the model:** "I could not find a matching company or deal data for that question." when the router wanted facts, otherwise "I could not find any ingested documents relevant to that question.", with `facts: null`, no citations and the scope kept.
- **Not yet:** the scan of the question and of the answer for prompt injection and for another customer's name (J6). The scope filter on retrieval is the part of that protection that exists now, and it is tested.

## Consequences

- **Documents ingested before J3.4 must be ingested again.** Their company-agnostic chunks have no `company_id`, so a scoped search would not find them. The collection is rebuilt on every ingestion, so one click on Ingest documents fixes it.
- The safety property has its own test: for three different companies, every citation of a scoped question belongs to that company or to no company.
- The router's weaknesses are Python's too: a company is found by any long word of its name, and the keyword lists do not understand meaning. Whether to improve them is a decision for both editions.
