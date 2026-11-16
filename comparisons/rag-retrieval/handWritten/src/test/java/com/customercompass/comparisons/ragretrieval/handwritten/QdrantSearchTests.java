package com.customercompass.comparisons.ragretrieval.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Retrieve;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QdrantSearchTests {

  @Test
  void askForTheClosestPointsWithTheirPayload() throws Exception {
    try (var qdrant = FakeHttp.qdrant(FakeHttp.point(0.71, "A call", "a.md", "Acme", 0, "text"))) {
      var points = new QdrantSearch(qdrant.url()).search("compare_handwritten", new float[] {1f, 0f}, 3);
      assertThat(points).hasSize(1);
      assertThat(points.get(0).score()).isEqualTo(0.71);
      assertThat(points.get(0).payload().get("title").asString()).isEqualTo("A call");
      var request = qdrant.requests.get(0);
      assertThat(request.method() + " " + request.path()).isEqualTo("POST /collections/compare_handwritten/points/query");
      var body = JsonMapper.builder().build().readTree(request.body());
      assertThat(body.get("limit").asInt()).isEqualTo(3);
      assertThat(body.get("with_payload").asBoolean()).isTrue();
      assertThat(body.get("query")).hasSize(2);
    }
  }

  @Test
  void aMissingCollectionSaysToRunTheIngestionFirst() throws Exception {
    try (var qdrant = new FakeHttp(r -> new FakeHttp.Reply(404, "{\"status\":{\"error\":\"Not found\"}}"))) {
      assertThatThrownBy(() -> new QdrantSearch(qdrant.url()).search("compare_handwritten", new float[] {1f}, 4))
          .isInstanceOf(Retrieve.UnavailableException.class)
          .hasMessage("The collection compare_handwritten does not exist. Run the ingestion for this approach first.");
    }
  }

  @Test
  void aRefusalIsReportedByStatusOnly() throws Exception {
    try (var qdrant = new FakeHttp(r -> new FakeHttp.Reply(500, "{\"secret\":\"internal text\"}"))) {
      assertThatThrownBy(() -> new QdrantSearch(qdrant.url()).search("c", new float[] {1f}, 4))
          .hasMessage("The vector store answered HTTP 500.");
    }
  }

  @Test
  void aStoreThatIsNotThereIsReportedAsUnreachable() {
    assertThatThrownBy(() -> new QdrantSearch("http://127.0.0.1:1").search("c", new float[] {1f}, 4))
        .hasMessage("The vector store is not reachable.");
  }

  @Test
  void aReplyWithoutPointsIsAnError() throws Exception {
    try (var qdrant = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"result\":{}}"))) {
      assertThatThrownBy(() -> new QdrantSearch(qdrant.url()).search("c", new float[] {1f}, 4))
          .hasMessage("The vector store sent a reply without points.");
    }
  }
}
