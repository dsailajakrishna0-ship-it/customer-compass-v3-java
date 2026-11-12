# ADR 0005: reading PDF, Word and Excel files (J3.2)

Status: accepted, 2026-11-12.

## Context

The Python edition reads PDF with `pypdfium2` and Word and Excel with Docling (the light `docling-slim` install). A good citation says where in a file the answer is, so a file is read into **segments**, each with its page (PDF) or sheet name (spreadsheet), and every chunk inherits that location. The Java edition must produce the same segments from the same files, so the two editions embed the same text and cite the same places.

## Decision

- **PDF: Apache PDFBox 3.0.8.** One segment per page that has text, with the page number counted from 1. Text PDFs only; a scanned PDF needs OCR, which neither edition does.
- **Word and Excel: Apache POI 6.0.0** (`poi-ooxml`), not a port of Docling. Docling is a Python library with no Java equivalent, so the Java readers write the *same Markdown shape* instead: a Word Heading 1 becomes `##` and a Heading 2 `###` (Docling's habit, found by reading its output), list items become `- item`, blocks are separated by a blank line; an Excel sheet becomes `Sheet: <name>`, a blank line, and a table.
- **The table layout is copied exactly.** Each column is as wide as its longest cell or its header plus two, whichever is more; a column whose body cells are all numbers is right-aligned; the separator row is dashes. This is `MarkdownTable`.
- **Metadata for these formats sits in a sidecar** next to the file (`report.pdf` has `report.meta.yaml`, with `title`, `type` and optionally `company`), the same rule as the Python edition. A missing sidecar names the file it expected.
- **How we know the readers equal Python's:** a test compares each of the three sample files with what the Python parser produced for the same file, character for character, plus the page numbers, the sheet names, the chunk counts (2, 2 and 1) and, in the integration test, the document ids (3, 9 and 14).
- Chunks keep their segment's location in the payload as `page` (an `int`) and `sheet`; both are left out when a file has neither, so Markdown chunks look as before.

## Consequences

- An Excel sheet is read as one table (its whole used range). Several separate tables on one sheet are not told apart; Docling can. The sample file has one table per sheet.
- Merged cells, images and text boxes are ignored. Word tables are read by rows and columns of text.
- POI and PDFBox are large libraries. They are used only by the readers, and the dependency scan in CI covers them.

## What the work found

- A document with a title but no `type` crashed with a `NullPointerException` (Java's `Set.of(...).contains(null)` throws) instead of the clear "missing required metadata" message. This was already in J3.1; fixed with a test.
