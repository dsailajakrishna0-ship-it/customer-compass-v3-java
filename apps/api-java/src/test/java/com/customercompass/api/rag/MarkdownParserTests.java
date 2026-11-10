package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MarkdownParserTests {

  private static final String GOOD =
      """
      ---
      title: Volume discount request
      type: email
      company: Acme Fabrication
      date: 2026-08-30
      ---

      From: Ravi

      Hello.
      """;

  @Test
  void readsTheFrontMatterAndTheTrimmedBody() {
    ParsedDocument document = MarkdownParser.parse("a.md", GOOD, "sample-data/documents/a.md");

    assertThat(document.title()).isEqualTo("Volume discount request");
    assertThat(document.docType()).isEqualTo("email");
    assertThat(document.companyName()).isEqualTo("Acme Fabrication");
    assertThat(document.sourcePath()).isEqualTo("sample-data/documents/a.md");
    assertThat(document.content()).isEqualTo("From: Ravi\n\nHello.");
  }

  @Test
  void theCompanyIsOptional() {
    ParsedDocument document =
        MarkdownParser.parse("p.md", "---\ntitle: Plan\ntype: product\n---\nBody", "p");

    assertThat(document.companyName()).isNull();
    assertThat(document.content()).isEqualTo("Body");
  }

  @Test
  void aMissingTitleNamesTheFile() {
    assertThatThrownBy(() -> MarkdownParser.parse("x.md", "---\ntype: email\n---\nBody", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Document x.md is missing required metadata (title, type).");
  }

  @Test
  void aTypeOutsideTheFiveKnownOnesIsRefused() {
    assertThatThrownBy(
            () -> MarkdownParser.parse("y.md", "---\ntitle: T\ntype: memo\n---\nBody", "y"))
        .hasMessageContaining("Document y.md is missing required metadata");
  }

  @Test
  void aFileWithNoFrontMatterIsRefused() {
    assertThatThrownBy(() -> MarkdownParser.parse("z.md", "Just text", "z"))
        .hasMessageContaining("Document z.md is missing required metadata");
  }
}
