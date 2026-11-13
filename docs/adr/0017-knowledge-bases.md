# ADR 0017: knowledge bases, each its own collection (E1.1)

Status: accepted, 2026-11-13. A Java-edition extension: not part of the contract shared with the Python edition.

## Context

The owner wants the application to search more than one corpus, for example the CRM documents and another business's handbook, with the model deciding where to look (multi-source, or federated, RAG; E1.2 does the routing). First the application needs to hold more than one corpus without them disturbing each other.

## Decision

- **A knowledge base is a name, a description and its own Qdrant collection.** The built-in `crm` knowledge base is the existing collection, unchanged. Each extra one (configured under `knowledge-bases.extra`) gets the collection `<rag.collection>_<id>` and, beside it, the keyword collection `<that>_bm25` (J3.6). The same `Retriever` class serves all of them, so hybrid search, the similarity floor and the optional reranker work in every knowledge base with no new code.
- **One collection per knowledge base, not one collection with a `knowledge_base` field.** The lifecycle decides it: CRM ingestion drops and recreates its collection, which would wipe a second corpus living inside it, and the extra corpus's documents must not appear in the CRM `documents` table, the counts the contract checks (19 documents, 33 chunks) or the document routes. Separate collections also let a corpus use a different embedding model later, and make corpus-level isolation structural: a search of one knowledge base cannot return another's chunks because it never looks there.
- **Ingestion of an extra knowledge base is its own service** (`KnowledgeBaseIngestion`): the same readers and chunker as the CRM documents, chunks stored with a keyword vector, nothing written to Postgres. It rebuilds only that knowledge base, so running it twice never duplicates. The CRM documents keep their route (`POST /documents/ingest`).
- **Extra documents are about no one customer:** `company_id` 0, so a company-scoped search would still see them (a knowledge base is chosen first; scoping applies inside it).
- **The description is the router's input.** E1.2's router, or the model, reads each knowledge base's description to decide whether to search it, so it is configuration, written for that purpose.
- **Sample corpus:** eight short synthetic documents for a fictional farm-supply business (re-entry intervals, cold chain, seed lots, sprayer calibration, delivery windows, organic certification, a supply-agreement renewal, a recall). Some words overlap the CRM corpus on purpose (renewal, pricing, support, delivery), so routing is not trivial.
- **Routes are Java-only** (`/api/v1/knowledge-bases...`), documented in `docs/java-extensions.md`; no shared route or tool changes.

## Alternatives considered

- **A `knowledge_base` payload field in the one collection, with a filter.** Fewer collections and one embedding model for all, but the wipe problem above, and Spring AI's Qdrant filter cannot test a missing value, so existing chunks would need re-ingesting to carry the field.
- **A tenant concept in Postgres** (a knowledge base table). More machinery than a learning extension needs; the configuration list is enough until something needs editing at run time.

## Consequences

- Every extra knowledge base is one more pair of Qdrant collections and, until its first ingestion, an empty search result (not an error).
- All knowledge bases currently share the configured embedding model; the index-model check reads the CRM ingestion runs.
- Scores from different knowledge bases are not comparable in general (E1.2's merge uses ranks, and the reranker, for exactly that reason).
