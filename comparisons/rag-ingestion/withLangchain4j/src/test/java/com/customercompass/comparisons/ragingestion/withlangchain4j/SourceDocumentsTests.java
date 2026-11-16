package com.customercompass.comparisons.ragingestion.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDocumentsTests {

  @TempDir Path folder;

  private void write(String name, String content) throws Exception {
    Files.writeString(folder.resolve(name), content);
  }

  @Test
  void frontMatterGivesTitleAndCompanyAndIsNotInTheText() throws Exception {
    write("a.md", "---\ntitle: A call\ncompany: Acme Fabrication\ndate: 2026-08-28\n---\n\nHello there.\n");
    Document document = SourceDocuments.read(folder, null).get(0);
    assertThat(document.text()).isEqualTo("Hello there.");
    assertThat(document.metadata().getString("title")).isEqualTo("A call");
    assertThat(document.metadata().getString("company")).isEqualTo("Acme Fabrication");
    assertThat(document.metadata().getString("source")).isEqualTo("a.md");
  }

  @Test
  void withoutFrontMatterTheTitleIsTheFileName() throws Exception {
    write("plain-note.md", "Just text.");
    Document document = SourceDocuments.read(folder, null).get(0);
    assertThat(document.metadata().getString("title")).isEqualTo("plain-note");
    assertThat(document.metadata().getString("company")).isEmpty();
  }

  @Test
  void filesAreSortedByNameWhateverOrderTheLoaderGives() throws Exception {
    write("c.md", "three");
    write("a.md", "one");
    write("b.md", "two");
    assertThat(SourceDocuments.read(folder, null).stream().map(d -> d.metadata().getString("source")))
        .containsExactly("a.md", "b.md", "c.md");
  }

  @Test
  void theLimitKeepsTheFirstDocumentsInNameOrder() throws Exception {
    write("b.md", "two");
    write("a.md", "one");
    write("c.md", "three");
    assertThat(SourceDocuments.read(folder, 2).stream().map(d -> d.metadata().getString("source")))
        .containsExactly("a.md", "b.md");
  }

  @Test
  void blankFilesAndFilesWithOnlyFrontMatterAreSkipped() throws Exception {
    write("blank.md", "  \n \n");
    write("empty.md", "");
    write("only-front.md", "---\ntitle: Empty\n---\n\n   \n");
    write("full.md", "text");
    assertThat(SourceDocuments.read(folder, null)).hasSize(1);
  }

  @Test
  void otherFileTypesAndSubFoldersAreIgnored() throws Exception {
    write("a.md", "one");
    write("b.pdf", "x");
    write("c.txt", "x");
    Files.createDirectory(folder.resolve("sub"));
    Files.writeString(folder.resolve("sub/d.md"), "nested");
    assertThat(SourceDocuments.read(folder, null)).hasSize(1);
  }

  @Test
  void aMissingFolderIsRefusedWithoutItsFullPath() {
    assertThatThrownBy(() -> SourceDocuments.read(folder.resolve("nope"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The source folder does not exist: nope");
  }
}
