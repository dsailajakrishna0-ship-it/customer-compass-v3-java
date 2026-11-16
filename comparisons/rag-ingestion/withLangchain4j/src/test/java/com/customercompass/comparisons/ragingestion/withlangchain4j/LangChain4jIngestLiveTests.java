package com.customercompass.comparisons.ragingestion.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Ingest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Against a real Qdrant, skipped unless QDRANT_REST_URL (for example http://localhost:26333) and
 * QDRANT_GRPC_PORT (for example 26334) are set, which {@code just qdrant} makes possible. The
 * embedding service is still a fake server: no embedding quota is spent. These are the measurements
 * of LangChain4j's Qdrant store in ADR 0035.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_REST_URL", matches = ".+")
class LangChain4jIngestLiveTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String REST = System.getenv("QDRANT_REST_URL");
  private static final int GRPC = Integer.parseInt(System.getenv().getOrDefault("QDRANT_GRPC_PORT", "26334"));

  @TempDir Path folder;

  private static JsonNode rest(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(REST + path))
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return JSON.readTree(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
  }

  private static void drop(String name) throws Exception {
    rest("DELETE", "/collections/" + name, null);
  }

  @Test
  void theStoreDoesNotCreateItsCollectionAndFailsWithoutOne() throws Exception {
    drop("probe_missing");
    try (var client = new QdrantClient(QdrantGrpcClient.newBuilder("localhost", GRPC, false).build())) {
      var store = QdrantEmbeddingStore.builder().client(client).collectionName("probe_missing").build();
      assertThat(rest("GET", "/collections/probe_missing", null).get("status").toString()).contains("Not found");
      var embeddings = List.of(Embedding.from(new float[] {1, 0, 0, 0}));
      assertThatThrownBy(() -> store.addAll(embeddings, List.of(TextSegment.from("x")))).hasMessageContaining("NOT_FOUND");
      assertThatThrownBy(store::removeAll).hasMessageContaining("NOT_FOUND");
      assertThatThrownBy(store::clearStore).hasMessageContaining("NOT_FOUND");
    }
  }

  @Test
  void aRunCreatesTheCollectionWithTheSizeAndCosineAndStoresTheChunks() throws Exception {
    Files.writeString(folder.resolve("a.md"), "---\ntitle: A call\ncompany: Acme\n---\n\n" + "Acme asked about renewal. ".repeat(40));
    drop(LangChain4jIngest.COLLECTION);
    try (var gemini = FakeHttp.gemini(4)) {
      var ingest = new LangChain4jIngest(folder, gemini.url(), "key", "m", 4, "localhost", GRPC);
      Ingest.Result result = ingest.run(null);
      JsonNode info = rest("GET", "/collections/" + LangChain4jIngest.COLLECTION, null).get("result");
      assertThat(info.get("config").get("params").get("vectors").get("size").asInt()).isEqualTo(4);
      assertThat(info.get("config").get("params").get("vectors").get("distance").asString()).isEqualTo("Cosine");
      assertThat(info.get("points_count").asInt()).isEqualTo(result.chunks());
      JsonNode points = rest("POST", "/collections/" + LangChain4jIngest.COLLECTION + "/points/scroll", "{\"limit\":50,\"with_payload\":true}").get("result").get("points");
      JsonNode payload = points.get(0).get("payload");
      System.out.println("LIVE payload keys=" + payload.propertyNames() + " id=" + points.get(0).get("id"));
      assertThat(payload.get("text_segment").asString()).startsWith("Acme asked about renewal.");
      assertThat(payload.get("title").asString()).isEqualTo("A call");
      assertThat(payload.get("company").asString()).isEqualTo("Acme");
      assertThat(payload.get("source").asString()).isEqualTo("a.md");
    }
  }

  @Test
  void runningTwiceReplacesTheCollectionAndNeverDuplicates() throws Exception {
    Files.writeString(folder.resolve("a.md"), "Acme asked about renewal. ".repeat(40));
    try (var gemini = FakeHttp.gemini(4)) {
      var ingest = new LangChain4jIngest(folder, gemini.url(), "key", "m", 4, "localhost", GRPC);
      var first = ingest.run(null);
      var second = ingest.run(null);
      int count = rest("GET", "/collections/" + LangChain4jIngest.COLLECTION, null).get("result").get("points_count").asInt();
      assertThat(count).isEqualTo(second.chunks()).isEqualTo(first.chunks());
    }
  }

  @Test
  void aVectorStoreThatIsNotThereIsReportedAsUnreachableAfterTheEmbeddingWasDone() throws Exception {
    Files.writeString(folder.resolve("a.md"), "text");
    try (var gemini = FakeHttp.gemini(4)) {
      var ingest = new LangChain4jIngest(folder, gemini.url(), "key", "m", 4, "localhost", 1);
      assertThatThrownBy(() -> ingest.run(null)).isInstanceOf(Ingest.UnavailableException.class).hasMessage("The vector store is not reachable.");
      assertThat(gemini.requests).hasSize(1);
    }
  }
}
