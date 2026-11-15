package com.customercompass.comparisons.ragingestion.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownDocumentsTests {

  @TempDir Path folder;

  private void write(String name, String content) throws Exception {
    Files.writeString(folder.resolve(name), content);
  }

  @Test
  void frontMatterGivesTheTitleAndTheCompany() throws Exception {
    write("a.md", "---\ntitle: A call\ncompany: Acme Fabrication\ndate: 2026-08-28\n---\n\nHello there.\n");
    var documents = MarkdownDocuments.read(folder, null);
    assertThat(documents).hasSize(1);
    assertThat(documents.get(0).title()).isEqualTo("A call");
    assertThat(documents.get(0).company()).isEqualTo("Acme Fabrication");
    assertThat(documents.get(0).text()).isEqualTo("Hello there.");
    assertThat(documents.get(0).source()).isEqualTo("a.md");
  }

  @Test
  void withoutFrontMatterTheTitleIsTheFileName() throws Exception {
    write("plain-note.md", "Just text.");
    var document = MarkdownDocuments.read(folder, null).get(0);
    assertThat(document.title()).isEqualTo("plain-note");
    assertThat(document.company()).isEmpty();
    assertThat(document.text()).isEqualTo("Just text.");
  }

  @Test
  void filesComeInNameOrderAndOtherTypesAreIgnored() throws Exception {
    write("b.md", "two");
    write("a.md", "one");
    write("c.pdf", "not markdown");
    write("d.yaml", "title: x");
    var documents = MarkdownDocuments.read(folder, null);
    assertThat(documents.stream().map(MarkdownDocuments.Document::source)).containsExactly("a.md", "b.md");
  }

  @Test
  void aFileWithNoTextIsSkipped() throws Exception {
    write("empty.md", "---\ntitle: Empty\n---\n\n   \n");
    write("full.md", "text");
    assertThat(MarkdownDocuments.read(folder, null)).hasSize(1);
  }

  @Test
  void theLimitKeepsTheFirstDocuments() throws Exception {
    write("a.md", "one");
    write("b.md", "two");
    write("c.md", "three");
    var documents = MarkdownDocuments.read(folder, 2);
    assertThat(documents.stream().map(MarkdownDocuments.Document::source)).containsExactly("a.md", "b.md");
  }

  @Test
  void aMissingFolderIsRefusedWithoutItsFullPath() {
    assertThatThrownBy(() -> MarkdownDocuments.read(folder.resolve("nope"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The source folder does not exist: nope");
  }
}
