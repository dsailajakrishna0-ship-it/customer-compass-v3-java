package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three non-Markdown readers, compared with what the Python edition's readers produce for the
 * same sample files (captured by running its parser): the same segments, the same page numbers and
 * sheet names, and the same text, character for character, so both editions embed the same thing.
 */
class DocumentReaderTests {

  private static final Path FOLDER = Path.of("../../sample-data/documents");

  private static ParsedDocument read(String name) throws IOException {
    return DocumentReader.read(FOLDER.resolve(name), "sample-data/documents/" + name);
  }

  @Test
  void aPdfIsOneSegmentPerPageWithThePageNumber() throws IOException {
    ParsedDocument pdf = read("acme-service-agreement.pdf");

    assertThat(pdf.title()).isEqualTo("Acme Fabrication service agreement summary");
    assertThat(pdf.docType()).isEqualTo("support");
    assertThat(pdf.companyName()).isEqualTo("Acme Fabrication");
    assertThat(pdf.segments()).extracting(Segment::page).containsExactly(1, 2);
    assertThat(pdf.segments()).extracting(Segment::sheet).containsOnlyNulls();
    assertThat(pdf.segments().get(0).text())
        .isEqualTo(
            """
            Acme Fabrication: Service agreement summary
            Section 1. Support hours. Acme Fabrication's Premium Operations Plan includes support from 07:00 to 19:00
            on weekdays. Priority 1 incidents, such as a stopped production line, receive a first response within 30
            minutes at any hour.
            Section 2. Onboarding. Each new factory floor receives two on-site onboarding sessions and a remote
            training day. Additional floors are priced per floor.""");
    assertThat(pdf.segments().get(1).text())
        .isEqualTo(
            """
            Section 7. Renewal and termination
            Clause 7.3. The agreement renews automatically on 1 October for twelve months. Either party may end it
            with 60 days' written notice before the renewal date. Clause 7.4. A price change for the renewal term must be
            sent to the customer at least 90 days before the renewal date.
            Section 8. Invoicing. Invoice INV-4821 for the Q3 onboarding block is under review by Acme's finance team;
            payment terms are 30 days from the corrected invoice.""");
  }

  @Test
  void anExcelFileIsOneSegmentPerSheetWithTheTableLaidOutAsPythonDoes() throws IOException {
    ParsedDocument excel = read("northstar-rate-card.xlsx");

    assertThat(excel.docType()).isEqualTo("product");
    assertThat(excel.segments())
        .extracting(Segment::sheet)
        .containsExactly("Rate card", "SLA credits");
    assertThat(excel.segments()).extracting(Segment::page).containsOnlyNulls();
    assertThat(excel.segments().get(0).text())
        .isEqualTo(
            """
            Sheet: Rate card

            | Plan             |   Monthly price (USD) |   Tracked vehicles | Alert channels         |
            |------------------|-----------------------|--------------------|------------------------|
            | Essentials       |                  1200 |                 50 | Email                  |
            | Fleet Plus       |                  3400 |                250 | Email and SMS          |
            | Fleet Enterprise |                  9800 |               1000 | Email, SMS and webhook |""");
    assertThat(excel.segments().get(1).text())
        .isEqualTo(
            """
            Sheet: SLA credits

            | Monthly uptime   | Service credit           |
            |------------------|--------------------------|
            | 99.9% or higher  | None                     |
            | 99.0% to 99.9%   | 10% of the monthly price |
            | Below 99.0%      | 25% of the monthly price |""");
  }

  @Test
  void aWordFileIsOneSegmentWithHeadingsAsPythonWritesThem() throws IOException {
    ParsedDocument word = read("riverside-data-handling-policy.docx");

    assertThat(word.companyName()).isEqualTo("Riverside Health Group");
    assertThat(word.segments()).hasSize(1);
    assertThat(word.segments().get(0).page()).isNull();
    assertThat(word.segments().get(0).sheet()).isNull();
    assertThat(word.segments().get(0).text())
        .isEqualTo(
            """
            ## Riverside Health Group: data handling policy

            Patient identifiers must never appear in support tickets or in exported reports. Exports are encrypted and expire after seven days.

            ### Access reviews

            Accounts with access to clinical data are reviewed every quarter. A reviewer who finds an unused account disables it within two business days.""");
  }

  @Test
  void theThreeFilesGiveThePythonChunkCountsAndEachChunkKeepsItsPageOrSheet() throws IOException {
    List<SegmentChunk> pdf = Chunker.chunkSegments(read("acme-service-agreement.pdf").segments());
    List<SegmentChunk> excel = Chunker.chunkSegments(read("northstar-rate-card.xlsx").segments());
    List<SegmentChunk> word =
        Chunker.chunkSegments(read("riverside-data-handling-policy.docx").segments());

    assertThat(pdf).hasSize(2);
    assertThat(pdf).extracting(c -> c.segment().page()).containsExactly(1, 2);
    assertThat(pdf).extracting(c -> c.chunk().index()).containsExactly(0, 1);
    assertThat(pdf)
        .extracting(c -> c.chunk().charStart())
        .containsExactly(0, 0); // relative to the page
    assertThat(excel).hasSize(2);
    assertThat(excel)
        .extracting(c -> c.segment().sheet())
        .containsExactly("Rate card", "SLA credits");
    assertThat(word).hasSize(1);
  }

  @Test
  void sidecarFilesAreNotDocumentsButTheSupportedFormatsAre() {
    assertThat(DocumentReader.isDocument(Path.of("a.md"))).isTrue();
    assertThat(DocumentReader.isDocument(Path.of("a.PDF"))).isTrue();
    assertThat(DocumentReader.isDocument(Path.of("a.docx"))).isTrue();
    assertThat(DocumentReader.isDocument(Path.of("a.xlsx"))).isTrue();
    assertThat(DocumentReader.isDocument(Path.of("a.meta.yaml"))).isFalse();
    assertThat(DocumentReader.isDocument(Path.of("a.txt"))).isFalse();
  }

  @Test
  void aFileWithNoSidecarNamesTheMissingMetadataFile(@TempDir Path dir) throws IOException {
    Path pdf = Files.copy(FOLDER.resolve("acme-service-agreement.pdf"), dir.resolve("report.pdf"));

    assertThatThrownBy(() -> DocumentReader.read(pdf, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Document report.pdf needs a metadata file named report.meta.yaml.");
  }

  @Test
  void aSidecarWithoutATypeIsRefused(@TempDir Path dir) throws IOException {
    Path pdf = Files.copy(FOLDER.resolve("acme-service-agreement.pdf"), dir.resolve("report.pdf"));
    Files.writeString(dir.resolve("report.meta.yaml"), "title: A report\n");

    assertThatThrownBy(() -> DocumentReader.read(pdf, "x"))
        .hasMessage("Document report.pdf is missing required metadata (title, type).");
  }

  @Test
  void aTableIsLaidOutByColumnWidthWithNumbersRightAligned() {
    String table =
        MarkdownTable.render(
            List.of("Name", "Qty"),
            List.of(List.of("Bolt", "5"), List.of("Long name here", "120")));

    assertThat(table)
        .isEqualTo(
            """
            | Name           |   Qty |
            |----------------|-------|
            | Bolt           |     5 |
            | Long name here |   120 |""");
  }
}
