package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagContextTests {

  private static RetrievedChunk chunk(String title, String type, Integer page, String sheet) {
    return new RetrievedChunk(
        "id",
        7,
        title,
        "sample-data/documents/x.md",
        type,
        "Acme Fabrication",
        0,
        "Body of " + title,
        0.75,
        page,
        sheet);
  }

  @Test
  void theContextNumbersEachSourceAndNamesItsTypeAndLocation() {
    String context =
        RagContext.build(
            List.of(
                chunk("Discount email", "email", null, null),
                chunk("Service agreement", "support", 2, null),
                chunk("Rate card", "product", null, "Rate card")));

    assertThat(context)
        .isEqualTo(
            "[1] Source: Discount email (email)\nBody of Discount email\n\n"
                + "[2] Source: Service agreement (support, page 2)\nBody of Service agreement\n\n"
                + "[3] Source: Rate card (product, sheet Rate card)\nBody of Rate card");
  }

  @Test
  void retrievedTextIsWrappedAsUntrustedData() {
    assertThat(RagContext.wrapUntrusted("[1] x"))
        .isEqualTo("<untrusted_context>\n[1] x\n</untrusted_context>");
  }

  @Test
  void aCitationHasTheShapeTheWebAppReadsWithPageAndSheetOnlyWhenPresent() {
    Map<String, Object> plain = RagContext.citation(0, chunk("A", "email", null, null));
    Map<String, Object> pdf = RagContext.citation(1, chunk("B", "support", 2, null));
    Map<String, Object> excel = RagContext.citation(2, chunk("C", "product", null, "SLA credits"));

    assertThat(plain.keySet())
        .containsExactly(
            "number",
            "documentId",
            "title",
            "sourcePath",
            "docType",
            "companyName",
            "similarity",
            "snippet");
    assertThat(plain).containsEntry("number", 1).containsEntry("documentId", 7);
    assertThat(pdf.keySet()).startsWith("page", "number");
    assertThat(pdf).containsEntry("page", 2).containsEntry("number", 2);
    assertThat(excel.keySet()).startsWith("sheet", "number");
    assertThat(excel).containsEntry("sheet", "SLA credits");
  }

  @Test
  void aSnippetIsTheFirst200CharactersAndShortTextIsKept() {
    assertThat(RagContext.snippet("short")).isEqualTo("short");
    assertThat(RagContext.snippet("x".repeat(300))).hasSize(200);
    // 200 code points, not 200 UTF-16 units: an emoji counts once
    assertThat(
            RagContext.snippet("😀".repeat(250))
                .codePointCount(0, RagContext.snippet("😀".repeat(250)).length()))
        .isEqualTo(200);
  }
}
